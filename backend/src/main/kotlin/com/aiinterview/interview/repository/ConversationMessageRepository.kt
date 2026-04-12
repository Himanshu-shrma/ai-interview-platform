package com.aiinterview.interview.repository

import com.aiinterview.interview.model.ConversationMessage
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux
import java.util.UUID

interface ConversationMessageRepository : ReactiveCrudRepository<ConversationMessage, UUID> {

    @Query("""
        SELECT * FROM conversation_messages
        WHERE session_id = :sessionId
          AND deleted_at IS NULL
        ORDER BY created_at
    """)
    fun findBySessionIdOrderByCreatedAt(sessionId: UUID): Flux<ConversationMessage>

    /** Returns the last N non-deleted messages for a session, ordered newest-first. */
    @Query("""
        SELECT * FROM conversation_messages
        WHERE session_id = :sessionId
          AND deleted_at IS NULL
        ORDER BY created_at DESC
        LIMIT :n
    """)
    fun findRecentBySessionId(sessionId: UUID, n: Int): Flux<ConversationMessage>
}
