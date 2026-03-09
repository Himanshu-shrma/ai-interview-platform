package com.aiinterview.interview

import com.aiinterview.interview.model.InterviewSession
import com.aiinterview.interview.model.Question
import com.aiinterview.interview.model.SessionQuestion
import com.aiinterview.interview.repository.InterviewSessionRepository
import com.aiinterview.interview.repository.SessionQuestionRepository
import com.aiinterview.interview.service.InterviewConfig
import com.aiinterview.interview.service.InterviewSessionService
import com.aiinterview.interview.service.QuestionService
import com.aiinterview.interview.service.SessionAccessDeniedException
import com.aiinterview.interview.service.UsageLimitExceededException
import com.aiinterview.report.model.EvaluationReport
import com.aiinterview.report.repository.EvaluationReportRepository
import com.aiinterview.shared.domain.Difficulty
import com.aiinterview.shared.domain.InterviewCategory
import com.aiinterview.user.model.User
import com.aiinterview.interview.service.RedisMemoryService
import com.aiinterview.user.service.UsageLimitService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

class InterviewSessionServiceTest {

    private val sessionRepository     = mockk<InterviewSessionRepository>()
    private val sessionQuestionRepo   = mockk<SessionQuestionRepository>()
    private val questionService       = mockk<QuestionService>()
    private val evaluationReportRepo  = mockk<EvaluationReportRepository>()
    private val usageLimitService     = mockk<UsageLimitService>()
    private val redisMemoryService   = mockk<RedisMemoryService>(relaxed = true)
    private val objectMapper          = jacksonObjectMapper()

    private val service = InterviewSessionService(
        interviewSessionRepository = sessionRepository,
        sessionQuestionRepository  = sessionQuestionRepo,
        questionService            = questionService,
        evaluationReportRepository = evaluationReportRepo,
        usageLimitService          = usageLimitService,
        redisMemoryService         = redisMemoryService,
        objectMapper               = objectMapper,
        wsBaseUrl                  = "ws://localhost:8080",
    )

    private val userId    = UUID.randomUUID()
    private val sessionId = UUID.randomUUID()
    private val questionId = UUID.randomUUID()

    private val freeUser = User(
        id            = userId,
        orgId         = UUID.randomUUID(),
        clerkUserId   = "user_test",
        email         = "test@example.com",
        subscriptionTier = "FREE",
    )

    private val defaultConfig = InterviewConfig(
        category   = InterviewCategory.CODING,
        difficulty = Difficulty.MEDIUM,
    )

    private fun makeSession(
        id: UUID = sessionId,
        status: String = "ACTIVE",
        startedAt: OffsetDateTime? = OffsetDateTime.now().minusMinutes(10),
        config: String = objectMapper.writeValueAsString(defaultConfig),
    ) = InterviewSession(
        id        = id,
        userId    = userId,
        status    = status,
        type      = "CODING",
        config    = config,
        startedAt = startedAt,
    )

    private fun makeQuestion(id: UUID = questionId) = Question(
        id          = id,
        title       = "Two Sum",
        description = "Given an array...",
        type        = "CODING",
        difficulty  = "MEDIUM",
        category    = "CODING",
        slug        = "two-sum",
    )

    // ── startSession ──────────────────────────────────────────────────────────

    @Test
    fun `startSession creates session and session question for free user`() = runTest {
        val savedSession = makeSession()
        val question = makeQuestion()

        coEvery { usageLimitService.checkAndIncrementUsage(userId, "FREE") } returns true
        coEvery { sessionRepository.save(any()) } returns Mono.just(savedSession)
        coEvery { questionService.selectQuestionsForSession(any(), any()) } returns listOf(question, question)
        coEvery { sessionQuestionRepo.save(any()) } returns Mono.just(
            SessionQuestion(sessionId = sessionId, questionId = questionId)
        )

        val response = service.startSession(freeUser, defaultConfig)

        assertEquals(sessionId, response.sessionId)
        assert(response.wsUrl.contains(sessionId.toString()))
        coVerify(exactly = 1) { sessionRepository.save(any()) }
        coVerify(exactly = 2) { sessionQuestionRepo.save(any()) }
    }

    @Test
    fun `startSession throws UsageLimitExceededException when limit reached`() = runTest {
        coEvery { usageLimitService.checkAndIncrementUsage(userId, "FREE") } returns false

        assertThrows<UsageLimitExceededException> {
            service.startSession(freeUser, defaultConfig)
        }

        coVerify(exactly = 0) { sessionRepository.save(any()) }
    }

    // ── endSession ────────────────────────────────────────────────────────────

    @Test
    fun `endSession transitions ACTIVE session to COMPLETED`() = runTest {
        val activeSession = makeSession(status = "ACTIVE")
        val completedSession = activeSession.copy(status = "COMPLETED", endedAt = OffsetDateTime.now())

        coEvery { sessionRepository.findById(sessionId) } returns Mono.just(activeSession)
        coEvery { sessionRepository.save(any()) } returns Mono.just(completedSession)

        val result = service.endSession(sessionId, userId)

        assertEquals("COMPLETED", result.status)
        coVerify(exactly = 1) { sessionRepository.save(any()) }
    }

    @Test
    fun `endSession is idempotent when session already COMPLETED`() = runTest {
        val completedSession = makeSession(status = "COMPLETED")

        coEvery { sessionRepository.findById(sessionId) } returns Mono.just(completedSession)

        val result = service.endSession(sessionId, userId)

        assertEquals("COMPLETED", result.status)
        coVerify(exactly = 0) { sessionRepository.save(any()) }
    }

    @Test
    fun `endSession throws SessionAccessDeniedException for wrong user`() = runTest {
        val otherUserId = UUID.randomUUID()
        val session = makeSession()

        coEvery { sessionRepository.findById(sessionId) } returns Mono.just(session)

        assertThrows<SessionAccessDeniedException> {
            service.endSession(sessionId, otherUserId)
        }
    }

    // ── getSession ────────────────────────────────────────────────────────────

    @Test
    fun `getSession returns SessionDetailDto with questions and score`() = runTest {
        val session = makeSession()
        val sq = SessionQuestion(sessionId = sessionId, questionId = questionId, orderIndex = 0)
        val question = makeQuestion()
        val report = EvaluationReport(
            sessionId    = sessionId,
            userId       = userId,
            overallScore = BigDecimal("85.0"),
        )

        coEvery { sessionRepository.findById(sessionId) } returns Mono.just(session)
        coEvery { sessionQuestionRepo.findBySessionIdOrderByOrderIndex(sessionId) } returns Flux.just(sq)
        coEvery { questionService.getQuestionById(questionId) } returns question
        coEvery { evaluationReportRepo.findBySessionId(sessionId) } returns Mono.just(report)

        val dto = service.getSession(sessionId, userId)

        assertEquals(sessionId, dto.id)
        assertEquals(1, dto.questions.size)
        assertEquals(BigDecimal("85.0"), dto.overallScore)
        assertEquals("CODING", dto.category)
        assertEquals("MEDIUM", dto.difficulty)
    }

    @Test
    fun `getSession throws SessionAccessDeniedException for wrong user`() = runTest {
        val otherUserId = UUID.randomUUID()
        val session = makeSession()

        coEvery { sessionRepository.findById(sessionId) } returns Mono.just(session)

        assertThrows<SessionAccessDeniedException> {
            service.getSession(sessionId, otherUserId)
        }
    }

    // ── listSessions ──────────────────────────────────────────────────────────

    @Test
    fun `listSessions returns paged results newest first`() = runTest {
        val session1 = makeSession(id = UUID.randomUUID())
        val session2 = makeSession(id = UUID.randomUUID())

        coEvery { sessionRepository.countByUserId(userId) } returns Mono.just(2L)
        coEvery { sessionRepository.findByUserIdOrderByCreatedAtDesc(userId) } returns Flux.just(session1, session2)
        coEvery { evaluationReportRepo.findBySessionId(any()) } returns Mono.empty()

        val result = service.listSessions(userId, page = 0, size = 20)

        assertEquals(2, result.content.size)
        assertEquals(2L, result.total)
        assertEquals(0, result.page)
        assertEquals(20, result.size)
        assertNotNull(result.content[0].id)
    }
}
