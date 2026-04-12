package com.aiinterview.report.controller

import com.aiinterview.report.dto.ProgressResponse
import com.aiinterview.report.service.ProgressService
import com.aiinterview.user.model.User
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/users/me")
class ProgressController(
    private val progressService: ProgressService,
) {

    @GetMapping("/progress")
    suspend fun getProgress(authentication: Authentication): ResponseEntity<ProgressResponse> {
        val user = authentication.principal as? User
            ?: return ResponseEntity.status(401).build()
        val userId = user.id ?: return ResponseEntity.status(401).build()
        return ResponseEntity.ok(progressService.getProgress(userId))
    }
}
