package com.aiinterview.report.dto

import java.time.Instant
import java.util.UUID

data class ScoresDto(
    val problemSolving: Double,
    val algorithmChoice: Double,
    val codeQuality: Double,
    val communication: Double,
    val efficiency: Double,
    val testing: Double,
    val overall: Double,
)

data class ReportDto(
    val reportId: UUID,
    val sessionId: UUID,
    val overallScore: Double,
    val scores: ScoresDto,
    val strengths: List<String>,
    val weaknesses: List<String>,
    val suggestions: List<String>,
    val narrativeSummary: String,
    val dimensionFeedback: Map<String, String>,
    val hintsUsed: Int,
    val category: String,
    val difficulty: String,
    val questionTitle: String,
    val programmingLanguage: String?,
    val durationSeconds: Long?,
    val completedAt: Instant,
)

/** Lightweight summary used in list endpoints (no dimensionFeedback). */
data class ReportSummaryDto(
    val reportId: UUID,
    val sessionId: UUID,
    val overallScore: Double,
    val category: String,
    val difficulty: String,
    val completedAt: Instant,
)

data class UserStatsDto(
    val totalInterviews: Int,
    val completedInterviews: Int,
    val averageScore: Double,
    val bestScore: Double,
    val interviewsThisMonth: Int,
    val freeInterviewsRemaining: Int,
    val scoreByCategory: Map<String, Double>,
    val scoreByDifficulty: Map<String, Double>,
)
