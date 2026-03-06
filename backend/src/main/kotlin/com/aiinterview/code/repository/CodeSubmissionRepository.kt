package com.aiinterview.code.repository

import com.aiinterview.code.model.CodeSubmission
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.UUID

interface CodeSubmissionRepository : ReactiveCrudRepository<CodeSubmission, UUID> {
    fun findBySessionQuestionId(sessionQuestionId: UUID): Flux<CodeSubmission>
    fun findByJudge0Token(token: String): Mono<CodeSubmission>
}
