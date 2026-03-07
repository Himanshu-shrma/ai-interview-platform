package com.aiinterview.conversation

import com.aiinterview.interview.service.InterviewMemory
import com.aiinterview.interview.service.RedisMemoryService
import com.aiinterview.shared.ai.LlmProviderRegistry
import com.aiinterview.shared.ai.LlmRequest
import com.aiinterview.shared.ai.ModelConfig
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

private const val MAX_FOLLOW_UPS = 3

@Component
class FollowUpGenerator(
    private val llm: LlmProviderRegistry,
    private val modelConfig: ModelConfig,
    private val redisMemoryService: RedisMemoryService,
) {
    private val log = LoggerFactory.getLogger(FollowUpGenerator::class.java)

    companion object {
        const val SYSTEM_PROMPT =
            "You are a technical interviewer generating a targeted follow-up question. " +
                "The question must address a specific gap in the candidate's understanding. " +
                "Be concise (1-2 sentences max). Do not repeat questions already asked."
    }

    suspend fun generate(memory: InterviewMemory, gaps: List<String>): String {
        if (memory.followUpsAsked.size >= MAX_FOLLOW_UPS) {
            log.debug("Follow-up limit reached for session {}", memory.sessionId)
            return ""
        }

        val userPrompt = buildUserPrompt(memory, gaps)
        val question = callLlm(userPrompt) ?: return ""

        redisMemoryService.updateMemory(memory.sessionId) { mem ->
            mem.copy(followUpsAsked = mem.followUpsAsked + question)
        }

        log.debug("Follow-up generated for session {}: {}", memory.sessionId, question.take(80))
        return question
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
        log.error("LLM call failed in FollowUpGenerator: {}", e.message)
        null
    }

    private fun buildUserPrompt(memory: InterviewMemory, gaps: List<String>): String = buildString {
        memory.currentQuestion?.let { q -> append("Question: ${q.title}\n") }
        append("Gaps in candidate's answer: ${gaps.joinToString(", ")}\n")
        if (memory.followUpsAsked.isNotEmpty()) {
            append("Questions already asked (do NOT repeat):\n")
            memory.followUpsAsked.forEach { append("- $it\n") }
        }
        append("\nGenerate a targeted follow-up question addressing one of the gaps above.")
    }
}
