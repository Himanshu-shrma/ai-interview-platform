package com.aiinterview.report.controller

import com.aiinterview.report.dto.ReportDto
import com.aiinterview.report.dto.ReportSummaryDto
import com.aiinterview.report.dto.UserStatsDto
import com.aiinterview.report.service.ReportService
import com.aiinterview.user.model.User
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class ReportController(
    private val reportService: ReportService,
) {

    /**
     * Fetches the evaluation report for a session.
     * Returns 404 if the report has not been generated yet (generation is async).
     */
    @GetMapping("/reports/{sessionId}")
    suspend fun getReport(
        @PathVariable sessionId: UUID,
        authentication: Authentication,
    ): ResponseEntity<ReportDto> {
        authentication.principal as? User ?: return ResponseEntity.status(401).build()
        val report = reportService.getReport(sessionId) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(report)
    }

    /**
     * Lists all evaluation reports for the authenticated user, newest first.
     */
    @GetMapping("/reports")
    suspend fun listReports(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "10") size: Int,
        authentication: Authentication,
    ): ResponseEntity<List<ReportSummaryDto>> {
        val user   = authentication.principal as? User ?: return ResponseEntity.status(401).build()
        val userId = user.id ?: return ResponseEntity.status(401).build()
        val list   = reportService.listReports(userId, page, size)
        return ResponseEntity.ok(list)
    }

    /**
     * Aggregated stats for the authenticated user's interview history.
     */
    @GetMapping("/users/me/stats")
    suspend fun getUserStats(authentication: Authentication): ResponseEntity<UserStatsDto> {
        val user   = authentication.principal as? User ?: return ResponseEntity.status(401).build()
        val userId = user.id ?: return ResponseEntity.status(401).build()
        val stats  = reportService.getUserStats(userId)
        return ResponseEntity.ok(stats)
    }
}
