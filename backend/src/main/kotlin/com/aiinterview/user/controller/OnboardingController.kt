package com.aiinterview.user.controller

import com.aiinterview.user.model.User
import com.aiinterview.user.service.OnboardingRecommendation
import com.aiinterview.user.service.OnboardingRequest
import com.aiinterview.user.service.OnboardingService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/users/me")
class OnboardingController(
    private val onboardingService: OnboardingService,
) {

    /**
     * Accepts onboarding answers, returns a personalised interview recommendation,
     * and marks the user's onboarding_completed flag as true.
     *
     * Called once after the user completes the 3-step wizard on the frontend.
     * Idempotent: safe to call again (just overwrites onboarding_answers).
     */
    @PostMapping("/onboarding")
    suspend fun completeOnboarding(
        authentication: Authentication,
        @RequestBody request: OnboardingRequest,
    ): ResponseEntity<OnboardingRecommendation> {
        val user = authentication.principal as? User
            ?: return ResponseEntity.status(401).build()

        val recommendation = onboardingService.complete(user, request)
        return ResponseEntity.ok(recommendation)
    }
}
