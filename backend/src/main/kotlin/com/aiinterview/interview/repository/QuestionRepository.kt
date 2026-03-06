package com.aiinterview.interview.repository

import com.aiinterview.interview.model.Question
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux
import java.util.UUID

interface QuestionRepository : ReactiveCrudRepository<Question, UUID> {
    fun findByType(type: String): Flux<Question>
    fun findByTypeAndDifficulty(type: String, difficulty: String): Flux<Question>
}
