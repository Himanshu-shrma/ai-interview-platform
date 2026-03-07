package com.aiinterview.conversation

import com.aiinterview.interview.service.InterviewMemory
import com.aiinterview.interview.service.RedisMemoryService
import com.aiinterview.interview.ws.OutboundMessage
import com.aiinterview.interview.ws.WsSessionRegistry
import com.aiinterview.shared.ai.LlmProviderRegistry
import com.aiinterview.shared.ai.LlmRequest
import com.aiinterview.shared.ai.ModelConfig
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

private const val MAX_HINTS = 3

data class HintResult(
    val hint: String,
    val level: Int,
    val hintsRemaining: Int,
    val refused: Boolean = false,
)

@Component
class HintGenerator(
    private val llm: LlmProviderRegistry,
    private val modelConfig: ModelConfig,
    private val redisMemoryService: RedisMemoryService,
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

    suspend fun generateHint(memory: InterviewMemory): HintResult {
        if (memory.hintsGiven >= MAX_HINTS) {
            registry.sendMessage(
                memory.sessionId,
                OutboundMessage.HintDelivered(
                    hint = "You've used all available hints for this question.",
                    level = 0,
                    hintsRemaining = 0,
                    refused = true,
                ),
            )
            log.debug("Hint refused for session {} — limit reached", memory.sessionId)
            return HintResult(hint = "", level = 0, hintsRemaining = 0, refused = true)
        }

        val level = memory.hintsGiven + 1
        val userPrompt = buildUserPrompt(memory, level)
        val hint = callLlm(userPrompt) ?: "Think carefully about the problem constraints."

        val deduction = DEDUCTIONS[level] ?: 0.0
        val updated = redisMemoryService.updateMemory(memory.sessionId) { mem ->
            mem.copy(
                hintsGiven = mem.hintsGiven + 1,
                evalScores = mem.evalScores.copy(
                    problemSolving = (mem.evalScores.problemSolving - deduction).coerceAtLeast(0.0),
                ),
            )
        }

        val hintsRemaining = MAX_HINTS - updated.hintsGiven
        registry.sendMessage(
            memory.sessionId,
            OutboundMessage.HintDelivered(hint = hint, level = level, hintsRemaining = hintsRemaining),
        )
        log.debug("Hint level {} delivered for session {}, remaining={}", level, memory.sessionId, hintsRemaining)
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

    private fun buildUserPrompt(memory: InterviewMemory, level: Int): String = buildString {
        memory.currentQuestion?.let { q ->
            append("Question: ${q.title}\n")
            q.optimalApproach?.let { append("Optimal approach (context only, do not reveal): $it\n") }
        }
        memory.candidateAnalysis?.let { ca ->
            ca.approach?.let { append("Candidate's approach: $it\n") }
            if (ca.gaps.isNotEmpty()) append("Known gaps: ${ca.gaps.joinToString(", ")}\n")
        }
        append("\nProvide a Level $level hint.")
    }
}
