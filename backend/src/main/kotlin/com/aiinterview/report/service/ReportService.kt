package com.aiinterview.report.service

import com.aiinterview.conversation.brain.BrainService
import com.aiinterview.interview.model.InterviewSession
import com.aiinterview.interview.repository.InterviewSessionRepository
import com.aiinterview.interview.repository.SessionQuestionRepository
import com.aiinterview.interview.service.InterviewConfig
import com.aiinterview.interview.ws.OutboundMessage
import com.aiinterview.interview.ws.WsSessionRegistry
import com.aiinterview.report.dto.NextStepDto
import com.aiinterview.report.dto.ReportDto
import com.aiinterview.report.dto.ReportSummaryDto
import com.aiinterview.report.dto.ScoresDto
import com.aiinterview.report.dto.UserStatsDto
import com.aiinterview.report.model.EvaluationReport
import com.aiinterview.report.repository.EvaluationReportRepository
import com.aiinterview.interview.repository.QuestionRepository
import com.aiinterview.user.service.UsageLimitService
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

@Service
class ReportService(
    private val evaluationAgent: EvaluationAgent,
    private val brainService: BrainService,
    private val registry: WsSessionRegistry,
    private val evaluationReportRepository: EvaluationReportRepository,
    private val interviewSessionRepository: InterviewSessionRepository,
    private val sessionQuestionRepository: SessionQuestionRepository,
    private val questionRepository: QuestionRepository,
    private val usageLimitService: UsageLimitService,
    private val objectMapper: ObjectMapper,
    @Value("\${interview.free-tier-limit:3}") private val freeTierLimit: Int,
) {
    private val log = LoggerFactory.getLogger(ReportService::class.java)

    // ── Weight constants for overall score (8 dimensions, sum = 1.0) ────────
    companion object {
        private const val W_PROBLEM_SOLVING  = 0.20
        private const val W_ALGORITHM_CHOICE = 0.15
        private const val W_CODE_QUALITY     = 0.15
        private const val W_COMMUNICATION    = 0.15
        private const val W_EFFICIENCY       = 0.10
        private const val W_TESTING          = 0.10
        private const val W_INITIATIVE       = 0.10
        private const val W_LEARNING_AGILITY = 0.05
    }

    /**
     * Runs the full evaluation pipeline for a completed interview session.
     *
     * Flow:
     *  1. Load memory → call EvaluationAgent → compute overallScore
     *  2. Persist [EvaluationReport] to DB
     *  3. Update [InterviewSession] status = COMPLETED + duration_secs
     *  4. Increment Redis usage counter
     *  5. Send SESSION_END WS frame
     *  6. Delete Redis memory (cleanup)
     *
     * Returns the new reportId. Idempotent: if a report already exists, returns the existing id.
     */
    suspend fun generateAndSaveReport(sessionId: UUID): UUID {
        // Idempotency check
        val existing = withContext(Dispatchers.IO) {
            evaluationReportRepository.findBySessionId(sessionId).awaitSingleOrNull()
        }
        if (existing?.id != null) {
            log.info("Report already exists for session {} — skipping generation", sessionId)
            return existing.id
        }

        // 1. Load brain (single source of truth)
        val brain = brainService.getBrainOrNull(sessionId)
            ?: run {
                log.error("Cannot generate report — brain missing for session {}", sessionId)
                throw RuntimeException("Brain not found for session $sessionId")
            }

        // 2. Load session from DB
        val session = withContext(Dispatchers.IO) {
            interviewSessionRepository.findById(sessionId).awaitSingleOrNull()
        } ?: run {
            log.error("Cannot generate report — session {} not found in DB", sessionId)
            throw NoSuchElementException("Session $sessionId not found")
        }

        // 3. Call EvaluationAgent (brain is the ONLY state source)
        val evalResult = evaluationAgent.evaluate(brain)

        // 4. Compute weighted overall score from LLM-generated scores (8 dimensions)
        val s = evalResult.scores
        val overallScore = (
            s.problemSolving  * W_PROBLEM_SOLVING  +
            s.algorithmChoice * W_ALGORITHM_CHOICE +
            s.codeQuality     * W_CODE_QUALITY     +
            s.communication   * W_COMMUNICATION   +
            s.efficiency      * W_EFFICIENCY       +
            s.testing         * W_TESTING          +
            s.initiative      * W_INITIATIVE       +
            s.learningAgility * W_LEARNING_AGILITY
        ).coerceIn(0.0, 10.0)

        log.info("Score formula session={}: ps={} algo={} code={} comm={} eff={} test={} init={} la={} → overall={}",
            sessionId, s.problemSolving, s.algorithmChoice, s.codeQuality, s.communication,
            s.efficiency, s.testing, s.initiative, s.learningAgility, overallScore)

        // 5. Serialise JSONB fields
        val strengthsJson         = objectMapper.writeValueAsString(evalResult.strengths)
        val weaknessesJson        = objectMapper.writeValueAsString(evalResult.weaknesses)
        val suggestionsJson       = objectMapper.writeValueAsString(evalResult.suggestions)
        val nextStepsJson         = objectMapper.writeValueAsString(evalResult.nextSteps)
        val dimensionFeedbackJson = objectMapper.writeValueAsString(evalResult.dimensionFeedback)

        val now = OffsetDateTime.now()
        val durationSecs = session.startedAt?.let { Duration.between(it, now).toSeconds().toInt() }

        // 6. Persist report
        val report = withContext(Dispatchers.IO) {
            evaluationReportRepository.save(
                EvaluationReport(
                    sessionId            = sessionId,
                    userId               = brain.userId,
                    overallScore         = BigDecimal(overallScore).setScale(2, RoundingMode.HALF_UP),
                    problemSolvingScore  = BigDecimal(s.problemSolving).setScale(2, RoundingMode.HALF_UP),
                    algorithmScore       = BigDecimal(s.algorithmChoice).setScale(2, RoundingMode.HALF_UP),
                    codeQualityScore     = BigDecimal(s.codeQuality).setScale(2, RoundingMode.HALF_UP),
                    communicationScore   = BigDecimal(s.communication).setScale(2, RoundingMode.HALF_UP),
                    efficiencyScore      = BigDecimal(s.efficiency).setScale(2, RoundingMode.HALF_UP),
                    testingScore         = BigDecimal(s.testing).setScale(2, RoundingMode.HALF_UP),
                    initiativeScore      = BigDecimal(s.initiative).setScale(2, RoundingMode.HALF_UP),
                    learningAgilityScore = BigDecimal(s.learningAgility).setScale(2, RoundingMode.HALF_UP),
                    strengths            = strengthsJson,
                    weaknesses           = weaknessesJson,
                    suggestions          = suggestionsJson,
                    narrativeSummary     = evalResult.narrativeSummary,
                    dimensionFeedback    = dimensionFeedbackJson,
                    hintsUsed            = brain.hintsGiven,
                    nextSteps            = nextStepsJson,
                    completedAt          = now,
                ),
            ).awaitSingle()
        }
        val reportId = requireNotNull(report.id) { "Saved report has null id" }

        // 7. Update session: COMPLETED + duration
        withContext(Dispatchers.IO) {
            interviewSessionRepository.save(
                session.copy(
                    status       = "COMPLETED",
                    endedAt      = now,
                    durationSecs = durationSecs,
                ),
            ).awaitSingle()
        }

        // 8. Increment usage counter
        try {
            usageLimitService.incrementUsage(brain.userId)
        } catch (e: Exception) {
            log.warn("Failed to increment usage for user {}: {}", brain.userId, e.message)
        }

        // 9. Send SESSION_END over WebSocket
        registry.sendMessage(sessionId, OutboundMessage.SessionEnd(reportId = reportId))
        log.info("Report generated for session={} reportId={} overallScore={}", sessionId, reportId, overallScore)
        log.info("""{"event":"INTERVIEW_END","session_id":"$sessionId","turn_count":${brain.turnCount},"overall_score":${"%.2f".format(overallScore)},"completion_reason":"CANDIDATE_ENDED"}""")

        return reportId
    }

    // ── Read-side methods ──────────────────────────────────────────────────────

    suspend fun getReport(sessionId: UUID): ReportDto? {
        val report = withContext(Dispatchers.IO) {
            evaluationReportRepository.findBySessionId(sessionId).awaitSingleOrNull()
        } ?: return null

        val session = withContext(Dispatchers.IO) {
            interviewSessionRepository.findById(sessionId).awaitSingleOrNull()
        }

        // Best-effort question title lookup
        val questionTitle = try {
            val sq = withContext(Dispatchers.IO) {
                sessionQuestionRepository.findBySessionIdOrderByOrderIndex(sessionId)
                    .next().awaitSingleOrNull()
            }
            sq?.let { withContext(Dispatchers.IO) { questionRepository.findById(it.questionId).awaitSingleOrNull() } }
                ?.title ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }

        val config = session?.config?.let { parseConfig(it) }

        return assembleDto(report, sessionId, questionTitle, config, session)
    }

    suspend fun listReports(userId: UUID, page: Int, size: Int): List<ReportSummaryDto> {
        val reports = withContext(Dispatchers.IO) {
            evaluationReportRepository.findByUserIdOrderByCompletedAtDesc(userId)
                .skip((page * size).toLong())
                .take(size.toLong())
                .collectList()
                .awaitSingle()
        }

        // Batch-load all sessions to avoid N+1 queries
        val sessionIds = reports.map { it.sessionId }
        val sessionMap = if (sessionIds.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                interviewSessionRepository.findAllById(sessionIds)
                    .collectList().awaitSingle()
            }.associateBy { it.id }
        } else emptyMap()

        return reports.mapNotNull { report ->
            val reportId = report.id ?: return@mapNotNull null
            val config = sessionMap[report.sessionId]?.config?.let { parseConfig(it) }
            ReportSummaryDto(
                reportId     = reportId,
                sessionId    = report.sessionId,
                overallScore = report.overallScore?.toDouble() ?: 0.0,
                category     = config?.category?.name ?: "CODING",
                difficulty   = config?.difficulty?.name ?: "MEDIUM",
                completedAt  = report.completedAt?.toInstant() ?: report.createdAt?.toInstant()
                    ?: java.time.Instant.now(),
            )
        }
    }

    suspend fun getUserStats(userId: UUID): UserStatsDto {
        val totalInterviews = withContext(Dispatchers.IO) {
            interviewSessionRepository.countByUserId(userId).awaitSingle()
        }.toInt()

        val completedInterviews = withContext(Dispatchers.IO) {
            interviewSessionRepository.countByUserIdAndStatus(userId, "COMPLETED").awaitSingle()
        }.toInt()

        val reports = withContext(Dispatchers.IO) {
            evaluationReportRepository.findByUserId(userId).collectList().awaitSingle()
        }

        val scores = reports.mapNotNull { it.overallScore?.toDouble() }
        val averageScore = if (scores.isEmpty()) 0.0 else scores.average()
        val bestScore    = scores.maxOrNull() ?: 0.0

        val interviewsThisMonth = usageLimitService.getUsageThisMonth(userId)
        val freeInterviewsRemaining = maxOf(0, freeTierLimit - interviewsThisMonth)

        // Score by category and difficulty — requires parsing session configs
        val sessions = withContext(Dispatchers.IO) {
            interviewSessionRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .collectList().awaitSingle()
        }
        val sessionMap = sessions.associateBy { it.id }

        data class SessionScore(val category: String, val difficulty: String, val score: Double)

        val sessionScores = reports.mapNotNull { report ->
            val session = sessionMap[report.sessionId] ?: return@mapNotNull null
            val config  = parseConfig(session.config) ?: return@mapNotNull null
            val score   = report.overallScore?.toDouble() ?: return@mapNotNull null
            SessionScore(config.category.name, config.difficulty.name, score)
        }

        val scoreByCategory   = sessionScores.groupBy { it.category }
            .mapValues { (_, list) -> list.map { it.score }.average() }
        val scoreByDifficulty = sessionScores.groupBy { it.difficulty }
            .mapValues { (_, list) -> list.map { it.score }.average() }

        return UserStatsDto(
            totalInterviews          = totalInterviews,
            completedInterviews      = completedInterviews,
            averageScore             = averageScore,
            bestScore                = bestScore,
            interviewsThisMonth      = interviewsThisMonth,
            freeInterviewsRemaining  = freeInterviewsRemaining,
            scoreByCategory          = scoreByCategory,
            scoreByDifficulty        = scoreByDifficulty,
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun assembleDto(
        report: EvaluationReport,
        sessionId: UUID,
        questionTitle: String,
        config: InterviewConfig?,
        session: InterviewSession?,
    ): ReportDto {
        val reportId = requireNotNull(report.id)
        val strengthsList  = parseJsonList(report.strengths)
        val weaknessesList = parseJsonList(report.weaknesses)
        val suggestionList = parseJsonList(report.suggestions)
        val nextStepsList  = parseNextSteps(report.nextSteps)
        val dimFeedback    = parseJsonMap(report.dimensionFeedback)

        val durationSeconds = session?.durationSecs?.toLong()
            ?: session?.startedAt?.let { started ->
                report.completedAt?.let { completed ->
                    Duration.between(started, completed).toSeconds()
                }
            }

        return ReportDto(
            reportId            = reportId,
            sessionId           = sessionId,
            overallScore        = report.overallScore?.toDouble() ?: 0.0,
            scores              = ScoresDto(
                problemSolving  = report.problemSolvingScore?.toDouble() ?: 0.0,
                algorithmChoice = report.algorithmScore?.toDouble() ?: 0.0,
                codeQuality     = report.codeQualityScore?.toDouble() ?: 0.0,
                communication   = report.communicationScore?.toDouble() ?: 0.0,
                efficiency      = report.efficiencyScore?.toDouble() ?: 0.0,
                testing         = report.testingScore?.toDouble() ?: 0.0,
                initiative      = report.initiativeScore?.toDouble() ?: 0.0,
                learningAgility = report.learningAgilityScore?.toDouble() ?: 0.0,
                overall         = report.overallScore?.toDouble() ?: 0.0,
            ),
            strengths           = strengthsList,
            weaknesses          = weaknessesList,
            suggestions         = suggestionList,
            nextSteps           = nextStepsList,
            narrativeSummary    = report.narrativeSummary ?: "",
            dimensionFeedback   = dimFeedback,
            hintsUsed           = report.hintsUsed,
            category            = config?.category?.name ?: "CODING",
            difficulty          = config?.difficulty?.name ?: "MEDIUM",
            questionTitle       = questionTitle,
            programmingLanguage = config?.programmingLanguage,
            durationSeconds     = durationSeconds,
            completedAt         = report.completedAt?.toInstant() ?: report.createdAt?.toInstant()
                ?: java.time.Instant.now(),
        )
    }

    private fun parseConfig(configJson: String): InterviewConfig? = try {
        objectMapper.readValue(configJson, InterviewConfig::class.java)
    } catch (e: Exception) {
        null
    }

    private fun parseJsonList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            objectMapper.readValue(json, object : TypeReference<List<String>>() {})
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseJsonMap(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            objectMapper.readValue(json, object : TypeReference<Map<String, String>>() {})
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun parseNextSteps(json: String?): List<NextStepDto> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            objectMapper.readValue(json, object : TypeReference<List<NextStep>>() {}).map {
                NextStepDto(
                    area = it.area,
                    specificGap = it.specificGap,
                    evidenceFromInterview = it.evidenceFromInterview,
                    actionItem = it.actionItem,
                    resource = it.resource,
                    priority = it.priority,
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
