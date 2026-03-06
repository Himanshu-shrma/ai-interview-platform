package com.aiinterview.interview.repository

import com.aiinterview.interview.model.InterviewTemplate
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux
import java.util.UUID

interface InterviewTemplateRepository : ReactiveCrudRepository<InterviewTemplate, UUID> {
    fun findByType(type: String): Flux<InterviewTemplate>
}
