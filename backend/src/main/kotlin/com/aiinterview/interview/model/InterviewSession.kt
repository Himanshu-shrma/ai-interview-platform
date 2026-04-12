package com.aiinterview.interview.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

@Table("interview_sessions")
data class InterviewSession(
    @Id val id: UUID? = null,
    val userId: UUID,
    val status: String = "PENDING",
    val type: String,
    /** JSONB — session configuration (difficulty, personality, language, etc.) */
    val config: String = "{}",
    val startedAt: OffsetDateTime? = null,
    val endedAt: OffsetDateTime? = null,
    val durationSecs: Int? = null,
    val createdAt: OffsetDateTime? = null,
    val lastHeartbeat: OffsetDateTime? = null,
    val currentStage: String? = "SMALL_TALK",
    val integritySignals: String? = null,
    val deletedAt: OffsetDateTime? = null,
)
