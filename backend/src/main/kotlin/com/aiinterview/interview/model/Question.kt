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
) {
    // Required because data class contains Array field
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Question) return false
        return id == other.id &&
            title == other.title &&
            description == other.description &&
            type == other.type &&
            difficulty == other.difficulty &&
            topicTags.contentEquals(other.topicTags) &&
            examples == other.examples &&
            constraintsText == other.constraintsText &&
            testCases == other.testCases &&
            solutionHints == other.solutionHints &&
            optimalApproach == other.optimalApproach &&
            followUpPrompts == other.followUpPrompts &&
            createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = id?.hashCode() ?: 0
        result = 31 * result + title.hashCode()
        result = 31 * result + (topicTags?.contentHashCode() ?: 0)
        return result
    }
}

private fun Array<String>?.contentEquals(other: Array<String>?): Boolean =
    if (this == null && other == null) true
    else if (this == null || other == null) false
    else this.contentEquals(other)
