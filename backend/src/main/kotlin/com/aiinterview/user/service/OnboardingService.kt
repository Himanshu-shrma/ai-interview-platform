package com.aiinterview.user.service

import com.aiinterview.user.model.User
import com.aiinterview.user.repository.UserRepository
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

data class OnboardingRequest(
    val roleTarget: String,        // "swe" | "senior_swe" | "staff" | "switching"
    val urgency: String,           // "active" | "preparing" | "exploring"
    val biggestChallenge: String,  // "algorithms" | "system_design" | "behavioral" | "communication"
)

data class OnboardingRecommendation(
    val category: String,
    val difficulty: String,
    val personality: String,
    val rationale: String,
)

@Service
class OnboardingService(
    private val userRepository: UserRepository,
    private val userBootstrapService: UserBootstrapService,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(OnboardingService::class.java)

    /**
     * Computes a personalised interview recommendation, persists the onboarding
     * answers, marks the user as onboarded, and evicts the Redis user cache.
     */
    suspend fun complete(user: User, request: OnboardingRequest): OnboardingRecommendation {
        val recommendation = recommend(request)

        val answersJson = objectMapper.writeValueAsString(request)
        userRepository.save(
            user.copy(
                onboardingCompleted = true,
                onboardingAnswers   = answersJson,
            )
        ).awaitSingle()

        // Evict Redis cache so subsequent getMe() reflects the updated flag
        userBootstrapService.evictCache(user.clerkUserId)

        log.info(
            "Onboarding completed userId={} role={} urgency={} → {}/{}",
            user.id, request.roleTarget, request.urgency,
            recommendation.category, recommendation.difficulty,
        )

        return recommendation
    }

    // ── Recommendation matrix ────────────────────────────────────────────────

    private fun recommend(req: OnboardingRequest): OnboardingRecommendation = when {
        req.urgency == "exploring" ->
            OnboardingRecommendation(
                category    = "CODING",
                difficulty  = "EASY",
                personality = "FriendlyMentor",
                rationale   = "You're exploring — start with an easy coding question to get a feel for the platform without pressure.",
            )

        req.roleTarget == "staff" ->
            OnboardingRecommendation(
                category    = "SYSTEM_DESIGN",
                difficulty  = "HARD",
                personality = "FaangSenior",
                rationale   = "Staff-level roles are heavy on system design. Practising hard design questions with a senior interviewer will sharpen your architectural thinking.",
            )

        req.roleTarget == "switching" ->
            OnboardingRecommendation(
                category    = "BEHAVIORAL",
                difficulty  = "MEDIUM",
                personality = "FriendlyMentor",
                rationale   = "Career switchers need to tell a compelling story. A behavioural round with a supportive interviewer is the best first step.",
            )

        req.roleTarget == "senior_swe" && req.urgency == "active" ->
            OnboardingRecommendation(
                category    = "CODING",
                difficulty  = "HARD",
                personality = "FaangSenior",
                rationale   = "Actively interviewing for senior roles means you need to perform under pressure. A hard coding round with a FAANG-style interviewer mirrors what you will face.",
            )

        else ->  // swe + active/preparing, or any unmatched combination
            OnboardingRecommendation(
                category    = "CODING",
                difficulty  = "MEDIUM",
                personality = "FriendlyMentor",
                rationale   = "A medium-difficulty coding round is the right starting point — challenging enough to be meaningful, approachable enough to build confidence.",
            )
    }
}
