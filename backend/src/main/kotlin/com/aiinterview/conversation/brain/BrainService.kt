package com.aiinterview.conversation.brain

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
class BrainService(
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(BrainService::class.java)

    private val sessionMutexes = ConcurrentHashMap<UUID, Mutex>()
    private fun getMutex(id: UUID): Mutex = sessionMutexes.getOrPut(id) { Mutex() }

    companion object {
        private const val KEY_PREFIX = "brain:"
        private val TTL = Duration.ofHours(3)
        fun brainKey(sessionId: UUID) = "$KEY_PREFIX$sessionId"
    }

    // ── Core operations ─────────────────────────────────────────────────

    suspend fun initBrain(
        sessionId: UUID,
        userId: UUID,
        interviewType: String,
        question: InterviewQuestion,
        goals: InterviewGoals,
        personality: String = "friendly",
        targetCompany: String? = null,
        experienceLevel: String? = null,
        programmingLanguage: String? = null,
    ): InterviewerBrain {
        val brain = InterviewerBrain(
            sessionId = sessionId,
            userId = userId,
            interviewType = interviewType,
            questionDetails = question.copy(scoringRubric = generateBasicRubric(question)),
            interviewGoals = goals,
            personality = personality,
            targetCompany = targetCompany,
            experienceLevel = experienceLevel,
            programmingLanguage = programmingLanguage,
        )
        saveBrain(sessionId, brain)
        log.debug("Initialized brain for session {}", sessionId)
        return brain
    }

    suspend fun getBrain(sessionId: UUID): InterviewerBrain {
        val key = brainKey(sessionId)
        val json = redisTemplate.opsForValue().get(key).awaitSingleOrNull()
            ?: throw RuntimeException("Brain not found for session: $sessionId")
        return objectMapper.readValue(json, InterviewerBrain::class.java)
    }

    suspend fun getBrainOrNull(sessionId: UUID): InterviewerBrain? =
        runCatching { getBrain(sessionId) }.getOrNull()

    /** Atomic read-modify-write with per-session mutex. */
    suspend fun updateBrain(
        sessionId: UUID,
        updater: (InterviewerBrain) -> InterviewerBrain,
    ) {
        getMutex(sessionId).withLock {
            val current = getBrain(sessionId)
            val updated = updater(current)
            saveBrain(sessionId, updated)
        }
    }

    suspend fun brainExists(sessionId: UUID): Boolean =
        redisTemplate.hasKey(brainKey(sessionId)).awaitSingle()

    suspend fun deleteBrain(sessionId: UUID) {
        redisTemplate.delete(brainKey(sessionId)).awaitSingleOrNull()
        sessionMutexes.remove(sessionId)
        log.debug("Deleted brain for session {}", sessionId)
    }

    private suspend fun saveBrain(sessionId: UUID, brain: InterviewerBrain) {
        val key = brainKey(sessionId)
        val json = objectMapper.writeValueAsString(brain)
        redisTemplate.opsForValue().set(key, json, TTL).awaitSingle()
    }

    // ── Convenience update methods (all mutex-safe via updateBrain) ──

    suspend fun appendThought(sessionId: UUID, thought: String) = updateBrain(sessionId) { brain ->
        val ct = brain.thoughtThread
        val newContent = if (ct.thread.isBlank()) thought else "${ct.thread}\n• $thought"
        val (compressed, active) = if (newContent.length > 600) {
            val toCompress = newContent.take(200)
            val remaining = newContent.drop(200)
            val compressedSummary = extractiveCompress(toCompress)
            val newHistory = if (ct.compressedHistory.isBlank()) compressedSummary
            else "${ct.compressedHistory} | $compressedSummary"
            Pair(newHistory.takeLast(300), remaining)
        } else {
            Pair(ct.compressedHistory, newContent)
        }
        brain.copy(thoughtThread = ct.copy(thread = active, compressedHistory = compressed, lastUpdatedTurn = brain.turnCount))
    }

    /** Extractive compression: first sentence + last sentence. Zero LLM cost. */
    private fun extractiveCompress(text: String): String {
        val sentences = text.split(". ", ".\n").map { it.trim() }.filter { it.length > 20 }
        return when {
            sentences.isEmpty() -> text.take(50)
            sentences.size == 1 -> sentences.first().take(80)
            else -> "${sentences.first().take(50)}... ${sentences.last().take(50)}"
        }
    }

    suspend fun addHypothesis(sessionId: UUID, h: Hypothesis) = updateBrain(sessionId) { brain ->
        val openCount = brain.hypothesisRegistry.hypotheses.count { it.status == HypothesisStatus.OPEN }
        if (openCount >= 5) return@updateBrain brain
        brain.copy(hypothesisRegistry = brain.hypothesisRegistry.copy(hypotheses = brain.hypothesisRegistry.hypotheses + h))
    }

    suspend fun updateHypothesis(sessionId: UUID, id: String, status: HypothesisStatus, evidence: String) =
        updateBrain(sessionId) { brain ->
            brain.copy(hypothesisRegistry = brain.hypothesisRegistry.copy(
                hypotheses = brain.hypothesisRegistry.hypotheses.map { h ->
                    if (h.id == id) h.copy(
                        status = status,
                        supportingEvidence = if (status == HypothesisStatus.CONFIRMED) h.supportingEvidence + evidence else h.supportingEvidence,
                        contradictingEvidence = if (status == HypothesisStatus.REFUTED) h.contradictingEvidence + evidence else h.contradictingEvidence,
                        lastTestedTurn = brain.turnCount,
                    ) else h
                },
            ))
        }

    suspend fun addClaim(sessionId: UUID, claim: Claim) = updateBrain(sessionId) { brain ->
        brain.copy(claimRegistry = brain.claimRegistry.copy(claims = brain.claimRegistry.claims + claim))
    }

    suspend fun addContradiction(sessionId: UUID, c: Contradiction) = updateBrain(sessionId) { brain ->
        brain.copy(claimRegistry = brain.claimRegistry.copy(pendingContradictions = brain.claimRegistry.pendingContradictions + c))
    }

    suspend fun markContradictionSurfaced(sessionId: UUID, claim1Id: String, claim2Id: String) =
        updateBrain(sessionId) { brain ->
            brain.copy(claimRegistry = brain.claimRegistry.copy(
                pendingContradictions = brain.claimRegistry.pendingContradictions.map { c ->
                    if (c.claim1Id == claim1Id && c.claim2Id == claim2Id) c.copy(surfaced = true) else c
                },
            ))
        }

    suspend fun markGoalComplete(sessionId: UUID, goalId: String) = updateBrain(sessionId) { brain ->
        if (goalId in brain.interviewGoals.completed) return@updateBrain brain
        brain.copy(interviewGoals = brain.interviewGoals.copy(completed = brain.interviewGoals.completed + goalId))
    }

    suspend fun markGoalsComplete(sessionId: UUID, goalIds: List<String>) = updateBrain(sessionId) { brain ->
        val newCompleted = (brain.interviewGoals.completed + goalIds).distinct()
        brain.copy(interviewGoals = brain.interviewGoals.copy(completed = newCompleted))
    }

    suspend fun addAction(sessionId: UUID, action: IntendedAction) = updateBrain(sessionId) { brain ->
        if (brain.actionQueue.pending.any { it.type == action.type }) return@updateBrain brain
        brain.copy(actionQueue = brain.actionQueue.copy(pending = (brain.actionQueue.pending + action).sortedBy { it.priority }))
    }

    suspend fun completeTopAction(sessionId: UUID) = updateBrain(sessionId) { brain ->
        val top = brain.actionQueue.topAction() ?: return@updateBrain brain
        brain.copy(actionQueue = brain.actionQueue.copy(pending = brain.actionQueue.pending.filter { it.id != top.id }, lastCompleted = top))
    }

    suspend fun updateStrategy(sessionId: UUID, strategy: InterviewStrategy) = updateBrain(sessionId) { brain ->
        brain.copy(currentStrategy = strategy)
    }

    suspend fun updateCandidateProfile(sessionId: UUID, updater: (CandidateProfile) -> CandidateProfile) =
        updateBrain(sessionId) { brain -> brain.copy(candidateProfile = updater(brain.candidateProfile)) }

    suspend fun addExchangeScore(sessionId: UUID, score: ExchangeScore) = updateBrain(sessionId) { brain ->
        brain.copy(exchangeScores = brain.exchangeScores + score)
    }

    suspend fun incrementTurnCount(sessionId: UUID) = updateBrain(sessionId) { brain ->
        brain.copy(
            turnCount = brain.turnCount + 1,
            lastActivityAt = Instant.now(),
            actionQueue = brain.actionQueue.withoutExpired(brain.turnCount + 1),
        )
    }

    suspend fun appendUsedAcknowledgment(sessionId: UUID, phrase: String) = updateBrain(sessionId) { brain ->
        brain.copy(usedAcknowledgments = (brain.usedAcknowledgments + phrase).takeLast(20))
    }

    suspend fun updateBloomsLevel(sessionId: UUID, topic: String, level: Int) = updateBrain(sessionId) { brain ->
        val current = brain.bloomsTracker[topic] ?: 0
        if (level <= current) return@updateBrain brain
        brain.copy(bloomsTracker = brain.bloomsTracker + (topic to level))
    }

    // ── Phase 4+5 methods ──

    suspend fun appendTopicToHistory(sessionId: UUID, topic: String) = updateBrain(sessionId) { brain ->
        brain.copy(topicHistory = (brain.topicHistory + topic).takeLast(20))
    }

    suspend fun updateChallengeSuccessRate(sessionId: UUID, wasSuccess: Boolean) = updateBrain(sessionId) { brain ->
        val tested = brain.hypothesisRegistry.hypotheses.count { it.status != HypothesisStatus.OPEN }.coerceAtLeast(1).toFloat()
        val newRate = if (wasSuccess) ((brain.challengeSuccessRate * (tested - 1)) + 1f) / tested
        else ((brain.challengeSuccessRate * (tested - 1))) / tested
        brain.copy(challengeSuccessRate = newRate.coerceIn(0f, 1f))
    }

    suspend fun updateZdpLevel(sessionId: UUID, topic: String, canDoAlone: Boolean, canDoWithPrompt: Boolean) = updateBrain(sessionId) { brain ->
        brain.copy(zdpEdge = brain.zdpEdge + (topic to ZdpLevel(canDoAlone, canDoWithPrompt, !canDoAlone && !canDoWithPrompt, topic)))
    }

    suspend fun recordQuestionType(sessionId: UUID, type: String) = updateBrain(sessionId) { brain ->
        brain.copy(questionTypeHistory = (brain.questionTypeHistory + type).takeLast(30))
    }

    suspend fun incrementFormativeFeedback(sessionId: UUID) = updateBrain(sessionId) { brain ->
        brain.copy(formativeFeedbackGiven = brain.formativeFeedbackGiven + 1)
    }

    suspend fun appendTranscriptTurn(sessionId: UUID, role: String, content: String) = updateBrain(sessionId) { brain ->
        val newTurn = BrainTranscriptTurn(role = role, content = content)
        val updated = brain.rollingTranscript + newTurn
        brain.copy(rollingTranscript = if (updated.size > 8) updated.takeLast(8) else updated)
    }

    private fun generateBasicRubric(question: InterviewQuestion): ScoringRubric {
        val approach = question.optimalApproach.lowercase()
        return ScoringRubric(
            algorithmIndicators = buildList {
                if (approach.contains("hash")) add("uses hash map/set")
                if (approach.contains("two pointer")) add("uses two pointers")
                if (approach.contains("dp") || approach.contains("dynamic")) add("uses DP")
                if (approach.contains("bfs")) add("uses BFS")
                if (approach.contains("dfs")) add("uses DFS")
                if (approach.contains("sort")) add("considers sorting")
                add("correct algorithm choice")
                add("states correct time complexity")
            },
            communicationIndicators = listOf("explained approach before coding", "talked through reasoning", "asked clarifying question"),
            edgeCasesRequired = listOf("empty input", "single element", "all same values"),
        )
    }

    @PreDestroy
    fun destroy() {
        sessionMutexes.clear()
    }
}
