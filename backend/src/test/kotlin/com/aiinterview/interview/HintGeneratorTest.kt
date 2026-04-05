package com.aiinterview.interview

import com.aiinterview.conversation.HintGenerator
import com.aiinterview.conversation.brain.BrainService
import com.aiinterview.conversation.brain.InterviewQuestion
import com.aiinterview.conversation.brain.InterviewerBrain
import com.aiinterview.interview.ws.OutboundMessage
import com.aiinterview.interview.ws.WsSessionRegistry
import com.aiinterview.shared.ai.LlmProviderRegistry
import com.aiinterview.shared.ai.LlmResponse
import com.aiinterview.shared.ai.ModelConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class HintGeneratorTest {

    private val llm         = mockk<LlmProviderRegistry>()
    private val modelConfig = ModelConfig()
    private val brainService = mockk<BrainService>()
    private val registry    = mockk<WsSessionRegistry>(relaxed = true)

    private val generator = HintGenerator(
        llm          = llm,
        modelConfig  = modelConfig,
        brainService = brainService,
        registry     = registry,
    )

    private val sessionId = UUID.randomUUID()

    // ── refused when limit reached ────────────────────────────────────────────

    @Test
    fun `generateHint refuses when hintsGiven equals 3`() = runTest {
        coEvery { brainService.getBrainOrNull(sessionId) } returns buildBrain(hintsGiven = 3)

        val result = generator.generateHint(sessionId)

        assertTrue(result.refused)
        assertEquals(0, result.hintsRemaining)
    }

    @Test
    fun `generateHint sends HintDelivered with refused=true at limit`() = runTest {
        coEvery { brainService.getBrainOrNull(sessionId) } returns buildBrain(hintsGiven = 3)

        generator.generateHint(sessionId)

        coVerify {
            registry.sendMessage(sessionId, match {
                it is OutboundMessage.HintDelivered && it.refused
            })
        }
    }

    // ── level 1 hint ─────────────────────────────────────────────────────────

    @Test
    fun `generateHint delivers level 1 when hintsGiven is 0`() = runTest {
        coEvery { brainService.getBrainOrNull(sessionId) } returns buildBrain(hintsGiven = 0)
        coEvery { brainService.updateBrain(sessionId, any()) } returns Unit
        stubLlm("Think about how to avoid repeated lookups.")

        val result = generator.generateHint(sessionId)

        assertEquals(1, result.level)
        assertEquals("Think about how to avoid repeated lookups.", result.hint)
        assertEquals(2, result.hintsRemaining)
    }

    // ── WS message ────────────────────────────────────────────────────────────

    @Test
    fun `generateHint sends HintDelivered WS message`() = runTest {
        coEvery { brainService.getBrainOrNull(sessionId) } returns buildBrain(hintsGiven = 0)
        coEvery { brainService.updateBrain(sessionId, any()) } returns Unit
        stubLlm("Think about complement lookup.")

        generator.generateHint(sessionId)

        coVerify {
            registry.sendMessage(sessionId, match { it is OutboundMessage.HintDelivered && !it.refused })
        }
    }

    // ── brain update ─────────────────────────────────────────────────────────

    @Test
    fun `generateHint increments hintsGiven in brain`() = runTest {
        coEvery { brainService.getBrainOrNull(sessionId) } returns buildBrain(hintsGiven = 0)
        coEvery { brainService.updateBrain(sessionId, any()) } returns Unit
        stubLlm("Consider using a set.")

        generator.generateHint(sessionId)

        coVerify { brainService.updateBrain(sessionId, any()) }
    }

    // ── brain missing ────────────────────────────────────────────────────────

    @Test
    fun `generateHint refuses when brain is missing`() = runTest {
        coEvery { brainService.getBrainOrNull(sessionId) } returns null

        val result = generator.generateHint(sessionId)

        assertTrue(result.refused)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun stubLlm(response: String) {
        coEvery { llm.complete(any()) } returns LlmResponse(
            content = response, model = "gpt-4o-mini", provider = "openai",
        )
    }

    private fun buildBrain(hintsGiven: Int = 0) = InterviewerBrain(
        sessionId = sessionId,
        userId = UUID.randomUUID(),
        interviewType = "CODING",
        questionDetails = InterviewQuestion(
            title = "Two Sum",
            description = "Find two numbers that add up to target",
            optimalApproach = "Use a hash map for O(n) lookup",
            difficulty = "MEDIUM",
            category = "CODING",
        ),
        hintsGiven = hintsGiven,
    )
}
