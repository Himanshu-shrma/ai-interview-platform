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
    fun buildSystemPrompt(
        memory: InterviewMemory,
        messageType: MessageType? = null,
        stateCtx: StateContext? = null,
        codeDetails: String? = null,
        testResultSummary: String? = null,
    ): String = buildString {
        // Part 0 — base persona
        appendLine(BASE_PERSONA)
        appendLine()

        // Part 0.5 — personality rules (before stage rules so they color everything)
        appendLine(personalityRules(memory.personality))
        appendLine()

        // Part 1 — STAGE-SPECIFIC behavior rules (most important part)
        appendLine(stageRules(memory))
        appendLine()

        // Part 2 — category framework
        appendLine(categoryFramework(memory.category))
        appendLine()

        // Part 2.5 — company-specific style
        memory.targetCompany?.let { company ->
            if (company.isNotBlank()) {
                appendLine(companyStyle(company))
                appendLine()
            }
        }

        // Part 2.6 — candidate context (experience level + background)
        candidateContext(memory)?.let { ctx ->
            appendLine(ctx)
            appendLine()
        }

        // Part 2.7 — LIVE STATE BLOCK (Phase 1 — fresh from StateContextBuilder)
        stateCtx?.let { sc ->
            appendLine(buildStateBlock(sc))
            appendLine()
        }

        // Part 2.8 — Code details from ToolContextService (Phase 3)
        codeDetails?.let { cd ->
            appendLine("=== CANDIDATE'S CODE ===")
            appendLine("```")
            appendLine(cd)
            appendLine("```")
            appendLine()
        }

        // Part 2.9 — Test results if available (Phase 3)
        testResultSummary?.let { tr ->
            appendLine("=== TEST RESULTS ===")
            appendLine(tr)
            appendLine()
        }

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

            "APPROACH" -> {
                val codeContext = if (!hasMeaningfulCode) """
CRITICAL — CODE EDITOR STATUS: EMPTY
The candidate has NOT written any code yet.

YOUR ONLY VALID ACTIONS:
- React to their approach explanation
- Ask ONE clarifying question about approach
- If approach is good: say "Go ahead and code it."

YOU MUST NOT:
- Ask about time complexity (no code exists)
- Ask about space complexity (no code exists)
- Ask about edge cases (no code to test)
- Ask more than ONE question

The moment you say "go ahead and code it" or "let's see it in code", transition to CODING stage.
""" else """
CODE EXISTS in editor.
You may now discuss complexity and edge cases.
"""
                """
=== STAGE: APPROACH ===
The candidate is explaining their approach.

$codeContext

RULES:
- React to THEIR specific approach, not generic. Reference what they said.
- If they describe a brute force approach: "Can you think of anything more efficient?"
- If they describe an optimal approach: "Sounds good. Go ahead and code it up."

THE MOMENT you say "go ahead and code it" or "implement that":
- This MUST be your final sentence. Say NOTHING else after it.
- No hints. No tips. No "keep in mind..." — just let them code.
""".trimIndent()
            }

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
            """=== INTERVIEW FRAMEWORK: BEHAVIORAL (STAR METHOD) ===
This is a behavioral interview. You must evaluate using the STAR framework:

STRUCTURE YOUR QUESTIONING:
1. Ask about a SITUATION: "Tell me about a time when..."
2. Probe for TASK: "What was your specific role/responsibility?"
3. Dig into ACTION: "Walk me through exactly what YOU did."
4. Demand RESULT: "What was the measurable outcome?"

DOMAINS TO COVER (pick 2-3 naturally):
- Leadership & Influence: Leading without authority, driving alignment
- Conflict Resolution: Disagreements, trade-offs, difficult conversations
- Technical Challenges: Hardest bug, system failure, architecture decision
- Growth & Learning: Failure stories, feedback received, skill development
- Collaboration: Cross-team work, mentoring, knowledge sharing

BEHAVIORAL INTERVIEW RULES:
- If they give vague answers ("we did X"), redirect: "What was YOUR specific contribution?"
- If they skip the result, ask: "What was the measurable outcome?"
- If the story is too short, probe deeper: "Walk me through the decision-making process."
- Cover at least 2 different domains across the interview.
- NEVER accept hypothetical answers — demand real experiences."""

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

    // ── Personality rules ────────────────────────────────────────────────────

    private fun personalityRules(personality: String): String = when (personality.uppercase()) {
        "FAANG_SENIOR" -> """
=== PERSONALITY: FAANG SENIOR ===
You are direct and efficient. Time is valuable.
- Skip pleasantries after the initial greeting.
- Ask precise, targeted questions. No hand-holding.
- Push back on suboptimal approaches: "That works, but can you do better?"
- Expect candidates to drive the conversation.
- Use phrases like: "What's the complexity?", "Edge cases?", "Can you optimize that?"
""".trimIndent()

        "FRIENDLY_MENTOR", "FRIENDLY" -> """
=== PERSONALITY: FRIENDLY MENTOR ===
You are warm, encouraging, and supportive — but still evaluating.
- Use encouraging language: "That's a great start!", "I like where you're going."
- Give gentle nudges when stuck: "What if you thought about it from the perspective of..."
- Celebrate small wins: "Nice catch on that edge case."
- Still maintain interview rigor — don't give away answers.
""".trimIndent()

        "STARTUP_ENGINEER", "STARTUP" -> """
=== PERSONALITY: STARTUP ENGINEER ===
You care about pragmatism and shipping.
- Focus on practical trade-offs: "Would this work at scale?"
- Value speed of implementation alongside correctness.
- Ask about real-world constraints: "What if we had limited time?"
- Use casual language: "Cool", "Makes sense", "Ship it"
""".trimIndent()

        "ADAPTIVE" -> """
=== PERSONALITY: ADAPTIVE ===
Match the candidate's energy and level.
- If they're nervous, be warmer and more encouraging.
- If they're confident, be more challenging.
- If they're struggling, offer slightly more guidance.
- If they're excelling, raise the bar.
""".trimIndent()

        else -> ""
    }

    // ── Company-specific style ──────────────────────────────────────────────

    private fun companyStyle(company: String): String {
        val companyLower = company.lowercase()
        val style = when {
            companyLower.contains("google") -> """
Focus on algorithmic thinking and scalability.
Emphasize: "How would this work with billions of records?"
Expect optimal solutions with clear complexity analysis.
Value clean, readable code over clever tricks."""

            companyLower.contains("meta") || companyLower.contains("facebook") -> """
Emphasis on move fast, practical solutions.
Value: working code quickly, then optimize.
Ask about product impact: "How would this affect the user experience?"
Expect strong coding fundamentals."""

            companyLower.contains("amazon") -> """
Leadership principles matter. Tie technical decisions to customer impact.
Ask: "How does this scale?" and "What are the failure modes?"
Value operational excellence and ownership.
Expect candidates to discuss trade-offs explicitly."""

            companyLower.contains("microsoft") -> """
Value thorough problem analysis and clean design.
Ask about extensibility: "How would you modify this for a new requirement?"
Expect clear communication and collaborative problem-solving."""

            companyLower.contains("apple") -> """
Attention to detail matters. Expect polished solutions.
Ask about edge cases meticulously.
Value simplicity and elegance in design."""

            else -> "Tailor questions to the style expected at ${company}."
        }
        return "=== TARGET COMPANY: ${company.uppercase()} ===\n$style"
    }

    // ── Candidate context ───────────────────────────────────────────────────

    private fun candidateContext(memory: InterviewMemory): String? {
        val parts = mutableListOf<String>()
        memory.experienceLevel?.let { level ->
            if (level.isNotBlank()) parts.add("Experience level: $level")
        }
        memory.background?.let { bg ->
            if (bg.isNotBlank()) parts.add("Background: $bg")
        }
        memory.targetRole?.let { role ->
            if (role.isNotBlank()) parts.add("Target role: $role")
        }
        if (parts.isEmpty()) return null
        return "=== CANDIDATE CONTEXT ===\n${parts.joinToString("\n")}\n" +
            "Adjust your question difficulty and expectations to match their level. " +
            "Do NOT mention that you know their background — use it to calibrate silently."
    }

    // ── Live state block (Phase 1) ─────────────────────────────────────────

    fun buildStateBlock(ctx: StateContext): String = buildString {
        appendLine("=== LIVE INTERVIEW STATE ===")

        // Time
        appendLine("TIME: ${ctx.remainingMinutes} min remaining")
        when {
            ctx.isOvertime -> appendLine("OVERTIME — end the interview NOW")
            ctx.shouldWrapUp -> appendLine("5 MIN LEFT — begin wrapping up")
        }

        // Code — THE KEY FIX
        appendLine()
        appendLine("CODE EDITOR:")
        when {
            ctx.isOvertime -> appendLine("-> Irrelevant, interview ending")
            !ctx.hasMeaningfulCode -> {
                appendLine("-> EMPTY — candidate has not written real code")
                appendLine("-> RULE: Do NOT ask about time complexity")
                appendLine("-> RULE: Do NOT ask about space complexity")
                appendLine("-> RULE: Do NOT ask about edge cases")
                appendLine("-> RULE: Do NOT say 'your solution'")
                appendLine("-> If approach explained: say 'go ahead and code it'")
            }
            else -> {
                appendLine("-> HAS CODE: ${ctx.codeLineCount} lines (${ctx.codeLanguage ?: "unknown"})")
                if (ctx.testsPassed != null && ctx.testsTotal != null) {
                    appendLine("-> TESTS: ${ctx.testsPassed}/${ctx.testsTotal} passing")
                    if (ctx.testsPassed < ctx.testsTotal) {
                        appendLine("-> Bug exists — do NOT reveal which test fails")
                        appendLine("-> Ask them to trace through a failing case")
                    }
                }
            }
        }

        // Checklist
        appendLine()
        appendLine("CHECKLIST:")
        appendLine("-> Complexity discussed: ${ctx.complexityDiscussed}")
        appendLine("-> Edge cases covered: ${ctx.edgeCasesCovered}")
        appendLine("-> Hints given: ${ctx.hintsGiven}")

        // Agent notes
        if (ctx.agentNotes.isNotBlank()) {
            appendLine()
            appendLine("YOUR NOTES ABOUT THIS CANDIDATE:")
            appendLine(ctx.agentNotes)
        }

        // Company context
        ctx.targetCompany?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine("TARGET COMPANY: $it — calibrate bar accordingly")
        }

        appendLine("=============================")
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
