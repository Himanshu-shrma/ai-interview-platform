package com.aiinterview.report.service

import com.aiinterview.interview.service.InterviewMemory
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.OpenAIClient
import com.openai.models.ChatModel
import com.openai.models.chat.completions.ChatCompletionCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@JsonIgnoreProperties(ignoreUnknown = true)
data class EvaluationResult(
    val strengths: List<String> = emptyList(),
    val weaknesses: List<String> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val narrativeSummary: String = "",
    val dimensionFeedback: Map<String, String> = emptyMap(),
)

/**
 * Calls GPT-4o (non-streaming) to produce a structured post-interview evaluation.
 *
 * Returns a default [EvaluationResult] on repeated parse failures — never throws.
 */
@Component
class EvaluationAgent(
    private val openAIClient: OpenAIClient,
    private val objectMapper: ObjectMapper,
    @Value("\${openai.model.interviewer:gpt-4o}") private val model: String,
) {
    private val log = LoggerFactory.getLogger(EvaluationAgent::class.java)

    companion object {
        // STATIC system prompt first — maximises prompt-cache hit rate
        const val SYSTEM_PROMPT =
            "You are an expert technical interviewer writing a post-interview evaluation report. " +
            "Be specific, constructive, and honest. " +
            "Base your evaluation ONLY on what was demonstrated in the interview. " +
            "Return ONLY valid JSON, no markdown, no preamble.\n\n" +
            "JSON schema:\n" +
            "{\n" +
            "  \"strengths\": [\"string\"],\n" +
            "  \"weaknesses\": [\"string\"],\n" +
            "  \"suggestions\": [\"string\"],\n" +
            "  \"narrativeSummary\": \"string\",\n" +
            "  \"dimensionFeedback\": {\n" +
            "    \"problemSolving\": \"string\",\n" +
            "    \"algorithmChoice\": \"string\",\n" +
            "    \"codeQuality\": \"string\",\n" +
            "    \"communication\": \"string\",\n" +
            "    \"efficiency\": \"string\",\n" +
            "    \"testing\": \"string\"\n" +
            "  }\n" +
            "}"
    }

    suspend fun evaluate(memory: InterviewMemory): EvaluationResult {
        val userPrompt = buildUserPrompt(memory)

        // First attempt
        val raw1 = callLlm(userPrompt)
        val result1 = raw1?.let { parseResult(it) }
        if (result1 != null) return result1

        // Retry once
        log.warn("EvaluationAgent first attempt failed for session {} — retrying", memory.sessionId)
        val raw2 = callLlm(userPrompt)
        val result2 = raw2?.let { parseResult(it) }
        if (result2 != null) return result2

        log.warn("EvaluationAgent both attempts failed for session {} — using default", memory.sessionId)
        return defaultResult()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun callLlm(userPrompt: String): String? = try {
        withContext(Dispatchers.IO) {
            val params = ChatCompletionCreateParams.builder()
                .model(ChatModel.of(model))
                .addSystemMessage(SYSTEM_PROMPT)
                .addUserMessage(userPrompt)
                .maxCompletionTokens(1000)
                .build()
            openAIClient.chat().completions().create(params)
                .choices().firstOrNull()?.message()?.content()?.orElse(null)
        }
    } catch (e: Exception) {
        log.error("LLM call failed in EvaluationAgent: {}", e.message)
        null
    }

    private fun parseResult(raw: String): EvaluationResult? {
        val json = raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```")
            .trim()
        return try {
            objectMapper.readValue(json, EvaluationResult::class.java)
        } catch (e: Exception) {
            log.warn("JSON parse error in EvaluationAgent: {}", e.message)
            null
        }
    }

    private fun buildUserPrompt(memory: InterviewMemory): String = buildString {
        append("Interview Category: ${memory.category}\n")
        memory.currentQuestion?.let { q ->
            append("Difficulty: ${q.difficulty}\n")
            append("Question: ${q.title}\n")
            append("Description: ${q.description.take(500)}\n")
        }

        append("\nTranscript Summary:\n")
        if (memory.earlierContext.isNotBlank()) {
            append(memory.earlierContext)
            append("\n")
        }
        memory.rollingTranscript.forEach { turn ->
            append("${turn.role}: ${turn.content}\n")
        }

        memory.currentCode?.let { code ->
            append("\nFinal Code Submitted:\n```\n${code.take(2000)}\n```\n")
        }

        memory.candidateAnalysis?.let { ca ->
            append("\nCandidate Analysis:\n")
            ca.approach?.let { append("Approach: $it\n") }
            ca.correctness?.let { append("Correctness: $it\n") }
            if (ca.gaps.isNotEmpty()) append("Gaps identified: ${ca.gaps.joinToString(", ")}\n")
        }

        append("\nRaw Eval Scores (for context only — do not repeat these numbers verbatim):\n")
        val s = memory.evalScores
        append("  problemSolving=${s.problemSolving}, algorithmChoice=${s.algorithmChoice}, ")
        append("codeQuality=${s.codeQuality}, communication=${s.communication}, ")
        append("efficiency=${s.efficiency}, testing=${s.testing}\n")
        append("Hints given: ${memory.hintsGiven}\n")
        if (memory.followUpsAsked.isNotEmpty()) {
            append("Follow-ups asked: ${memory.followUpsAsked.joinToString(", ")}\n")
        }
    }

    private fun defaultResult() = EvaluationResult(
        strengths         = listOf("Participated in the interview session"),
        weaknesses        = listOf("Detailed feedback unavailable for this session"),
        suggestions       = listOf(
            "Review core data structures and algorithms",
            "Practice explaining your thought process aloud",
        ),
        narrativeSummary  = "The candidate completed the interview session. " +
            "Detailed evaluation could not be generated at this time.",
        dimensionFeedback = mapOf(
            "problemSolving"  to "Not evaluated",
            "algorithmChoice" to "Not evaluated",
            "codeQuality"     to "Not evaluated",
            "communication"   to "Not evaluated",
            "efficiency"      to "Not evaluated",
            "testing"         to "Not evaluated",
        ),
    )
}
