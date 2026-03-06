package com.aiinterview.user.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

@Table("org_invitations")
data class OrgInvitation(
    @Id val id: UUID? = null,
    val orgId: UUID,
    val email: String,
    val role: String = "CANDIDATE",
    val token: String,
    val expiresAt: OffsetDateTime,
    val acceptedAt: OffsetDateTime? = null,
    val createdAt: OffsetDateTime? = null,
)
