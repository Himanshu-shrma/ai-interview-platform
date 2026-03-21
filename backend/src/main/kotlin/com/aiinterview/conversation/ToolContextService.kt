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

        return when (stage) {
            "CODING" -> ToolContext(
                stateContext = base,
                codeDetails = null,     // AI should be silent during coding
                testResultSummary = null,
            )

            "REVIEW" -> {
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

            "APPROACH" -> ToolContext(
                stateContext = base,
                codeDetails = if (base.hasMeaningfulCode)
                    "CODE EXISTS (${base.codeLineCount} lines of ${base.codeLanguage ?: "unknown"})"
                else "EDITOR IS EMPTY",
                testResultSummary = null,
            )

            else -> ToolContext(
                stateContext = base,
                codeDetails = null,
                testResultSummary = null,
            )
        }
    }
}
