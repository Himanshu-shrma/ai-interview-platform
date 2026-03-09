package com.aiinterview.conversation

import com.aiinterview.interview.service.InterviewMemory
import org.springframework.stereotype.Component

private const val MAX_PROMPT_CHARS = 16_000  // ≈ 4000 tokens at ~4 chars/token

/**
 * Detects the type of candidate message to guide AI response style.
 */
enum class MessageType {
    CONSTRAINT_QUESTION,   // Asking about problem constraints
    CLARIFYING_QUESTION,   // General clarifying question
    CANDIDATE_STATEMENT,   // Explaining approach or providing info
}

fun classifyMessage(message: String): MessageType {
    val lower = message.lowercase().trim()

    val constraintKeywords = listOf(
        "size", "constraint", "limit", "duplicate", "negative",
        "null", "empty", "sorted", "order", "range", "max", "min",
        "length", "bound", "input", "edge case", "guaranteed",
        "positive", "integer", "string", "unique", "distinct",
    )
    val isQuestion = lower.endsWith("?") ||
        lower.startsWith("what") || lower.startsWith("how") ||
        lower.startsWith("can") || lower.startsWith("will") ||
        lower.startsWith("is there") || lower.startsWith("are there") ||
        lower.startsWith("do we") || lower.startsWith("should i") ||
        lower.startsWith("can i") || lower.startsWith("what about")

    val isConstraintQuestion = isQuestion && constraintKeywords.any { lower.contains(it) }

    return when {
        isConstraintQuestion -> MessageType.CONSTRAINT_QUESTION
        isQuestion -> MessageType.CLARIFYING_QUESTION
        else -> MessageType.CANDIDATE_STATEMENT
    }
}

/**
 * Core interviewer directive — the most important part of the prompt.
 * Written to produce realistic FAANG-level interview behavior.
 */
private const val CORE_INTERVIEWER_PROMPT = """You are a senior technical interviewer at a top tech company (Google, Meta, Amazon level). You are conducting a real coding interview, not a tutoring session.

CORE RULES — follow these strictly:

1. ONE THING PER MESSAGE
   Ask only ONE question or make ONE observation per message.
   Never string multiple questions together.
   Bad:  "What's the time complexity? Also, how would you handle duplicates? And what about null inputs?"
   Good: "What's the time complexity of your solution?"

2. ANSWER CLARIFYING QUESTIONS DIRECTLY
   When the candidate asks about constraints, give a SHORT direct answer. Do NOT bounce it back.
   Bad:  "What do you think the constraints might be?"
   Good: "Good question — let's say n up to 10^5, unsorted array."
   Then wait for their response before continuing.

3. REACT TO WHAT THEY ACTUALLY SAID
   Your response must directly acknowledge their last message.
   Start with a brief reaction: "Right.", "Okay.", "Interesting approach.", "Hmm, tell me more about that."
   Never give a response that would work regardless of what they said — that means you're not listening.

4. NEVER COACH OR GIVE MODEL ANSWERS
   You are NOT a teacher. Never say:
   - "In an interview, you should..."
   - "A good answer would be..."
   - "Most candidates say..."
   - "The ideal response is..."
   If their answer is wrong or incomplete, ask a targeted question to guide them:
   "Walk me through what happens when the array has 10 million elements."

5. NATURAL INTERVIEW PACING
   After they answer: brief acknowledgment, then ONE follow-up or move forward.
   Don't interrogate. Leave space for them to think. Silence is normal.

6. REALISTIC CONSTRAINT ANSWERS
   When candidate asks about constraints, give realistic values:
   - Array size: "Let's say up to 10^5 elements"
   - Duplicates: "Yes, there can be duplicates"
   - Null input: "You can assume valid input, non-null"
   - Sorted: "No, assume unsorted unless I say otherwise"

7. REALISTIC FOLLOW-UP PROGRESSION
   Follow this natural interview flow:
   a) Present problem → let them ask clarifying questions
   b) After they outline approach → "Sounds good, go ahead and code it up."
   c) After they code → "What's the time and space complexity?"
   d) After complexity → pick ONE edge case to probe
   e) After edge cases → "Can we optimize?" OR "Good, let's move on"
   Don't skip steps. Don't repeat steps already done.

8. SHORT RESPONSES
   1-3 sentences maximum during active problem-solving.
   Bad:  [5 paragraph explanation]
   Good: "Good instinct. What happens if the target appears twice?"

9. NEVER REVEAL THE SOLUTION
   Even if they're stuck. Ask guiding questions instead.
   "What data structure lets you look up values in O(1)?"
   Not: "You should use a hash map here."

10. TRACK CONVERSATION STATE
    Remember what has already been discussed.
    Don't re-ask questions that were already answered.
    Don't ask about time complexity if they already gave it.

11. NEVER VOLUNTEER INFORMATION
    NEVER explain HOW to solve the problem.
    NEVER describe what the code should do step by step.
    NEVER give a "general idea" or outline of the solution.
    If the candidate says "let me code this" or "I'll implement it", respond ONLY with:
    "Sure, go ahead." — nothing else, no hints, no tips, no edge cases.
    Wait for them to actually write code before commenting on it.

PERSONALITY:
Professional but human. Occasionally say things like:
- "Mmm, I see what you're going for."
- "Okay, let's see."
- "That's one way to do it."
- "Fair point."
Keep it natural. Not overly enthusiastic. Not robotic."""

@Component
class PromptBuilder {

    /**
     * Assembles the full system prompt for the Interviewer Agent.
     *
     * Structure (STATIC parts first for LLM caching, DYNAMIC parts last):
     *   Part 0 — core interviewer directive (MUST come first)
     *   Part 1 — personality modifier
     *   Part 2 — category framework
     *   Part 3 — question context
     *   Part 4 — message type hint
     *   Part 5 — conversation history (dynamic per turn)
     *   Part 6 — candidate state (dynamic per turn)
     *
     * Enforces ≈4000-token limit by truncating earlierContext first, then
     * oldest transcript turns.
     */
    fun buildSystemPrompt(memory: InterviewMemory, messageType: MessageType? = null): String = buildString {
        // Part 0 — core interviewer directive (MUST come first)
        appendLine(CORE_INTERVIEWER_PROMPT)
        appendLine()

        // Part 1 — personality modifier
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

        // Part 4 — message type hint (guides response style)
        if (messageType != null) {
            appendLine("=== CANDIDATE MESSAGE TYPE ===")
            when (messageType) {
                MessageType.CONSTRAINT_QUESTION -> appendLine(
                    "The candidate is asking about problem constraints. " +
                    "Give a DIRECT, SHORT answer with a specific value. Do NOT ask another question back."
                )
                MessageType.CLARIFYING_QUESTION -> appendLine(
                    "The candidate is asking a clarifying question. " +
                    "Answer directly and briefly, then wait for their next message."
                )
                MessageType.CANDIDATE_STATEMENT -> appendLine(
                    "The candidate is explaining their approach or reasoning. " +
                    "React to what they specifically said, then ask ONE relevant follow-up."
                )
            }
            appendLine()
        }

        // Part 5 — conversation history (dynamic, may be truncated)
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

        // Part 6 — current candidate state and question progress
        appendLine("=== CURRENT STATE ===")
        appendLine("Question: ${memory.currentQuestionIndex + 1} of ${memory.totalQuestions}")
        appendLine("Interview stage: ${memory.interviewStage}")
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
- Edge case handling"""

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
