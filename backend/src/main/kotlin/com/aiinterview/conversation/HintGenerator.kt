package com.aiinterview.conversation

import com.aiinterview.conversation.brain.BrainService
import com.aiinterview.conversation.brain.InterviewerBrain
import com.aiinterview.conversation.brain.computeBrainInterviewState
import com.aiinterview.interview.ws.OutboundMessage
import com.aiinterview.interview.ws.WsSessionRegistry
import com.aiinterview.shared.ai.LlmProviderRegistry
import com.aiinterview.shared.ai.LlmRequest
import com.aiinterview.shared.ai.ModelConfig
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

private const val MAX_HINTS = 3

data class HintResult(
    val hint: String,
    val level: Int,
    val hintsRemaining: Int,
    val refused: Boolean = false,
)

/**
 * Generates context-aware hints from InterviewerBrain.
 * Reads: brain.hintsGiven, brain.candidateProfile.anxietyLevel,
 * brain.questionDetails, brain.hintOutcomes, brain.interviewGoals.
 * Zero InterviewMemory dependency.
 */
@Component
class HintGenerator(
    private val llm: LlmProviderRegistry,
    private val modelConfig: ModelConfig,
    private val brainService: BrainService,
    private val registry: WsSessionRegistry,
) {
    private val log = LoggerFactory.getLogger(HintGenerator::class.java)

    companion object {
        const val SYSTEM_PROMPT =
            "You are a technical interviewer providing a hint. " +
                "The hint must be at the appropriate level: " +
                "Level 1 is abstract (point toward a concept), " +
                "Level 2 names a data structure, " +
                "Level 3 describes the approach without giving code."

        private val DEDUCTIONS = mapOf(1 to 0.5, 2 to 1.0, 3 to 1.5)
    }

    /**
     * Generates a hint using Brain as the sole state source.
     * Accepts sessionId — loads brain internally.
     */
    suspend fun generateHint(sessionId: UUID): HintResult {
        val brain = brainService.getBrainOrNull(sessionId)
        if (brain == null) {
            log.warn("Brain not found for hint request: session={}", sessionId)
            registry.sendMessage(
                sessionId,
                OutboundMessage.HintDelivered(hint = "Unable to generate hint.", level = 0, hintsRemaining = 0, refused = true),
            )
            return HintResult(hint = "", level = 0, hintsRemaining = 0, refused = true)
        }

        if (brain.hintsGiven >= MAX_HINTS) {
            registry.sendMessage(
                sessionId,
                OutboundMessage.HintDelivered(
                    hint = "You've used all available hints for this question.",
                    level = 0,
                    hintsRemaining = 0,
                    refused = true,
                ),
            )
            log.debug("Hint refused for session {} — limit reached", sessionId)
            return HintResult(hint = "", level = 0, hintsRemaining = 0, refused = true)
        }

        val level = brain.hintsGiven + 1
        val userPrompt = buildUserPrompt(brain, level)
        val hint = callLlm(userPrompt) ?: "Think carefully about the problem constraints."

        // Update brain: increment hintsGiven
        brainService.updateBrain(sessionId) { b ->
            b.copy(hintsGiven = b.hintsGiven + 1)
        }

        val hintsRemaining = MAX_HINTS - level
        registry.sendMessage(
            sessionId,
            OutboundMessage.HintDelivered(hint = hint, level = level, hintsRemaining = hintsRemaining),
        )
        log.debug("Hint level {} delivered for session {}, remaining={}", level, sessionId, hintsRemaining)
        return HintResult(hint, level, hintsRemaining)
    }

    private suspend fun callLlm(userPrompt: String): String? = try {
        val response = llm.complete(
            LlmRequest.build(
                systemPrompt = SYSTEM_PROMPT,
                userMessage = userPrompt,
                model = modelConfig.backgroundModel,
                maxTokens = 150,
            ),
        )
        response.content
    } catch (e: Exception) {
        log.error("LLM call failed in HintGenerator: {}", e.message)
        null
    }

    private fun buildUserPrompt(brain: InterviewerBrain, level: Int): String = buildString {
        // Question context
        val q = brain.questionDetails
        if (q.title.isNotBlank()) {
            append("Question: ${q.title}\n")
            if (q.optimalApproach.isNotBlank()) {
                append("Optimal approach (context only, do not reveal): ${q.optimalApproach}\n")
            }
        }

        // Anxiety-aware tone
        val anxiety = brain.candidateProfile.anxietyLevel
        when {
            anxiety > 0.7f -> append("\nThe candidate is showing HIGH anxiety. Lead with reassurance before the technical hint. Keep the tone calm.\n")
            anxiety > 0.4f -> append("\nThe candidate shows moderate anxiety. Be encouraging.\n")
        }

        // Phase context
        val state = computeBrainInterviewState(brain, 30)
        when (state.currentPhaseLabel) {
            "APPROACH" -> append("This is an APPROACH-phase hint. Guide their thinking, do not reveal algorithm names.\n")
            "CODING" -> append("This is a CODING-phase hint. Focus on debugging and implementation direction.\n")
        }

        // Previous hints (don't repeat)
        if (brain.hintOutcomes.isNotEmpty()) {
            val prev = brain.hintOutcomes.joinToString("; ") { "L${it.hintLevel}: ${it.conceptTaught}" }
            append("Previous hints given: $prev. Do NOT repeat these angles.\n")
        }

        append("\nProvide a Level $level hint.")
    }
}
