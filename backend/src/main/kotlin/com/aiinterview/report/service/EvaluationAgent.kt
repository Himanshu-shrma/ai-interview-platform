package com.aiinterview.report.service

import com.aiinterview.interview.service.InterviewMemory
import com.aiinterview.shared.ai.LlmProviderRegistry
import com.aiinterview.shared.ai.LlmRequest
import com.aiinterview.shared.ai.ModelConfig
import com.aiinterview.shared.ai.ResponseFormat
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@JsonIgnoreProperties(ignoreUnknown = true)
data class NextStep(
    val area: String = "",
    val specificGap: String = "",
    val evidenceFromInterview: String = "",
    val actionItem: String = "",
    val resource: String = "",
    val priority: String = "MEDIUM",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EvaluationResult(
    val strengths: List<String> = emptyList(),
    val weaknesses: List<String> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val nextSteps: List<NextStep> = emptyList(),
    val narrativeSummary: String = "",
    val dimensionFeedback: Map<String, String> = emptyMap(),
    val scores: EvaluationScores = EvaluationScores(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EvaluationScores(
    val problemSolving: Double = 0.0,
    val algorithmChoice: Double = 0.0,
    val codeQuality: Double = 0.0,
    val communication: Double = 0.0,
    val efficiency: Double = 0.0,
    val testing: Double = 0.0,
    val initiative: Double = 5.0,
    val learningAgility: Double = 5.0,
)

@Component
class EvaluationAgent(
    private val llm: LlmProviderRegistry,
    private val modelConfig: ModelConfig,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(EvaluationAgent::class.java)

    companion object {
        private const val BASE_SYSTEM_PROMPT =
            "You are an expert technical interviewer writing a post-interview evaluation report. " +
                "Be specific, constructive, and honest. " +
                "Base your evaluation ONLY on what was demonstrated in the interview. " +
                "Return ONLY valid JSON, no markdown, no preamble.\n\n" +
                "SCORING RULES:\n" +
                "- Each dimension is scored 0.0 to 10.0 (one decimal place).\n" +
                "- 0 = not demonstrated at all, 5 = average, 8+ = strong, 10 = exceptional.\n" +
                "- If a dimension was not applicable (e.g. no code written → codeQuality), " +
                "score it based on what WAS discussed, or give 2.0-3.0 if nothing relevant was shown.\n" +
                "- Hints used should lower scores slightly (each hint ≈ -0.5 from relevant dimensions).\n" +
                "- Be fair but honest — most candidates score 3-7.\n\n"

        private const val CODING_CRITERIA =
            "EVALUATION CRITERIA (Coding/DSA):\n" +
                "- problemSolving: Understanding the problem, breaking it down, identifying edge cases\n" +
                "- algorithmChoice: Selecting appropriate data structures and algorithms\n" +
                "- codeQuality: Clean, readable, correct code with proper error handling\n" +
                "- communication: Explaining thought process clearly throughout\n" +
                "- efficiency: Time and space complexity awareness, optimization\n" +
                "- testing: Edge case identification, debugging, verification\n\n"

        private const val BEHAVIORAL_CRITERIA =
            "EVALUATION CRITERIA (Behavioral — use STAR method):\n" +
                "- problemSolving: Maps to SITUATION — Did they set clear context?\n" +
                "- algorithmChoice: Maps to TASK — Did they define their specific role and goals?\n" +
                "- codeQuality: Maps to ACTION — Were their actions specific, detailed, and impactful?\n" +
                "- communication: Maps to RESULT — Did they quantify outcomes and reflect on learnings?\n" +
                "- efficiency: Maps to DEPTH — Did they provide multiple examples across different domains?\n" +
                "- testing: Maps to GROWTH — Did they show self-awareness and learning from experience?\n\n"

        private const val SYSTEM_DESIGN_CRITERIA =
            "EVALUATION CRITERIA (System Design):\n" +
                "- problemSolving: Requirements gathering, scope definition, functional + non-functional\n" +
                "- algorithmChoice: Architecture decisions, component selection, technology choices\n" +
                "- codeQuality: Data modeling, API design, schema decisions\n" +
                "- communication: Explaining trade-offs, driving the design discussion\n" +
                "- efficiency: Scalability analysis, bottleneck identification, capacity estimation\n" +
                "- testing: Reliability, fault tolerance, monitoring considerations\n\n"

        private const val JSON_SCHEMA =
            "JSON schema:\n" +
                "{\n" +
                "  \"strengths\": [\"string\"],\n" +
                "  \"weaknesses\": [\"string\"],\n" +
                "  \"suggestions\": [\"string\"],\n" +
                "  \"nextSteps\": [{\"area\": \"string\", \"specificGap\": \"string\", " +
                "\"evidenceFromInterview\": \"string\", \"actionItem\": \"string\", " +
                "\"resource\": \"string\", \"priority\": \"HIGH|MEDIUM|LOW\"}],\n" +
                "  \"narrativeSummary\": \"string\",\n" +
                "  \"dimensionFeedback\": {\n" +
                "    \"problemSolving\": \"string\",\n" +
                "    \"algorithmChoice\": \"string\",\n" +
                "    \"codeQuality\": \"string\",\n" +
                "    \"communication\": \"string\",\n" +
                "    \"efficiency\": \"string\",\n" +
                "    \"testing\": \"string\"\n" +
                "  },\n" +
                "  \"scores\": {\n" +
                "    \"problemSolving\": 0.0,\n" +
                "    \"algorithmChoice\": 0.0,\n" +
                "    \"codeQuality\": 0.0,\n" +
                "    \"communication\": 0.0,\n" +
                "    \"efficiency\": 0.0,\n" +
                "    \"testing\": 0.0,\n" +
                "    \"initiative\": 5.0,\n" +
                "    \"learningAgility\": 5.0\n" +
                "  }\n" +
                "}"

        fun systemPromptFor(category: String): String {
            val criteria = when (category.uppercase()) {
                "BEHAVIORAL" -> BEHAVIORAL_CRITERIA
                "SYSTEM_DESIGN" -> SYSTEM_DESIGN_CRITERIA
                else -> CODING_CRITERIA
            }
            return BASE_SYSTEM_PROMPT + criteria + JSON_SCHEMA
        }
    }

    private val evaluationTimeoutMs = 60_000L

    suspend fun evaluate(memory: InterviewMemory, brain: com.aiinterview.conversation.brain.InterviewerBrain? = null): EvaluationResult {
        return try {
            withTimeout(evaluationTimeoutMs) {
                attemptEvaluation(memory, brain)
            }
        } catch (e: TimeoutCancellationException) {
            log.error("Evaluation timed out for session {} after {}ms", memory.sessionId, evaluationTimeoutMs)
            defaultResult()
        }
    }

    private suspend fun attemptEvaluation(memory: InterviewMemory, brain: com.aiinterview.conversation.brain.InterviewerBrain? = null): EvaluationResult {
        val userPrompt = buildUserPrompt(memory) + buildBrainEnrichment(brain)
        val systemPrompt = systemPromptFor(memory.category)

        val raw1 = callLlm(systemPrompt, userPrompt)
        val result1 = raw1?.let { parseResult(it) }
        if (result1 != null) return result1

        log.warn("EvaluationAgent first attempt failed for session {} — retrying", memory.sessionId)
        val raw2 = callLlm(systemPrompt, userPrompt)
        val result2 = raw2?.let { parseResult(it) }
        if (result2 != null) return result2

        log.warn("EvaluationAgent both attempts failed for session {} — using default", memory.sessionId)
        return defaultResult()
    }

    private suspend fun callLlm(systemPrompt: String, userPrompt: String): String? = try {
        val response = llm.complete(
            LlmRequest.build(
                systemPrompt = systemPrompt,
                userMessage = userPrompt,
                model = modelConfig.evaluatorModel,
                maxTokens = 2000,
                responseFormat = ResponseFormat.JSON,
            ),
        )
        response.content
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

        append("\nHints given: ${memory.hintsGiven}/3\n")
        if (memory.followUpsAsked.isNotEmpty()) {
            append("Follow-ups asked: ${memory.followUpsAsked.joinToString(", ")}\n")
        }
    }

    private fun buildBrainEnrichment(brain: com.aiinterview.conversation.brain.InterviewerBrain?): String {
        if (brain == null) return ""
        val p = brain.candidateProfile
        val sb = StringBuilder("\n\n=== BRAIN-DERIVED INSIGHTS ===\n")

        // Exchange scores as primary input
        if (brain.exchangeScores.isNotEmpty()) {
            sb.appendLine("EXCHANGE SCORES (use as PRIMARY scoring input — anti-halo):")
            brain.exchangeScores.groupBy { it.dimension }.forEach { (dim, scores) ->
                sb.appendLine("  $dim: avg ${"%.1f".format(scores.map { it.score }.average())}/10 (${scores.size} data points)")
            }
        }

        // Anxiety adjustment
        if (p.avgAnxietyLevel > 0.5f) {
            val adj = if (p.avgAnxietyLevel > 0.7f) "+0.75" else "+0.5"
            sb.appendLine("ANXIETY ADJUSTMENT: avg ${p.avgAnxietyLevel}. Apply $adj to all dimension scores. Note in report.")
        }

        // Productive struggle
        if (p.selfRepairCount > 2) sb.appendLine("PRODUCTIVE STRUGGLE: ${p.selfRepairCount} self-corrections. Positive signal — reward metacognitive awareness.")

        // Reasoning pattern
        if (p.reasoningPattern == com.aiinterview.conversation.brain.ReasoningPattern.SCHEMA_DRIVEN)
            sb.appendLine("REASONING: Schema-driven (expert). +1.0 to algorithm score.")

        // Linguistic pattern
        if (p.linguisticPattern == com.aiinterview.conversation.brain.LinguisticPattern.HEDGED_UNDERSTANDER)
            sb.appendLine("LINGUISTIC: Hedged understander — low confidence ≠ low competence. Adjust upward.")

        // Safety
        if (p.psychologicalSafety < 0.5f) sb.appendLine("LOW SAFETY (${p.psychologicalSafety}): Performance may underestimate ability.")

        // Bloom's
        val highBlooms = brain.bloomsTracker.filter { it.value >= 4 }
        if (highBlooms.isNotEmpty()) sb.appendLine("DEPTH: ${highBlooms.entries.joinToString(", ") { "${it.key}=L${it.value}" }}")

        // Confirmed/refuted hypotheses
        brain.hypothesisRegistry.hypotheses.filter { it.status == com.aiinterview.conversation.brain.HypothesisStatus.CONFIRMED }
            .takeIf { it.isNotEmpty() }?.let { confirmed ->
                sb.appendLine("CONFIRMED GAPS: ${confirmed.joinToString("; ") { it.claim }}")
            }

        // Incorrect claims
        brain.claimRegistry.claims.filter { it.correctness == com.aiinterview.conversation.brain.ClaimCorrectness.INCORRECT }
            .takeIf { it.isNotEmpty() }?.let { incorrect ->
                sb.appendLine("INCORRECT CLAIMS: ${incorrect.joinToString("; ") { "T${it.turn}: ${it.claim}" }}")
            }

        // Initiative guidance
        sb.appendLine("\nINITIATIVE SCORE (0-10): Did candidate go beyond minimum? Proactive edge cases, voluntary optimizations, genuine curiosity = high.")
        sb.appendLine("LEARNING AGILITY SCORE (0-10): How effectively did candidate learn during interview? Hint generalization, self-correction, good 'why' questions = high.")

        sb.appendLine("===========================")
        return sb.toString()
    }

    private fun defaultResult() = EvaluationResult(
        strengths = listOf("Participated in the interview session"),
        weaknesses = listOf("Detailed feedback unavailable for this session"),
        suggestions = listOf(
            "Review core data structures and algorithms",
            "Practice explaining your thought process aloud",
        ),
        narrativeSummary = "The candidate completed the interview session. " +
            "Detailed evaluation could not be generated at this time.",
        dimensionFeedback = mapOf(
            "problemSolving" to "Not evaluated",
            "algorithmChoice" to "Not evaluated",
            "codeQuality" to "Not evaluated",
            "communication" to "Not evaluated",
            "efficiency" to "Not evaluated",
            "testing" to "Not evaluated",
        ),
        scores = EvaluationScores(
            problemSolving = 3.0, algorithmChoice = 3.0, codeQuality = 3.0,
            communication = 3.0, efficiency = 3.0, testing = 3.0,
        ),
    )
}
