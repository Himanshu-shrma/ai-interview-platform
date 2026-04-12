package com.aiinterview.user.dto

import java.util.UUID

data class UserDto(
    val id: UUID,
    val email: String,
    val fullName: String?,
    val role: String,
    val orgId: UUID,
    val orgType: String,
    val plan: String,
    val interviewsUsedThisMonth: Int,
    val onboardingCompleted: Boolean = false,
)
