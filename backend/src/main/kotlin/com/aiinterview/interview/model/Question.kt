package com.aiinterview.interview.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

@Table("questions")
data class Question(
    @Id val id: UUID? = null,
    val title: String,
    val description: String,
    /** Maps to interview_type Postgres enum (DSA, CODING, SYSTEM_DESIGN, BEHAVIORAL) */
    val type: String,
    val difficulty: String,
    /** PostgreSQL TEXT[] — mapped as String array by the r2dbc-postgresql driver */
    val topicTags: Array<String>? = null,
    /** JSONB — serialized as JSON string */
    val examples: String? = null,
    @Column("constraints") val constraintsText: String? = null,
    /** JSONB */
    val testCases: String? = null,
    /** JSONB */
    val solutionHints: String? = null,
    val optimalApproach: String? = null,
    /** JSONB */
    val followUpPrompts: String? = null,
    val createdAt: OffsetDateTime? = null,
    // ── V6 fields ─────────────────────────────────────────────────────────────
    val source: String = "AI_GENERATED",
    val deletedAt: OffsetDateTime? = null,
    /** JSONB — stores QuestionGenerationParams for audit/replay */
    val generationParams: String? = null,
    val spaceComplexity: String? = null,
    val timeComplexity: String? = null,
    /** JSONB — evaluation rubric for BEHAVIORAL / SYSTEM_DESIGN / CASE_STUDY */
    val evaluationCriteria: String? = null,
    val slug: String? = null,
    /** Maps to interview_category VARCHAR(30) column — canonical routing field */
    @Column("interview_category") val category: String = "CODING",
    /** JSONB — starter code templates per language: {"python": "def ...", "java": "class ..."} */
    val codeTemplates: String? = null,
    /** JSONB — function signature metadata for template generation */
    val functionSignature: String? = null,
    /** Validation status: PENDING | PASSED | FAILED — only PASSED questions reach sessions */
    val validationStatus: String = "PENDING",
    val validatedAt: java.time.OffsetDateTime? = null,
) {
    // Required because data class contains Array field
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Question) return false
        // DB entities: prefer ID comparison when both non-null
        if (id != null && other.id != null) return id == other.id
        return title == other.title && type == other.type && difficulty == other.difficulty && category == other.category
    }

    override fun hashCode(): Int = id?.hashCode() ?: (title.hashCode() * 31 + type.hashCode())
}
