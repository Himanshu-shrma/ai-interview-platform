package com.aiinterview.conversation

import com.aiinterview.interview.service.CandidateAnalysis
import com.aiinterview.interview.service.InterviewMemory
import com.aiinterview.interview.service.RedisMemoryService
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.OpenAIClient
import com.openai.models.ChatModel
import com.openai.models.chat.completions.ChatCompletionCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Result returned from [ReasoningAnalyzer.analyze].
 * [suggestedTransition] is null when no state change is needed.
 */
data class AnalysisResult(
    val analysis: CandidateAnalysis,
    val suggestedTransition: InterviewState?,
)

/**
 * Background agent that calls GPT-4o-mini to extract structured analysis
 * from the candidate's latest message, updates Redis memory, and signals
 * the appropriate state transition to [AgentOrchestrator].
 */
@Component
class ReasoningAnalyzer(
    private val openAIClient: OpenAIClient,
    private val redisMemoryService: RedisMemoryService,
    private val objectMapper: ObjectMapper,
    @Value("\${openai.model.background:gpt-4o-mini}") private val model: String,
) {
    private val log = LoggerFactory.getLogger(ReasoningAnalyzer::class.java)

    companion object {
        // STATIC part first — maximises LLM prompt-cache hit rate
        const val SYSTEM_PROMPT =
            "You are an expert technical interviewer analyzer. Given a candidate's response " +
            "in a coding interview, extract structured analysis. " +
            "Return ONLY valid JSON, no markdown, no explanation."
    }

    suspend fun analyze(memory: InterviewMemory, candidateMessage: String): AnalysisResult {
        val userPrompt = buildUserPrompt(memory, candidateMessage)
        val rawJson    = callLlm(userPrompt)

        val analysis = rawJson?.let { parseAnalysis(it) }
            ?: return fallbackResult().also {
                log.warn("Analysis failed/empty for session {} — using fallback", memory.sessionId)
            }

        val analysisWithTs = analysis.copy(lastUpdatedAt = Instant.now())
        val newScores      = computeUpdatedScores(memory, analysis)

        redisMemoryService.updateMemory(memory.sessionId) { mem ->
            mem.copy(candidateAnalysis = analysisWithTs, evalScores = newScores)
        }

        val transition = when {
            analysis.codingSignalDetected -> InterviewState.CodingChallenge
            analysis.readyForEvaluation   -> InterviewState.Evaluating
            analysis.gaps.isNotEmpty()    -> InterviewState.FollowUp
            else                          -> null
        }

        log.debug(
            "Analysis done session={}: correctness={} gaps={} transition={}",
            memory.sessionId, analysis.correctness, analysis.gaps.size, transition,
        )
        return AnalysisResult(analysisWithTs, transition)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun callLlm(userPrompt: String): String? = try {
        withContext(Dispatchers.IO) {
            val params = ChatCompletionCreateParams.builder()
                .model(ChatModel.of(model))
                .addSystemMessage(SYSTEM_PROMPT)
                .addUserMessage(userPrompt)
                .maxCompletionTokens(300)
                .build()
            openAIClient.chat().completions().create(params)
                .choices().firstOrNull()?.message()?.content()?.orElse(null)
        }
    } catch (e: Exception) {
        log.error("LLM call failed in ReasoningAnalyzer: {}", e.message)
        null
    }

    private fun parseAnalysis(raw: String): CandidateAnalysis? {
        // Strip optional markdown code fences the model might add
        val json = raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```")
            .trim()
        return try {
            objectMapper.readValue(json, CandidateAnalysis::class.java)
        } catch (e: Exception) {
            log.warn("JSON parse error in ReasoningAnalyzer: {}", e.message)
            null
        }
    }

    private fun buildUserPrompt(memory: InterviewMemory, candidateMessage: String): String = buildString {
        memory.currentQuestion?.let { q ->
            append("Question: ${q.title}\n")
            append("Description: ${q.description.take(500)}\n")
            q.optimalApproach?.let { append("Optimal approach: $it\n") }
        }
        memory.candidateAnalysis?.let { prev ->
            append("Previous analysis: correctness=${prev.correctness}, gaps=${prev.gaps}\n")
        }
        append("\nCandidate's latest message:\n$candidateMessage")
    }

    private fun computeUpdatedScores(memory: InterviewMemory, analysis: CandidateAnalysis) =
        memory.evalScores.let { s ->
            val psDelta   = when (analysis.correctness) {
                "correct" -> 1.5;  "partial" -> 0.75;  else -> 0.0
            }
            val commDelta = if (analysis.gaps.isNotEmpty()) -0.25 * analysis.gaps.size else 0.0
            s.copy(
                problemSolving = (s.problemSolving + psDelta).coerceAtMost(10.0),
                communication  = (s.communication + commDelta).coerceAtLeast(0.0),
            )
        }

    private fun fallbackResult() = AnalysisResult(
        analysis            = CandidateAnalysis(lastUpdatedAt = Instant.now()),
        suggestedTransition = null,
    )
}
