package com.aiinterview.memory.service

import com.aiinterview.conversation.brain.CandidateHistory
import com.aiinterview.interview.repository.SessionQuestionRepository
import com.aiinterview.memory.model.CandidateMemoryProfile
import com.aiinterview.memory.repository.CandidateMemoryRepository
import com.aiinterview.report.model.EvaluationReport
import com.aiinterview.report.repository.EvaluationReportRepository
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.UUID

@Service
class CandidateMemoryService(
    private val candidateMemoryRepository: CandidateMemoryRepository,
    private val evaluationReportRepository: EvaluationReportRepository,
    private val sessionQuestionRepository: SessionQuestionRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(CandidateMemoryService::class.java)

    /**
     * Aggregates the last 5 EvaluationReports into raw signals and upserts the memory profile.
     * Called from ReportService after a session completes.
     */
    suspend fun upsertFromReport(userId: UUID, sessionId: UUID) {
        try {
            // Load last 5 reports (most recent first)
            val reports = withContext(Dispatchers.IO) {
                evaluationReportRepository.findByUserIdOrderByCompletedAtDesc(userId)
                    .take(5).collectList().awaitSingle()
            }
            if (reports.isEmpty()) return

            // Compute avg_score_per_dimension — store last 5 values per dimension
            val avgScores = extractDimensionScores(reports)

            // Compute avg_anxiety_per_session — use anxietyLevel from report
            val anxietyList = reports.mapNotNull { it.anxietyLevel?.toDouble() }.reversed()

            // Load questions seen in the completed session
            val questionsThisSession = withContext(Dispatchers.IO) {
                sessionQuestionRepository.findBySessionIdOrderByOrderIndex(sessionId)
                    .map { it.questionId.toString() }.collectList().awaitSingle()
            }

            // Merge with existing questions_seen (keep last 20 unique)
            val existing = withContext(Dispatchers.IO) {
                candidateMemoryRepository.findById(userId.toString()).awaitSingleOrNull()
            }
            val existingQuestions = parseStringList(existing?.questionsSeen)
            val allQuestions = (existingQuestions + questionsThisSession).distinct().takeLast(20)

            // Compute dimension trends from score arrays
            val trends = computeTrends(avgScores)

            val profile = CandidateMemoryProfile(
                userId = userId.toString(),
                sessionCount = reports.size,
                avgScorePerDimension = objectMapper.writeValueAsString(avgScores),
                avgAnxietyPerSession = objectMapper.writeValueAsString(anxietyList),
                questionsSeen = objectMapper.writeValueAsString(allQuestions),
                dimensionTrend = objectMapper.writeValueAsString(trends),
                lastUpdated = LocalDateTime.now(),
            )
            withContext(Dispatchers.IO) {
                candidateMemoryRepository.save(profile).awaitSingle()
            }
            log.info("Memory profile upserted for userId={} sessionCount={}", userId, reports.size)
        } catch (e: Exception) {
            log.warn("Failed to upsert memory profile for userId={}: {}", userId, e.message)
        }
    }

    /** Loads the raw profile. Returns null if this is the candidate's first session. */
    suspend fun loadProfile(userId: UUID): CandidateMemoryProfile? =
        withContext(Dispatchers.IO) {
            candidateMemoryRepository.findById(userId.toString()).awaitSingleOrNull()
        }

    /**
     * Computes derived insights at read time. Never stores these derived labels in the DB.
     * Weaknesses = dimensions averaging below 6.5.
     * Top dimension = highest average.
     * Trend = IMPROVING / DECLINING / STABLE per dimension.
     */
    fun derivedInsights(profile: CandidateMemoryProfile): CandidateHistory {
        val avgScores = parseDimensionScores(profile.avgScorePerDimension)
        val anxieties = parseDoubleList(profile.avgAnxietyPerSession)
        val questions = parseStringList(profile.questionsSeen)
        val trends = parseStringMap(profile.dimensionTrend)

        val weaknesses = avgScores.entries
            .filter { (_, scores) -> scores.isNotEmpty() && scores.average() < 6.5 }
            .sortedBy { it.value.average() }
            .map { formatDimensionName(it.key) }

        val topDimension = avgScores.entries
            .filter { it.value.isNotEmpty() }
            .maxByOrNull { it.value.average() }
            ?.key?.let { formatDimensionName(it) } ?: ""

        val avgAnxiety = if (anxieties.isEmpty()) 0.3 else anxieties.average()

        return CandidateHistory(
            sessionCount = profile.sessionCount,
            weaknesses = weaknesses,
            topDimension = topDimension,
            avgAnxiety = avgAnxiety,
            trend = trends,
            questionsSeen = questions,
        )
    }

    /** Hard delete — called from UserDeletionService (TASK-P0-10) */
    suspend fun deleteProfile(userId: UUID) {
        withContext(Dispatchers.IO) {
            candidateMemoryRepository.deleteById(userId.toString()).awaitSingleOrNull()
        }
        log.info("Memory profile deleted for userId={}", userId)
    }

    /** Resets profile to empty state (keeps row, zeroes all signal arrays). */
    suspend fun resetProfile(userId: UUID) {
        val reset = CandidateMemoryProfile(
            userId = userId.toString(),
            sessionCount = 0,
            avgScorePerDimension = "{}",
            avgAnxietyPerSession = "[]",
            questionsSeen = "[]",
            dimensionTrend = "{}",
            lastUpdated = LocalDateTime.now(),
        )
        withContext(Dispatchers.IO) {
            candidateMemoryRepository.save(reset).awaitSingle()
        }
        log.info("Memory profile reset for userId={}", userId)
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun extractDimensionScores(reports: List<EvaluationReport>): Map<String, List<Double>> {
        val dims = mapOf(
            "problem_solving" to reports.mapNotNull { it.problemSolvingScore?.toDouble() },
            "algorithm_choice" to reports.mapNotNull { it.algorithmScore?.toDouble() },
            "code_quality" to reports.mapNotNull { it.codeQualityScore?.toDouble() },
            "communication" to reports.mapNotNull { it.communicationScore?.toDouble() },
            "efficiency" to reports.mapNotNull { it.efficiencyScore?.toDouble() },
            "testing" to reports.mapNotNull { it.testingScore?.toDouble() },
            "initiative" to reports.mapNotNull { it.initiativeScore?.toDouble() },
            "learning_agility" to reports.mapNotNull { it.learningAgilityScore?.toDouble() },
        )
        // Keep only dimensions with at least one score, reversed to chronological order
        return dims.filter { it.value.isNotEmpty() }.mapValues { it.value.reversed() }
    }

    private fun computeTrends(scores: Map<String, List<Double>>): Map<String, String> =
        scores.mapValues { (_, vals) ->
            when {
                vals.size < 2 -> "STABLE"
                vals.last() - vals.first() > 0.5 -> "IMPROVING"
                vals.first() - vals.last() > 0.5 -> "DECLINING"
                else -> "STABLE"
            }
        }

    private fun formatDimensionName(key: String): String =
        key.split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    private fun parseDimensionScores(json: String): Map<String, List<Double>> = try {
        objectMapper.readValue(json, object : TypeReference<Map<String, List<Double>>>() {})
    } catch (e: Exception) { emptyMap() }

    private fun parseDoubleList(json: String): List<Double> = try {
        objectMapper.readValue(json, object : TypeReference<List<Double>>() {})
    } catch (e: Exception) { emptyList() }

    private fun parseStringList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            objectMapper.readValue(json, object : TypeReference<List<String>>() {})
        } catch (e: Exception) { emptyList() }
    }

    private fun parseStringMap(json: String): Map<String, String> = try {
        objectMapper.readValue(json, object : TypeReference<Map<String, String>>() {})
    } catch (e: Exception) { emptyMap() }
}
