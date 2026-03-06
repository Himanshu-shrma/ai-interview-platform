package com.aiinterview.conversation

import com.aiinterview.interview.service.InterviewMemory
import org.springframework.stereotype.Component

private const val MAX_PROMPT_CHARS = 16_000  // ≈ 4000 tokens at ~4 chars/token

@Component
class PromptBuilder {

    /**
     * Assembles the full system prompt for the Interviewer Agent.
     *
     * Structure (STATIC parts first for LLM caching, DYNAMIC parts last):
     *   Part 1 — personality (static per session)
     *   Part 2 — category framework (static per category)
     *   Part 3 — question context (changes if question changes)
     *   Part 4 — conversation history (dynamic per turn)
     *   Part 5 — candidate state (dynamic per turn)
     *
     * Enforces ≈4000-token limit by truncating earlierContext first, then
     * oldest transcript turns.
     */
    fun buildSystemPrompt(memory: InterviewMemory): String = buildString {
        // Part 1 — personality
        appendLine(personalityPrompt(memory.personality))
        appendLine()

        // Part 2 — category framework
        appendLine(categoryFramework(memory.category))
        appendLine()

        // Part 3 — question context
        val q = memory.currentQuestion
        if (q != null) {
            appendLine("=== CURRENT QUESTION ===")
            appendLine("Title: ${q.title}")
            appendLine("Description: ${q.description}")
            if (!q.optimalApproach.isNullOrBlank()) {
                appendLine("Optimal approach (your reference only — DO NOT reveal to candidate): ${q.optimalApproach}")
            }
            appendLine()
        }

        // Part 4 — conversation history (dynamic, may be truncated)
        val transcript = memory.rollingTranscript
        val earlier = memory.earlierContext

        if (earlier.isNotBlank() || transcript.isNotEmpty()) {
            appendLine("=== CONVERSATION HISTORY ===")
            if (earlier.isNotBlank()) {
                appendLine("Earlier in this interview: $earlier")
                appendLine()
            }
            transcript.forEach { turn ->
                val label = if (turn.role == "AI") "Interviewer" else "Candidate"
                appendLine("$label: ${turn.content}")
            }
            appendLine()
        }

        // Part 5 — current candidate state
        appendLine("=== CURRENT STATE ===")
        memory.candidateAnalysis?.let { analysis ->
            if (!analysis.approach.isNullOrBlank()) appendLine("Candidate approach: ${analysis.approach}")
            if (!analysis.confidence.isNullOrBlank()) appendLine("Confidence level: ${analysis.confidence}")
            if (!analysis.correctness.isNullOrBlank()) appendLine("Correctness: ${analysis.correctness}")
            if (analysis.gaps.isNotEmpty()) appendLine("Identified gaps: ${analysis.gaps.joinToString(", ")}")
        }
        appendLine("Hints given: ${memory.hintsGiven}/3")
        if (!memory.currentCode.isNullOrBlank()) {
            appendLine("Current code (${memory.programmingLanguage ?: "unknown"}):")
            appendLine("```")
            appendLine(memory.currentCode)
            appendLine("```")
        }
    }.let { prompt ->
        truncateIfNeeded(prompt, memory)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun categoryFramework(category: String): String = when (category.uppercase()) {
        "CODING", "DSA" ->
            """=== INTERVIEW FRAMEWORK: CODING ===
This is a coding interview. Evaluate the candidate on:
- Problem understanding and clarifying questions
- Algorithm choice and rationale
- Code correctness and completeness
- Time and space complexity analysis
- Edge case handling
Keep the conversation focused on technical depth. Ask follow-up questions about complexity."""

        "BEHAVIORAL" ->
            """=== INTERVIEW FRAMEWORK: BEHAVIORAL ===
This is a behavioral interview using the STAR method (Situation, Task, Action, Result).
Evaluate: situation clarity, specific actions taken, measurable results achieved, leadership and growth demonstrated.
Prompt candidates who are vague to provide concrete examples."""

        "SYSTEM_DESIGN" ->
            """=== INTERVIEW FRAMEWORK: SYSTEM DESIGN ===
This is a system design interview. Evaluate:
- Requirements clarification (functional and non-functional)
- High-level architecture and component selection
- Data modeling and storage decisions
- Scalability, reliability, and trade-offs
- Deep-dive into specific components
Start broad, then drive toward specific areas."""

        else ->
            """=== INTERVIEW FRAMEWORK: CODING ===
Evaluate problem understanding, algorithm choice, code correctness, and complexity analysis."""
    }

    /**
     * Truncates the prompt to stay within MAX_PROMPT_CHARS.
     * Strategy: truncate earlierContext first, then drop oldest transcript turns.
     */
    private fun truncateIfNeeded(prompt: String, memory: InterviewMemory): String {
        if (prompt.length <= MAX_PROMPT_CHARS) return prompt

        // Rebuild with truncated earlierContext
        val truncatedEarlier = memory.earlierContext.take(500)
        val rebuiltMemory = memory.copy(earlierContext = truncatedEarlier)
        val rebuilt = buildSystemPrompt(rebuiltMemory)
        if (rebuilt.length <= MAX_PROMPT_CHARS) return rebuilt

        // If still too long, drop oldest transcript turns one by one
        var dropCount = 1
        while (dropCount < memory.rollingTranscript.size) {
            val trimmed = memory.copy(
                earlierContext    = truncatedEarlier,
                rollingTranscript = memory.rollingTranscript.drop(dropCount),
            )
            val attempt = buildSystemPrompt(trimmed)
            if (attempt.length <= MAX_PROMPT_CHARS) return attempt
            dropCount++
        }

        // Last resort: hard truncate
        return prompt.take(MAX_PROMPT_CHARS)
    }
}
