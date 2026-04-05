package com.aiinterview.interview

import com.aiinterview.interview.dto.InternalQuestionDto
import com.aiinterview.interview.service.InterviewConfig
import com.aiinterview.interview.service.RedisMemoryService
import com.aiinterview.interview.service.SessionNotFoundException
import com.aiinterview.interview.service.InterviewMemory
import com.aiinterview.shared.domain.Difficulty
import com.aiinterview.shared.domain.InterviewCategory
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import reactor.core.publisher.Mono
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Unit test for RedisMemoryService — mocks ReactiveStringRedisTemplate.
 * No Docker, no Testcontainers, no real Redis. Runs in < 1 second.
 *
 * For real Redis round-trip tests see RedisMemoryServiceIT.kt
 */
class RedisMemoryServiceTest {

    private val objectMapper = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
        disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private val redisTemplate = mockk<ReactiveStringRedisTemplate>()
    private val valueOps = mockk<ReactiveValueOperations<String, String>>()

    private lateinit var service: RedisMemoryService

    private val sessionId = UUID.randomUUID()
    private val userId = UUID.randomUUID()

    private val testConfig = InterviewConfig(
        category = InterviewCategory.CODING,
        difficulty = Difficulty.MEDIUM,
    )

    @BeforeEach
    fun setup() {
        every { redisTemplate.opsForValue() } returns valueOps

        service = RedisMemoryService(
            redisTemplate = redisTemplate,
            objectMapper = objectMapper,
            ttlHours = 1,
            maxTranscriptTurns = 6,
        )
    }

    // ── initMemory ────────────────────────────────────────────────────────────

    @Test
    fun `initMemory returns memory with correct sessionId and initial state`() = runTest {
        every { valueOps.set(any(), any(), any<java.time.Duration>()) } returns Mono.just(true)

        val memory = service.initMemory(sessionId, userId, testConfig, buildTestQuestion())

        assertEquals(sessionId, memory.sessionId)
        assertEquals(userId, memory.userId)
        assertEquals("INTERVIEW_STARTING", memory.state)
        assertEquals("CODING", memory.category)
        assertTrue(memory.rollingTranscript.isEmpty())
    }

    // ── getMemory ─────────────────────────────────────────────────────────────

    @Test
    fun `getMemory deserializes correctly from Redis`() = runTest {
        val existingMemory = buildMemory("CANDIDATE_RESPONDING")
        val serialized = objectMapper.writeValueAsString(existingMemory)
        every { valueOps.get(any<String>()) } returns Mono.just(serialized)

        val retrieved = service.getMemory(sessionId)

        assertEquals(sessionId, retrieved.sessionId)
        assertEquals("CANDIDATE_RESPONDING", retrieved.state)
    }

    @Test
    fun `getMemory throws SessionNotFoundException when key not found`() = runTest {
        every { valueOps.get(any<String>()) } returns Mono.empty()

        assertThrows<SessionNotFoundException> {
            service.getMemory(sessionId)
        }
    }

    // ── updateMemory ──────────────────────────────────────────────────────────

    @Test
    fun `updateMemory applies lambda and persists result`() = runTest {
        val original = buildMemory("INTERVIEW_STARTING")
        val serialized = objectMapper.writeValueAsString(original)
        every { valueOps.get(any<String>()) } returns Mono.just(serialized)
        every { valueOps.set(any(), any(), any<java.time.Duration>()) } returns Mono.just(true)

        val updated = service.updateMemory(sessionId) { mem ->
            mem.copy(state = "CANDIDATE_RESPONDING", hintsGiven = 2)
        }

        assertEquals("CANDIDATE_RESPONDING", updated.state)
        assertEquals(2, updated.hintsGiven)
        coVerify { valueOps.set(any(), any(), any<java.time.Duration>()) }
    }

    // ── memoryExists ──────────────────────────────────────────────────────────

    @Test
    fun `memoryExists returns false when key missing, true when present`() = runTest {
        every { redisTemplate.hasKey(any<String>()) } returns Mono.just(false)
        assertFalse(service.memoryExists(UUID.randomUUID()))

        every { redisTemplate.hasKey(any<String>()) } returns Mono.just(true)
        assertTrue(service.memoryExists(sessionId))
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun buildMemory(state: String) = InterviewMemory(
        sessionId = sessionId,
        userId = userId,
        state = state,
        category = "CODING",
        personality = "friendly",
        currentQuestion = null,
        candidateAnalysis = null,
        createdAt = Instant.now(),
        lastActivityAt = Instant.now(),
    )

    private fun buildTestQuestion() = InternalQuestionDto(
        id = UUID.randomUUID(),
        title = "Two Sum",
        description = "Given an array of integers nums and an integer target...",
        category = "CODING",
        type = "CODING",
        difficulty = "MEDIUM",
        topicTags = listOf("arrays", "hash-map"),
        examples = null,
        constraintsText = "1 <= nums.length <= 10^4",
        testCases = null,
        solutionHints = null,
        optimalApproach = null,
        followUpPrompts = null,
        evaluationCriteria = null,
        timeComplexity = "O(n)",
        spaceComplexity = "O(n)",
        slug = "two-sum",
        source = "AI_GENERATED",
        generationParams = null,
        codeTemplates = null,
        createdAt = OffsetDateTime.now(),
    )
}
