package com.aiinterview.conversation

import com.aiinterview.interview.service.RedisMemoryService
import org.springframework.stereotype.Component
import java.util.UUID

data class ToolContext(
    val stateContext: StateContext,
    val codeDetails: String?,
    val testResultSummary: String?,
)

/**
 * Stage-specific pre-fetching before LLM call (Phase 3).
 * Fetches exactly what this stage needs — no more, no less.
 */
@Component
class ToolContextService(
    private val stateContextBuilder: StateContextBuilder,
    private val redisMemoryService: RedisMemoryService,
) {
    suspend fun fetchForStage(sessionId: UUID, stage: String): ToolContext {
        val base = stateContextBuilder.build(sessionId)
        val isCodingInterview = base.category.uppercase() in setOf("CODING", "DSA")

        return when {
            // CODING stage — only relevant for coding interviews
            stage == "CODING" && isCodingInterview -> ToolContext(
                stateContext = base,
                codeDetails = null,     // AI should be silent during coding
                testResultSummary = null,
            )

            // REVIEW stage — only fetch code/tests for coding interviews
            stage == "REVIEW" && isCodingInterview -> {
                val memory = redisMemoryService.getMemory(sessionId)
                val code = memory.currentCode?.take(2000)

                val testSummary = memory.lastTestResult?.let { tr ->
                    buildString {
                        append("${tr.passed}/${tr.total} passing")
                        if (tr.passed < tr.total) {
                            tr.failedCases?.take(2)?.let { cases ->
                                append(" | Failing: ")
                                append(cases.joinToString("; ") { c ->
                                    "input=${c.input} expected=${c.expected}"
                                })
                            }
                        }
                    }
                }

                ToolContext(
                    stateContext = base,
                    codeDetails = code,
                    testResultSummary = testSummary,
                )
            }

            // APPROACH stage — only show code status for coding interviews
            stage == "APPROACH" && isCodingInterview -> ToolContext(
                stateContext = base,
                codeDetails = if (base.hasMeaningfulCode)
                    "CODE EXISTS (${base.codeLineCount} lines of ${base.codeLanguage ?: "unknown"})"
                else "EDITOR IS EMPTY",
                testResultSummary = null,
            )

            // All other stages / non-coding interview types — no code context
            else -> ToolContext(
                stateContext = base,
                codeDetails = null,
                testResultSummary = null,
            )
        }
    }
}
