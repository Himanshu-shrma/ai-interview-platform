package com.aiinterview.user.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

@Table("users")
data class User(
    @Id val id: UUID? = null,
    val orgId: UUID,
    val clerkUserId: String,
    val email: String,
    val fullName: String? = null,
    val role: String = "CANDIDATE",
    val subscriptionTier: String = "FREE",
    val createdAt: OffsetDateTime? = null,
)
