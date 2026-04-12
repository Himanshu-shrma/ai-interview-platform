package com.aiinterview.interview.controller

import com.aiinterview.interview.repository.InterviewSessionRepository
import com.aiinterview.user.model.User
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
data class OutcomeRequest(
    val hired: Boolean? = null,
    val company: String? = null,
    val level: String? = null,
    val platformHelped: Boolean? = null,
    val feltRealistic: String? = null,   // "yes" | "somewhat" | "no"
    val nps: Int? = null,                // 1–10
)

@RestController
@RequestMapping("/api/v1/outcomes")
class OutcomeController(
    private val sessionRepository: InterviewSessionRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(OutcomeController::class.java)

    @PostMapping("/{sessionId}")
    suspend fun submitOutcome(
        @PathVariable sessionId: UUID,
        @RequestBody req: OutcomeRequest,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        val user = authentication.principal as? User
            ?: return ResponseEntity.status(401).build()
        val userId = user.id ?: return ResponseEntity.status(401).build()

        // Validate nps range if provided
        if (req.nps != null && req.nps !in 1..10) {
            return ResponseEntity.badRequest().build()
        }

        return try {
            val json = objectMapper.writeValueAsString(req)
            val updated = withContext(Dispatchers.IO) {
                sessionRepository.updateFeedback(sessionId, userId, json).awaitSingleOrNull()
            } ?: 0L

            if (updated == 0L) {
                log.warn("submitOutcome: no session found id={} userId={}", sessionId, userId)
                ResponseEntity.notFound().build()
            } else {
                ResponseEntity.noContent().build()
            }
        } catch (e: Exception) {
            log.warn("submitOutcome failed for sessionId={}: {}", sessionId, e.message)
            ResponseEntity.internalServerError().build()
        }
    }
}
