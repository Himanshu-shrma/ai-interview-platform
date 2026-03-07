package com.aiinterview.interview

import com.aiinterview.conversation.FollowUpGenerator
import com.aiinterview.interview.service.InterviewMemory
import com.aiinterview.interview.service.RedisMemoryService
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
import java.time.Instant
import java.util.UUID

class FollowUpGeneratorTest {

    private val llm           = mockk<LlmProviderRegistry>()
    private val modelConfig   = ModelConfig()
    private val memoryService = mockk<RedisMemoryService>()

    private val generator = FollowUpGenerator(
        llm                = llm,
        modelConfig        = modelConfig,
        redisMemoryService = memoryService,
    )

    private val sessionId = UUID.randomUUID()

    // ── generate ─────────────────────────────────────────────────────────────

    @Test
    fun `generate returns follow-up targeting the gaps`() = runTest {
        val question = "What is the time complexity of your approach?"
        stubLlm(question)
        coEvery { memoryService.updateMemory(sessionId, any()) } returns buildMemory(followUpsAsked = emptyList())

        val result = generator.generate(buildMemory(), listOf("time complexity", "space complexity"))

        assertEquals(question, result)
    }

    @Test
    fun `generate returns empty string when followUpsAsked limit reached`() = runTest {
        val result = generator.generate(
            buildMemory(followUpsAsked = listOf("q1", "q2", "q3")),
            listOf("time complexity"),
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `generate updates followUpsAsked in memory`() = runTest {
        stubLlm("Can you explain the trade-offs?")
        coEvery { memoryService.updateMemory(sessionId, any()) } returns buildMemory()

        generator.generate(buildMemory(), listOf("trade-offs"))

        coVerify { memoryService.updateMemory(sessionId, any()) }
    }

    @Test
    fun `generate with 2 follow-ups asked still generates (under limit)`() = runTest {
        val question = "What about edge cases?"
        stubLlm(question)
        coEvery { memoryService.updateMemory(sessionId, any()) } returns buildMemory()

        val result = generator.generate(
            buildMemory(followUpsAsked = listOf("q1", "q2")),
            listOf("edge cases"),
        )

        assertEquals(question, result)
    }

    @Test
    fun `generate returns empty on LLM failure`() = runTest {
        coEvery { llm.complete(any()) } throws RuntimeException("Network error")

        val result = generator.generate(buildMemory(), listOf("gaps"))

        assertTrue(result.isEmpty())
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun stubLlm(response: String) {
        coEvery { llm.complete(any()) } returns LlmResponse(
            content = response, model = "gpt-4o-mini", provider = "openai",
        )
    }

    private fun buildMemory(followUpsAsked: List<String> = emptyList()) = InterviewMemory(
        sessionId         = sessionId,
        userId            = UUID.randomUUID(),
        state             = "QUESTION_PRESENTED",
        category          = "CODING",
        personality       = "friendly_mentor",
        currentQuestion   = null,
        candidateAnalysis = null,
        followUpsAsked    = followUpsAsked,
        createdAt         = Instant.now(),
        lastActivityAt    = Instant.now(),
    )
}
