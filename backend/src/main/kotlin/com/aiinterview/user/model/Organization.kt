package com.aiinterview.user.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

@Table("organizations")
data class Organization(
    @Id val id: UUID? = null,
    val name: String,
    val type: String = "PERSONAL",
    val plan: String = "FREE",
    val seatsLimit: Int = 1,
    val createdAt: OffsetDateTime? = null,
)
