package com.aiinterview.report

import com.aiinterview.interview.service.EvalScores
import com.aiinterview.interview.service.InterviewMemory
import com.aiinterview.report.service.EvaluationAgent
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openai.client.OpenAIClient
import com.openai.models.ChatModel
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessage
import com.openai.services.blocking.chat.ChatCompletionService
import com.openai.services.blocking.ChatService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional
import java.util.UUID

class EvaluationAgentTest {

    private val openAIClient     = mockk<OpenAIClient>()
    private val chatService      = mockk<ChatService>()
    private val completionService = mockk<ChatCompletionService>()
    private val objectMapper     = jacksonObjectMapper()

    private val agent = EvaluationAgent(
        openAIClient = openAIClient,
        objectMapper = objectMapper,
        model        = "gpt-4o",
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

    @BeforeEach
    fun setUp() {
        every { openAIClient.chat() } returns chatService
        every { chatService.completions() } returns completionService
    }

    private fun mockLlmResponse(json: String) {
        val choice  = mockk<ChatCompletion.Choice>()
        val message = mockk<ChatCompletionMessage>()
        val result  = mockk<ChatCompletion>()

        every { message.content() } returns Optional.of(json)
        every { choice.message() } returns message
        every { result.choices() } returns listOf(choice)
        every { completionService.create(any<ChatCompletionCreateParams>()) } returns result
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
        mockLlmResponse(json)

        val result = agent.evaluate(memory)

        assertEquals(2, result.strengths.size)
        assertEquals(1, result.weaknesses.size)
        assertEquals(1, result.suggestions.size)
        assertTrue(result.narrativeSummary.isNotBlank())
        assertEquals(6, result.dimensionFeedback.size)
        assertTrue(result.dimensionFeedback.containsKey("problemSolving"))
    }

    @Test
    fun `evaluate uses gpt-4o not gpt-4o-mini`() = runBlocking {
        mockLlmResponse("""{"strengths":[],"weaknesses":[],"suggestions":[],"narrativeSummary":"ok","dimensionFeedback":{}}""")

        agent.evaluate(memory)

        verify {
            completionService.create(
                match<ChatCompletionCreateParams> { params ->
                    params.model() == ChatModel.of("gpt-4o")
                }
            )
        }
    }

    @Test
    fun `evaluate retries once on parse failure and returns result`() = runBlocking {
        val badJson  = "this is not valid json at all"
        val goodJson = """{"strengths":["ok"],"weaknesses":[],"suggestions":[],"narrativeSummary":"good","dimensionFeedback":{}}"""
        val choice1  = mockk<ChatCompletion.Choice>()
        val message1 = mockk<ChatCompletionMessage>()
        val result1  = mockk<ChatCompletion>()
        val choice2  = mockk<ChatCompletion.Choice>()
        val message2 = mockk<ChatCompletionMessage>()
        val result2  = mockk<ChatCompletion>()

        every { message1.content() } returns Optional.of(badJson)
        every { choice1.message() } returns message1
        every { result1.choices() } returns listOf(choice1)

        every { message2.content() } returns Optional.of(goodJson)
        every { choice2.message() } returns message2
        every { result2.choices() } returns listOf(choice2)

        every { completionService.create(any<ChatCompletionCreateParams>()) } returnsMany listOf(result1, result2)

        val result = agent.evaluate(memory)

        assertEquals(listOf("ok"), result.strengths)
    }

    @Test
    fun `evaluate returns default result when both attempts fail`() = runBlocking {
        val badJson = "{ invalid json }"
        val choice  = mockk<ChatCompletion.Choice>()
        val message = mockk<ChatCompletionMessage>()
        val llmResult = mockk<ChatCompletion>()

        every { message.content() } returns Optional.of(badJson)
        every { choice.message() } returns message
        every { llmResult.choices() } returns listOf(choice)
        every { completionService.create(any<ChatCompletionCreateParams>()) } returns llmResult

        val result = agent.evaluate(memory)

        assertFalse(result.strengths.isEmpty())
        assertFalse(result.narrativeSummary.isBlank())
        assertNotNull(result.dimensionFeedback["problemSolving"])
    }

    @Test
    fun `evaluate prompt includes transcript and code context`() = runBlocking {
        val capturedParams = mutableListOf<ChatCompletionCreateParams>()
        val json = """{"strengths":[],"weaknesses":[],"suggestions":[],"narrativeSummary":"ok","dimensionFeedback":{}}"""
        val choice  = mockk<ChatCompletion.Choice>()
        val message = mockk<ChatCompletionMessage>()
        val result  = mockk<ChatCompletion>()

        every { message.content() } returns Optional.of(json)
        every { choice.message() } returns message
        every { result.choices() } returns listOf(choice)
        every { completionService.create(capture(capturedParams)) } returns result

        val memoryWithCode = memory.copy(currentCode = "def solution(): pass")
        agent.evaluate(memoryWithCode)

        assertEquals(1, capturedParams.size)
        val userMessage = capturedParams[0].messages()
            .mapNotNull { it.user().orElse(null)?.content()?.asText() }
            .firstOrNull()
        assertTrue(userMessage?.contains("def solution()") == true)
    }
}
