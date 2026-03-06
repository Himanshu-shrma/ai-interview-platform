package com.aiinterview.interview.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

@Table("session_questions")
data class SessionQuestion(
    @Id val id: UUID? = null,
    val sessionId: UUID,
    val questionId: UUID,
    val orderIndex: Int = 0,
    val finalCode: String? = null,
    val languageUsed: String? = null,
    val submittedAt: OffsetDateTime? = null,
    val createdAt: OffsetDateTime? = null,
)
