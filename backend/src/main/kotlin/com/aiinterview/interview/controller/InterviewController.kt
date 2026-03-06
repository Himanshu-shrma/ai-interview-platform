package com.aiinterview.interview.controller

import com.aiinterview.interview.dto.ApiError
import com.aiinterview.interview.dto.PagedResponse
import com.aiinterview.interview.dto.SessionDetailDto
import com.aiinterview.interview.dto.SessionSummaryDto
import com.aiinterview.interview.dto.StartSessionResponse
import com.aiinterview.interview.service.InterviewConfig
import com.aiinterview.interview.service.InterviewSessionService
import com.aiinterview.interview.service.SessionAccessDeniedException
import com.aiinterview.interview.service.UsageLimitExceededException
import com.aiinterview.user.model.User
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.UUID

@RestController
@RequestMapping("/api/v1/interviews")
class InterviewController(
    private val interviewSessionService: InterviewSessionService,
) {

    @PostMapping("/sessions")
    suspend fun startSession(
        @RequestBody config: InterviewConfig,
        authentication: Authentication,
    ): ResponseEntity<StartSessionResponse> {
        val user = authentication.principal as? User
            ?: return ResponseEntity.status(401).build()
        val response = interviewSessionService.startSession(user, config)
        return ResponseEntity.status(201).body(response)
    }

    @PostMapping("/sessions/{sessionId}/end")
    suspend fun endSession(
        @PathVariable sessionId: UUID,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        val user = authentication.principal as? User
            ?: return ResponseEntity.status(401).build()
        val userId = user.id ?: return ResponseEntity.status(401).build()
        interviewSessionService.endSession(sessionId, userId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/sessions/{sessionId}")
    suspend fun getSession(
        @PathVariable sessionId: UUID,
        authentication: Authentication,
    ): ResponseEntity<SessionDetailDto> {
        val user = authentication.principal as? User
            ?: return ResponseEntity.status(401).build()
        val userId = user.id ?: return ResponseEntity.status(401).build()
        val dto = interviewSessionService.getSession(sessionId, userId)
        return ResponseEntity.ok(dto)
    }

    @GetMapping("/sessions")
    suspend fun listSessions(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        authentication: Authentication,
    ): ResponseEntity<PagedResponse<SessionSummaryDto>> {
        val user = authentication.principal as? User
            ?: return ResponseEntity.status(401).build()
        val userId = user.id ?: return ResponseEntity.status(401).build()
        val result = interviewSessionService.listSessions(userId, page, size)
        return ResponseEntity.ok(result)
    }
}

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(ex: NoSuchElementException): ResponseEntity<ApiError> =
        ResponseEntity.status(404).body(
            ApiError(error = "NOT_FOUND", message = ex.message ?: "Resource not found")
        )

    @ExceptionHandler(SessionAccessDeniedException::class)
    fun handleForbidden(ex: SessionAccessDeniedException): ResponseEntity<ApiError> =
        ResponseEntity.status(403).body(
            ApiError(error = "FORBIDDEN", message = ex.message ?: "Access denied")
        )

    @ExceptionHandler(UsageLimitExceededException::class)
    fun handleUsageLimit(ex: UsageLimitExceededException): ResponseEntity<ApiError> =
        ResponseEntity.status(429).body(
            ApiError(error = "USAGE_LIMIT_EXCEEDED", message = ex.message ?: "Monthly interview limit reached")
        )

    @ExceptionHandler(Exception::class)
    fun handleGeneral(ex: Exception): ResponseEntity<ApiError> =
        ResponseEntity.status(500).body(
            ApiError(error = "INTERNAL_ERROR", message = "An unexpected error occurred")
        )
}
