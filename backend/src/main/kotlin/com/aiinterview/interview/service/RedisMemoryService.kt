package com.aiinterview.interview.service

import com.aiinterview.interview.dto.InternalQuestionDto
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID

class SessionNotFoundException(sessionId: UUID) :
    RuntimeException("Session memory not found: $sessionId (session may have expired or never been initialized)")

@Service
class RedisMemoryService(
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val transcriptCompressor: TranscriptCompressor,
    @Value("\${interview.redis-ttl-hours:2}") private val ttlHours: Long,
    @Value("\${interview.transcript-max-turns:6}") private val maxTranscriptTurns: Int,
) {
    private val log = LoggerFactory.getLogger(RedisMemoryService::class.java)

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
    ): InterviewMemory {
        val current = getMemory(sessionId)
        val updated = updater(current)
        saveMemory(sessionId, updated)
        return updated
    }

    /**
     * Appends a new transcript turn and triggers compression when the rolling
     * transcript exceeds [maxTranscriptTurns].
     *
     * Compression calls [TranscriptCompressor.compress] (suspend) outside the
     * non-suspend updateMemory lambda, then saves the result atomically.
     */
    suspend fun appendTranscriptTurn(
        sessionId: UUID,
        role: String,
        content: String,
    ): InterviewMemory {
        val memory  = getMemory(sessionId)
        val newTurn = TranscriptTurn(role = role, content = content)
        val updated = memory.rollingTranscript + newTurn

        val (transcript, earlierContext) = if (updated.size > maxTranscriptTurns) {
            val oldest     = updated.take(2)
            val compressed = transcriptCompressor.compress(oldest)   // suspend — OK here
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
        return newMemory
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
        log.debug("Deleted memory for session {}", sessionId)
    }

    /** Returns true if the session memory key exists in Redis. Used for reconnect logic. */
    suspend fun memoryExists(sessionId: UUID): Boolean =
        redisTemplate.hasKey(memoryKey(sessionId)).awaitSingle()

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun saveMemory(sessionId: UUID, memory: InterviewMemory) {
        val json = objectMapper.writeValueAsString(memory)
        redisTemplate.opsForValue()
            .set(memoryKey(sessionId), json, Duration.ofHours(ttlHours))
            .awaitSingle()
    }
}
