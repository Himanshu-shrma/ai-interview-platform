package com.aiinterview.report.service

import com.aiinterview.conversation.brain.ClaimCorrectness
import com.aiinterview.conversation.brain.HypothesisStatus
import com.aiinterview.conversation.brain.InterviewerBrain
import com.aiinterview.conversation.brain.LinguisticPattern
import com.aiinterview.conversation.brain.ReasoningPattern
import com.aiinterview.shared.ai.LlmProviderRegistry
import com.aiinterview.shared.ai.LlmRequest
import com.aiinterview.shared.ai.ModelConfig
import com.aiinterview.shared.ai.ResponseFormat
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@JsonIgnoreProperties(ignoreUnknown = true)
data class StudyResource(
    val type: String = "leetcode",   // "leetcode" | "youtube" | "article"
    val id: Int? = null,
    val url: String = "",
    val title: String = "",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NextStep(
    // New structured fields
    val topic: String = "",
    val gap: String = "",
    val evidence: String = "",
    val resources: List<StudyResource> = emptyList(),
    val estimatedHours: Int = 1,
    val priority: String = "MEDIUM",
    // Legacy fields — kept so old DB records still deserialize cleanly
    val area: String = "",
    val specificGap: String = "",
    val evidenceFromInterview: String = "",
    val actionItem: String = "",
    val resource: String = "",
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
            "JSON schema (return ONLY this object, no other text):\n" +
                "{\n" +
                "  \"strengths\": [\"string\"],\n" +
                "  \"weaknesses\": [\"string\"],\n" +
                "  \"suggestions\": [\"string\"],\n" +
                "  \"nextSteps\": [\n" +
                "    {\n" +
                "      \"topic\": \"short topic label\",\n" +
                "      \"gap\": \"one sentence describing the specific gap observed\",\n" +
                "      \"evidence\": \"Turn N: exact quote from this interview\",\n" +
                "      \"resources\": [\n" +
                "        {\"type\": \"leetcode\", \"id\": 200, \"title\": \"Number of Islands\"},\n" +
                "        {\"type\": \"youtube\", \"url\": \"https://neetcode.io/...\", \"title\": \"Video title\"}\n" +
                "      ],\n" +
                "      \"estimatedHours\": 2,\n" +
                "      \"priority\": \"HIGH\"\n" +
                "    }\n" +
                "  ],\n" +
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
                "}\n\n" +
                "STUDY PLAN RULES:\n" +
                "- nextSteps: 3-5 items ordered HIGH → MEDIUM → LOW priority.\n" +
                "- topic: concise label (e.g. \"Graph BFS vs DFS\", \"Hash map design\").\n" +
                "- evidence: MUST quote an actual turn from this transcript — not generic.\n" +
                "- resources: include 1-2 leetcode problems AND 1 neetcode.io or YouTube link per item.\n" +
                "- estimatedHours: realistic study time (1-6).\n" +
                "- priority: HIGH|MEDIUM|LOW only."

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

    /**
     * Evaluates the interview using InterviewerBrain as the ONLY state source.
     * No InterviewMemory dependency — brain contains all signals needed.
     */
    suspend fun evaluate(brain: InterviewerBrain): EvaluationResult {
        return try {
            withTimeout(evaluationTimeoutMs) {
                attemptEvaluation(brain)
            }
        } catch (e: TimeoutCancellationException) {
            log.error("Evaluation timed out for session {} after {}ms", brain.sessionId, evaluationTimeoutMs)
            defaultResult()
        }
    }

    private suspend fun attemptEvaluation(brain: InterviewerBrain): EvaluationResult {
        val userPrompt = buildUserPrompt(brain) + buildBrainEnrichment(brain)
        val systemPrompt = systemPromptFor(brain.interviewType)

        val raw1 = callLlm(systemPrompt, userPrompt)
        val result1 = raw1?.let { parseResult(it) }
        if (result1 != null) return result1

        log.warn("EvaluationAgent first attempt failed for session {} — retrying", brain.sessionId)
        val raw2 = callLlm(systemPrompt, userPrompt)
        val result2 = raw2?.let { parseResult(it) }
        if (result2 != null) return result2

        log.warn("EvaluationAgent both attempts failed for session {} — using default", brain.sessionId)
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
            log.warn("Full JSON parse failed in EvaluationAgent: {} — attempting partial parse", e.message)
            tryPartialParse(json)
        }
    }

    /**
     * Partial parse fallback: uses Jackson tree to extract each field independently.
     * This recovers a usable EvaluationResult even when one field (e.g. a nextStep
     * with an unescaped quote in evidence) causes the full parse to fail.
     */
    private fun tryPartialParse(json: String): EvaluationResult? {
        return try {
        val node: JsonNode = objectMapper.readTree(json)

        val scoresNode = node.path("scores")
        if (scoresNode.isMissingNode) {
            log.warn("EvaluationAgent partial parse: scores missing — giving up")
            return null
        }
        val scores = objectMapper.treeToValue(scoresNode, EvaluationScores::class.java)

        val nextSteps: List<NextStep> = try {
            val ns = node.path("nextSteps")
            if (!ns.isMissingNode && ns.isArray)
                objectMapper.readerForListOf(NextStep::class.java).readValue(ns)
            else emptyList()
        } catch (ex: Exception) {
            log.warn("EvaluationAgent partial parse: nextSteps extraction failed: {}", ex.message)
            emptyList()
        }

        val dimFeedback: Map<String, String> = try {
            objectMapper.convertValue(
                node.path("dimensionFeedback"),
                object : TypeReference<Map<String, String>>() {},
            )
        } catch (_: Exception) { emptyMap() }

        fun stringList(field: String): List<String> =
            node.path(field).mapNotNull { it.asText().takeIf { s -> s.isNotBlank() } }

        EvaluationResult(
            strengths        = stringList("strengths"),
            weaknesses       = stringList("weaknesses"),
            suggestions      = stringList("suggestions"),
            nextSteps        = nextSteps,
            narrativeSummary = node.path("narrativeSummary").asText(""),
            dimensionFeedback = dimFeedback,
            scores           = scores,
        )
        } catch (e: Exception) {
            log.warn("EvaluationAgent partial parse also failed: {}", e.message)
            null
        }
    }

    /**
     * Builds the user prompt from InterviewerBrain — replaces the old InterviewMemory-based prompt.
     */
    private fun buildUserPrompt(brain: InterviewerBrain): String = buildString {
        append("Interview Category: ${brain.interviewType}\n")
        append("Difficulty: ${brain.questionDetails.difficulty}\n")
        if (brain.questionDetails.title.isNotBlank()) {
            append("Question: ${brain.questionDetails.title}\n")
            append("Description: ${brain.questionDetails.description.take(500)}\n")
        }

        append("\nTranscript Summary:\n")
        if (brain.earlierContext.isNotBlank()) {
            append(brain.earlierContext)
            append("\n")
        }
        brain.rollingTranscript.forEach { turn ->
            append("${turn.role}: ${turn.content}\n")
        }

        brain.currentCode?.let { code ->
            append("\nFinal Code Submitted:\n```\n${code.take(2000)}\n```\n")
        }

        // Candidate profile summary (replaces old candidateAnalysis)
        val p = brain.candidateProfile
        if (p.dataPoints > 0) {
            append("\nCandidate Profile:\n")
            append("Overall signal: ${p.overallSignal}\n")
            append("Thinking style: ${p.thinkingStyle}\n")
            if (p.avoidancePatterns.isNotEmpty()) append("Avoidance patterns: ${p.avoidancePatterns.joinToString(", ")}\n")
        }

        append("\nHints given: ${brain.hintsGiven}/3\n")
        append("Turns: ${brain.turnCount}\n")
    }

    private fun buildBrainEnrichment(brain: InterviewerBrain): String {
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
        if (p.reasoningPattern == ReasoningPattern.SCHEMA_DRIVEN)
            sb.appendLine("REASONING: Schema-driven (expert). +1.0 to algorithm score.")

        // Linguistic pattern
        if (p.linguisticPattern == LinguisticPattern.HEDGED_UNDERSTANDER)
            sb.appendLine("LINGUISTIC: Hedged understander — low confidence ≠ low competence. Adjust upward.")

        // Safety
        if (p.psychologicalSafety < 0.5f) sb.appendLine("LOW SAFETY (${p.psychologicalSafety}): Performance may underestimate ability.")

        // Bloom's
        val highBlooms = brain.bloomsTracker.filter { it.value >= 4 }
        if (highBlooms.isNotEmpty()) sb.appendLine("DEPTH: ${highBlooms.entries.joinToString(", ") { "${it.key}=L${it.value}" }}")

        // Confirmed/refuted hypotheses
        brain.hypothesisRegistry.hypotheses.filter { it.status == HypothesisStatus.CONFIRMED }
            .takeIf { it.isNotEmpty() }?.let { confirmed ->
                sb.appendLine("CONFIRMED GAPS: ${confirmed.joinToString("; ") { it.claim }}")
            }

        // Incorrect claims
        brain.claimRegistry.claims.filter { it.correctness == ClaimCorrectness.INCORRECT }
            .takeIf { it.isNotEmpty() }?.let { incorrect ->
                sb.appendLine("INCORRECT CLAIMS: ${incorrect.joinToString("; ") { "T${it.turn}: ${it.claim}" }}")
            }

        // Initiative guidance
        sb.appendLine("\nINITIATIVE SCORE (0-10): Did candidate go beyond minimum? Proactive edge cases, voluntary optimizations, genuine curiosity = high.")
        sb.appendLine("LEARNING AGILITY SCORE (0-10): How effectively did candidate learn during interview? Hint generalization, self-correction, good 'why' questions = high.")

        // ZDP edge topics
        brain.zdpEdge.values.filter { it.canDoWithPrompt && !it.canDoAlone }.takeIf { it.isNotEmpty() }?.let { edge ->
            sb.appendLine("ZDP EDGE (knows with help): ${edge.joinToString(", ") { it.topic }}. Growth areas near current capability.")
        }
        // Challenge calibration
        sb.appendLine("DIFFICULTY: ${(brain.challengeSuccessRate * 100).toInt()}% success rate. ${
            when { brain.challengeSuccessRate > 0.85f -> "Interview may have been too easy."; brain.challengeSuccessRate < 0.50f -> "Interview may have been too hard."; else -> "Optimal calibration." }
        }")
        // Interleaving
        val interleavedTopics = brain.topicHistory.distinct().filter { t -> brain.topicHistory.count { it == t } > 1 }
        if (interleavedTopics.isNotEmpty()) sb.appendLine("INTERLEAVING: Topics revisited: ${interleavedTopics.joinToString(", ")}. Return-visit performance tests generalization.")
        // STAR ownership (behavioral)
        if (brain.interviewType.uppercase() == "BEHAVIORAL") {
            val ownershipGoals = brain.interviewGoals.completed.count { it.contains("ownership") }
            val stories = brain.interviewGoals.completed.count { it.startsWith("star_q") && it.endsWith("_complete") }
            if (stories > 0) sb.appendLine("OWNERSHIP: $ownershipGoals/$stories stories had clear personal ownership.")
        }
        // Formative feedback
        if (brain.formativeFeedbackGiven > 0) sb.appendLine("FORMATIVE FEEDBACK: Given ${brain.formativeFeedbackGiven}x. Normal — does not penalize candidate.")
        // Scoring rubric
        brain.questionDetails.scoringRubric?.let { rubric ->
            if (rubric.algorithmIndicators.isNotEmpty()) {
                sb.appendLine("RUBRIC: ${rubric.algorithmIndicators.joinToString(", ")}")
            }
        }
        // Dimension independence reminder
        sb.appendLine("\nDIMENSION INDEPENDENCE: Score each dimension INDEPENDENTLY. algorithm_depth = WHY it works (not just correct choice). code_quality = readability (not complexity).")

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
