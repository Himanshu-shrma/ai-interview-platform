package com.aiinterview.code.service

import com.aiinterview.code.Judge0Client
import com.aiinterview.code.LanguageMap
import com.aiinterview.code.UnsupportedLanguageException
import com.aiinterview.code.model.CodeSubmission
import com.aiinterview.code.repository.CodeSubmissionRepository
import com.aiinterview.conversation.ConversationEngine
import com.aiinterview.conversation.InterviewState
import com.aiinterview.interview.repository.QuestionRepository
import com.aiinterview.interview.repository.SessionQuestionRepository
import com.aiinterview.interview.service.RedisMemoryService
import com.aiinterview.interview.ws.OutboundMessage
import com.aiinterview.interview.ws.TestResult
import com.aiinterview.interview.ws.WsSessionRegistry
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

@Service
class CodeExecutionService(
    private val judge0Client: Judge0Client,
    private val registry: WsSessionRegistry,
    private val redisMemoryService: RedisMemoryService,
    private val sessionQuestionRepository: SessionQuestionRepository,
    private val questionRepository: QuestionRepository,
    private val codeSubmissionRepository: CodeSubmissionRepository,
    private val conversationEngine: ConversationEngine,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(CodeExecutionService::class.java)

    /**
     * Runs code against optional stdin, sends CODE_RESULT over WebSocket.
     * Does NOT persist to DB — use [submitCode] for that.
     */
    suspend fun runCode(
        sessionId: UUID,
        code: String,
        language: String,
        stdin: String? = null,
    ) {
        val languageId = resolveLanguageId(sessionId, language) ?: return

        try {
            val token  = judge0Client.submit(code, languageId, stdin)
            val result = judge0Client.pollResult(token)

            registry.sendMessage(
                sessionId,
                OutboundMessage.CodeResult(
                    status    = result.status?.description ?: "Unknown",
                    stdout    = result.stdout,
                    stderr    = result.stderr ?: result.compileOutput,
                    runtimeMs = result.time?.toDoubleOrNull()?.let { (it * 1000).toLong() },
                ),
            )

            // Keep memory.currentCode in sync
            redisMemoryService.updateMemory(sessionId) { mem ->
                mem.copy(currentCode = code, programmingLanguage = language)
            }
        } catch (e: Exception) {
            log.error("Code execution failed for session {}: {}", sessionId, e.message)
            registry.sendMessage(sessionId, OutboundMessage.Error("EXECUTION_ERROR", e.message ?: "Code execution failed"))
        }
    }

    /**
     * Runs code against all test cases concurrently, persists [CodeSubmission],
     * updates session_questions, and triggers a CODING→FOLLOW_UP transition on
     * full pass.
     */
    suspend fun submitCode(
        sessionId: UUID,
        sessionQuestionId: UUID,
        code: String,
        language: String,
    ) {
        val languageId = resolveLanguageId(sessionId, language) ?: return

        // Resolve question for test cases
        val sessionQuestion = withContext(Dispatchers.IO) {
            sessionQuestionRepository.findById(sessionQuestionId).awaitSingleOrNull()
        }
        if (sessionQuestion == null) {
            registry.sendMessage(sessionId, OutboundMessage.Error("QUESTION_NOT_FOUND", "Session question not found"))
            return
        }
        val question = withContext(Dispatchers.IO) {
            questionRepository.findById(sessionQuestion.questionId).awaitSingleOrNull()
        }

        val testCasesJson = question?.testCases
        val testCases     = parseTestCases(testCasesJson)

        val memory = try { redisMemoryService.getMemory(sessionId) } catch (e: Exception) {
            registry.sendMessage(sessionId, OutboundMessage.Error("SESSION_ERROR", "Session not found"))
            return
        }

        // Run all test cases concurrently
        val testResults = coroutineScope {
            if (testCases.isEmpty()) {
                // No test cases — single run with no stdin
                val deferred = async {
                    runSingleTestCase(code, languageId, stdin = null, expected = null)
                }
                listOf(deferred.await())
            } else {
                testCases.map { tc ->
                    async { runSingleTestCase(code, languageId, tc.input, tc.expected) }
                }.map { it.await() }
            }
        }

        val allPassed  = testResults.all { it.passed }
        val status     = if (allPassed) "ACCEPTED" else "FAILED"
        val overallMs  = testResults.mapNotNull { it.runtimeMs }.maxOrNull()

        // Persist submission
        val resultsJson = objectMapper.writeValueAsString(testResults)
        withContext(Dispatchers.IO) {
            codeSubmissionRepository.save(
                CodeSubmission(
                    sessionQuestionId = sessionQuestionId,
                    userId            = memory.userId,
                    code              = code,
                    language          = language,
                    status            = status,
                    testResults       = resultsJson,
                    runtimeMs         = overallMs?.toInt(),
                    submittedAt       = OffsetDateTime.now(),
                ),
            ).awaitSingle()

            // Update session_question with final code and language
            sessionQuestionRepository.save(
                sessionQuestion.copy(
                    finalCode    = code,
                    languageUsed = language,
                    submittedAt  = OffsetDateTime.now(),
                ),
            ).awaitSingle()
        }

        // Update memory with latest code
        redisMemoryService.updateMemory(sessionId) { mem ->
            mem.copy(currentCode = code, programmingLanguage = language)
        }

        // Send result to client
        registry.sendMessage(
            sessionId,
            OutboundMessage.CodeResult(
                status      = status,
                stdout      = testResults.firstOrNull()?.actual,
                stderr      = null,
                runtimeMs   = overallMs,
                testResults = testResults,
            ),
        )

        log.info("Code submit session={} status={} passed={}/{}", sessionId, status, testResults.count { it.passed }, testResults.size)

        // Transition on full pass
        if (allPassed) {
            conversationEngine.transition(sessionId, InterviewState.FollowUp)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun resolveLanguageId(sessionId: UUID, language: String): Int? {
        if (!LanguageMap.isSupported(language)) {
            registry.sendMessage(
                sessionId,
                OutboundMessage.Error(
                    "UNSUPPORTED_LANGUAGE",
                    "Unsupported language: '$language'. Supported: ${LanguageMap.getSupportedLanguages().joinToString()}",
                ),
            )
            return null
        }
        return LanguageMap.getLanguageId(language)
    }

    private suspend fun runSingleTestCase(
        code: String,
        languageId: Int,
        stdin: String?,
        expected: String?,
    ): TestResult {
        return try {
            val token  = judge0Client.submit(code, languageId, stdin)
            val result = judge0Client.pollResult(token)
            val actual = result.stdout?.trimEnd()
            val passed = result.status?.id == 3 && (expected == null || actual?.trimEnd() == expected.trimEnd())
            TestResult(
                passed    = passed,
                input     = stdin,
                expected  = expected,
                actual    = actual,
                runtimeMs = result.time?.toDoubleOrNull()?.let { (it * 1000).toLong() },
            )
        } catch (e: Exception) {
            log.error("Test case execution failed: {}", e.message)
            TestResult(passed = false, input = stdin, expected = expected, actual = "Error: ${e.message}", runtimeMs = null)
        }
    }

    private fun parseTestCases(testCasesJson: String?): List<TestCaseEntry> {
        if (testCasesJson.isNullOrBlank()) return emptyList()
        return try {
            val node = objectMapper.readTree(testCasesJson)
            if (node.isArray) {
                node.mapNotNull { entry ->
                    val input    = entry.path("input").textOrNull()
                    val expected = entry.path("expectedOutput").textOrNull()
                        ?: entry.path("expected_output").textOrNull()
                        ?: entry.path("output").textOrNull()
                    TestCaseEntry(input, expected)
                }
            } else emptyList()
        } catch (e: Exception) {
            log.warn("Failed to parse test cases JSON: {}", e.message)
            emptyList()
        }
    }

    private fun JsonNode.textOrNull(): String? =
        if (this.isNull || this.isMissingNode) null else this.asText()

    private data class TestCaseEntry(val input: String?, val expected: String?)
}
