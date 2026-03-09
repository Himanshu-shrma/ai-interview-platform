package com.aiinterview.conversation

import com.aiinterview.interview.service.CandidateAnalysis
import com.aiinterview.interview.service.InterviewMemory
import com.aiinterview.interview.service.RedisMemoryService
import com.aiinterview.shared.ai.LlmProviderRegistry
import com.aiinterview.shared.ai.LlmRequest
import com.aiinterview.shared.ai.ModelConfig
import com.aiinterview.shared.ai.ResponseFormat
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

data class AnalysisResult(
    val analysis: CandidateAnalysis,
    val suggestedTransition: InterviewState?,
)

@Component
class ReasoningAnalyzer(
    private val llm: LlmProviderRegistry,
    private val modelConfig: ModelConfig,
    private val redisMemoryService: RedisMemoryService,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(ReasoningAnalyzer::class.java)

    companion object {
        const val SYSTEM_PROMPT = """You are an expert technical interviewer analyzer. Given a candidate's response in a coding interview, extract structured analysis.

Return ONLY valid JSON matching this exact schema:
{
  "approach": "brief description of candidate's approach or null",
  "confidence": "high|medium|low",
  "correctness": "correct|partial|incorrect",
  "gaps": ["list of knowledge gaps or missing points, empty if none"],
  "codingSignalDetected": true/false,
  "readyForEvaluation": true/false
}

Rules for codingSignalDetected:
- Set TRUE when the candidate has explained their approach sufficiently and is ready to code
- Set TRUE when the candidate says things like "let me code this", "I'll implement it", "let me write the code"
- Set TRUE when they have discussed complexity and approach — they should move to coding
- Set FALSE during initial problem discussion, clarification, or when approach is still unclear
- Set FALSE if the interview stage is already CODING or REVIEW (they're past this point)

Rules for readyForEvaluation:
- Set TRUE only when the candidate has explained approach AND written code AND code has been reviewed
- Set TRUE when the interview stage is REVIEW or FOLLOWUP and the review seems complete
- Set FALSE if they are still in CODING stage or have not coded yet

No markdown, no explanation — return ONLY the JSON object."""
    }

    suspend fun analyze(memory: InterviewMemory, candidateMessage: String): AnalysisResult {
        val userPrompt = buildUserPrompt(memory, candidateMessage)
        val rawJson = callLlm(userPrompt)

        val analysis = rawJson?.let { parseAnalysis(it) }
            ?: return fallbackResult().also {
                log.warn("Analysis failed/empty for session {} — using fallback", memory.sessionId)
            }

        val analysisWithTs = analysis.copy(lastUpdatedAt = Instant.now())
        val newScores = computeUpdatedScores(memory, analysis)

        redisMemoryService.updateMemory(memory.sessionId) { mem ->
            mem.copy(candidateAnalysis = analysisWithTs, evalScores = newScores)
        }

        // Stage-aware transition logic:
        // Only suggest CODING_CHALLENGE if we're pre-coding (not if already coding/reviewing)
        // Only suggest EVALUATING if we're past REVIEW stage
        val stage = memory.interviewStage
        val transition = when {
            analysis.codingSignalDetected && stage in listOf("CLARIFYING", "APPROACH", "PROBLEM_PRESENTED") ->
                InterviewState.CodingChallenge
            analysis.readyForEvaluation && stage in listOf("REVIEW", "FOLLOWUP", "WRAP_UP") ->
                InterviewState.Evaluating
            analysis.gaps.isNotEmpty() && stage !in listOf("CODING", "SMALL_TALK") ->
                InterviewState.FollowUp
            else -> null
        }

        log.debug(
            "Analysis done session={}: stage={} correctness={} gaps={} transition={}",
            memory.sessionId, stage, analysis.correctness, analysis.gaps.size, transition,
        )
        return AnalysisResult(analysisWithTs, transition)
    }

    private suspend fun callLlm(userPrompt: String): String? = try {
        val response = llm.complete(
            LlmRequest.build(
                systemPrompt = SYSTEM_PROMPT,
                userMessage = userPrompt,
                model = modelConfig.backgroundModel,
                maxTokens = 300,
                responseFormat = ResponseFormat.JSON,
            ),
        )
        response.content
    } catch (e: Exception) {
        log.error("LLM call failed in ReasoningAnalyzer: {}", e.message)
        null
    }

    private fun parseAnalysis(raw: String): CandidateAnalysis? {
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
        append("Current interview stage: ${memory.interviewStage}\n")
        append("Has code been submitted: ${!memory.currentCode.isNullOrBlank()}\n")
        if (!memory.currentCode.isNullOrBlank()) {
            append("Code length: ${memory.currentCode.lines().size} lines\n")
        }
        memory.candidateAnalysis?.let { prev ->
            append("Previous analysis: correctness=${prev.correctness}, gaps=${prev.gaps}\n")
        }
        append("\nCandidate's latest message:\n$candidateMessage")
    }

    private fun computeUpdatedScores(memory: InterviewMemory, analysis: CandidateAnalysis) =
        memory.evalScores.let { s ->
            val psDelta = when (analysis.correctness) {
                "correct" -> 1.5; "partial" -> 0.75; else -> 0.0
            }
            val commDelta = if (analysis.gaps.isNotEmpty()) -0.25 * analysis.gaps.size else 0.0
            s.copy(
                problemSolving = (s.problemSolving + psDelta).coerceAtMost(10.0),
                communication = (s.communication + commDelta).coerceAtLeast(0.0),
            )
        }

    private fun fallbackResult() = AnalysisResult(
        analysis = CandidateAnalysis(lastUpdatedAt = Instant.now()),
        suggestedTransition = null,
    )
}
