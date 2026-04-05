package com.aiinterview.report

import com.aiinterview.conversation.brain.BrainService
import com.aiinterview.conversation.brain.InterviewQuestion
import com.aiinterview.conversation.brain.InterviewerBrain
import com.aiinterview.interview.model.InterviewSession
import com.aiinterview.interview.repository.InterviewSessionRepository
import com.aiinterview.interview.repository.QuestionRepository
import com.aiinterview.interview.repository.SessionQuestionRepository
import com.aiinterview.interview.ws.OutboundMessage
import com.aiinterview.interview.ws.WsSessionRegistry
import com.aiinterview.report.model.EvaluationReport
import com.aiinterview.report.repository.EvaluationReportRepository
import com.aiinterview.report.service.EvaluationAgent
import com.aiinterview.report.service.EvaluationResult
import com.aiinterview.report.service.EvaluationScores
import com.aiinterview.report.service.ReportService
import com.aiinterview.user.service.UsageLimitService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.time.OffsetDateTime
import java.util.UUID

class ReportServiceTest {

    private val evaluationAgent            = mockk<EvaluationAgent>()
    private val brainService               = mockk<BrainService>()
    private val registry                   = mockk<WsSessionRegistry>(relaxed = true)
    private val evaluationReportRepository = mockk<EvaluationReportRepository>()
    private val interviewSessionRepository = mockk<InterviewSessionRepository>()
    private val sessionQuestionRepository  = mockk<SessionQuestionRepository>()
    private val questionRepository         = mockk<QuestionRepository>()
    private val usageLimitService          = mockk<UsageLimitService>(relaxed = true)
    private val objectMapper               = jacksonObjectMapper()

    private val service = ReportService(
        evaluationAgent            = evaluationAgent,
        brainService               = brainService,
        registry                   = registry,
        evaluationReportRepository = evaluationReportRepository,
        interviewSessionRepository = interviewSessionRepository,
        sessionQuestionRepository  = sessionQuestionRepository,
        questionRepository         = questionRepository,
        usageLimitService          = usageLimitService,
        objectMapper               = objectMapper,
        freeTierLimit              = 3,
    )

    private val sessionId = UUID.randomUUID()
    private val userId    = UUID.randomUUID()
    private val reportId  = UUID.randomUUID()

    private val brain = InterviewerBrain(
        sessionId = sessionId,
        userId = userId,
        interviewType = "CODING",
        questionDetails = InterviewQuestion(
            title = "Two Sum", description = "Find two numbers...",
            difficulty = "MEDIUM", category = "CODING",
        ),
        hintsGiven = 0,
        turnCount = 10,
    )

    private val session = InterviewSession(
        id        = sessionId,
        userId    = userId,
        status    = "ACTIVE",
        type      = "CODING",
        config    = """{"category":"CODING","difficulty":"MEDIUM"}""",
        startedAt = OffsetDateTime.now().minusMinutes(30),
    )

    private val evalResult = EvaluationResult(
        strengths         = listOf("Strong problem approach", "Good communication"),
        weaknesses        = listOf("Could test edge cases"),
        suggestions       = listOf("Review dynamic programming"),
        narrativeSummary  = "Solid performance overall.",
        dimensionFeedback = mapOf(
            "problemSolving" to "Strong",
            "algorithmChoice" to "Good",
            "codeQuality" to "Average",
            "communication" to "Excellent",
            "efficiency" to "Adequate",
            "testing" to "Needs work",
        ),
        scores = EvaluationScores(
            problemSolving  = 8.0,
            algorithmChoice = 7.0,
            codeQuality     = 6.0,
            communication   = 9.0,
            efficiency      = 5.0,
            testing         = 4.0,
            initiative      = 7.0,
            learningAgility = 6.0,
        ),
    )

    @BeforeEach
    fun setUp() {
        coEvery { evaluationReportRepository.findBySessionId(sessionId) } returns Mono.empty()
        coEvery { brainService.getBrainOrNull(sessionId) } returns brain
        coEvery { interviewSessionRepository.findById(sessionId) } returns Mono.just(session)
        coEvery { evaluationAgent.evaluate(any()) } returns evalResult
        coEvery { evaluationReportRepository.save(any()) } answers {
            Mono.just(firstArg<EvaluationReport>().copy(id = reportId))
        }
        coEvery { interviewSessionRepository.save(any()) } answers {
            Mono.just(firstArg<InterviewSession>())
        }
    }

    @Test
    fun `generateAndSaveReport persists evaluation report with all fields`() = runBlocking {
        val savedReport = slot<EvaluationReport>()
        coEvery { evaluationReportRepository.save(capture(savedReport)) } returns
            Mono.just(EvaluationReport(id = reportId, sessionId = sessionId, userId = userId))

        service.generateAndSaveReport(sessionId)

        assertNotNull(savedReport.captured)
        assertEquals(sessionId, savedReport.captured.sessionId)
        assertEquals(userId, savedReport.captured.userId)
        assertNotNull(savedReport.captured.overallScore)
        assertNotNull(savedReport.captured.narrativeSummary)
        assertNotNull(savedReport.captured.strengths)
    }

    @Test
    fun `generateAndSaveReport computes overallScore with correct 8-dimension weights`() = runBlocking {
        // 8-dimension weighted formula (weights sum to 1.00):
        // ps=8.0*0.20=1.60, algo=7.0*0.15=1.05, cq=6.0*0.15=0.90,
        // comm=9.0*0.15=1.35, eff=5.0*0.10=0.50, test=4.0*0.10=0.40,
        // init=7.0*0.10=0.70, la=6.0*0.05=0.30
        // total = 6.80
        val savedReport = slot<EvaluationReport>()
        coEvery { evaluationReportRepository.save(capture(savedReport)) } returns
            Mono.just(EvaluationReport(id = reportId, sessionId = sessionId, userId = userId))

        service.generateAndSaveReport(sessionId)

        val score = savedReport.captured.overallScore?.toDouble() ?: 0.0
        assertEquals(6.80, score, 0.01)
    }

    @Test
    fun `generateAndSaveReport updates session status to COMPLETED`() = runBlocking {
        val savedSession = slot<InterviewSession>()
        coEvery { interviewSessionRepository.save(capture(savedSession)) } returns
            Mono.just(session.copy(status = "COMPLETED"))

        service.generateAndSaveReport(sessionId)

        assertEquals("COMPLETED", savedSession.captured.status)
        assertNotNull(savedSession.captured.endedAt)
    }

    @Test
    fun `generateAndSaveReport increments usage counter`() = runBlocking {
        service.generateAndSaveReport(sessionId)

        coVerify { usageLimitService.incrementUsage(userId) }
    }

    @Test
    fun `generateAndSaveReport sends SESSION_END WS message with reportId`() = runBlocking {
        service.generateAndSaveReport(sessionId)

        coVerify {
            registry.sendMessage(sessionId, match {
                it is OutboundMessage.SessionEnd && it.reportId == reportId
            })
        }
    }

    @Test
    fun `generateAndSaveReport is idempotent when report already exists`() = runBlocking {
        val existingReport = EvaluationReport(id = reportId, sessionId = sessionId, userId = userId)
        coEvery { evaluationReportRepository.findBySessionId(sessionId) } returns Mono.just(existingReport)

        val result = service.generateAndSaveReport(sessionId)

        assertEquals(reportId, result)
        coVerify(exactly = 0) { evaluationAgent.evaluate(any()) }
        coVerify(exactly = 0) { evaluationReportRepository.save(any()) }
    }

    @Test
    fun `generateAndSaveReport returns reportId`() = runBlocking {
        val result = service.generateAndSaveReport(sessionId)
        assertEquals(reportId, result)
    }
}
