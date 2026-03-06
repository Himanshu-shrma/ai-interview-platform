package com.aiinterview.interview

import com.aiinterview.conversation.AnalysisResult
import com.aiinterview.conversation.InterviewState
import com.aiinterview.conversation.ReasoningAnalyzer
import com.aiinterview.interview.service.CandidateAnalysis
import com.aiinterview.interview.service.EvalScores
import com.aiinterview.interview.service.InterviewMemory
import com.aiinterview.interview.service.RedisMemoryService
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openai.client.OpenAIClient
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionCreateParams
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ReasoningAnalyzerTest {

    private val openAIClient    = mockk<OpenAIClient>()
    private val memoryService   = mockk<RedisMemoryService>()
    private val objectMapper    = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
        disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private val analyzer = ReasoningAnalyzer(
        openAIClient       = openAIClient,
        redisMemoryService = memoryService,
        objectMapper       = objectMapper,
        model              = "gpt-4o-mini",
    )

    private val sessionId = UUID.randomUUID()
    private val memory    = buildMemory()

    // ── happy path ───────────────────────────────────────────────────────────

    @Test
    fun `analyze returns CandidateAnalysis with parsed fields`() = runTest {
        val json = """{"approach":"hash map","confidence":"high","correctness":"correct","gaps":[],"codingSignalDetected":false,"readyForEvaluation":false}"""
        stubLlm(json)
        coEvery { memoryService.updateMemory(sessionId, any()) } returns memory

        val result = analyzer.analyze(memory, "I'd use a hash map")

        assertEquals("hash map", result.analysis.approach)
        assertEquals("high", result.analysis.confidence)
        assertEquals("correct", result.analysis.correctness)
        assertTrue(result.analysis.gaps.isEmpty())
    }

    @Test
    fun `codingSignalDetected=true suggests CodingChallenge transition`() = runTest {
        val json = """{"approach":"brute force","confidence":"medium","correctness":"partial","gaps":[],"codingSignalDetected":true,"readyForEvaluation":false}"""
        stubLlm(json)
        coEvery { memoryService.updateMemory(sessionId, any()) } returns memory

        val result = analyzer.analyze(memory, "Let me code this up")

        assertEquals(InterviewState.CodingChallenge, result.suggestedTransition)
    }

    @Test
    fun `readyForEvaluation=true suggests Evaluating transition`() = runTest {
        val json = """{"approach":"optimal","confidence":"high","correctness":"correct","gaps":[],"codingSignalDetected":false,"readyForEvaluation":true}"""
        stubLlm(json)
        coEvery { memoryService.updateMemory(sessionId, any()) } returns memory

        val result = analyzer.analyze(memory, "I think the time complexity is O(n)")

        assertEquals(InterviewState.Evaluating, result.suggestedTransition)
    }

    @Test
    fun `gaps present suggests FollowUp transition`() = runTest {
        val json = """{"approach":"nested loop","confidence":"low","correctness":"partial","gaps":["time complexity","space complexity"],"codingSignalDetected":false,"readyForEvaluation":false}"""
        stubLlm(json)
        coEvery { memoryService.updateMemory(sessionId, any()) } returns memory

        val result = analyzer.analyze(memory, "I'd loop through twice")

        assertEquals(InterviewState.FollowUp, result.suggestedTransition)
        assertEquals(2, result.analysis.gaps.size)
    }

    @Test
    fun `no signals returns null transition`() = runTest {
        val json = """{"approach":"unknown","confidence":"low","correctness":"incorrect","gaps":[],"codingSignalDetected":false,"readyForEvaluation":false}"""
        stubLlm(json)
        coEvery { memoryService.updateMemory(sessionId, any()) } returns memory

        val result = analyzer.analyze(memory, "I'm not sure")

        assertNull(result.suggestedTransition)
    }

    @Test
    fun `updates Redis memory with analysis`() = runTest {
        val json = """{"approach":"two pointers","confidence":"medium","correctness":"partial","gaps":["edge cases"],"codingSignalDetected":false,"readyForEvaluation":false}"""
        stubLlm(json)
        coEvery { memoryService.updateMemory(sessionId, any()) } returns memory

        analyzer.analyze(memory, "I'd use two pointers")

        coVerify { memoryService.updateMemory(sessionId, any()) }
    }

    @Test
    fun `malformed JSON returns fallback with null transition`() = runTest {
        stubLlm("this is not JSON { broken")
        // No updateMemory call expected for fallback

        val result = analyzer.analyze(memory, "some answer")

        assertNull(result.suggestedTransition)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun stubLlm(responseText: String) {
        every { openAIClient.chat() } returns mockk {
            every { completions() } returns mockk {
                every { create(any<ChatCompletionCreateParams>()) } returns mockChatCompletion(responseText)
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

    private fun buildMemory() = InterviewMemory(
        sessionId         = sessionId,
        userId            = UUID.randomUUID(),
        state             = "QUESTION_PRESENTED",
        category          = "CODING",
        personality       = "friendly_mentor",
        currentQuestion   = null,
        candidateAnalysis = null,
        evalScores        = EvalScores(),
        createdAt         = Instant.now(),
        lastActivityAt    = Instant.now(),
    )
}
