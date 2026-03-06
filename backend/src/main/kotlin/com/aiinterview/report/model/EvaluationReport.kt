package com.aiinterview.report.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

@Table("evaluation_reports")
data class EvaluationReport(
    @Id val id: UUID? = null,
    val sessionId: UUID,
    val userId: UUID,
    val overallScore: BigDecimal? = null,
    val problemSolvingScore: BigDecimal? = null,
    val algorithmScore: BigDecimal? = null,
    val codeQualityScore: BigDecimal? = null,
    val communicationScore: BigDecimal? = null,
    val efficiencyScore: BigDecimal? = null,
    val testingScore: BigDecimal? = null,
    /** JSONB — list of strength strings */
    val strengths: String? = null,
    /** JSONB — list of weakness strings */
    val weaknesses: String? = null,
    /** JSONB — list of suggestion strings */
    val suggestions: String? = null,
    val narrativeSummary: String? = null,
    /** JSONB — map of dimensionName → one-sentence feedback string */
    val dimensionFeedback: String? = null,
    val hintsUsed: Int = 0,
    val completedAt: OffsetDateTime? = null,
    val createdAt: OffsetDateTime? = null,
)
