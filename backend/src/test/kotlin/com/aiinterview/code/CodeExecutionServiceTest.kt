package com.aiinterview.code

import com.aiinterview.code.model.CodeSubmission
import com.aiinterview.code.repository.CodeSubmissionRepository
import com.aiinterview.code.service.CodeExecutionService
import com.aiinterview.conversation.ConversationEngine
import com.aiinterview.conversation.InterviewState
import com.aiinterview.conversation.brain.BrainService
import com.aiinterview.interview.model.Question
import com.aiinterview.interview.model.SessionQuestion
import com.aiinterview.interview.repository.QuestionRepository
import com.aiinterview.interview.repository.SessionQuestionRepository
import com.aiinterview.interview.service.EvalScores
import com.aiinterview.interview.service.InterviewMemory
import com.aiinterview.interview.service.RedisMemoryService
import com.aiinterview.interview.ws.OutboundMessage
import com.aiinterview.interview.ws.WsSessionRegistry
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.UUID

class CodeExecutionServiceTest {

    private val judge0Client             = mockk<Judge0Client>()
    private val registry                 = mockk<WsSessionRegistry>(relaxed = true)
    private val redisMemoryService       = mockk<RedisMemoryService>(relaxed = true)
    private val sessionQuestionRepository = mockk<SessionQuestionRepository>()
    private val questionRepository        = mockk<QuestionRepository>()
    private val codeSubmissionRepository  = mockk<CodeSubmissionRepository>()
    private val conversationEngine        = mockk<ConversationEngine>(relaxed = true)
    private val objectMapper              = ObjectMapper()

    private val brainService: BrainService = mockk(relaxed = true)

    private val service = CodeExecutionService(
        judge0Client             = judge0Client,
        registry                 = registry,
        redisMemoryService       = redisMemoryService,
        sessionQuestionRepository = sessionQuestionRepository,
        questionRepository        = questionRepository,
        codeSubmissionRepository  = codeSubmissionRepository,
        conversationEngine        = conversationEngine,
        objectMapper              = objectMapper,
        brainService              = brainService,
    )

    private val sessionId         = UUID.randomUUID()
    private val userId            = UUID.randomUUID()
    private val questionId        = UUID.randomUUID()
    private val sessionQuestionId = UUID.randomUUID()

    private val memory = InterviewMemory(
        sessionId  = sessionId,
        userId     = userId,
        state      = "CODING_CHALLENGE",
        category   = "CODING",
        personality = "professional",
        currentQuestion = null,
        candidateAnalysis = null,
        evalScores = EvalScores(),
        createdAt  = Instant.now(),
        lastActivityAt = Instant.now(),
    )

    @BeforeEach
    fun setUp() {
        coEvery { redisMemoryService.getMemory(sessionId) } returns memory
        coEvery { redisMemoryService.updateMemory(sessionId, any()) } returns memory
    }

    // ── runCode ───────────────────────────────────────────────────────────────

    @Test
    fun `runCode sends CodeResult over WebSocket on success`() = runBlocking {
        coEvery { judge0Client.submit(any(), any(), any()) } returns "tok-1"
        coEvery { judge0Client.pollResult("tok-1") } returns Judge0Result(
            token = "tok-1",
            status = Judge0Status(3, "Accepted"),
            stdout = "42\n",
            stderr = null,
            compileOutput = null,
            time = "0.02",
            memory = null,
        )

        service.runCode(sessionId, "print(42)", "python")

        val slot = slot<OutboundMessage>()
        coVerify { registry.sendMessage(sessionId, capture(slot)) }
        val msg = slot.captured as OutboundMessage.CodeResult
        assertEquals("Accepted", msg.status)
        assertEquals("42\n", msg.stdout)
        assertEquals(20L, msg.runtimeMs)  // 0.02 * 1000 = 20
    }

    @Test
    fun `runCode sends Error for unsupported language`() = runBlocking {
        service.runCode(sessionId, "code", "brainfuck")

        val slot = slot<OutboundMessage>()
        coVerify { registry.sendMessage(sessionId, capture(slot)) }
        val error = slot.captured as OutboundMessage.Error
        assertEquals("UNSUPPORTED_LANGUAGE", error.code)
    }

    @Test
    fun `runCode sends Error when judge0Client throws`() = runBlocking {
        coEvery { judge0Client.submit(any(), any(), any()) } throws RuntimeException("Network error")

        service.runCode(sessionId, "print(1)", "python")

        val slot = slot<OutboundMessage>()
        // Two messages: possible memory update calls; last should be an error
        coVerify(atLeast = 1) { registry.sendMessage(sessionId, capture(slot)) }
        assertTrue(slot.captured is OutboundMessage.Error)
    }

    // ── submitCode ────────────────────────────────────────────────────────────

    @Test
    fun `submitCode runs all test cases and persists submission`() = runBlocking {
        val sessionQuestion = SessionQuestion(
            id         = sessionQuestionId,
            sessionId  = sessionId,
            questionId = questionId,
        )
        val testCasesJson = """[{"input":"1\n2","expectedOutput":"3"},{"input":"5\n5","expectedOutput":"10"}]"""
        val question = Question(
            id         = questionId,
            title      = "Add Two Numbers",
            description = "Add a and b",
            type       = "CODING",
            difficulty = "EASY",
            testCases  = testCasesJson,
        )

        coEvery { sessionQuestionRepository.findById(sessionQuestionId) } returns Mono.just(sessionQuestion)
        coEvery { questionRepository.findById(questionId) } returns Mono.just(question)
        coEvery { judge0Client.submit(any(), any(), any()) } returns "tok"
        coEvery { judge0Client.pollResult("tok") } returns Judge0Result(
            token = "tok",
            status = Judge0Status(3, "Accepted"),
            stdout = "3\n",
            stderr = null,
            compileOutput = null,
            time = "0.01",
            memory = null,
        )
        val savedSubmission = CodeSubmission(
            id = UUID.randomUUID(),
            sessionQuestionId = sessionQuestionId,
            userId = userId,
            code = "code",
            language = "python",
        )
        coEvery { codeSubmissionRepository.save(any()) } returns Mono.just(savedSubmission)
        coEvery { sessionQuestionRepository.save(any()) } returns Mono.just(sessionQuestion)

        service.submitCode(sessionId, sessionQuestionId, "code", "python")

        coVerify(exactly = 1) { codeSubmissionRepository.save(any()) }
    }

    @Test
    fun `submitCode transitions to FollowUp when all tests pass`() = runBlocking {
        val sessionQuestion = SessionQuestion(
            id         = sessionQuestionId,
            sessionId  = sessionId,
            questionId = questionId,
        )
        val question = Question(
            id         = questionId,
            title      = "Test",
            description = "desc",
            type       = "CODING",
            difficulty = "EASY",
            testCases  = """[{"input":"1","expectedOutput":"1"}]""",
        )

        coEvery { sessionQuestionRepository.findById(sessionQuestionId) } returns Mono.just(sessionQuestion)
        coEvery { questionRepository.findById(questionId) } returns Mono.just(question)
        coEvery { judge0Client.submit(any(), any(), any()) } returns "tok"
        coEvery { judge0Client.pollResult("tok") } returns Judge0Result(
            token = "tok",
            status = Judge0Status(3, "Accepted"),
            stdout = "1",
            stderr = null,
            compileOutput = null,
            time = "0.01",
            memory = null,
        )
        val savedSubmission = CodeSubmission(
            id = UUID.randomUUID(),
            sessionQuestionId = sessionQuestionId,
            userId = userId,
            code = "code",
            language = "python",
        )
        coEvery { codeSubmissionRepository.save(any()) } returns Mono.just(savedSubmission)
        coEvery { sessionQuestionRepository.save(any()) } returns Mono.just(sessionQuestion)

        service.submitCode(sessionId, sessionQuestionId, "code", "python")

        coVerify { conversationEngine.transition(sessionId, InterviewState.FollowUp) }
    }

    @Test
    fun `submitCode sends QUESTION_NOT_FOUND error when session question missing`() = runBlocking {
        coEvery { sessionQuestionRepository.findById(sessionQuestionId) } returns Mono.empty()

        service.submitCode(sessionId, sessionQuestionId, "code", "python")

        val slot = slot<OutboundMessage>()
        coVerify { registry.sendMessage(sessionId, capture(slot)) }
        val error = slot.captured as OutboundMessage.Error
        assertEquals("QUESTION_NOT_FOUND", error.code)
    }

    @Test
    fun `submitCode sends FAILED status when test case output does not match`() = runBlocking {
        val sessionQuestion = SessionQuestion(
            id         = sessionQuestionId,
            sessionId  = sessionId,
            questionId = questionId,
        )
        val question = Question(
            id         = questionId,
            title      = "Test",
            description = "desc",
            type       = "CODING",
            difficulty = "EASY",
            testCases  = """[{"input":"1","expectedOutput":"99"}]""",  // expected=99, actual will be 1
        )

        coEvery { sessionQuestionRepository.findById(sessionQuestionId) } returns Mono.just(sessionQuestion)
        coEvery { questionRepository.findById(questionId) } returns Mono.just(question)
        coEvery { judge0Client.submit(any(), any(), any()) } returns "tok"
        coEvery { judge0Client.pollResult("tok") } returns Judge0Result(
            token = "tok",
            status = Judge0Status(3, "Accepted"),
            stdout = "1",
            stderr = null,
            compileOutput = null,
            time = "0.01",
            memory = null,
        )
        val savedSubmission = CodeSubmission(
            id = UUID.randomUUID(),
            sessionQuestionId = sessionQuestionId,
            userId = userId,
            code = "code",
            language = "python",
        )
        coEvery { codeSubmissionRepository.save(any()) } returns Mono.just(savedSubmission)
        coEvery { sessionQuestionRepository.save(any()) } returns Mono.just(sessionQuestion)

        service.submitCode(sessionId, sessionQuestionId, "code", "python")

        // Capture the CodeResult message and verify status=FAILED
        val messages = mutableListOf<OutboundMessage>()
        coVerify(atLeast = 1) { registry.sendMessage(sessionId, capture(messages)) }
        val codeResult = messages.filterIsInstance<OutboundMessage.CodeResult>().firstOrNull()
        assertEquals("FAILED", codeResult?.status)
    }
}
