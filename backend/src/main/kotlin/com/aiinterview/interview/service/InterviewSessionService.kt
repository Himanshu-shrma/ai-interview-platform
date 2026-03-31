package com.aiinterview.interview.service

import com.aiinterview.interview.dto.PagedResponse
import com.aiinterview.interview.dto.SessionDetailDto
import com.aiinterview.interview.dto.SessionSummaryDto
import com.aiinterview.interview.dto.StartSessionResponse
import com.aiinterview.interview.dto.toCandidateDto
import com.aiinterview.interview.dto.toInternalDto
import com.aiinterview.interview.model.InterviewSession
import com.aiinterview.interview.model.SessionQuestion
import com.aiinterview.interview.repository.InterviewSessionRepository
import com.aiinterview.interview.repository.SessionQuestionRepository
import com.aiinterview.report.repository.EvaluationReportRepository
import com.aiinterview.shared.domain.InterviewCategory
import com.aiinterview.user.model.User
import com.aiinterview.user.service.UsageLimitService
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

class SessionAccessDeniedException(message: String) : RuntimeException(message)
class UsageLimitExceededException(message: String) : RuntimeException(message)

@Service
class InterviewSessionService(
    private val interviewSessionRepository: InterviewSessionRepository,
    private val sessionQuestionRepository: SessionQuestionRepository,
    private val questionService: QuestionService,
    private val evaluationReportRepository: EvaluationReportRepository,
    private val usageLimitService: UsageLimitService,
    private val redisMemoryService: RedisMemoryService,
    private val objectMapper: ObjectMapper,
    @Value("\${app.websocket.base-url:ws://localhost:8080}") private val wsBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger(InterviewSessionService::class.java)

    suspend fun startSession(user: User, config: InterviewConfig): StartSessionResponse {
        val userId = requireNotNull(user.id) { "User has null id" }

        val allowed = usageLimitService.checkUsageAllowed(userId, user.subscriptionTier)
        if (!allowed) throw UsageLimitExceededException("Monthly interview limit reached for plan ${user.subscriptionTier}")

        val configJson = objectMapper.writeValueAsString(config)
        val session = interviewSessionRepository.save(
            InterviewSession(
                userId    = userId,
                status    = "ACTIVE",
                type      = config.dbType(),
                config    = configJson,
                startedAt = OffsetDateTime.now(),
            )
        ).awaitSingle()

        val sessionId = requireNotNull(session.id) { "Session save returned null id" }

        val questionCount = questionsPerCategory(config.category)
        val questions = questionService.selectQuestionsForSession(config, count = questionCount)
        questions.forEachIndexed { index, question ->
            val questionId = requireNotNull(question.id) { "Question has null id" }
            sessionQuestionRepository.save(
                SessionQuestion(
                    sessionId  = sessionId,
                    questionId = questionId,
                    orderIndex = index,
                )
            ).awaitSingle()
        }

        // Initialize Redis memory so the WS handler can start the interview
        val firstQuestion = questions.first().toInternalDto(objectMapper)
        redisMemoryService.initMemory(sessionId, userId, config, firstQuestion, totalQuestions = questions.size)

        log.info("Started session {} for user {} (category={}, difficulty={})",
            sessionId, userId, config.category, config.difficulty)

        return StartSessionResponse(
            sessionId = sessionId,
            wsUrl     = "$wsBaseUrl/ws/interview/$sessionId",
        )
    }

    suspend fun endSession(sessionId: UUID, userId: UUID): InterviewSession {
        val session = interviewSessionRepository.findById(sessionId).awaitSingleOrNull()
            ?: throw NoSuchElementException("Session not found: $sessionId")

        if (session.userId != userId) throw SessionAccessDeniedException("Not your session")

        if (session.status == "COMPLETED") return session

        val now = OffsetDateTime.now()
        val durationSecs = session.startedAt?.let {
            Duration.between(it, now).toSeconds().toInt()
        }

        return interviewSessionRepository.save(
            session.copy(
                status       = "COMPLETED",
                endedAt      = now,
                durationSecs = durationSecs,
            )
        ).awaitSingle()
    }

    suspend fun getSession(sessionId: UUID, userId: UUID): SessionDetailDto {
        val session = interviewSessionRepository.findById(sessionId).awaitSingleOrNull()
            ?: throw NoSuchElementException("Session not found: $sessionId")

        if (session.userId != userId) throw SessionAccessDeniedException("Not your session")

        val config = runCatching {
            objectMapper.readValue(session.config, InterviewConfig::class.java)
        }.getOrElse { InterviewConfig(category = com.aiinterview.shared.domain.InterviewCategory.CODING) }

        val sessionQuestions = sessionQuestionRepository
            .findBySessionIdOrderByOrderIndex(sessionId)
            .collectList()
            .awaitSingle()

        val questions = sessionQuestions.map { sq ->
            questionService.getQuestionById(sq.questionId).toCandidateDto(objectMapper)
                .copy(sessionQuestionId = sq.id)
        }

        val report = evaluationReportRepository.findBySessionId(sessionId).awaitSingleOrNull()

        return SessionDetailDto(
            id                  = sessionId,
            status              = session.status,
            type                = session.type,
            category            = config.category.name,
            difficulty          = config.difficulty.name,
            personality         = config.personality,
            programmingLanguage = config.programmingLanguage,
            targetRole          = config.targetRole,
            targetCompany       = config.targetCompany,
            durationMinutes     = config.durationMinutes,
            createdAt           = session.createdAt,
            startedAt           = session.startedAt,
            endedAt             = session.endedAt,
            durationSecs        = session.durationSecs,
            questions           = questions,
            overallScore        = report?.overallScore,
        )
    }

    suspend fun listSessions(userId: UUID, page: Int, size: Int): PagedResponse<SessionSummaryDto> {
        val total = interviewSessionRepository.countByUserId(userId).awaitSingle()

        val sessions = interviewSessionRepository
            .findByUserIdOrderByCreatedAtDesc(userId)
            .skip((page * size).toLong())
            .take(size.toLong())
            .collectList()
            .awaitSingle()

        // Batch-load all user reports to avoid N+1 queries (1 query instead of N)
        val reportMap = evaluationReportRepository.findByUserId(userId)
            .collectList().awaitSingle()
            .associateBy { it.sessionId }

        val summaries = sessions.map { session ->
            val config = runCatching {
                objectMapper.readValue(session.config, InterviewConfig::class.java)
            }.getOrElse { InterviewConfig(category = com.aiinterview.shared.domain.InterviewCategory.CODING) }

            SessionSummaryDto(
                id           = requireNotNull(session.id),
                status       = session.status,
                type         = session.type,
                category     = config.category.name,
                difficulty   = config.difficulty.name,
                createdAt    = session.createdAt,
                endedAt      = session.endedAt,
                durationSecs = session.durationSecs,
                overallScore = reportMap[session.id]?.overallScore,
            )
        }

        return PagedResponse(
            content = summaries,
            page    = page,
            size    = size,
            total   = total,
        )
    }

    private fun questionsPerCategory(category: InterviewCategory): Int = when (category) {
        InterviewCategory.CODING        -> 2
        InterviewCategory.DSA           -> 2
        InterviewCategory.BEHAVIORAL    -> 3
        InterviewCategory.SYSTEM_DESIGN -> 1
        InterviewCategory.CASE_STUDY    -> 1
    }
}
