package com.aiinterview.interview

import com.aiinterview.conversation.AnalysisResult
import com.aiinterview.conversation.InterviewState
import com.aiinterview.conversation.ReasoningAnalyzer
import com.aiinterview.interview.service.CandidateAnalysis
import com.aiinterview.interview.service.EvalScores
import com.aiinterview.interview.service.InterviewMemory
import com.aiinterview.interview.service.RedisMemoryService
import com.aiinterview.shared.ai.LlmProviderRegistry
import com.aiinterview.shared.ai.LlmResponse
import com.aiinterview.shared.ai.ModelConfig
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ReasoningAnalyzerTest {

    private val llm           = mockk<LlmProviderRegistry>()
    private val modelConfig   = ModelConfig()
    private val memoryService = mockk<RedisMemoryService>()
    private val objectMapper  = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
        disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private val analyzer = ReasoningAnalyzer(
        llm                = llm,
        modelConfig        = modelConfig,
        redisMemoryService = memoryService,
        objectMapper       = objectMapper,
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

        val result = analyzer.analyze(memory, "some answer")

        assertNull(result.suggestedTransition)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun stubLlm(responseText: String) {
        coEvery { llm.complete(any()) } returns LlmResponse(
            content = responseText, model = "gpt-4o-mini", provider = "openai",
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
