package com.aiinterview.interview.repository

import com.aiinterview.interview.model.Question
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.UUID

interface QuestionRepository : ReactiveCrudRepository<Question, UUID> {

    /** Legacy queries kept for compatibility with existing session code. */
    fun findByType(type: String): Flux<Question>
    fun findByTypeAndDifficulty(type: String, difficulty: String): Flux<Question>

    /** Derived query — used for slug uniqueness checks. */
    fun findBySlug(slug: String): Mono<Question>

    /** Count questions with this slug (for uniqueness loops). */
    fun countBySlug(slug: String): Mono<Long>

    /**
     * Returns one random non-deleted question matching category + difficulty.
     * Used when excludeIds is empty (avoids NOT IN on an empty list).
     */
    @Query("""
        SELECT * FROM questions
        WHERE interview_category = :category
          AND difficulty          = :difficulty
          AND deleted_at IS NULL
        ORDER BY RANDOM()
        LIMIT 1
    """)
    fun findRandom(category: String, difficulty: String): Mono<Question>

    /**
     * Returns up to 50 random non-deleted candidates for a category + difficulty.
     * The caller filters out excluded IDs in Kotlin to avoid the R2DBC
     * empty-IN-list problem entirely.
     */
    @Query("""
        SELECT * FROM questions
        WHERE interview_category = :category
          AND difficulty          = :difficulty
          AND deleted_at IS NULL
        ORDER BY RANDOM()
        LIMIT 50
    """)
    fun findRandomCandidates(category: String, difficulty: String): Flux<Question>
}
