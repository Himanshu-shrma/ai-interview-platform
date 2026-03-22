package com.aiinterview.conversation.brain

import com.aiinterview.shared.ai.LlmProviderRegistry
import com.aiinterview.shared.ai.LlmRequest
import com.aiinterview.shared.ai.ModelConfig
import com.aiinterview.shared.ai.ResponseFormat
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Meta-cognitive reviewer. Runs every 5 turns.
 * Reviews the full InterviewerBrain and updates InterviewStrategy.
 * Uses backgroundModel (gpt-4o-mini). NEVER throws.
 */
@Component
class TheStrategist(
    private val brainService: BrainService,
    private val llm: LlmProviderRegistry,
    private val modelConfig: ModelConfig,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(TheStrategist::class.java)

    suspend fun review(sessionId: UUID, brain: InterviewerBrain) {
        if (brain.turnCount == 0 || brain.turnCount % 5 != 0) return

        try {
            val prompt = buildStrategistPrompt(brain)
            val response = llm.complete(
                LlmRequest.build(
                    systemPrompt = prompt,
                    userMessage = "Review the interview and update strategy now.",
                    model = modelConfig.backgroundModel,
                    maxTokens = 300,
                    responseFormat = ResponseFormat.JSON,
                ),
            )

            val cleaned = response.content.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()

            val node = objectMapper.readTree(cleaned)
            val strategy = parseStrategy(node, brain)
            brainService.updateStrategy(sessionId, strategy)

            // Abandon stale hypotheses
            val toAbandon = node.get("hypothesesToAbandon")?.map { it.asText() } ?: emptyList()
            toAbandon.forEach { id ->
                brainService.updateHypothesis(sessionId, id, HypothesisStatus.ABANDONED, "Abandoned by strategist after 10+ turns")
            }

            // Queue formative feedback if struggling for 2+ reviews (TASK-043)
            if (brain.challengeSuccessRate < 0.5f && brain.currentStrategy.updatedAtTurn > 5) {
                brainService.addAction(sessionId, IntendedAction(
                    id = "formative_${brain.turnCount}", type = ActionType.FORMATIVE_FEEDBACK,
                    description = "Candidate struggling. Offer a PRINCIPLE without answer. 'Let me offer a perspective: [general principle].' NEVER give the solution.",
                    priority = 3, expiresAfterTurn = brain.turnCount + 5, source = ActionSource.META_STRATEGY,
                ))
            }

            log.info("TheStrategist updated strategy for session={} at turn={}", sessionId, brain.turnCount)
        } catch (e: Exception) {
            log.warn("TheStrategist failed silently for session={}: {}", sessionId, e.message)
        }
    }

    private fun buildStrategistPrompt(brain: InterviewerBrain): String {
        val completedGoals = brain.interviewGoals.completed.joinToString(", ").ifBlank { "none" }
        val remainingGoals = brain.interviewGoals.remainingRequired().take(3).map { it.description }.joinToString("; ").ifBlank { "all done" }
        val openHypotheses = brain.hypothesisRegistry.hypotheses.filter { it.status == HypothesisStatus.OPEN }.map { it.claim }.take(3).joinToString("; ").ifBlank { "none" }
        val recentScores = brain.exchangeScores.takeLast(5).joinToString(", ") { "${it.dimension}:${it.score}" }.ifBlank { "no scores yet" }
        val unsurfaced = brain.claimRegistry.pendingContradictions.count { !it.surfaced }

        return """
You are reviewing your own interview strategy mid-session. Be honest. Be critical.

STATUS:
Turn: ${brain.turnCount} | Signal: ${brain.candidateProfile.overallSignal}
State: ${brain.candidateProfile.currentState} | Trajectory: ${brain.candidateProfile.trajectory}
Anxiety: ${brain.candidateProfile.anxietyLevel} | Flow: ${brain.candidateProfile.flowState}
Safety: ${brain.candidateProfile.psychologicalSafety}

GOALS COMPLETED: $completedGoals
GOALS REMAINING: $remainingGoals
OPEN HYPOTHESES: $openHypotheses
UNSURFACED CONTRADICTIONS: $unsurfaced
RECENT SCORES: $recentScores

CHALLENGE SUCCESS RATE: ${"%.0f".format(brain.challengeSuccessRate * 100)}%
${when { brain.challengeSuccessRate > 0.85f -> "TOO EASY — raise difficulty immediately"
    brain.challengeSuccessRate < 0.50f -> "TOO HARD — reduce difficulty, find strengths"
    else -> "OPTIMAL (target 60-80%)" }}

THOUGHT THREAD: ${brain.thoughtThread.thread.takeLast(300).ifBlank { "empty" }}

Answer:
1. Is current approach yielding useful signal?
2. What should change about tone/pace/depth?
3. Time allocation for remaining goals?
4. What have you done WRONG? Be brutally honest.
5. Most important thing for next 5 turns?

Return ONLY valid JSON:
{
  "approach": "strategy for next 5 turns (2-3 sentences)",
  "toneGuidance": "how to calibrate tone right now",
  "timeGuidance": "how to allocate remaining time",
  "avoidance": "one thing to STOP doing immediately",
  "recommendedTokens": 100,
  "selfCritique": "honest assessment of what went wrong (1-2 sentences)",
  "hypothesesToAbandon": []
}

recommendedTokens: STRONG+flowing=80, STRUGGLING+overloaded=130, default=100. Range: 60-180.
hypothesesToAbandon: list hypothesis IDs that have been OPEN 10+ turns without being tested. Keeps hypothesis list fresh.
        """.trimIndent()
    }

    private fun parseStrategy(node: com.fasterxml.jackson.databind.JsonNode, brain: InterviewerBrain): InterviewStrategy = try {
        InterviewStrategy(
            approach = node.get("approach")?.asText() ?: "",
            toneGuidance = node.get("toneGuidance")?.asText() ?: "",
            timeGuidance = node.get("timeGuidance")?.asText() ?: "",
            avoidance = node.get("avoidance")?.asText() ?: "",
            recommendedTokens = node.get("recommendedTokens")?.asInt() ?: 100,
            updatedAtTurn = brain.turnCount,
            selfCritique = node.get("selfCritique")?.asText() ?: "",
        )
    } catch (e: Exception) {
        log.warn("Failed to parse strategy: {}", e.message)
        brain.currentStrategy.copy(updatedAtTurn = brain.turnCount)
    }
}
