package com.aiinterview.memory.controller

import com.aiinterview.memory.model.CandidateMemoryProfile
import com.aiinterview.memory.service.CandidateMemoryService
import com.aiinterview.user.model.User
import com.aiinterview.user.repository.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class DerivedInsightsDto(
    val sessionCount: Int,
    val weaknesses: List<String>,
    val topDimension: String,
    val avgAnxiety: Double,
    val dimensionTrend: Map<String, String>,
    val questionsSeen: List<String>,
    val lastUpdated: Instant?,
)

data class MemoryEnabledRequest(val enabled: Boolean)

@RestController
@RequestMapping("/api/v1/users/me")
class MemoryController(
    private val candidateMemoryService: CandidateMemoryService,
    private val userRepository: UserRepository,
) {

    /** Returns what the AI knows about this candidate from prior sessions. */
    @GetMapping("/memory")
    suspend fun getMemory(authentication: Authentication): ResponseEntity<DerivedInsightsDto> {
        val user = authentication.principal as? User ?: return ResponseEntity.status(401).build()
        val userId = user.id ?: return ResponseEntity.status(401).build()

        val profile = candidateMemoryService.loadProfile(userId)
            ?: return ResponseEntity.ok(
                DerivedInsightsDto(0, emptyList(), "", 0.3, emptyMap(), emptyList(), null)
            )

        val insights = candidateMemoryService.derivedInsights(profile)
        return ResponseEntity.ok(
            DerivedInsightsDto(
                sessionCount = insights.sessionCount,
                weaknesses = insights.weaknesses,
                topDimension = insights.topDimension,
                avgAnxiety = insights.avgAnxiety,
                dimensionTrend = insights.trend,
                questionsSeen = insights.questionsSeen,
                lastUpdated = profile.lastUpdated.toInstant(java.time.ZoneOffset.UTC),
            )
        )
    }

    /** Resets the candidate's memory profile (zeroes all signal arrays). */
    @DeleteMapping("/memory")
    suspend fun resetMemory(authentication: Authentication): ResponseEntity<Void> {
        val user = authentication.principal as? User ?: return ResponseEntity.status(401).build()
        val userId = user.id ?: return ResponseEntity.status(401).build()
        candidateMemoryService.resetProfile(userId)
        return ResponseEntity.noContent().build()
    }

    /** Enables or disables AI memory for this user. */
    @PatchMapping("/memory-enabled")
    suspend fun setMemoryEnabled(
        authentication: Authentication,
        @RequestBody request: MemoryEnabledRequest,
    ): ResponseEntity<Void> {
        val user = authentication.principal as? User ?: return ResponseEntity.status(401).build()
        val userId = user.id ?: return ResponseEntity.status(401).build()

        val updatedUser = user.copy(memoryEnabled = request.enabled)
        withContext(Dispatchers.IO) {
            userRepository.save(updatedUser).awaitSingle()
        }
        return ResponseEntity.noContent().build()
    }
}
