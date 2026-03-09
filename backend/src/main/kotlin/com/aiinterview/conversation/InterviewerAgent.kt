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

/** Dynamic maxTokens per message type — keeps responses appropriately short. */
private fun maxTokensFor(messageType: MessageType): Int = when (messageType) {
    MessageType.CONSTRAINT_QUESTION -> 80   // "Yes, up to 10^5 elements."
    MessageType.CLARIFYING_QUESTION -> 100  // Short direct answer
    MessageType.CANDIDATE_STATEMENT -> 150  // React + one follow-up
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
        val messageType = classifyMessage(userMessage)
        val systemPrompt = promptBuilder.buildSystemPrompt(memory, messageType)
        val fullResponse = StringBuilder()

        val maxTokens = maxTokensFor(messageType)
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
            // Update interview stage based on AI response content
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
     * Detects the interview stage from conversation content and updates Redis.
     * Stages progress naturally: PROBLEM_PRESENTED → CLARIFICATION → APPROACH → CODING → CODE_REVIEW → COMPLEXITY → EDGE_CASES → WRAP_UP
     */
    private suspend fun updateInterviewStage(
        sessionId: UUID,
        memory: InterviewMemory,
        candidateMessage: String,
        aiResponse: String,
    ) {
        val currentStage = memory.interviewStage
        val lower = candidateMessage.lowercase()
        val aiLower = aiResponse.lowercase()

        val newStage = when (currentStage) {
            "PROBLEM_PRESENTED" -> {
                if (lower.endsWith("?") || lower.contains("constraint") || lower.contains("clarif")) {
                    "CLARIFICATION"
                } else {
                    "APPROACH_DISCUSSION"
                }
            }
            "CLARIFICATION" -> {
                if (!lower.endsWith("?") && (lower.contains("approach") || lower.contains("think") ||
                            lower.contains("would") || lower.contains("use") || lower.contains("idea"))) {
                    "APPROACH_DISCUSSION"
                } else {
                    currentStage // stay in clarification
                }
            }
            "APPROACH_DISCUSSION" -> {
                if (memory.currentCode != null || aiLower.contains("code it") || aiLower.contains("go ahead")) {
                    "CODING"
                } else {
                    currentStage
                }
            }
            "CODING" -> {
                if (memory.currentCode != null && lower.length > 20) {
                    "CODE_REVIEW"
                } else {
                    currentStage
                }
            }
            "CODE_REVIEW" -> {
                if (lower.contains("o(") || lower.contains("complexity") || lower.contains("time") ||
                    aiLower.contains("complexity")) {
                    "COMPLEXITY_ANALYSIS"
                } else {
                    currentStage
                }
            }
            "COMPLEXITY_ANALYSIS" -> "EDGE_CASES"
            "EDGE_CASES" -> {
                if (aiLower.contains("optimize") || aiLower.contains("improve")) {
                    "OPTIMIZATION"
                } else {
                    "WRAP_UP"
                }
            }
            else -> currentStage
        }

        if (newStage != currentStage) {
            try {
                redisMemoryService.updateMemory(sessionId) { mem ->
                    mem.copy(interviewStage = newStage)
                }
                log.debug("Interview stage {} → {} for session {}", currentStage, newStage, sessionId)
            } catch (e: Exception) {
                log.warn("Failed to update interview stage for session {}: {}", sessionId, e.message)
            }
        }
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
