package com.aiinterview.memory.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime

/**
 * Stores raw signal arrays for a candidate across all completed sessions.
 * Raw arrays only — derived labels are computed at read time in CandidateMemoryService.
 */
@Table("candidate_memory_profiles")
data class CandidateMemoryProfile(
    @Id val userId: String,   // UUID stored as VARCHAR(255)
    val sessionCount: Int = 0,
    /** JSON: {"problem_solving": [6.5, 7.0, 7.2], ...} — last 5 scores per dimension */
    val avgScorePerDimension: String = "{}",
    /** JSON: [0.7, 0.4, 0.5] — anxiety level per session, newest last */
    val avgAnxietyPerSession: String = "[]",
    /** JSON: ["uuid1", "uuid2"] — question IDs seen across sessions */
    val questionsSeen: String = "[]",
    /** JSON: {"problem_solving": "IMPROVING"} — trend per dimension */
    val dimensionTrend: String = "{}",
    val lastUpdated: LocalDateTime = LocalDateTime.now(),
)
