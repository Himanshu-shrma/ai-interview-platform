package com.aiinterview.interview.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

@Table("conversation_messages")
data class ConversationMessage(
    @Id val id: UUID? = null,
    val sessionId: UUID,
    val role: String,
    val content: String,
    /** JSONB — optional metadata (token count, latency, etc.) */
    val metadata: String? = null,
    val createdAt: OffsetDateTime? = null,
    val deletedAt: OffsetDateTime? = null,
)
