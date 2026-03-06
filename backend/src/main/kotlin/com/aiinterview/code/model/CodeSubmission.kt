package com.aiinterview.code.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

@Table("code_submissions")
data class CodeSubmission(
    @Id val id: UUID? = null,
    val sessionQuestionId: UUID,
    val userId: UUID,
    val code: String,
    val language: String,
    val status: String = "PENDING",
    val judge0Token: String? = null,
    /** JSONB — array of per-test-case results */
    val testResults: String? = null,
    val runtimeMs: Int? = null,
    val memoryKb: Int? = null,
    val submittedAt: OffsetDateTime? = null,
)
