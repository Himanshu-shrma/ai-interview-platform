package com.aiinterview.interview.dto

import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

data class StartSessionResponse(
    val sessionId: UUID,
    val wsUrl: String,
)

data class SessionSummaryDto(
    val id: UUID,
    val status: String,
    val type: String,
    val category: String,
    val difficulty: String,
    val createdAt: OffsetDateTime?,
    val endedAt: OffsetDateTime?,
    val durationSecs: Int?,
    val overallScore: BigDecimal?,
)

data class SessionDetailDto(
    val id: UUID,
    val status: String,
    val type: String,
    val category: String,
    val difficulty: String,
    val personality: String,
    val programmingLanguage: String?,
    val targetRole: String?,
    val targetCompany: String?,
    val durationMinutes: Int,
    val createdAt: OffsetDateTime?,
    val startedAt: OffsetDateTime?,
    val endedAt: OffsetDateTime?,
    val durationSecs: Int?,
    val questions: List<CandidateQuestionDto>,
    val overallScore: BigDecimal?,
)

data class PagedResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val total: Long,
)
