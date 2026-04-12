package com.aiinterview.interview.service

import com.aiinterview.code.Judge0Client
import com.aiinterview.interview.model.Question
import com.aiinterview.interview.repository.QuestionRepository
import com.aiinterview.shared.ai.LlmProviderRegistry
import com.aiinterview.shared.ai.LlmRequest
import com.aiinterview.shared.ai.ModelConfig
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Validates LLM-generated questions by:
 *  1. Asking the LLM to produce a reference Python3 solution.
 *  2. Running every test case through Judge0.
 *  3. If all test cases pass → validation_status = PASSED, otherwise FAILED.
 *
 * Non-coding categories (BEHAVIORAL, SYSTEM_DESIGN) auto-pass — no Judge0 needed.
 *
 * Called:
 *  - On startup for all PENDING questions (validatePendingOnStartup).
 *  - After question generation via scheduleValidation().
 */
@Service
class QuestionValidationService(
    private val judge0Client: Judge0Client,
    private val llmRegistry: LlmProviderRegistry,
    private val questionRepository: QuestionRepository,
    private val modelConfig: ModelConfig,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(QuestionValidationService::class.java)
    private val validationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Validates a single question synchronously.
     * Updates validation_status in DB and returns the new status.
     */
    suspend fun validateQuestion(question: Question): String {
        val questionId = question.id ?: return "FAILED"
        val category = question.category.uppercase()

        // Non-coding questions have no test cases to execute — auto-pass
        if (category !in setOf("CODING", "DSA")) {
            updateValidationStatus(question, "PASSED")
            log.info("""{"event":"QUESTION_VALIDATION_RESULT","question_id":"$questionId","status":"PASSED","tests_passed":0,"tests_total":0}""")
            return "PASSED"
        }

        val testCases = parseTestCases(question.testCases)
        if (testCases.isEmpty()) {
            updateValidationStatus(question, "FAILED")
            log.info("""{"event":"QUESTION_VALIDATION_RESULT","question_id":"$questionId","status":"FAILED","tests_passed":0,"tests_total":0}""")
            return "FAILED"
        }

        val solution = generateReferenceSolution(question) ?: run {
            log.warn("No reference solution generated for question={}", questionId)
            updateValidationStatus(question, "FAILED")
            log.info("""{"event":"QUESTION_VALIDATION_RESULT","question_id":"$questionId","status":"FAILED","tests_passed":0,"tests_total":${testCases.size}}""")
            return "FAILED"
        }

        val results = runTestCases(solution, testCases)
        val passed = results.count { it }
        val total = testCases.size
        val status = if (passed == total && total > 0) "PASSED" else "FAILED"

        updateValidationStatus(question, status)
        log.info("""{"event":"QUESTION_VALIDATION_RESULT","question_id":"$questionId","status":"$status","tests_passed":$passed,"tests_total":$total}""")
        return status
    }

    /**
     * Fire-and-forget wrapper — used by QuestionService after generating a new question.
     */
    fun scheduleValidation(question: Question) {
        validationScope.launch {
            try {
                validateQuestion(question)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                log.warn("Background validation failed for question={}: {}", question.id, e.message)
            }
        }
    }

    /**
     * Runs on startup. Validates all PENDING questions in batches of 5 (non-blocking).
     */
    @EventListener(ApplicationReadyEvent::class)
    fun validatePendingOnStartup() {
        validationScope.launch {
            try {
                val pending = questionRepository.findByValidationStatus("PENDING")
                    .collectList().awaitSingle()
                if (pending.isEmpty()) {
                    log.info("QuestionValidationService: no PENDING questions — startup validation skipped")
                    return@launch
                }
                log.info("QuestionValidationService: validating {} PENDING questions on startup", pending.size)
                pending.chunked(5).forEach { batch ->
                    batch.map { q -> async { validateQuestion(q) } }.awaitAll()
                }
                log.info("QuestionValidationService: startup validation complete")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                log.warn("Startup question validation failed: {}", e.message)
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun generateReferenceSolution(question: Question): String? = try {
        val systemPrompt = """You are an expert algorithm engineer. Write a correct Python3 solution.
RULES:
- Read input from stdin, write output to stdout — no class or function wrappers
- Handle EXACTLY the input format shown in the question's test cases
- Return ONLY the Python3 code — no markdown, no explanation""".trimIndent()

        val userMessage = buildString {
            appendLine("Question: ${question.title}")
            appendLine()
            appendLine(question.description.take(1200))
            if (!question.constraintsText.isNullOrBlank()) {
                appendLine()
                appendLine("Constraints: ${question.constraintsText}")
            }
            appendLine()
            appendLine("Write a Python3 solution that reads from stdin and prints the answer to stdout.")
        }

        val response = llmRegistry.complete(
            LlmRequest.build(
                systemPrompt = systemPrompt,
                userMessage  = userMessage,
                model        = modelConfig.backgroundModel,
                maxTokens    = 800,
                temperature  = 0.2,
            )
        )
        response.content.trim()
            .removePrefix("```python3").removePrefix("```python").removePrefix("```")
            .removeSuffix("```").trim()
            .takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        log.warn("LLM solution generation failed for question={}: {}", question.id, e.message)
        null
    }

    private suspend fun runTestCases(solution: String, testCases: List<TestCaseEntry>): List<Boolean> =
        testCases.map { tc ->
            try {
                val token  = judge0Client.submit(solution, PYTHON3_LANGUAGE_ID, tc.input)
                val result = judge0Client.pollResult(token)
                val actual   = result.stdout?.trim() ?: ""
                val expected = tc.expected?.trim() ?: ""
                result.status?.id == 3 && outputMatches(actual, expected)
            } catch (e: Exception) {
                log.debug("Test case execution error for question: {}", e.message)
                false
            }
        }

    private suspend fun updateValidationStatus(question: Question, status: String) {
        try {
            questionRepository.save(
                question.copy(validationStatus = status, validatedAt = OffsetDateTime.now())
            ).awaitSingle()
        } catch (e: Exception) {
            log.warn("Failed to persist validation status={} for question={}: {}", status, question.id, e.message)
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

    /** Flexible output comparison — mirrors CodeExecutionService.outputMatches(). */
    private fun outputMatches(actual: String?, expected: String): Boolean {
        if (actual == null) return false
        val a = actual.trim()
        val e = expected.trim()
        if (a == e) return true
        val normalize = { s: String ->
            s.removePrefix("[").removeSuffix("]")
                .removePrefix("{").removeSuffix("}")
                .removePrefix("(").removeSuffix(")")
                .split(Regex("[,\\s]+"))
                .map { it.trim().trim('"').trim('\'') }
                .filter { it.isNotBlank() }
                .sorted()
        }
        return normalize(a) == normalize(e)
    }

    private fun JsonNode.textOrNull(): String? =
        if (this.isNull || this.isMissingNode) null else this.asText()

    private data class TestCaseEntry(val input: String?, val expected: String?)

    companion object {
        private const val PYTHON3_LANGUAGE_ID = 71
    }
}
