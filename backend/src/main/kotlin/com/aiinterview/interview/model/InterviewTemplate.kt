package com.aiinterview.interview.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

@Table("interview_templates")
data class InterviewTemplate(
    @Id val id: UUID? = null,
    val name: String,
    val type: String,
    val difficulty: String,
    /** JSONB */
    val config: String = "{}",
    val createdAt: OffsetDateTime? = null,
)
