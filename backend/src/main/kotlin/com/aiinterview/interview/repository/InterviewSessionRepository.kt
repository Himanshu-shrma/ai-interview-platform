package com.aiinterview.interview.repository

import com.aiinterview.interview.model.InterviewSession
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.UUID

interface InterviewSessionRepository : ReactiveCrudRepository<InterviewSession, UUID> {
    fun findByUserId(userId: UUID): Flux<InterviewSession>
    fun findByIdAndUserId(id: UUID, userId: UUID): Mono<InterviewSession>
    fun findByUserIdAndStatus(userId: UUID, status: String): Flux<InterviewSession>
    fun findByUserIdOrderByCreatedAtDesc(userId: UUID): Flux<InterviewSession>
    fun countByUserId(userId: UUID): Mono<Long>
    fun countByUserIdAndStatus(userId: UUID, status: String): Mono<Long>
}
