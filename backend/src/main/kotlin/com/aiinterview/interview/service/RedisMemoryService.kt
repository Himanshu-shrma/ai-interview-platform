package com.aiinterview.interview.service

import com.aiinterview.interview.dto.InternalQuestionDto
import com.aiinterview.interview.dto.toInternalDto
import com.aiinterview.interview.repository.ConversationMessageRepository
import com.aiinterview.interview.repository.InterviewSessionRepository
import com.aiinterview.interview.repository.SessionQuestionRepository
import com.aiinterview.interview.repository.QuestionRepository
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID

class SessionNotFoundException(sessionId: UUID) :
    RuntimeException("Session memory not found: $sessionId (session may have expired or never been initialized)")

@Deprecated("Replaced by InterviewerBrain via BrainService. Do not add new readers.")
@Service
class RedisMemoryService(
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val objectMapper: ObjectMapper,
    @Value("\${interview.redis-ttl-hours:2}") private val ttlHours: Long,
    @Value("\${interview.transcript-max-turns:6}") private val maxTranscriptTurns: Int,
) {
    private val log = LoggerFactory.getLogger(RedisMemoryService::class.java)

    /** Per-session mutex to serialize concurrent read-modify-write cycles. */
    private val sessionMutexes = java.util.concurrent.ConcurrentHashMap<UUID, Mutex>()
    private fun getMutex(sessionId: UUID): Mutex = sessionMutexes.getOrPut(sessionId) { Mutex() }

    companion object {
        fun memoryKey(sessionId: UUID) = "interview:session:$sessionId:memory"
    }

    /**
     * Creates and saves the initial session memory to Redis.
     * Called once when a session starts, before the WebSocket connects.
     */
    suspend fun initMemory(
        sessionId: UUID,
        userId: UUID,
        config: InterviewConfig,
        firstQuestion: InternalQuestionDto,
        totalQuestions: Int = 1,
    ): InterviewMemory {
        val memory = InterviewMemory(
            sessionId           = sessionId,
            userId              = userId,
            state               = "INTERVIEW_STARTING",
            category            = config.category.name,
            personality         = config.personality,
            currentQuestion     = firstQuestion,
            candidateAnalysis   = null,
            programmingLanguage = config.programmingLanguage,
            currentQuestionIndex = 0,
            totalQuestions       = totalQuestions,
            targetCompany       = config.targetCompany,
            targetRole          = config.targetRole,
            experienceLevel     = config.experienceLevel,
            background          = config.background,
        )
        saveMemory(sessionId, memory)
        log.debug("Initialized memory for session {}", sessionId)
        return memory
    }

    /**
     * Fetches and deserializes memory from Redis.
     * Throws [SessionNotFoundException] if the key is missing (expired or never initialized).
     */
    suspend fun getMemory(sessionId: UUID): InterviewMemory {
        val json = redisTemplate.opsForValue()
            .get(memoryKey(sessionId))
            .awaitSingleOrNull()
            ?: throw SessionNotFoundException(sessionId)
        return objectMapper.readValue(json, InterviewMemory::class.java)
    }

    /**
     * Atomic read-modify-write: applies [updater] to current memory and persists the result.
     * This is the ONLY way memory should be mutated — never mutate the object directly.
     */
    suspend fun updateMemory(
        sessionId: UUID,
        updater: (InterviewMemory) -> InterviewMemory,
    ): InterviewMemory = getMutex(sessionId).withLock {
        val current = getMemory(sessionId)
        val updated = updater(current)
        saveMemory(sessionId, updated)
        updated
    }

    /**
     * Appends a new transcript turn and triggers compression when the rolling
     * transcript exceeds [maxTranscriptTurns].
     *
     * Compression uses simple extractive summary when rolling transcript exceeds max turns.
     */
    suspend fun appendTranscriptTurn(
        sessionId: UUID,
        role: String,
        content: String,
    ): InterviewMemory = getMutex(sessionId).withLock {
        val memory  = getMemory(sessionId)
        val newTurn = TranscriptTurn(role = role, content = content)
        val updated = memory.rollingTranscript + newTurn

        val (transcript, earlierContext) = if (updated.size > maxTranscriptTurns) {
            val oldest     = updated.take(2)
            val compressed = oldest.joinToString(" | ") { "${it.role}: ${it.content.take(80)}" }
            val newContext = if (memory.earlierContext.isBlank()) compressed
                            else "$compressed | ${memory.earlierContext}"
            log.debug("Compressed {} turns for session {}", oldest.size, sessionId)
            updated.drop(2) to newContext
        } else {
            updated to memory.earlierContext
        }

        val newMemory = memory.copy(
            rollingTranscript = transcript,
            earlierContext    = earlierContext,
            lastActivityAt    = Instant.now(),
        )
        saveMemory(sessionId, newMemory)
        newMemory
    }

    /**
     * Resets the TTL to [ttlHours] without deserializing the full object.
     * Call this on every WebSocket activity.
     */
    suspend fun refreshTTL(sessionId: UUID) {
        redisTemplate.expire(memoryKey(sessionId), Duration.ofHours(ttlHours))
            .awaitSingleOrNull()
    }

    /** Deletes the session memory key. Call this on session end. */
    suspend fun deleteMemory(sessionId: UUID) {
        redisTemplate.delete(memoryKey(sessionId)).awaitSingleOrNull()
        sessionMutexes.remove(sessionId)
        log.debug("Deleted memory for session {}", sessionId)
    }

    /** Returns true if the session memory key exists in Redis. Used for reconnect logic. */
    suspend fun memoryExists(sessionId: UUID): Boolean =
        redisTemplate.hasKey(memoryKey(sessionId)).awaitSingle()

    /**
     * Tries Redis first; on miss, reconstructs a minimal InterviewMemory from DB.
     * Returns null only if the session doesn't exist or isn't ACTIVE.
     */
    suspend fun getMemoryWithFallback(
        sessionId: UUID,
        sessionRepository: InterviewSessionRepository,
        sessionQuestionRepository: SessionQuestionRepository,
        conversationMessageRepository: ConversationMessageRepository,
        questionRepository: QuestionRepository,
    ): InterviewMemory? {
        val fromRedis = runCatching { getMemory(sessionId) }.getOrNull()
        if (fromRedis != null) return fromRedis

        // Redis miss — reconstruct from DB
        val session = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            sessionRepository.findById(sessionId).awaitSingleOrNull()
        } ?: return null

        if (session.status != "ACTIVE") return null

        val config = runCatching {
            objectMapper.readValue(session.config, InterviewConfig::class.java)
        }.getOrNull() ?: return null

        val sessionQuestions = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            sessionQuestionRepository.findBySessionIdOrderByOrderIndex(sessionId)
                .collectList().awaitSingle()
        }

        val firstSq = sessionQuestions.firstOrNull() ?: return null
        val firstQuestion = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            questionRepository.findById(firstSq.questionId).awaitSingleOrNull()
        }?.toInternalDto(objectMapper) ?: return null

        val recentMessages = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            conversationMessageRepository.findRecentBySessionId(sessionId, 10)
                .collectList().awaitSingle()
        }.reversed().map { TranscriptTurn(role = it.role, content = it.content) }

        val reconstructed = InterviewMemory(
            sessionId = sessionId,
            userId = session.userId,
            state = "QUESTION_PRESENTED",
            category = config.category.name,
            personality = config.personality,
            currentQuestion = firstQuestion,
            candidateAnalysis = null,
            programmingLanguage = config.programmingLanguage,
            rollingTranscript = recentMessages,
            earlierContext = "[Session recovered after restart. Some context may be missing.]",
            interviewStage = session.currentStage ?: "SMALL_TALK",
            currentQuestionIndex = 0,
            totalQuestions = sessionQuestions.size.coerceAtLeast(1),
            targetCompany = config.targetCompany,
            targetRole = config.targetRole,
            experienceLevel = config.experienceLevel,
            background = config.background,
            createdAt = session.startedAt?.toInstant() ?: java.time.Instant.now(),
            lastActivityAt = java.time.Instant.now(),
        )

        saveMemory(sessionId, reconstructed)
        log.info("Reconstructed memory from DB for session {}", sessionId)
        return reconstructed
    }

    // ── Smart orchestrator helpers ──────────────────────────────────────────

    suspend fun appendAgentNote(sessionId: UUID, note: String) = getMutex(sessionId).withLock {
        val memory = getMemory(sessionId)
        val existing = memory.agentNotes
        val updated = if (existing.isBlank()) note else "$existing\n• $note"
        val trimmed = if (updated.length > 500) updated.takeLast(500) else updated
        saveMemory(sessionId, memory.copy(agentNotes = trimmed))
    }

    suspend fun setComplexityDiscussed(sessionId: UUID, value: Boolean) {
        updateMemory(sessionId) { it.copy(complexityDiscussed = value) }
    }

    suspend fun setEdgeCasesCovered(sessionId: UUID, count: Int) {
        updateMemory(sessionId) { it.copy(edgeCasesCovered = count) }
    }

    suspend fun updateStage(sessionId: UUID, stage: String) {
        updateMemory(sessionId) { it.copy(interviewStage = stage) }
    }

    suspend fun incrementQuestionIndex(sessionId: UUID) {
        updateMemory(sessionId) { it.copy(currentQuestionIndex = it.currentQuestionIndex + 1) }
    }

    // ── Objectives + candidate model helpers ────────────────────────────────

    suspend fun markObjectivesComplete(sessionId: UUID, objectiveIds: List<String>) {
        updateMemory(sessionId) { memory ->
            memory.copy(completedObjectives = (memory.completedObjectives + objectiveIds).distinct())
        }
    }

    suspend fun incrementTurnCount(sessionId: UUID) {
        updateMemory(sessionId) { memory ->
            memory.copy(turnCount = memory.turnCount + 1)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun saveMemory(sessionId: UUID, memory: InterviewMemory) {
        val json = objectMapper.writeValueAsString(memory)
        redisTemplate.opsForValue()
            .set(memoryKey(sessionId), json, Duration.ofHours(ttlHours))
            .awaitSingle()
    }
}
