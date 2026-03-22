package com.aiinterview.conversation.brain

import com.aiinterview.conversation.knowledge.KnowledgeAdjacencyMap
import com.aiinterview.shared.ai.LlmProviderRegistry
import com.aiinterview.shared.ai.LlmRequest
import com.aiinterview.shared.ai.ModelConfig
import com.aiinterview.shared.ai.ResponseFormat
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Single background agent replacing 7 existing agents:
 * SmartOrchestrator, ReasoningAnalyzer, FollowUpGenerator,
 * AgentOrchestrator, StageReflectionAgent, CandidateModelUpdater, ObjectiveTracker.
 *
 * ONE LLM call (backgroundModel = gpt-4o-mini) per exchange.
 * Runs fire-and-forget. NEVER throws — always fails silently.
 */
@Component
class TheAnalyst(
    private val brainService: BrainService,
    private val llm: LlmProviderRegistry,
    private val modelConfig: ModelConfig,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(TheAnalyst::class.java)

    suspend fun analyze(
        sessionId: UUID,
        candidateMessage: String,
        aiResponse: String,
        brain: InterviewerBrain,
    ) {
        try {
            val systemPrompt = buildAnalystPrompt(brain)
            val userMessage = buildExchangeContext(candidateMessage, aiResponse, brain)

            val response = llm.complete(
                LlmRequest.build(
                    systemPrompt = systemPrompt,
                    userMessage = userMessage,
                    model = modelConfig.backgroundModel,
                    maxTokens = 600,
                    responseFormat = ResponseFormat.JSON,
                ),
            )

            val cleaned = response.content.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()

            val decision = parseAnalystResponse(cleaned)
            applyUpdates(sessionId, decision, brain)
        } catch (e: Exception) {
            log.warn("TheAnalyst failed silently for session={}: {}", sessionId, e.message)
        }
    }

    private fun buildAnalystPrompt(brain: InterviewerBrain): String {
        val openHypotheses = brain.hypothesisRegistry.hypotheses
            .filter { it.status == HypothesisStatus.OPEN }.take(3)
            .joinToString("\n") { "- [${it.id}] ${it.claim} (conf: ${it.confidence})" }
            .ifBlank { "none" }

        val recentClaims = brain.claimRegistry.claims.takeLast(5)
            .joinToString("\n") { "- Turn ${it.turn}: ${it.claim}" }
            .ifBlank { "none" }

        val completedGoals = brain.interviewGoals.completed.joinToString(", ").ifBlank { "none" }
        val remainingGoals = brain.interviewGoals.remainingRequired().take(3).map { it.id }.joinToString(", ").ifBlank { "none" }
        val thoughtSnippet = brain.thoughtThread.thread.takeLast(200).ifBlank { "not started" }

        return """
You are the interview analyst. After each exchange, update the interviewer's mental model.
You do NOT generate responses to the candidate.

BRAIN STATE:
Type: ${brain.interviewType} | Turn: ${brain.turnCount}
Signal: ${brain.candidateProfile.overallSignal} | Style: ${brain.candidateProfile.thinkingStyle}
State: ${brain.candidateProfile.currentState} | Anxiety: ${brain.candidateProfile.anxietyLevel}

OPEN HYPOTHESES:
$openHypotheses

RECENT CLAIMS:
$recentClaims

GOALS COMPLETED: $completedGoals
GOALS REMAINING: $remainingGoals

THOUGHT THREAD: $thoughtSnippet

Return ONLY valid JSON. No markdown. No other text.
{
  "candidateProfileUpdate": {
    "thinkingStyle": "bottom-up|top-down|intuitive|methodical|null",
    "overallSignal": "strong|solid|average|struggling|null",
    "currentState": "confident|nervous|stuck|flowing|frustrated|null",
    "trajectory": "improving|declining|stable|null",
    "anxietyLevel": null,
    "flowState": null,
    "psychologicalSafety": null,
    "abstractionLevel": null,
    "selfRepairDetected": false,
    "cognitiveLoadSignal": "nominal|elevated|overloaded|null",
    "newAvoidancePattern": null,
    "dismissalLanguage": false
  },
  "newHypothesis": null,
  "hypothesisUpdates": [],
  "newClaims": [],
  "contradictionFound": null,
  "goalsCompleted": [],
  "thoughtThreadAppend": "",
  "nextAction": null,
  "exchangeScore": null,
  "bloomsLevelUpdate": null,
  "adjacentTopicsToProbe": []
}

RULES:
- candidateProfileUpdate: null = no change. Be conservative.
  anxietyLevel (0.0-1.0): Count hedges ("maybe","perhaps","I think","I'm not sure"). 3+ = 0.5+. Apologies ("sorry") + self-doubt = 0.7+. Excessive checking ("does that make sense?") = +0.1 each.
  cognitiveLoadSignal: "overloaded" if 2+ of: contradicts self within message, repeats same thing, "wait sorry" restarts, asks to repeat question, sentences break down. "elevated" if 1 of these.
  selfRepairDetected: true if "wait actually...", "no I mean...", "let me rethink...", or starts one direction then catches themselves. Self-repair is POSITIVE — active reasoning.
  psychologicalSafety (0.0-1.0): HIGH signals (+0.15 each): thinks aloud freely, admits uncertainty, genuine curiosity, attempts stretch questions. LOW signals (-0.15 each): formulaic answers, refuses uncertain questions, constant apologizing, defensive.
- newHypothesis: only if genuinely new insight. testStrategy must be an OPEN question.
- hypothesisUpdates: newStatus "confirmed"|"refuted"|null.
- newClaims: ONLY specific falsifiable technical claims. ALL must be true: (1) technical assertion, (2) specific not vague, (3) falsifiable, (4) 5+ words, (5) said by CANDIDATE. INCLUDE: "BFS has O(V+E) time complexity". EXCLUDE: "it should work", "I think this is correct", "let me think".
- contradictionFound: only if a new claim CONTRADICTS a previous claim above. Must be genuine technical inconsistency (not a revision). Set claim1 and claim2 to EXACT claim text from recentClaims.
- goalsCompleted: only if CLEARLY demonstrated this exchange. Be conservative.
- thoughtThreadAppend: 1-2 sentences ONLY. MUST reference something specific from THIS exchange. MUST be forward-looking ("next I should..."). MUST NOT be generic ("candidate answered well"). GOOD: "Said BFS without mentioning shortest path — next: probe why BFS specifically?" BAD: "Candidate discussed algorithms."
- nextAction: type=TEST_HYPOTHESIS|SURFACE_CONTRADICTION|ADVANCE_GOAL|PROBE_DEPTH|REDIRECT|EMOTIONAL_ADJUST|REDUCE_LOAD|PRODUCTIVE_UNKNOWN|MENTAL_SIMULATION
- exchangeScore: dimension=problem_solving|algorithm|code_quality|communication|efficiency|testing|initiative|learning_agility. Score 0.0-10.0. PRODUCTIVE STRUGGLE BONUS: if candidate struggled but arrived at correct answer AND selfRepairDetected=true, add +0.5 to the score (cap at 10.0).
- bloomsLevelUpdate: {"topic": level} where level 1-6.
- adjacentTopicsToProbe: topic IDs candidate just demonstrated.

IDK PROTOCOL:
If candidate says "i don't know"/"not sure"/"no idea"/"not familiar with":
  nextAction MUST be: {"type":"PRODUCTIVE_UNKNOWN","description":"Candidate doesn't know [X]. Do NOT give answer. Ask: 'How would you approach figuring this out?' or 'What do you know that might be related?'","priority":1,"expiresInTurns":2}

After a PRODUCTIVE_UNKNOWN was acted on last turn, detect response quality:
  candidateProfileUpdate.unknownHandlingPattern: "reasons-from-principles"|"admits-and-stops"|"panics"|"guesses-blindly"|null

MENTAL SIMULATION:
When solution_implemented is newly in goalsCompleted:
  nextAction MUST be: {"type":"MENTAL_SIMULATION","description":"Code just written. Ask: 'Before we run it — walk me through what happens if I pass in [specific non-trivial input]. Step by step.' Then assess abstraction level 1-5.","priority":1,"bloomsLevel":3,"expiresInTurns":3}
  After simulation, update abstractionLevel: 1=syntax, 2=operation, 3=purpose, 4=algorithm, 5=evaluation.

BLOOM'S LEVELS (for bloomsLevelUpdate):
  1=REMEMBER (recalls fact), 2=UNDERSTAND (explains concept), 3=APPLY (uses correctly),
  4=ANALYZE (compares/contrasts), 5=EVALUATE (judges trade-offs), 6=CREATE (designs novel)
  Format: {"topic_name": level}. Only update if higher than recorded.
        """.trimIndent()
    }

    private fun buildExchangeContext(candidateMessage: String, aiResponse: String, brain: InterviewerBrain): String {
        val isCodingType = brain.interviewType.uppercase() in setOf("CODING", "DSA")
        return buildString {
            appendLine("EXCHANGE:")
            appendLine("Candidate: \"${candidateMessage.take(500)}\"")
            appendLine("AI: \"${aiResponse.take(300)}\"")
            if (isCodingType) {
                val hasCode = !brain.currentCode.isNullOrBlank() && (brain.currentCode?.trim()?.length ?: 0) > 50
                appendLine("Has meaningful code: $hasCode")
            }
            appendLine("Turn: ${brain.turnCount}")
        }
    }

    private fun parseAnalystResponse(json: String): AnalystDecision = try {
        objectMapper.readValue(json, AnalystDecision::class.java)
    } catch (e: Exception) {
        log.warn("Failed to parse analyst response: {}", e.message)
        AnalystDecision()
    }

    private suspend fun applyUpdates(sessionId: UUID, decision: AnalystDecision, brain: InterviewerBrain) {
        // 1. Candidate profile
        decision.candidateProfileUpdate?.let { u ->
            brainService.updateCandidateProfile(sessionId) { p ->
                p.copy(
                    thinkingStyle = u.thinkingStyle?.let { safeEnum<ThinkingStyle>(it) } ?: p.thinkingStyle,
                    overallSignal = u.overallSignal?.let { safeEnum<CandidateSignal>(it) } ?: p.overallSignal,
                    currentState = u.currentState?.let { safeEnum<EmotionalState>(it) } ?: p.currentState,
                    trajectory = u.trajectory?.let { safeEnum<PerformanceTrajectory>(it) } ?: p.trajectory,
                    anxietyLevel = u.anxietyLevel ?: p.anxietyLevel,
                    flowState = u.flowState ?: p.flowState,
                    psychologicalSafety = u.psychologicalSafety ?: p.psychologicalSafety,
                    abstractionLevel = u.abstractionLevel ?: p.abstractionLevel,
                    selfRepairCount = if (u.selfRepairDetected == true) p.selfRepairCount + 1 else p.selfRepairCount,
                    cognitiveLoadSignal = u.cognitiveLoadSignal?.let { safeEnum<CognitiveLoad>(it) } ?: p.cognitiveLoadSignal,
                    avoidancePatterns = u.newAvoidancePattern?.let { p.avoidancePatterns + it } ?: p.avoidancePatterns,
                    unknownHandlingPattern = u.unknownHandlingPattern?.let { safeEnum<UnknownHandling>(it) } ?: p.unknownHandlingPattern,
                    dataPoints = p.dataPoints + 1,
                )
            }
        }

        // 2. New hypothesis + auto-queue TEST_HYPOTHESIS action
        decision.newHypothesis?.let { h ->
            val hId = "h_${brain.turnCount}_${brain.hypothesisRegistry.hypotheses.size}"
            brainService.addHypothesis(sessionId, Hypothesis(
                id = hId, claim = h.claim, confidence = h.confidence,
                supportingEvidence = h.evidence, testStrategy = h.testStrategy,
                priority = h.priority, formedAtTurn = brain.turnCount,
                bloomsLevel = h.bloomsLevel, status = HypothesisStatus.OPEN,
            ))
            brainService.addAction(sessionId, IntendedAction(
                id = "test_$hId", type = ActionType.TEST_HYPOTHESIS,
                description = "Test hypothesis: ${h.claim}\nAsk: ${h.testStrategy}",
                priority = h.priority, expiresAfterTurn = brain.turnCount + 6,
                source = ActionSource.HYPOTHESIS, bloomsLevel = h.bloomsLevel,
            ))
        }

        // 3. Hypothesis updates
        decision.hypothesisUpdates.forEach { u ->
            u.newStatus?.let { status ->
                val hs = when (status.lowercase()) {
                    "confirmed" -> HypothesisStatus.CONFIRMED
                    "refuted" -> HypothesisStatus.REFUTED
                    else -> null
                }
                hs?.let { brainService.updateHypothesis(sessionId, u.id, it, u.newEvidence) }
            }
        }

        // 4. New claims
        decision.newClaims.forEach { c ->
            brainService.addClaim(sessionId, Claim(
                id = "c_${brain.turnCount}_${brain.claimRegistry.claims.size}",
                turn = brain.turnCount, claim = c.claim, topic = c.topic,
                correctness = when (c.correctness.lowercase()) {
                    "correct" -> ClaimCorrectness.CORRECT
                    "incorrect" -> ClaimCorrectness.INCORRECT
                    "partially_correct" -> ClaimCorrectness.PARTIALLY_CORRECT
                    else -> ClaimCorrectness.UNVERIFIED
                },
            ))
        }

        // 5. Contradiction
        decision.contradictionFound?.let { cf ->
            val c1 = brain.claimRegistry.claims.firstOrNull { it.claim == cf.claim1 }
            val c2 = brain.claimRegistry.claims.firstOrNull { it.claim == cf.claim2 }
            if (c1 != null && c2 != null) {
                brainService.addContradiction(sessionId, Contradiction(
                    claim1Id = c1.id, claim2Id = c2.id,
                    claim1Text = cf.claim1, claim2Text = cf.claim2,
                    contradictionDescription = cf.description,
                ))
            }
        }

        // 6. Goals completed
        if (decision.goalsCompleted.isNotEmpty()) brainService.markGoalsComplete(sessionId, decision.goalsCompleted)

        // 7. Thought thread
        if (decision.thoughtThreadAppend.isNotBlank()) brainService.appendThought(sessionId, decision.thoughtThreadAppend)

        // 8. Next action
        decision.nextAction?.let { a ->
            val actionType = try { ActionType.valueOf(a.type.uppercase().replace("-", "_")) } catch (_: Exception) { ActionType.ADVANCE_GOAL }
            brainService.addAction(sessionId, IntendedAction(
                id = "a_${brain.turnCount}", type = actionType, description = a.description,
                priority = a.priority, expiresAfterTurn = brain.turnCount + a.expiresInTurns,
                source = ActionSource.ANALYST, bloomsLevel = a.bloomsLevel,
            ))
        }

        // 9. Exchange score
        decision.exchangeScore?.let { es ->
            brainService.addExchangeScore(sessionId, ExchangeScore(
                turn = brain.turnCount, dimension = es.dimension,
                score = es.score, evidence = es.evidence, bloomsLevel = es.bloomsLevel,
            ))
        }

        // 10. Bloom's level
        decision.bloomsLevelUpdate?.forEach { (topic, level) -> brainService.updateBloomsLevel(sessionId, topic, level) }

        // 10b. Topic signal budget + depletion detection
        decision.topicSignalUpdate?.forEach { (topic, signal) ->
            brainService.updateBrain(sessionId) { b -> b.copy(topicSignalBudget = b.topicSignalBudget + (topic to signal)) }
            if (signal > 0.8f) {
                brainService.addAction(sessionId, IntendedAction(
                    id = "move_on_${topic}_${brain.turnCount}", type = ActionType.WRAP_UP_TOPIC,
                    description = "SIGNAL DEPLETED for $topic. Move to orthogonal topic. Diminishing returns here.",
                    priority = 3, expiresAfterTurn = brain.turnCount + 3, source = ActionSource.ANALYST,
                ))
            }
        }

        // 11. Adjacent topic hypotheses
        decision.adjacentTopicsToProbe.forEach { topicId ->
            KnowledgeAdjacencyMap.getAdjacentTopics(topicId).take(1).forEach { adj ->
                brainService.addHypothesis(sessionId, KnowledgeAdjacencyMap.toHypothesis(adj, brain.turnCount))
            }
        }

        // 12. Dismissal language → probe
        if (decision.candidateProfileUpdate?.dismissalLanguage == true) {
            brainService.addAction(sessionId, IntendedAction(
                id = "dismissal_${brain.turnCount}", type = ActionType.PROBE_DEPTH,
                description = "Candidate used dismissal language. Probe: 'Walk me through why that seems straightforward.'",
                priority = 2, expiresAfterTurn = brain.turnCount + 2, source = ActionSource.ANALYST,
            ))
        }

        // 13. Cognitive overload → REDUCE_LOAD
        if (decision.candidateProfileUpdate?.cognitiveLoadSignal == "overloaded") {
            brainService.addAction(sessionId, IntendedAction(
                id = "reduce_load_${brain.turnCount}", type = ActionType.REDUCE_LOAD,
                description = "OVERLOADED: Remove a constraint, simplify, or give concrete example. Do NOT add complexity.",
                priority = 1, expiresAfterTurn = brain.turnCount + 2, source = ActionSource.COGNITIVE_LOAD,
            ))
        }

        // 14. Low psychological safety → RESTORE_SAFETY
        decision.candidateProfileUpdate?.psychologicalSafety?.let { safety ->
            if (safety < 0.4f) {
                brainService.addAction(sessionId, IntendedAction(
                    id = "safety_${brain.turnCount}", type = ActionType.RESTORE_SAFETY,
                    description = "LOW SAFETY ($safety). Restore before next question. Options: acknowledge difficulty, normalize, reframe, affirm effort.",
                    priority = 1, expiresAfterTurn = brain.turnCount + 2, source = ActionSource.SAFETY,
                ))
            }
        }

        // 15. Update average anxiety (running average)
        decision.candidateProfileUpdate?.anxietyLevel?.let { newLevel ->
            brainService.updateCandidateProfile(sessionId) { cp ->
                val n = cp.dataPoints.coerceAtLeast(1).toFloat()
                cp.copy(avgAnxietyLevel = ((cp.avgAnxietyLevel * (n - 1)) + newLevel) / n)
            }
        }
    }

    private inline fun <reified T : Enum<T>> safeEnum(value: String): T? = try {
        enumValueOf<T>(value.uppercase().replace("-", "_"))
    } catch (_: Exception) { null }
}

// ═══ JSON DTOs ═══

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnalystDecision(
    val candidateProfileUpdate: CandidateProfileUpdateDto? = null,
    val newHypothesis: NewHypothesisDto? = null,
    val hypothesisUpdates: List<HypothesisUpdateDto> = emptyList(),
    val newClaims: List<NewClaimDto> = emptyList(),
    val contradictionFound: ContradictionDto? = null,
    val goalsCompleted: List<String> = emptyList(),
    val thoughtThreadAppend: String = "",
    val nextAction: NextActionDto? = null,
    val exchangeScore: ExchangeScoreDto? = null,
    val bloomsLevelUpdate: Map<String, Int>? = null,
    val topicSignalUpdate: Map<String, Float>? = null,
    val adjacentTopicsToProbe: List<String> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CandidateProfileUpdateDto(
    val thinkingStyle: String? = null, val overallSignal: String? = null,
    val currentState: String? = null, val trajectory: String? = null,
    val anxietyLevel: Float? = null, val flowState: Boolean? = null,
    val psychologicalSafety: Float? = null, val abstractionLevel: Int? = null,
    val selfRepairDetected: Boolean? = null, val cognitiveLoadSignal: String? = null,
    val newAvoidancePattern: String? = null, val dismissalLanguage: Boolean? = null,
    val unknownHandlingPattern: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NewHypothesisDto(val claim: String = "", val confidence: Float = 0.6f, val evidence: List<String> = emptyList(), val testStrategy: String = "", val priority: Int = 3, val bloomsLevel: Int = 3)
@JsonIgnoreProperties(ignoreUnknown = true)
data class HypothesisUpdateDto(val id: String = "", val newEvidence: String = "", val newStatus: String? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class NewClaimDto(val claim: String = "", val topic: String = "", val correctness: String = "unverified")
@JsonIgnoreProperties(ignoreUnknown = true)
data class ContradictionDto(val claim1: String = "", val claim2: String = "", val description: String = "")
@JsonIgnoreProperties(ignoreUnknown = true)
data class NextActionDto(val type: String = "ADVANCE_GOAL", val description: String = "", val priority: Int = 3, val bloomsLevel: Int = 3, val expiresInTurns: Int = 3)
@JsonIgnoreProperties(ignoreUnknown = true)
data class ExchangeScoreDto(val dimension: String = "", val score: Float = 0f, val evidence: String = "", val bloomsLevel: Int = 3)
