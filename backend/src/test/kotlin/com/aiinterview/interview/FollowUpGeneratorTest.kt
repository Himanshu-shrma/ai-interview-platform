package com.aiinterview.interview

import com.aiinterview.conversation.FollowUpGenerator
import com.aiinterview.interview.service.InterviewMemory
import com.aiinterview.interview.service.RedisMemoryService
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

class FollowUpGeneratorTest {

    private val openAIClient  = mockk<OpenAIClient>()
    private val memoryService = mockk<RedisMemoryService>()

    private val generator = FollowUpGenerator(
        openAIClient       = openAIClient,
        redisMemoryService = memoryService,
        model              = "gpt-4o-mini",
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
        every { openAIClient.chat() } throws RuntimeException("Network error")

        val result = generator.generate(buildMemory(), listOf("gaps"))

        assertTrue(result.isEmpty())
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
