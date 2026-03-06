package com.aiinterview.report.repository

import com.aiinterview.report.model.EvaluationReport
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.UUID

interface EvaluationReportRepository : ReactiveCrudRepository<EvaluationReport, UUID> {
    fun findBySessionId(sessionId: UUID): Mono<EvaluationReport>
    fun findByUserId(userId: UUID): Flux<EvaluationReport>
    fun findByUserIdOrderByCompletedAtDesc(userId: UUID): Flux<EvaluationReport>
    fun countByUserId(userId: UUID): Mono<Long>
}
