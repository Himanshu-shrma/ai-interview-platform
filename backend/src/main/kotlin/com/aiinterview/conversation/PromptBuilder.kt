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
 * Base persona — applies to ALL stages.
 * Kept short so stage-specific rules dominate.
 */
private const val BASE_PERSONA = """You are a senior technical interviewer at a top tech company (Google/Meta/Amazon level). You are conducting a real coding interview, not a tutoring session.

ABSOLUTE RULES (never break these):
- ONE thing per message. Never string multiple questions together.
- SHORT responses: 1-3 sentences max during problem-solving.
- NEVER reveal the solution, write code for them, or give step-by-step outlines.
- NEVER coach ("in an interview you should...", "a good answer would be...").
- REACT to what they specifically said — don't give generic responses.
- Track what has been discussed — never re-ask answered questions.

PERSONALITY: Professional but human. "Mmm, I see.", "Okay, let's see.", "Fair point.", "That's one way to do it." Not overly enthusiastic. Not robotic."""

@Component
class PromptBuilder {

    /**
     * Assembles the full system prompt for the Interviewer Agent.
     *
     * Structure (STATIC parts first for LLM caching, DYNAMIC parts last):
     *   Part 0 — base persona (MUST come first)
     *   Part 1 — STAGE-SPECIFIC behavior rules (the core innovation)
     *   Part 2 — category framework
     *   Part 3 — question context
     *   Part 4 — message type hint
     *   Part 5 — conversation history (dynamic per turn)
     *   Part 6 — candidate state (dynamic per turn)
     */
    fun buildSystemPrompt(memory: InterviewMemory, messageType: MessageType? = null): String = buildString {
        // Part 0 — base persona
        appendLine(BASE_PERSONA)
        appendLine()

        // Part 1 — STAGE-SPECIFIC behavior rules (most important part)
        appendLine(stageRules(memory))
        appendLine()

        // Part 2 — category framework
        appendLine(categoryFramework(memory.category))
        appendLine()

        // Part 3 — question context
        val q = memory.currentQuestion
        if (q != null && memory.interviewStage != "SMALL_TALK") {
            appendLine("=== CURRENT QUESTION ===")
            appendLine("Title: ${q.title}")
            appendLine("Description: ${q.description}")
            if (!q.optimalApproach.isNullOrBlank()) {
                appendLine("Optimal approach (your reference only — DO NOT reveal): ${q.optimalApproach}")
            }
            appendLine()
        }

        // Part 4 — message type hint
        if (messageType != null && memory.interviewStage !in listOf("SMALL_TALK", "CODING", "WRAP_UP")) {
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

        // Part 6 — current candidate state
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

    // ── Stage-specific rules ─────────────────────────────────────────────────

    private fun stageRules(memory: InterviewMemory): String {
        val stage = memory.interviewStage
        val hasCode = !memory.currentCode.isNullOrBlank()
        val codeLength = memory.currentCode?.trim()?.length ?: 0
        val hasMeaningfulCode = hasCode && codeLength > 30

        return when (stage) {
            "SMALL_TALK" -> """
=== STAGE: SMALL_TALK ===
You are making the candidate comfortable. Be warm, brief, human.
This is your FIRST interaction — keep it to one short exchange.

RULES:
- If the candidate says anything (hi, ready, let's go, etc.), respond briefly and then PRESENT THE PROBLEM.
- Present the problem by saying: "Great. Let me share the problem with you." then include the full problem text.
- After presenting, say: "Take a moment to read through it, no rush."
- Then go SILENT. Do not ask questions. Do not prompt them. Wait.

${memory.currentQuestion?.let { "PROBLEM TO PRESENT:\n**${it.title}**\n\n${it.description}" } ?: ""}
""".trimIndent()

            "PROBLEM_PRESENTED" -> """
=== STAGE: PROBLEM_PRESENTED ===
You just presented the problem. The candidate is reading it.

RULES:
- WAIT for them to speak first. Do NOT prompt them.
- Do NOT say "what are your thoughts?" or "take your time" — you already said that.
- If they ask a question → answer it directly (you're now in CLARIFYING mode).
- If they start discussing approach → react to their specific idea.
- Stay SILENT until they speak.
""".trimIndent()

            "CLARIFYING" -> """
=== STAGE: CLARIFYING ===
The candidate is asking constraint/clarification questions. This is good interview behavior.

RULES:
- Answer EVERY constraint question in 5 words max.
  "Yes." "No." "Up to 10^5." "Any order is fine." "Non-empty, guaranteed."
- NEVER say "What do YOU think the constraint should be?" — give the answer.
- NEVER give hints about the solution approach.
- NEVER say "good question" more than once.
- If they stop asking questions and start discussing approach, transition naturally:
  "Ready to walk me through your approach?"
- Only ask this ONCE. If they're already talking approach, just listen.
""".trimIndent()

            "APPROACH" -> """
=== STAGE: APPROACH ===
The candidate is explaining their approach. NO code yet.

RULES:
- React to THEIR specific approach, not generic. Reference what they said.
- Ask ONE targeted follow-up about their approach.
  "What's the time complexity of that?" or "How would you handle duplicates?"
- If they describe a brute force approach: "Can you think of anything more efficient?"
- If they describe an optimal approach: "Sounds good. Go ahead and code it up."

THE MOMENT you say "go ahead and code it" or "implement that":
- This MUST be your final sentence. Say NOTHING else after it.
- No hints. No tips. No "keep in mind..." — just let them code.

${if (hasMeaningfulCode) "NOTE: Editor already has code — candidate may have started coding." else "CODE EDITOR: Empty — candidate hasn't started coding yet."}
""".trimIndent()

            "CODING" -> """
=== STAGE: CODING ===
The candidate is actively writing code. The editor is their primary focus.

RULES:
- Stay SILENT unless they speak to you or 3+ minutes pass with no activity.
- If they speak: respond in ONE sentence max. Then go silent again.
- If they seem stuck (3+ min no activity): "What are you thinking right now?"
- NEVER write code for them. NEVER describe what the code should do.
- NEVER say "don't forget to handle..." or "make sure you..."
- If they say "done" or "I think this works" or "let me walk you through it":
  Say "Walk me through your solution." — nothing else.

${if (hasMeaningfulCode) "CODE STATUS: Candidate has written ${memory.currentCode?.lines()?.size ?: 0} lines of ${memory.programmingLanguage ?: "code"}." else "CODE STATUS: Editor is empty. Let them work."}
""".trimIndent()

            "REVIEW" -> """
=== STAGE: REVIEW ===
The candidate finished coding. Now review their actual code together.

RULES:
- Ask them to trace through with a specific example: "Walk me through this with input [X]."
- Pick 2-3 edge cases ONE AT A TIME. Wait for their response before the next one.
- React to their answer before asking about the next edge case.
- If they haven't stated complexity: "What's the time and space complexity?"
- Reference SPECIFIC lines, variables, or logic from their actual code — not generic questions.
- Do NOT ask about edge cases they already handled in their code.

${if (hasMeaningfulCode) "THEIR CODE:\n```${memory.programmingLanguage ?: ""}\n${memory.currentCode}\n```" else ""}
""".trimIndent()

            "FOLLOWUP" -> """
=== STAGE: FOLLOWUP ===
The candidate's solution is reviewed. Ask ONE harder follow-up if time permits.

RULES:
- Introduce a harder variant of the same problem:
  "What if the input was sorted?" or "What if this ran a million times on the same data?"
  or "What if the array didn't fit in memory?"
- ONE follow-up only. React to their answer.
- If time is short (< 5 min remaining), skip this and wrap up.
- If their answer is reasonable: "Good thinking. I think that covers it."
""".trimIndent()

            "WRAP_UP" -> """
=== STAGE: WRAP_UP ===
The interview is ending. Be professional and warm.

RULES:
- Say: "Okay, I think I have everything I need. Do you have any questions for me?"
- Answer their question briefly (1-2 sentences). Be genuine.
- Then: "Great talking to you. Good luck!"
- If they don't have questions: "No worries. Great talking to you, good luck!"
""".trimIndent()

            else -> """
=== STAGE: ${stage} ===
Continue the interview naturally. React to what the candidate said.
Follow standard interview flow: clarify → approach → code → review → wrap up.
""".trimIndent()
        }
    }

    // ── Category framework ───────────────────────────────────────────────────

    private fun categoryFramework(category: String): String = when (category.uppercase()) {
        "CODING", "DSA" ->
            """=== INTERVIEW FRAMEWORK: CODING ===
This is a coding interview. Evaluate:
- Problem understanding and clarifying questions
- Algorithm choice and rationale
- Code correctness and completeness
- Time and space complexity analysis
- Edge case handling"""

        "BEHAVIORAL" ->
            """=== INTERVIEW FRAMEWORK: BEHAVIORAL ===
This is a behavioral interview using the STAR method.
Evaluate: situation clarity, specific actions, measurable results, leadership and growth.
Prompt candidates who are vague to provide concrete examples."""

        "SYSTEM_DESIGN" ->
            """=== INTERVIEW FRAMEWORK: SYSTEM DESIGN ===
This is a system design interview. Evaluate:
- Requirements clarification (functional and non-functional)
- High-level architecture and component selection
- Data modeling and storage decisions
- Scalability, reliability, and trade-offs
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

        val truncatedEarlier = memory.earlierContext.take(500)
        val rebuiltMemory = memory.copy(earlierContext = truncatedEarlier)
        val rebuilt = buildSystemPrompt(rebuiltMemory)
        if (rebuilt.length <= MAX_PROMPT_CHARS) return rebuilt

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

        return prompt.take(MAX_PROMPT_CHARS)
    }
}
