package com.aiinterview.user.controller

import com.aiinterview.user.dto.UserDto
import com.aiinterview.user.model.User
import com.aiinterview.user.repository.OrganizationRepository
import com.aiinterview.user.service.UsageLimitService
import com.aiinterview.user.service.UserDeletionService
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/users")
class AuthController(
    private val organizationRepository: OrganizationRepository,
    private val usageLimitService: UsageLimitService,
    private val userDeletionService: UserDeletionService,
) {

    @GetMapping("/me")
    suspend fun me(authentication: Authentication): ResponseEntity<UserDto> {
        val user = authentication.principal as? User
            ?: return ResponseEntity.status(401).build()

        val userId = user.id ?: return ResponseEntity.status(401).build()

        val org = organizationRepository.findById(user.orgId).awaitSingleOrNull()
            ?: return ResponseEntity.notFound().build()

        val usedThisMonth = usageLimitService.getUsageThisMonth(userId)

        return ResponseEntity.ok(
            UserDto(
                id = userId,
                email = user.email,
                fullName = user.fullName,
                role = user.role,
                orgId = user.orgId,
                orgType = org.type,
                plan = org.plan,
                interviewsUsedThisMonth = usedThisMonth,
                onboardingCompleted = user.onboardingCompleted,
                memoryEnabled = user.memoryEnabled,
            )
        )
    }

    @DeleteMapping("/me")
    suspend fun deleteAccount(authentication: Authentication): ResponseEntity<Void> {
        val user = authentication.principal as? User
            ?: return ResponseEntity.status(401).build()
        val userId = user.id ?: return ResponseEntity.status(401).build()
        userDeletionService.deleteUser(userId)
        return ResponseEntity.noContent().build()
    }
}
