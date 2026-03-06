package com.aiinterview.interview.repository

import com.aiinterview.interview.model.ConversationMessage
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux
import java.util.UUID

interface ConversationMessageRepository : ReactiveCrudRepository<ConversationMessage, UUID> {
    fun findBySessionIdOrderByCreatedAt(sessionId: UUID): Flux<ConversationMessage>
}
