package com.aiinterview.interview.controller

import com.aiinterview.interview.repository.InterviewSessionRepository
import com.aiinterview.user.model.User
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class IntegritySignal(
    val sessionId: UUID,
    val signals: List<IntegrityEvent>,
)

data class IntegrityEvent(
    val type: String,       // TAB_SWITCH, PASTE_DETECTED, TYPING_ANOMALY
    val timestamp: Long,
    val metadata: Map<String, Any>? = null,
)

@RestController
@RequestMapping("/api/v1/integrity")
class IntegrityController(
    private val interviewSessionRepository: InterviewSessionRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(IntegrityController::class.java)

    @PostMapping
    suspend fun reportSignals(
        @RequestBody signal: IntegritySignal,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        val user = authentication.principal as? User
            ?: return ResponseEntity.status(401).build()
        val userId = user.id ?: return ResponseEntity.status(401).build()

        val session = withContext(Dispatchers.IO) {
            interviewSessionRepository.findById(signal.sessionId).awaitSingleOrNull()
        } ?: return ResponseEntity.notFound().build()

        if (session.userId != userId) return ResponseEntity.status(403).build()

        // Merge new signals with existing ones
        val existing = session.integritySignals?.let {
            try {
                objectMapper.readValue(it, List::class.java) ?: emptyList<Any>()
            } catch (_: Exception) { emptyList<Any>() }
        } ?: emptyList()

        val merged = existing + signal.signals.map { event ->
            mapOf("type" to event.type, "timestamp" to event.timestamp, "metadata" to event.metadata)
        }

        val json = objectMapper.writeValueAsString(merged)
        withContext(Dispatchers.IO) {
            interviewSessionRepository.save(session.copy(integritySignals = json)).awaitSingle()
        }

        log.debug("Recorded {} integrity signals for session {}", signal.signals.size, signal.sessionId)
        return ResponseEntity.noContent().build()
    }
}
