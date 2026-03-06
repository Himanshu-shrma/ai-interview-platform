package com.aiinterview.interview

import com.aiinterview.interview.dto.InternalQuestionDto
import com.aiinterview.interview.service.InterviewConfig
import com.aiinterview.interview.service.RedisMemoryService
import com.aiinterview.interview.service.SessionNotFoundException
import com.aiinterview.interview.service.TranscriptCompressor
import com.aiinterview.shared.domain.Difficulty
import com.aiinterview.shared.domain.InterviewCategory
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.OffsetDateTime
import java.util.UUID

@Testcontainers
class RedisMemoryServiceTest {

    companion object {
        @JvmStatic
        @Container
        val redis: GenericContainer<*> = GenericContainer("redis:7-alpine")
            .withExposedPorts(6379)
    }

    private val objectMapper = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
        disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private val compressor = TranscriptCompressor()

    private lateinit var connectionFactory: LettuceConnectionFactory
    private lateinit var redisTemplate: ReactiveStringRedisTemplate
    private lateinit var service: RedisMemoryService

    private val sessionId = UUID.randomUUID()
    private val userId = UUID.randomUUID()

    private val testConfig = InterviewConfig(
        category   = InterviewCategory.CODING,
        difficulty = Difficulty.MEDIUM,
    )

    private val testQuestion = InternalQuestionDto(
        id                 = UUID.randomUUID(),
        title              = "Two Sum",
        description        = "Given an array of integers nums and an integer target...",
        category           = "CODING",
        type               = "CODING",
        difficulty         = "MEDIUM",
        topicTags          = listOf("arrays", "hash-map"),
        examples           = null,
        constraintsText    = "1 <= nums.length <= 10^4",
        testCases          = null,
        solutionHints      = null,
        optimalApproach    = null,
        followUpPrompts    = null,
        evaluationCriteria = null,
        timeComplexity     = "O(n)",
        spaceComplexity    = "O(n)",
        slug               = "two-sum",
        source             = "AI_GENERATED",
        generationParams   = null,
        createdAt          = OffsetDateTime.now(),
    )

    @BeforeEach
    fun setup() {
        connectionFactory = LettuceConnectionFactory("localhost", redis.getMappedPort(6379))
        connectionFactory.afterPropertiesSet()
        redisTemplate = ReactiveStringRedisTemplate(connectionFactory)
        service = RedisMemoryService(
            redisTemplate        = redisTemplate,
            objectMapper         = objectMapper,
            transcriptCompressor = compressor,
            ttlHours             = 1,
            maxTranscriptTurns   = 6,
        )
        // Flush all keys before each test
        runBlocking {
            val keys = redisTemplate.keys("*").collectList().awaitSingle()
            if (keys.isNotEmpty()) redisTemplate.delete(*keys.toTypedArray()).awaitSingle()
        }
    }

    @AfterEach
    fun tearDown() {
        connectionFactory.destroy()
    }

    // ── initMemory ────────────────────────────────────────────────────────────

    @Test
    fun `initMemory creates correct structure and saves to Redis`() = runTest {
        val memory = service.initMemory(sessionId, userId, testConfig, testQuestion)

        assertEquals(sessionId, memory.sessionId)
        assertEquals(userId, memory.userId)
        assertEquals("INTERVIEW_STARTING", memory.state)
        assertEquals("CODING", memory.category)
        assertEquals("friendly", memory.personality)
        assertNotNull(memory.currentQuestion)
        assertEquals("Two Sum", memory.currentQuestion!!.title)
        assertEquals(0, memory.hintsGiven)
        assertTrue(memory.rollingTranscript.isEmpty())
        assertTrue(memory.earlierContext.isBlank())

        // Verify it was actually persisted in Redis
        assertTrue(service.memoryExists(sessionId))
    }

    // ── getMemory ─────────────────────────────────────────────────────────────

    @Test
    fun `getMemory deserializes correctly from Redis`() = runTest {
        service.initMemory(sessionId, userId, testConfig, testQuestion)

        val retrieved = service.getMemory(sessionId)

        assertEquals(sessionId, retrieved.sessionId)
        assertEquals(userId, retrieved.userId)
        assertEquals("INTERVIEW_STARTING", retrieved.state)
        assertEquals("Two Sum", retrieved.currentQuestion!!.title)
        assertEquals("O(n)", retrieved.currentQuestion!!.timeComplexity)
    }

    @Test
    fun `getMemory throws SessionNotFoundException when key not found`() = runTest {
        val missingId = UUID.randomUUID()

        assertThrows<SessionNotFoundException> {
            service.getMemory(missingId)
        }
    }

    // ── updateMemory ──────────────────────────────────────────────────────────

    @Test
    fun `updateMemory applies updater and persists result`() = runTest {
        service.initMemory(sessionId, userId, testConfig, testQuestion)

        val updated = service.updateMemory(sessionId) { mem ->
            mem.copy(state = "CANDIDATE_RESPONSE", hintsGiven = 2)
        }

        assertEquals("CANDIDATE_RESPONSE", updated.state)
        assertEquals(2, updated.hintsGiven)

        // Verify the change was persisted
        val retrieved = service.getMemory(sessionId)
        assertEquals("CANDIDATE_RESPONSE", retrieved.state)
        assertEquals(2, retrieved.hintsGiven)
    }

    // ── appendTranscriptTurn ──────────────────────────────────────────────────

    @Test
    fun `appendTranscriptTurn adds turns to rollingTranscript`() = runTest {
        service.initMemory(sessionId, userId, testConfig, testQuestion)

        service.appendTranscriptTurn(sessionId, "AI", "Welcome! Let's start with Two Sum.")
        val memory = service.appendTranscriptTurn(sessionId, "CANDIDATE", "I'll use a hash map approach.")

        assertEquals(2, memory.rollingTranscript.size)
        assertEquals("AI", memory.rollingTranscript[0].role)
        assertEquals("Welcome! Let's start with Two Sum.", memory.rollingTranscript[0].content)
        assertEquals("CANDIDATE", memory.rollingTranscript[1].role)
        assertEquals("I'll use a hash map approach.", memory.rollingTranscript[1].content)
        assertTrue(memory.earlierContext.isBlank())
    }

    @Test
    fun `appendTranscriptTurn triggers compression when transcript exceeds maxTurns`() = runTest {
        service.initMemory(sessionId, userId, testConfig, testQuestion)

        // Add exactly 6 turns (at the limit — no compression yet)
        repeat(6) { i ->
            val role = if (i % 2 == 0) "AI" else "CANDIDATE"
            service.appendTranscriptTurn(sessionId, role, "Turn $i content")
        }

        var memory = service.getMemory(sessionId)
        assertEquals(6, memory.rollingTranscript.size)
        assertTrue(memory.earlierContext.isBlank())

        // Add 7th turn — triggers compression of oldest 2
        memory = service.appendTranscriptTurn(sessionId, "AI", "Turn 6 content")

        // Oldest 2 compressed out, 5 remain
        assertEquals(5, memory.rollingTranscript.size)
        assertTrue(memory.earlierContext.isNotBlank(), "earlierContext should contain compressed turns")
        // The compressed context should mention content from the oldest turns
        assertTrue(memory.earlierContext.contains("Turn 0 content"))
        assertTrue(memory.earlierContext.contains("Turn 1 content"))
    }

    // ── memoryExists ──────────────────────────────────────────────────────────

    @Test
    fun `memoryExists returns false for missing key and true after initMemory`() = runTest {
        val missingId = UUID.randomUUID()
        assertFalse(service.memoryExists(missingId))

        service.initMemory(sessionId, userId, testConfig, testQuestion)
        assertTrue(service.memoryExists(sessionId))
    }
}
