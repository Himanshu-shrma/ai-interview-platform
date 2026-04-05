package com.aiinterview.conversation.brain

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.UUID

class BrainServiceTest {

    private val objectMapper = jacksonObjectMapper().apply {
        findAndRegisterModules()
    }

    private val redisTemplate = mockk<ReactiveStringRedisTemplate>()
    private val valueOps = mockk<ReactiveValueOperations<String, String>>()

    private lateinit var service: BrainService

    private val sessionId = UUID.randomUUID()
    private val userId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        every { redisTemplate.opsForValue() } returns valueOps
        service = BrainService(redisTemplate, objectMapper)
    }

    @Test
    fun `initBrain saves brain to Redis with correct sessionId key`() = runTest {
        val savedKey = slot<String>()
        val savedJson = slot<String>()
        every { valueOps.set(capture(savedKey), capture(savedJson), any<Duration>()) } returns Mono.just(true)

        val brain = service.initBrain(
            sessionId = sessionId,
            userId = userId,
            interviewType = "CODING",
            question = InterviewQuestion(title = "Two Sum", description = "Find two numbers..."),
            goals = BrainObjectivesRegistry.forCategory("CODING"),
        )

        assertEquals("brain:$sessionId", savedKey.captured)
        assertEquals(sessionId, brain.sessionId)
        assertEquals(userId, brain.userId)
        assertEquals("CODING", brain.interviewType)

        // Verify the JSON stored in Redis deserializes back correctly
        val deserialized = objectMapper.readValue(savedJson.captured, InterviewerBrain::class.java)
        assertEquals(sessionId, deserialized.sessionId)
        assertEquals("Two Sum", deserialized.questionDetails.title)
    }

    @Test
    fun `updateBrain is atomic — concurrent updates do not lose data`() = runTest {
        // Setup: mock Redis GET to return a brain, SET to capture
        val baseBrain = InterviewerBrain(
            sessionId = sessionId,
            userId = userId,
            interviewType = "CODING",
            questionDetails = InterviewQuestion(title = "Test"),
            turnCount = 0,
        )
        val baseJson = objectMapper.writeValueAsString(baseBrain)

        // Track all saved values — the last one should have the highest turnCount
        val savedValues = mutableListOf<String>()
        every { valueOps.get("brain:$sessionId") } answers {
            val latest = savedValues.lastOrNull() ?: baseJson
            Mono.just(latest)
        }
        every { valueOps.set(any(), any<String>(), any<Duration>()) } answers {
            savedValues.add(secondArg())
            Mono.just(true)
        }

        // Run 10 concurrent increments — mutex should serialize them
        val jobs = (1..10).map {
            async {
                service.updateBrain(sessionId) { brain ->
                    brain.copy(turnCount = brain.turnCount + 1)
                }
            }
        }
        jobs.awaitAll()

        // Final state should have turnCount = 10 (no lost updates)
        val finalJson = savedValues.last()
        val finalBrain = objectMapper.readValue(finalJson, InterviewerBrain::class.java)
        assertEquals(10, finalBrain.turnCount, "All 10 increments must be preserved — mutex must serialize")
    }

    @Test
    fun `markGoalComplete adds goal to interviewGoals completed`() = runTest {
        val brain = InterviewerBrain(
            sessionId = sessionId,
            userId = userId,
            interviewType = "CODING",
            questionDetails = InterviewQuestion(title = "Test"),
            interviewGoals = BrainObjectivesRegistry.forCategory("CODING"),
        )
        val brainJson = objectMapper.writeValueAsString(brain)

        every { valueOps.get("brain:$sessionId") } returns Mono.just(brainJson)
        val savedJson = slot<String>()
        every { valueOps.set(any(), capture(savedJson), any<Duration>()) } returns Mono.just(true)

        service.markGoalComplete(sessionId, "problem_shared")

        val updated = objectMapper.readValue(savedJson.captured, InterviewerBrain::class.java)
        assertTrue("problem_shared" in updated.interviewGoals.completed,
            "problem_shared should be in completed goals")
    }
}
