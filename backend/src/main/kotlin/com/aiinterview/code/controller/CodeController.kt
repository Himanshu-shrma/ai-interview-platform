package com.aiinterview.code.controller

import com.aiinterview.code.LanguageMap
import com.aiinterview.code.service.CodeExecutionService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class CodeRunRequest(
    val sessionId: UUID,
    val code: String,
    val language: String,
    val stdin: String? = null,
)

data class CodeSubmitRequest(
    val sessionId: UUID,
    val sessionQuestionId: UUID,
    val code: String,
    val language: String,
)

/**
 * REST fallback for code execution (e.g., debugging without WebSocket).
 * The primary path is WebSocket — these endpoints mirror it for convenience.
 */
@RestController
@RequestMapping("/api/v1/code")
class CodeController(
    private val codeExecutionService: CodeExecutionService,
) {

    /** Runs code against optional stdin; result is delivered over WebSocket. */
    @PostMapping("/run")
    suspend fun runCode(@RequestBody req: CodeRunRequest): ResponseEntity<Map<String, String>> {
        codeExecutionService.runCode(req.sessionId, req.code, req.language, req.stdin)
        return ResponseEntity.accepted().body(mapOf("message" to "Execution started; result will arrive over WebSocket"))
    }

    /** Submits code against all test cases; result is delivered over WebSocket. */
    @PostMapping("/submit")
    suspend fun submitCode(@RequestBody req: CodeSubmitRequest): ResponseEntity<Map<String, String>> {
        codeExecutionService.submitCode(req.sessionId, req.sessionQuestionId, req.code, req.language)
        return ResponseEntity.accepted().body(mapOf("message" to "Submission started; result will arrive over WebSocket"))
    }

    /** Returns the list of supported programming languages. No auth required. */
    @GetMapping("/languages")
    fun getSupportedLanguages(): ResponseEntity<Map<String, Any>> =
        ResponseEntity.ok(mapOf("languages" to LanguageMap.getSupportedLanguages()))
}
