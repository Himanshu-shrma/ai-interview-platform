package com.aiinterview.report

import com.aiinterview.interview.service.EvalScores
import com.aiinterview.interview.service.InterviewMemory
import com.aiinterview.report.service.EvaluationAgent
import com.aiinterview.shared.ai.LlmProviderRegistry
import com.aiinterview.shared.ai.LlmResponse
import com.aiinterview.shared.ai.ModelConfig
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class EvaluationAgentTest {

    private val llm          = mockk<LlmProviderRegistry>()
    private val modelConfig  = ModelConfig()
    private val objectMapper = jacksonObjectMapper()

    private val agent = EvaluationAgent(
        llm          = llm,
        modelConfig  = modelConfig,
        objectMapper = objectMapper,
    )

    private val sessionId = UUID.randomUUID()

    private val memory = InterviewMemory(
        sessionId         = sessionId,
        userId            = UUID.randomUUID(),
        state             = "EVALUATING",
        category          = "CODING",
        personality       = "professional",
        currentQuestion   = null,
        candidateAnalysis = null,
        evalScores        = EvalScores(
            problemSolving  = 7.0,
            algorithmChoice = 6.0,
            codeQuality     = 5.0,
            communication   = 8.0,
            efficiency      = 6.0,
            testing         = 4.0,
        ),
        hintsGiven        = 1,
        createdAt         = Instant.now(),
        lastActivityAt    = Instant.now(),
    )

    private fun stubLlm(json: String) {
        coEvery { llm.complete(any()) } returns LlmResponse(
            content = json, model = "gpt-4o", provider = "openai",
        )
    }

    @Test
    fun `evaluate returns EvaluationResult with all fields`() = runBlocking {
        val json = """
            {
              "strengths": ["Good problem approach", "Clear communication"],
              "weaknesses": ["Could optimize time complexity"],
              "suggestions": ["Practice more dynamic programming"],
              "narrativeSummary": "The candidate showed a solid understanding of the problem.",
              "dimensionFeedback": {
                "problemSolving": "Strong analytical approach",
                "algorithmChoice": "Good choice of hash map",
                "codeQuality": "Clean readable code",
                "communication": "Explained clearly",
                "efficiency": "Solution is O(n)",
                "testing": "Did not test edge cases"
              }
            }
        """.trimIndent()
        stubLlm(json)

        val result = agent.evaluate(memory)

        assertEquals(2, result.strengths.size)
        assertEquals(1, result.weaknesses.size)
        assertEquals(1, result.suggestions.size)
        assertTrue(result.narrativeSummary.isNotBlank())
        assertEquals(6, result.dimensionFeedback.size)
        assertTrue(result.dimensionFeedback.containsKey("problemSolving"))
    }

    @Test
    fun `evaluate retries once on parse failure and returns result`() = runBlocking {
        val badJson  = "this is not valid json at all"
        val goodJson = """{"strengths":["ok"],"weaknesses":[],"suggestions":[],"narrativeSummary":"good","dimensionFeedback":{}}"""

        coEvery { llm.complete(any()) } returnsMany listOf(
            LlmResponse(content = badJson, model = "gpt-4o", provider = "openai"),
            LlmResponse(content = goodJson, model = "gpt-4o", provider = "openai"),
        )

        val result = agent.evaluate(memory)

        assertEquals(listOf("ok"), result.strengths)
    }

    @Test
    fun `evaluate returns default result when both attempts fail`() = runBlocking {
        coEvery { llm.complete(any()) } returns LlmResponse(
            content = "{ invalid json }", model = "gpt-4o", provider = "openai",
        )

        val result = agent.evaluate(memory)

        assertFalse(result.strengths.isEmpty())
        assertFalse(result.narrativeSummary.isBlank())
        assertNotNull(result.dimensionFeedback["problemSolving"])
    }

    @Test
    fun `evaluate prompt includes code context`() = runBlocking {
        stubLlm("""{"strengths":[],"weaknesses":[],"suggestions":[],"narrativeSummary":"ok","dimensionFeedback":{}}""")

        val memoryWithCode = memory.copy(currentCode = "def solution(): pass")
        agent.evaluate(memoryWithCode)
    }
}
