package com.aiinterview.report.dto

import java.time.Instant
import java.util.UUID

data class ProgressResponse(
    /** Last 10 completed sessions (chronological — oldest first). */
    val sessions: List<SessionSummary>,
    /** Dimension key → scores in chronological order across all available sessions. */
    val dimensionTrends: Map<String, List<Double>>,
    /** Dimension key → average score over the last 5 sessions. */
    val rollingAverage: Map<String, Double>,
    /** Dimension with the highest positive delta over the last 5 sessions. */
    val mostImproved: DimensionDelta?,
    /** Dimension with the lowest avg or stagnant trend over the last 5 sessions. */
    val needsAttention: DimensionDelta?,
    /** Total number of completed sessions for this user. */
    val sessionCount: Int,
    /** Null until the user has 10+ sessions. */
    val platformPercentile: Int?,
)

data class SessionSummary(
    val sessionId: UUID,
    val completedAt: Instant,
    val overallScore: Double,
    val category: String,
    val difficulty: String,
)

data class DimensionDelta(
    /** Camel-case dimension key matching dimensionTrends keys. */
    val dimension: String,
    /** Positive = improved, negative = declined, ~0 = stagnant. */
    val delta: Double,
    /** How many sessions the delta was computed across. */
    val sessionCount: Int,
)
