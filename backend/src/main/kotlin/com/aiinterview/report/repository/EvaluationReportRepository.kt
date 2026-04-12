package com.aiinterview.report.repository

import com.aiinterview.report.model.EvaluationReport
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.UUID

interface EvaluationReportRepository : ReactiveCrudRepository<EvaluationReport, UUID> {

    @Query("SELECT * FROM evaluation_reports WHERE session_id = :sessionId AND deleted_at IS NULL LIMIT 1")
    fun findBySessionId(sessionId: UUID): Mono<EvaluationReport>

    @Query("SELECT * FROM evaluation_reports WHERE user_id = :userId AND deleted_at IS NULL")
    fun findByUserId(userId: UUID): Flux<EvaluationReport>

    @Query("SELECT * FROM evaluation_reports WHERE user_id = :userId AND deleted_at IS NULL ORDER BY completed_at DESC")
    fun findByUserIdOrderByCompletedAtDesc(userId: UUID): Flux<EvaluationReport>

    @Query("SELECT COUNT(*) FROM evaluation_reports WHERE user_id = :userId AND deleted_at IS NULL")
    fun countByUserId(userId: UUID): Mono<Long>
}
