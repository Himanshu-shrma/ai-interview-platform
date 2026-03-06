package com.aiinterview.conversation

import com.aiinterview.interview.service.InterviewMemory
import com.aiinterview.interview.service.RedisMemoryService
import com.openai.client.OpenAIClient
import com.openai.models.ChatModel
import com.openai.models.chat.completions.ChatCompletionCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

private const val MAX_FOLLOW_UPS = 3

/**
 * Background agent that generates targeted follow-up questions based on
 * gaps identified by [ReasoningAnalyzer].
 *
 * Returns an empty string when the follow-up limit is reached; the caller
 * ([AgentOrchestrator]) should transition to [InterviewState.Evaluating].
 */
@Component
class FollowUpGenerator(
    private val openAIClient: OpenAIClient,
    private val redisMemoryService: RedisMemoryService,
    @Value("\${openai.model.background:gpt-4o-mini}") private val model: String,
) {
    private val log = LoggerFactory.getLogger(FollowUpGenerator::class.java)

    companion object {
        // STATIC part first for prompt caching
        const val SYSTEM_PROMPT =
            "You are a technical interviewer generating a targeted follow-up question. " +
            "The question must address a specific gap in the candidate's understanding. " +
            "Be concise (1-2 sentences max). Do not repeat questions already asked."
    }

    /**
     * Generates a follow-up question targeting [gaps].
     * Returns empty string when the follow-up limit has been reached.
     */
    suspend fun generate(memory: InterviewMemory, gaps: List<String>): String {
        if (memory.followUpsAsked.size >= MAX_FOLLOW_UPS) {
            log.debug("Follow-up limit reached for session {}", memory.sessionId)
            return ""
        }

        val userPrompt = buildUserPrompt(memory, gaps)
        val question   = callLlm(userPrompt) ?: return ""

        redisMemoryService.updateMemory(memory.sessionId) { mem ->
            mem.copy(followUpsAsked = mem.followUpsAsked + question)
        }

        log.debug("Follow-up generated for session {}: {}", memory.sessionId, question.take(80))
        return question
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun callLlm(userPrompt: String): String? = try {
        withContext(Dispatchers.IO) {
            val params = ChatCompletionCreateParams.builder()
                .model(ChatModel.of(model))
                .addSystemMessage(SYSTEM_PROMPT)
                .addUserMessage(userPrompt)
                .maxCompletionTokens(150)
                .build()
            openAIClient.chat().completions().create(params)
                .choices().firstOrNull()?.message()?.content()?.orElse(null)
        }
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
