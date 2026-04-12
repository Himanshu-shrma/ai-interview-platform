package com.aiinterview.interview.repository

import com.aiinterview.interview.model.InterviewSession
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.UUID

interface InterviewSessionRepository : ReactiveCrudRepository<InterviewSession, UUID> {

    @Query("SELECT * FROM interview_sessions WHERE user_id = :userId AND deleted_at IS NULL")
    fun findByUserId(userId: UUID): Flux<InterviewSession>

    @Query("SELECT * FROM interview_sessions WHERE id = :id AND user_id = :userId AND deleted_at IS NULL")
    fun findByIdAndUserId(id: UUID, userId: UUID): Mono<InterviewSession>

    @Query("SELECT * FROM interview_sessions WHERE user_id = :userId AND status = :status AND deleted_at IS NULL")
    fun findByUserIdAndStatus(userId: UUID, status: String): Flux<InterviewSession>

    @Query("SELECT * FROM interview_sessions WHERE user_id = :userId AND deleted_at IS NULL ORDER BY created_at DESC")
    fun findByUserIdOrderByCreatedAtDesc(userId: UUID): Flux<InterviewSession>

    @Query("SELECT COUNT(*) FROM interview_sessions WHERE user_id = :userId AND deleted_at IS NULL")
    fun countByUserId(userId: UUID): Mono<Long>

    @Query("SELECT COUNT(*) FROM interview_sessions WHERE user_id = :userId AND status = :status AND deleted_at IS NULL")
    fun countByUserIdAndStatus(userId: UUID, status: String): Mono<Long>
}
