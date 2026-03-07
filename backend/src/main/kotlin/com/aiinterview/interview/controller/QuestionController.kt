package com.aiinterview.interview.controller

import com.aiinterview.interview.dto.CandidateQuestionDto
import com.aiinterview.interview.dto.InternalQuestionDto
import com.aiinterview.interview.dto.toCandidateDto
import com.aiinterview.interview.dto.toInternalDto
import com.aiinterview.interview.service.QuestionGenerationParams
import com.aiinterview.interview.service.QuestionService
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class QuestionController(
    private val questionService: QuestionService,
    private val objectMapper: ObjectMapper,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    /** List all non-deleted questions (auth required — used for debug/admin). */
    @GetMapping("/questions")
    suspend fun listQuestions(): List<CandidateQuestionDto> =
        questionService.listAll().map { it.toCandidateDto(objectMapper) }

    /** Fetch a single question by ID (candidate view — no hints/solutions). */
    @GetMapping("/questions/{id}")
    suspend fun getQuestion(@PathVariable id: UUID): ResponseEntity<CandidateQuestionDto> =
        runCatching { questionService.getQuestionById(id).toCandidateDto(objectMapper) }
            .fold(
                onSuccess  = { ResponseEntity.ok(it) },
                onFailure  = { ResponseEntity.notFound().build() },
            )

    /**
     * Trigger AI generation of a question (admin / QA use).
     * Returns the full internal DTO including hints, test cases, and generation params.
     */
    @PostMapping("/admin/questions/generate")
    suspend fun generateQuestion(
        @RequestBody params: QuestionGenerationParams,
    ): ResponseEntity<InternalQuestionDto> =
        runCatching { questionService.getOrGenerateQuestion(params).toInternalDto(objectMapper) }
            .fold(
                onSuccess  = { ResponseEntity.status(HttpStatus.CREATED).body(it) },
                onFailure  = {
                    log.error("generateQuestion failed", it)
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()
                },
            )

    /** Soft-delete a question (admin only). */
    @DeleteMapping("/admin/questions/{id}")
    suspend fun deleteQuestion(@PathVariable id: UUID): ResponseEntity<Void> =
        runCatching { questionService.softDeleteQuestion(id) }
            .fold(
                onSuccess  = { ResponseEntity.noContent().build() },
                onFailure  = { ResponseEntity.notFound().build() },
            )
}
