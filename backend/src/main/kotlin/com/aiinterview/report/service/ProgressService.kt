package com.aiinterview.report.service

import com.aiinterview.interview.repository.InterviewSessionRepository
import com.aiinterview.interview.service.InterviewConfig
import com.aiinterview.report.dto.DimensionDelta
import com.aiinterview.report.dto.ProgressResponse
import com.aiinterview.report.dto.SessionSummary
import com.aiinterview.report.model.EvaluationReport
import com.aiinterview.report.repository.EvaluationReportRepository
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class ProgressService(
    private val evaluationReportRepository: EvaluationReportRepository,
    private val interviewSessionRepository: InterviewSessionRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(ProgressService::class.java)

    // Ordered dimension keys — consistent with EvaluationReport fields
    private val DIMENSIONS = listOf(
        "problemSolving",
        "algorithmChoice",
        "codeQuality",
        "communication",
        "efficiency",
        "testing",
        "initiative",
        "learningAgility",
    )

    suspend fun getProgress(userId: UUID): ProgressResponse {
        // Load all completed reports in chronological order (oldest first)
        val allReports = withContext(Dispatchers.IO) {
            evaluationReportRepository.findByUserIdOrderByCompletedAtDesc(userId)
                .collectList().awaitSingle()
        }.reversed() // chronological (oldest → newest)

        val sessionCount = allReports.size

        // Last 10 for session summaries and dimension trends
        val recentReports = allReports.takeLast(10)

        // Batch-load sessions for category/difficulty lookup
        val sessionIds = recentReports.map { it.sessionId }
        val sessionMap = withContext(Dispatchers.IO) {
            interviewSessionRepository.findAllById(sessionIds).collectList().awaitSingle()
        }.associateBy { it.id }

        // Session summaries (last 10)
        val sessions = recentReports.mapNotNull { r ->
            val session = sessionMap[r.sessionId]
            val config = session?.config?.let { parseConfig(it) }
            SessionSummary(
                sessionId = r.sessionId,
                completedAt = r.completedAt?.toInstant() ?: Instant.now(),
                overallScore = r.overallScore?.toDouble() ?: 0.0,
                category = config?.category?.name ?: "CODING",
                difficulty = config?.difficulty?.name ?: "MEDIUM",
            )
        }

        // Dimension trends — chronological scores across last 10 sessions
        val dimensionTrends = extractDimensionTrends(recentReports)

        // Last 5 sessions for rolling average and insight cards
        val last5 = recentReports.takeLast(5)
        val rollingAverage = computeRollingAverage(last5)

        // Insight cards — computed from last 5 sessions only
        val last5Trends = extractDimensionTrends(last5)
        val deltas = computeDeltas(last5Trends, last5.size)
        val mostImproved = deltas.maxByOrNull { it.delta }?.takeIf { it.delta > 0 }
        val needsAttention = computeNeedsAttention(deltas, rollingAverage)

        // Platform percentile — only when user has 10+ sessions
        val platformPercentile = if (sessionCount >= 10) computePercentile(userId) else null

        return ProgressResponse(
            sessions = sessions,
            dimensionTrends = dimensionTrends,
            rollingAverage = rollingAverage,
            mostImproved = mostImproved,
            needsAttention = needsAttention,
            sessionCount = sessionCount,
            platformPercentile = platformPercentile,
        )
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun extractDimensionTrends(reports: List<EvaluationReport>): Map<String, List<Double>> {
        val raw = mapOf(
            "problemSolving"  to reports.mapNotNull { it.problemSolvingScore?.toDouble() },
            "algorithmChoice" to reports.mapNotNull { it.algorithmScore?.toDouble() },
            "codeQuality"     to reports.mapNotNull { it.codeQualityScore?.toDouble() },
            "communication"   to reports.mapNotNull { it.communicationScore?.toDouble() },
            "efficiency"      to reports.mapNotNull { it.efficiencyScore?.toDouble() },
            "testing"         to reports.mapNotNull { it.testingScore?.toDouble() },
            "initiative"      to reports.mapNotNull { it.initiativeScore?.toDouble() },
            "learningAgility" to reports.mapNotNull { it.learningAgilityScore?.toDouble() },
        )
        return raw.filter { it.value.isNotEmpty() }
    }

    private fun computeRollingAverage(reports: List<EvaluationReport>): Map<String, Double> =
        extractDimensionTrends(reports).mapValues { (_, scores) ->
            if (scores.isEmpty()) 0.0 else scores.average()
        }

    /**
     * Delta = avg(second half) - avg(first half) of the score list.
     * Requires at least 2 data points.
     */
    private fun computeDeltas(
        trends: Map<String, List<Double>>,
        sessionCount: Int,
    ): List<DimensionDelta> =
        trends.mapNotNull { (dim, scores) ->
            if (scores.size < 2) return@mapNotNull null
            val mid = (scores.size + 1) / 2
            val firstHalf = scores.take(scores.size - mid)
            val secondHalf = scores.takeLast(mid)
            if (firstHalf.isEmpty() || secondHalf.isEmpty()) return@mapNotNull null
            val delta = secondHalf.average() - firstHalf.average()
            DimensionDelta(dimension = dim, delta = delta, sessionCount = sessionCount)
        }

    private fun computeNeedsAttention(
        deltas: List<DimensionDelta>,
        rollingAverage: Map<String, Double>,
    ): DimensionDelta? {
        if (deltas.isEmpty()) return null
        // Needs attention = lowest rolling average among stagnant or declining dimensions
        val stagnantOrDeclining = deltas.filter { it.delta <= 0.5 }
        val candidates = if (stagnantOrDeclining.isNotEmpty()) stagnantOrDeclining else deltas
        return candidates.minByOrNull { rollingAverage[it.dimension] ?: 10.0 }
    }

    private suspend fun computePercentile(userId: UUID): Int? {
        return try {
            val userAvg = withContext(Dispatchers.IO) {
                evaluationReportRepository.findAverageOverallScoreForUser(userId).awaitSingleOrNull()
            } ?: return null

            val totalUsers = withContext(Dispatchers.IO) {
                evaluationReportRepository.countDistinctUsers().awaitSingle()
            }
            if (totalUsers == 0L) return null

            val usersBelow = withContext(Dispatchers.IO) {
                evaluationReportRepository.countUsersWithAverageBelow(userAvg).awaitSingle()
            }
            ((usersBelow.toDouble() / totalUsers.toDouble()) * 100).toInt()
        } catch (e: Exception) {
            log.warn("Failed to compute platform percentile for userId={}: {}", userId, e.message)
            null
        }
    }

    private fun parseConfig(json: String): InterviewConfig? = try {
        objectMapper.readValue(json, InterviewConfig::class.java)
    } catch (_: Exception) { null }
}
