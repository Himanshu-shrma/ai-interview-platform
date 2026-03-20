package com.aiinterview.conversation

import com.aiinterview.interview.model.ConversationMessage
import com.aiinterview.interview.repository.ConversationMessageRepository
import com.aiinterview.interview.service.InterviewMemory
import com.aiinterview.interview.service.RedisMemoryService
import com.aiinterview.interview.ws.OutboundMessage
import com.aiinterview.interview.ws.WsSessionRegistry
import com.aiinterview.shared.ai.LlmMessage
import com.aiinterview.shared.ai.LlmProviderRegistry
import com.aiinterview.shared.ai.LlmRequest
import com.aiinterview.shared.ai.LlmRole
import com.aiinterview.shared.ai.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

private const val STREAM_TIMEOUT_MS = 10_000L
private const val MAX_TOKENS_FALLBACK = 200

/** Dynamic maxTokens based on interview stage and message type. */
private fun maxTokensFor(stage: String, messageType: MessageType): Int = when (stage) {
    "SMALL_TALK" -> 120        // Brief greeting + problem presentation
    "PROBLEM_PRESENTED" -> 50  // Stay silent or very brief
    "CLARIFYING" -> when (messageType) {
        MessageType.CONSTRAINT_QUESTION -> 60   // "Up to 10^5 elements."
        MessageType.CLARIFYING_QUESTION -> 80   // Short direct answer
        MessageType.CANDIDATE_STATEMENT -> 100  // Brief transition
    }
    "APPROACH" -> 120           // React + one follow-up
    "CODING" -> 60              // Mostly silent — one sentence max
    "REVIEW" -> 150             // Need space for code-specific questions
    "FOLLOWUP" -> 150           // Harder variant introduction
    "WRAP_UP" -> 100            // Brief closing
    else -> when (messageType) {
        MessageType.CONSTRAINT_QUESTION -> 80
        MessageType.CLARIFYING_QUESTION -> 100
        MessageType.CANDIDATE_STATEMENT -> 150
    }
}

@Component
class InterviewerAgent(
    private val llm: LlmProviderRegistry,
    private val modelConfig: ModelConfig,
    private val promptBuilder: PromptBuilder,
    private val registry: WsSessionRegistry,
    private val redisMemoryService: RedisMemoryService,
    private val conversationMessageRepository: ConversationMessageRepository,
) {
    private val log = LoggerFactory.getLogger(InterviewerAgent::class.java)

    suspend fun streamResponse(
        sessionId: UUID,
        memory: InterviewMemory,
        userMessage: String,
    ) {
        // ── FIX 1: CODING GATE — skip LLM when stage=CODING and no real code ──
        val codeContent = memory.currentCode?.trim()
        val hasMeaningfulCode = !codeContent.isNullOrBlank()
            && codeContent.length > 50
            && codeContent != "class {}"

        if (memory.interviewStage == "CODING" && !hasMeaningfulCode) {
            val response = when {
                userMessage.length > 100 ->
                    "I think I get the idea — go ahead and implement it."
                userMessage.lowercase().let { it.contains("code") || it.contains("implement") } ->
                    "Sure, take your time."
                else -> "Go ahead."
            }
            streamStaticResponse(sessionId, response)
            persistResponse(sessionId, response)
            return
        }

        val messageType = classifyMessage(userMessage)
        val systemPrompt = promptBuilder.buildSystemPrompt(memory, messageType)
        val fullResponse = StringBuilder()

        val maxTokens = maxTokensFor(memory.interviewStage, messageType)
        val success = tryStreaming(sessionId, systemPrompt, userMessage, fullResponse, maxTokens)

        if (!success) {
            log.warn("Streaming timed out or failed for session {}, falling back to complete()", sessionId)
            val ok = tryFallback(sessionId, systemPrompt, userMessage, fullResponse)
            if (!ok) {
                registry.sendMessage(sessionId, OutboundMessage.Error("AI_ERROR", "Interview assistant unavailable"))
                return
            }
        }

        registry.sendMessage(sessionId, OutboundMessage.AiChunk(delta = "", done = true))

        val responseText = fullResponse.toString()
        if (responseText.isNotBlank()) {
            persistResponse(sessionId, responseText)
            updateInterviewStage(sessionId, memory, userMessage, responseText)
        }
    }

    private suspend fun tryStreaming(
        sessionId: UUID,
        systemPrompt: String,
        userMessage: String,
        fullResponse: StringBuilder,
        maxTokens: Int = 150,
    ): Boolean = try {
        val request = LlmRequest(
            messages = listOf(
                LlmMessage(LlmRole.SYSTEM, systemPrompt),
                LlmMessage(LlmRole.USER, userMessage),
            ),
            model = modelConfig.interviewerModel,
            maxTokens = maxTokens,
        )

        withTimeout(STREAM_TIMEOUT_MS) {
            llm.stream(request)
                .catch { e ->
                    log.error("Stream error for session {}: {}", sessionId, e.message)
                }
                .collect { token ->
                    fullResponse.append(token)
                    registry.sendMessage(sessionId, OutboundMessage.AiChunk(delta = token, done = false))
                }
        }
        fullResponse.isNotEmpty()
    } catch (e: TimeoutCancellationException) {
        log.warn("First-token timeout for session {}", sessionId)
        false
    } catch (e: Exception) {
        log.error("Streaming error for session {}: {}", sessionId, e.message)
        false
    }

    private suspend fun tryFallback(
        sessionId: UUID,
        systemPrompt: String,
        userMessage: String,
        fullResponse: StringBuilder,
    ): Boolean = try {
        val request = LlmRequest.build(
            systemPrompt = systemPrompt,
            userMessage = userMessage,
            model = modelConfig.backgroundModel,
            maxTokens = MAX_TOKENS_FALLBACK,
        )
        val response = llm.complete(request)
        fullResponse.append(response.content)
        registry.sendMessage(sessionId, OutboundMessage.AiChunk(delta = response.content, done = false))
        true
    } catch (e: Exception) {
        log.error("Fallback model error for session {}: {}", sessionId, e.message)
        false
    }

    /**
     * Strict 8-stage interview progression.
     *
     * SMALL_TALK → PROBLEM_PRESENTED → CLARIFYING → APPROACH → CODING → REVIEW → FOLLOWUP → WRAP_UP
     *
     * Transitions are based on conversation content, NOT random.
     * Each stage can only progress forward — never backward.
     */
    private suspend fun updateInterviewStage(
        sessionId: UUID,
        memory: InterviewMemory,
        candidateMessage: String,
        aiResponse: String,
    ) {
        val currentStage = memory.interviewStage
        val candidateLower = candidateMessage.lowercase()
        val aiLower = aiResponse.lowercase()
        val hasCode = !memory.currentCode.isNullOrBlank()
        val hasMeaningfulCode = hasCode && (memory.currentCode?.trim()?.length ?: 0) > 30

        val newStage = when (currentStage) {
            // After one exchange of small talk, AI should present the problem
            "SMALL_TALK" -> {
                // AI presents the problem → move to PROBLEM_PRESENTED
                if (aiLower.contains("problem") || aiLower.contains("question") ||
                    aiLower.contains("challenge") || aiLower.contains("let me share")) {
                    "PROBLEM_PRESENTED"
                } else {
                    // Force transition after first candidate message regardless
                    "PROBLEM_PRESENTED"
                }
            }

            // Candidate is reading — wait for them to speak
            "PROBLEM_PRESENTED" -> {
                if (candidateLower.endsWith("?") || candidateLower.contains("constraint") ||
                    candidateLower.contains("clarif") || candidateLower.contains("assume") ||
                    candidateLower.contains("can the") || candidateLower.contains("will the") ||
                    candidateLower.contains("is it") || candidateLower.contains("what if")) {
                    "CLARIFYING"
                } else if (candidateLower.contains("approach") || candidateLower.contains("think") ||
                    candidateLower.contains("would use") || candidateLower.contains("idea") ||
                    candidateLower.contains("solution") || candidateLower.contains("algorithm") ||
                    candidateLower.contains("hash") || candidateLower.contains("sort") ||
                    candidateLower.contains("iterate") || candidateLower.contains("loop")) {
                    "APPROACH"
                } else {
                    "CLARIFYING"  // Default: treat first response as clarifying phase
                }
            }

            // Answering constraint questions
            "CLARIFYING" -> {
                // Candidate stops asking questions, starts discussing solution
                if (!candidateLower.endsWith("?") && (
                    candidateLower.contains("approach") || candidateLower.contains("think") ||
                    candidateLower.contains("would") || candidateLower.contains("use") ||
                    candidateLower.contains("idea") || candidateLower.contains("solution") ||
                    candidateLower.contains("algorithm") || candidateLower.contains("hash") ||
                    candidateLower.contains("sort") || candidateLower.contains("iterate") ||
                    candidateLower.contains("brute") || candidateLower.contains("optimal"))
                ) {
                    "APPROACH"
                } else if (aiLower.contains("ready to") && aiLower.contains("approach")) {
                    "APPROACH"
                } else {
                    currentStage
                }
            }

            // Discussing approach — only exit is "go ahead and code it"
            "APPROACH" -> {
                if (aiLower.contains("go ahead") || aiLower.contains("code it") ||
                    aiLower.contains("implement") || aiLower.contains("write it") ||
                    aiLower.contains("start coding")) {
                    "CODING"
                } else if (hasMeaningfulCode) {
                    "CODING"  // They started coding already
                } else {
                    currentStage
                }
            }

            // Candidate is coding — mostly silent
            "CODING" -> {
                val donePhrases = listOf("done", "finished", "i think this works", "that should work",
                    "let me walk", "walk you through", "here's my", "here is my", "looks good",
                    "i'm done", "completed", "ready to review", "take a look")
                if (hasMeaningfulCode && donePhrases.any { candidateLower.contains(it) }) {
                    "REVIEW"
                } else if (aiLower.contains("walk me through") || aiLower.contains("trace through")) {
                    "REVIEW"
                } else {
                    currentStage
                }
            }

            // Reviewing code together
            "REVIEW" -> {
                val complexityMentioned = candidateLower.contains("o(") || candidateLower.contains("complexity") ||
                    candidateLower.contains("linear") || candidateLower.contains("quadratic") ||
                    candidateLower.contains("constant") || candidateLower.contains("log")
                val edgeCasesDone = memory.followUpsAsked.size >= 2 || (
                    complexityMentioned && (candidateLower.contains("edge") || aiLower.contains("edge") ||
                    aiLower.contains("what if") || aiLower.contains("good")))

                if (edgeCasesDone && complexityMentioned) {
                    "FOLLOWUP"
                } else if (aiLower.contains("think that covers") || aiLower.contains("good job") ||
                    aiLower.contains("let's move") || aiLower.contains("nice work")) {
                    "FOLLOWUP"
                } else {
                    currentStage
                }
            }

            // Follow-up question done → wrap up
            "FOLLOWUP" -> {
                if (aiLower.contains("covers it") || aiLower.contains("that's it") ||
                    aiLower.contains("everything i need") || aiLower.contains("questions for me") ||
                    aiLower.contains("good thinking") || aiLower.contains("nice")) {
                    "WRAP_UP"
                } else {
                    currentStage
                }
            }

            "WRAP_UP" -> currentStage  // Terminal for this question
            else -> currentStage
        }

        if (newStage != currentStage) {
            try {
                redisMemoryService.updateMemory(sessionId) { mem ->
                    mem.copy(interviewStage = newStage)
                }
                log.info("Interview stage {} → {} for session {}", currentStage, newStage, sessionId)
            } catch (e: Exception) {
                log.warn("Failed to update interview stage for session {}: {}", sessionId, e.message)
            }
        }
    }

    /** Streams a canned response as AI_CHUNK frames (same as LLM output from frontend perspective). */
    private suspend fun streamStaticResponse(sessionId: UUID, response: String) {
        registry.sendMessage(sessionId, OutboundMessage.AiChunk(delta = response, done = false))
        registry.sendMessage(sessionId, OutboundMessage.AiChunk(delta = "", done = true))
    }

    private suspend fun persistResponse(sessionId: UUID, responseText: String) {
        try {
            redisMemoryService.appendTranscriptTurn(sessionId, "AI", responseText)
        } catch (e: Exception) {
            log.warn("Failed to append AI transcript turn for session {}: {}", sessionId, e.message)
        }
        try {
            withContext(Dispatchers.IO) {
                conversationMessageRepository.save(
                    ConversationMessage(sessionId = sessionId, role = "AI", content = responseText),
                ).awaitSingle()
            }
        } catch (e: Exception) {
            log.warn("Failed to persist AI message to DB for session {}: {}", sessionId, e.message)
        }
    }
}
