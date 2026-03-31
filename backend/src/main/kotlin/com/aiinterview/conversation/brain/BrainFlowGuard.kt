package com.aiinterview.conversation.brain

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Minimal safety net — exactly 4 rules.
 * Returns null when all is fine.
 * Returns IntendedAction when intervention is needed.
 * Never sends messages directly — only injects into ActionQueue.
 *
 * This is the brain-based replacement for objectives/FlowGuard.
 * The old FlowGuard is kept for backward compatibility.
 */
@Component
class BrainFlowGuard {
    private val log = LoggerFactory.getLogger(BrainFlowGuard::class.java)

    fun check(brain: InterviewerBrain, state: InterviewState): IntendedAction? {
        val completed = brain.interviewGoals.completed
        val turn = brain.turnCount
        val needsProblem = brain.interviewType.uppercase() in setOf("CODING", "DSA", "SYSTEM_DESIGN")

        // RULE 1: Problem must be shared by turn 4
        if (needsProblem && "problem_shared" !in completed && turn >= 4) {
            log.warn("BrainFlowGuard: problem not presented by turn 4, session={}", brain.sessionId)
            return IntendedAction(
                id = "fg_problem_$turn",
                type = ActionType.ADVANCE_GOAL,
                description = "URGENT: Present the problem NOW. Share the complete problem statement immediately.",
                priority = 1,
                expiresAfterTurn = turn + 1,
                source = ActionSource.FLOW_GUARD,
            )
        }

        // RULE 2: Force wrap-up when overtime
        if (state.remainingMinutes <= 0 && "interview_closed" !in completed) {
            return IntendedAction(
                id = "fg_overtime_$turn",
                type = ActionType.END_INTERVIEW,
                description = "TIME IS UP. Close the interview now. Wrap up professionally.",
                priority = 1,
                expiresAfterTurn = turn + 2,
                source = ActionSource.FLOW_GUARD,
            )
        }

        // RULE 3: Nudge if stalled 8+ attempts on same goal
        state.nextObjective?.let { nextGoal ->
            val attempts = brain.interviewGoals.failedAttempts[nextGoal.id] ?: 0
            if (attempts >= 8) {
                return IntendedAction(
                    id = "fg_stall_$turn",
                    type = ActionType.ADVANCE_GOAL,
                    description = "STALLED on '${nextGoal.description}'. Ask ONE direct question to unlock this.",
                    priority = 2,
                    expiresAfterTurn = turn + 3,
                    source = ActionSource.FLOW_GUARD,
                )
            }
        }

        // RULE 4: Pace warning when behind schedule
        if (state.isBehindSchedule && state.remainingRequired.size > 1) {
            return IntendedAction(
                id = "fg_pace_$turn",
                type = ActionType.ADVANCE_GOAL,
                description = "BEHIND SCHEDULE: Be direct. Next needed: ${state.nextObjective?.description ?: "wrap up"}.",
                priority = 2,
                expiresAfterTurn = turn + 4,
                source = ActionSource.FLOW_GUARD,
            )
        }

        return null
    }
}
