package com.aiinterview.interview.service

import com.aiinterview.shared.domain.Difficulty
import com.aiinterview.shared.domain.InterviewCategory
import com.aiinterview.shared.domain.InterviewType

data class InterviewConfig(
    val category: InterviewCategory,
    val type: InterviewType? = null,
    val difficulty: Difficulty = Difficulty.MEDIUM,
    val personality: String = "friendly",
    val programmingLanguage: String? = null,
    val targetRole: String? = null,
    val targetCompany: String? = null,
    val durationMinutes: Int = 45,
) {
    /**
     * Maps InterviewCategory → DB InterviewType string for interview_sessions.type column.
     * CASE_STUDY has no matching Postgres enum value and maps to CODING.
     */
    fun dbType(): String = when (category) {
        InterviewCategory.DSA           -> InterviewType.DSA.name
        InterviewCategory.CODING        -> InterviewType.CODING.name
        InterviewCategory.BEHAVIORAL    -> InterviewType.BEHAVIORAL.name
        InterviewCategory.SYSTEM_DESIGN -> InterviewType.SYSTEM_DESIGN.name
        InterviewCategory.CASE_STUDY    -> InterviewType.CODING.name
    }
}
