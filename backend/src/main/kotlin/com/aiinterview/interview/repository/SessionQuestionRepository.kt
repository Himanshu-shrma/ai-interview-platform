package com.aiinterview.interview.repository

import com.aiinterview.interview.model.SessionQuestion
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux
import java.util.UUID

interface SessionQuestionRepository : ReactiveCrudRepository<SessionQuestion, UUID> {
    fun findBySessionId(sessionId: UUID): Flux<SessionQuestion>
    fun findBySessionIdOrderByOrderIndex(sessionId: UUID): Flux<SessionQuestion>
}
