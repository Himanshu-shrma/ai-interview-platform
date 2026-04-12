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

    @Query("SELECT * FROM evaluation_reports WHERE user_id = :userId AND deleted_at IS NULL AND status = 'COMPLETED' ORDER BY completed_at ASC")
    fun findByUserIdOrderByCompletedAtAsc(userId: UUID): Flux<EvaluationReport>

    @Query("SELECT COUNT(DISTINCT user_id) FROM evaluation_reports WHERE deleted_at IS NULL")
    fun countDistinctUsers(): Mono<Long>

    @Query("""
        SELECT COUNT(DISTINCT sub.user_id) FROM (
            SELECT user_id, AVG(overall_score) AS avg_score
            FROM evaluation_reports
            WHERE deleted_at IS NULL AND overall_score IS NOT NULL
            GROUP BY user_id
        ) sub WHERE sub.avg_score < :avgScore
    """)
    fun countUsersWithAverageBelow(avgScore: Double): Mono<Long>

    @Query("SELECT AVG(overall_score) FROM evaluation_reports WHERE user_id = :userId AND deleted_at IS NULL AND overall_score IS NOT NULL")
    fun findAverageOverallScoreForUser(userId: UUID): Mono<Double>
}
