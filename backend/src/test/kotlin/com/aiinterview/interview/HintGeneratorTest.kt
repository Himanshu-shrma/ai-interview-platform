package com.aiinterview.interview

import com.aiinterview.conversation.HintGenerator
import com.aiinterview.interview.service.EvalScores
import com.aiinterview.interview.service.InterviewMemory
import com.aiinterview.interview.service.RedisMemoryService
import com.aiinterview.interview.ws.OutboundMessage
import com.aiinterview.interview.ws.WsSessionRegistry
import com.openai.client.OpenAIClient
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionCreateParams
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class HintGeneratorTest {

    private val openAIClient  = mockk<OpenAIClient>()
    private val memoryService = mockk<RedisMemoryService>()
    private val registry      = mockk<WsSessionRegistry>(relaxed = true)

    private val generator = HintGenerator(
        openAIClient       = openAIClient,
        redisMemoryService = memoryService,
        registry           = registry,
        model              = "gpt-4o-mini",
    )

    private val sessionId = UUID.randomUUID()

    // ── refused when limit reached ────────────────────────────────────────────

    @Test
    fun `generateHint refuses when hintsGiven equals 3`() = runTest {
        coEvery { registry.sendMessage(sessionId, any()) } returns true

        val result = generator.generateHint(buildMemory(hintsGiven = 3))

        assertTrue(result.refused)
        assertEquals(0, result.hintsRemaining)
    }

    @Test
    fun `generateHint sends HintDelivered with refused=true at limit`() = runTest {
        coEvery { registry.sendMessage(sessionId, any()) } returns true

        generator.generateHint(buildMemory(hintsGiven = 3))

        coVerify {
            registry.sendMessage(sessionId, match {
                it is OutboundMessage.HintDelivered && it.refused
            })
        }
    }

    // ── level 1 hint ─────────────────────────────────────────────────────────

    @Test
    fun `generateHint delivers level 1 when hintsGiven is 0`() = runTest {
        stubLlm("Think about how to avoid repeated lookups.")
        coEvery { memoryService.updateMemory(sessionId, any()) } returns buildMemory(hintsGiven = 1)
        coEvery { registry.sendMessage(sessionId, any()) } returns true

        val result = generator.generateHint(buildMemory(hintsGiven = 0))

        assertEquals(1, result.level)
        assertEquals("Think about how to avoid repeated lookups.", result.hint)
    }

    // ── score deduction ───────────────────────────────────────────────────────

    @Test
    fun `generateHint deducts 0_5 from problemSolving for level 1`() = runTest {
        stubLlm("abstract hint")
        val initialScores = EvalScores(problemSolving = 5.0)
        val memoryAfterUpdate = buildMemory(hintsGiven = 1, evalScores = EvalScores(problemSolving = 4.5))
        coEvery { memoryService.updateMemory(sessionId, any()) } returns memoryAfterUpdate
        coEvery { registry.sendMessage(sessionId, any()) } returns true

        generator.generateHint(buildMemory(hintsGiven = 0, evalScores = initialScores))

        coVerify {
            memoryService.updateMemory(sessionId, any())
        }
    }

    @Test
    fun `generateHint deducts 1_0 from problemSolving for level 2`() = runTest {
        stubLlm("Use a HashMap.")
        val memoryAfterUpdate = buildMemory(hintsGiven = 2, evalScores = EvalScores(problemSolving = 4.0))
        coEvery { memoryService.updateMemory(sessionId, any()) } returns memoryAfterUpdate
        coEvery { registry.sendMessage(sessionId, any()) } returns true

        generator.generateHint(buildMemory(hintsGiven = 1, evalScores = EvalScores(problemSolving = 5.0)))

        coVerify { memoryService.updateMemory(sessionId, any()) }
    }

    // ── WS message ────────────────────────────────────────────────────────────

    @Test
    fun `generateHint sends HintDelivered WS message`() = runTest {
        stubLlm("Think about complement lookup.")
        coEvery { memoryService.updateMemory(sessionId, any()) } returns buildMemory(hintsGiven = 1)
        coEvery { registry.sendMessage(sessionId, any()) } returns true

        generator.generateHint(buildMemory(hintsGiven = 0))

        coVerify {
            registry.sendMessage(sessionId, match { it is OutboundMessage.HintDelivered && !it.refused })
        }
    }

    // ── memory update ─────────────────────────────────────────────────────────

    @Test
    fun `generateHint updates hintsGiven in memory`() = runTest {
        stubLlm("Consider using a set.")
        coEvery { memoryService.updateMemory(sessionId, any()) } returns buildMemory(hintsGiven = 1)
        coEvery { registry.sendMessage(sessionId, any()) } returns true

        generator.generateHint(buildMemory(hintsGiven = 0))

        coVerify { memoryService.updateMemory(sessionId, any()) }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun stubLlm(response: String) {
        every { openAIClient.chat() } returns mockk {
            every { completions() } returns mockk {
                every { create(any<ChatCompletionCreateParams>()) } returns mockChatCompletion(response)
            }
        }
    }

    private fun mockChatCompletion(text: String): ChatCompletion = mockk {
        every { choices() } returns listOf(
            mockk {
                every { message() } returns mockk {
                    every { content() } returns java.util.Optional.of(text)
                }
            }
        )
    }

    private fun buildMemory(
        hintsGiven: Int = 0,
        evalScores: EvalScores = EvalScores(),
    ) = InterviewMemory(
        sessionId         = sessionId,
        userId            = UUID.randomUUID(),
        state             = "QUESTION_PRESENTED",
        category          = "CODING",
        personality       = "friendly_mentor",
        currentQuestion   = null,
        candidateAnalysis = null,
        hintsGiven        = hintsGiven,
        evalScores        = evalScores,
        createdAt         = Instant.now(),
        lastActivityAt    = Instant.now(),
    )
}
