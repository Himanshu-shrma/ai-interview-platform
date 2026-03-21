package com.aiinterview.conversation.objectives

import com.aiinterview.interview.service.InterviewMemory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Minimal safety net — 4 rules only.
 * Returns a pendingProbe string if intervention is needed, null otherwise.
 * Never forces a message directly — only sets a probe for the AI to use.
 */
@Component
class FlowGuard {
    private val log = LoggerFactory.getLogger(FlowGuard::class.java)

    fun check(
        sessionId: UUID,
        memory: InterviewMemory,
        state: ObjectiveState,
    ): String? {
        val completed = memory.completedObjectives
        val turnCount = memory.turnCount

        // RULE 1: Problem must be presented by turn 4 (coding/design types)
        val needsProblem = memory.category.uppercase() in setOf("CODING", "DSA", "SYSTEM_DESIGN")
        if (needsProblem && "problem_presented" !in completed && turnCount >= 4) {
            log.warn("FlowGuard: problem not presented by turn 4, session={}", sessionId)
            return "URGENT: Present the problem now. You have been in small talk too long."
        }

        // RULE 2: Wrap up if overtime
        if (state.remainingMinutes <= 0 && "wrap_up" !in completed) {
            return "TIME IS UP. End the interview now. Wrap up professionally."
        }

        // RULE 3: Warn if behind schedule
        if (state.isBehindSchedule && state.nextObjective != null) {
            return "PACE WARNING: Move faster. Focus on: ${state.nextObjective.description}."
        }

        // RULE 4: Nudge if stalled on same objective for 8+ turns
        if (memory.stalledTurnCount >= 8 && state.nextObjective != null) {
            return "STALLED: Move toward ${state.nextObjective.description}. Ask a direct question."
        }

        return null
    }
}
