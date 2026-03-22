package com.aiinterview.conversation.brain

import com.aiinterview.interview.model.ConversationMessage
import com.aiinterview.interview.repository.ConversationMessageRepository
import com.aiinterview.interview.repository.InterviewSessionRepository
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
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID

private const val STREAM_TIMEOUT_MS = 10_000L
private const val MAX_TOKENS_FALLBACK = 200

/**
 * The main response generator for the new brain system.
 * Replaces InterviewerAgent when feature flag is enabled.
 * Uses NaturalPromptBuilder to build brain-driven prompts.
 * Preserves identical streaming behavior to InterviewerAgent.
 */
@Component
class TheConductor(
    private val brainService: BrainService,
    private val promptBuilder: NaturalPromptBuilder,
    private val llm: LlmProviderRegistry,
    private val modelConfig: ModelConfig,
    private val registry: WsSessionRegistry,
    private val conversationMessageRepository: ConversationMessageRepository,
    private val sessionRepository: InterviewSessionRepository,
    @Value("\${interview.use-new-brain:false}") val useNewBrain: Boolean,
) {
    private val log = LoggerFactory.getLogger(TheConductor::class.java)

    companion object {
        private val IDK_SIGNALS = listOf(
            "i don't know", "i'm not sure", "i have no idea",
            "i haven't seen this", "i don't remember", "not familiar with", "no idea",
        )
    }

    enum class SilenceDecision { RESPOND, SILENT, WAIT_THEN_RESPOND }

    /**
     * Generate AI response using the brain system.
     * Returns the full response text (for TheAnalyst).
     * Streaming is identical to InterviewerAgent.
     */
    suspend fun respond(
        sessionId: UUID,
        candidateMessage: String,
        brain: InterviewerBrain,
        state: InterviewState,
    ): String {
        // Check silence intelligence
        val silenceDecision = shouldRespond(brain, candidateMessage, state)

        return when (silenceDecision) {
            SilenceDecision.SILENT -> {
                log.debug("TheConductor: SILENT for session={}", sessionId)
                ""
            }
            SilenceDecision.WAIT_THEN_RESPOND -> {
                kotlinx.coroutines.delay(2000)
                val msg = listOf("Take your time.", "Go ahead.", "Whenever you're ready.", "No rush.").random()
                streamStaticResponse(sessionId, msg)
                persistResponse(sessionId, msg)
                msg
            }
            SilenceDecision.RESPOND -> {
                generateResponse(sessionId, candidateMessage, brain, state)
            }
        }
    }

    private suspend fun generateResponse(
        sessionId: UUID,
        candidateMessage: String,
        brain: InterviewerBrain,
        state: InterviewState,
    ): String {
        // CODING GATE — only for coding types
        val isCodingInterview = brain.interviewType.uppercase() in setOf("CODING", "DSA")
        val hasMeaningfulCode = brain.currentCode?.trim()?.let { it.length > 50 && it.lines().count { l -> l.isNotBlank() } > 3 } ?: false

        if (isCodingInterview && state.currentPhaseLabel == "CODING" && !hasMeaningfulCode) {
            val msgLower = candidateMessage.lowercase()
            val canned = when {
                candidateMessage.length > 150 -> "I think I follow the approach \u2014 go ahead and implement it."
                msgLower.contains("done") || msgLower.contains("finish") -> "I don't see code yet \u2014 go ahead and implement when ready."
                else -> "Go ahead \u2014 I'll wait while you code."
            }
            streamStaticResponse(sessionId, canned)
            persistResponse(sessionId, canned)
            return canned
        }

        // Build prompt from brain state
        val codeContent = if (isCodingInterview && state.currentPhaseLabel in setOf("REVIEW", "FOLLOWUP", "WRAP_UP")) brain.currentCode?.take(2000) else null
        val systemPrompt = promptBuilder.build(brain, state, codeContent)
        val fullResponse = StringBuilder()

        // Consume top action after it's in the prompt
        brain.actionQueue.topAction()?.let {
            try { brainService.completeTopAction(sessionId) } catch (_: Exception) {}
        }

        // Stream using interviewerModel (same as InterviewerAgent)
        val maxTokens = brain.currentStrategy.recommendedTokens.coerceIn(60, 200)
        val success = tryStreaming(sessionId, systemPrompt, candidateMessage, fullResponse, maxTokens)

        if (!success) {
            log.warn("TheConductor streaming failed for session {}, falling back", sessionId)
            val ok = tryFallback(sessionId, systemPrompt, candidateMessage, fullResponse)
            if (!ok) {
                registry.sendMessage(sessionId, OutboundMessage.Error("AI_ERROR", "Interview assistant unavailable"))
                return ""
            }
        }

        registry.sendMessage(sessionId, OutboundMessage.AiChunk(delta = "", done = true))

        val responseText = fullResponse.toString()
        if (responseText.isNotBlank()) {
            persistResponse(sessionId, responseText)
            brainService.appendTranscriptTurn(sessionId, "AI", responseText)
        }
        return responseText
    }

    private fun shouldRespond(brain: InterviewerBrain, candidateMessage: String, state: InterviewState): SilenceDecision {
        // Always respond to questions, help requests, done signals
        val lower = candidateMessage.lowercase().trim()
        if (lower.endsWith("?")) return SilenceDecision.RESPOND
        if (lower.contains("help") || lower.contains("hint") || lower.contains("stuck")) return SilenceDecision.RESPOND
        if (lower.contains("done") || lower.contains("finished") || lower.contains("i think this works")) return SilenceDecision.RESPOND
        if (IDK_SIGNALS.any { lower.contains(it) }) return SilenceDecision.RESPOND

        // Always respond to FlowGuard actions
        if (brain.actionQueue.pending.any { it.source == ActionSource.FLOW_GUARD }) return SilenceDecision.RESPOND

        // Silent during coding (no text, just code updates)
        val isCodingPhase = state.currentPhaseLabel == "CODING"
        if (isCodingPhase && candidateMessage.length < 10) return SilenceDecision.SILENT

        // Wait on long approach explanation with no urgent action
        if (state.currentPhaseLabel == "APPROACH" && candidateMessage.length > 200 && brain.actionQueue.pending.isEmpty()) {
            return SilenceDecision.WAIT_THEN_RESPOND
        }

        return SilenceDecision.RESPOND
    }

    // ── Streaming (identical pattern to InterviewerAgent) ──

    private suspend fun tryStreaming(
        sessionId: UUID, systemPrompt: String, userMessage: String, fullResponse: StringBuilder, maxTokens: Int,
    ): Boolean = try {
        val request = LlmRequest(
            messages = listOf(LlmMessage(LlmRole.SYSTEM, systemPrompt), LlmMessage(LlmRole.USER, userMessage)),
            model = modelConfig.interviewerModel, maxTokens = maxTokens,
        )
        withTimeout(STREAM_TIMEOUT_MS) {
            llm.stream(request)
                .catch { e -> log.error("Stream error for session {}: {}", sessionId, e.message) }
                .collect { token ->
                    fullResponse.append(token)
                    registry.sendMessage(sessionId, OutboundMessage.AiChunk(delta = token, done = false))
                }
        }
        fullResponse.isNotEmpty()
    } catch (e: TimeoutCancellationException) {
        log.warn("TheConductor first-token timeout for session {}", sessionId); false
    } catch (e: Exception) {
        log.error("TheConductor streaming error for session {}: {}", sessionId, e.message); false
    }

    private suspend fun tryFallback(
        sessionId: UUID, systemPrompt: String, userMessage: String, fullResponse: StringBuilder,
    ): Boolean = try {
        val request = LlmRequest.build(systemPrompt = systemPrompt, userMessage = userMessage, model = modelConfig.backgroundModel, maxTokens = MAX_TOKENS_FALLBACK)
        val response = llm.complete(request)
        fullResponse.append(response.content)
        registry.sendMessage(sessionId, OutboundMessage.AiChunk(delta = response.content, done = false))
        true
    } catch (e: Exception) {
        log.error("TheConductor fallback error for session {}: {}", sessionId, e.message); false
    }

    private suspend fun streamStaticResponse(sessionId: UUID, response: String) {
        registry.sendMessage(sessionId, OutboundMessage.AiChunk(delta = response, done = false))
        registry.sendMessage(sessionId, OutboundMessage.AiChunk(delta = "", done = true))
    }

    private suspend fun persistResponse(sessionId: UUID, responseText: String) {
        try {
            withContext(Dispatchers.IO) {
                conversationMessageRepository.save(
                    ConversationMessage(sessionId = sessionId, role = "AI", content = responseText),
                ).awaitSingle()
            }
        } catch (e: Exception) {
            log.warn("Failed to persist AI message for session {}: {}", sessionId, e.message)
        }
    }
}
