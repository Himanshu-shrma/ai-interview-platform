package com.aiinterview.conversation.objectives

import com.aiinterview.interview.service.InterviewMemory

/**
 * Computed interview state based on objectives progress + time remaining.
 * Calculated before every LLM call. Phase labels are informational only.
 */
data class ObjectiveState(
    val completedObjectives: List<String>,
    val remainingRequired: List<Objective>,
    val nextObjective: Objective?,
    val nextObjectiveUnlocked: Boolean,
    val remainingMinutes: Int,
    val isBehindSchedule: Boolean,
    val currentPhaseLabel: String,
    val allRequiredComplete: Boolean,
)

fun computeObjectiveState(
    memory: InterviewMemory,
    objectives: InterviewObjectives,
    remainingMinutes: Int,
): ObjectiveState {
    val completed = memory.completedObjectives
    val remainingRequired = objectives.required.filter { it.id !in completed }

    val nextObjective = remainingRequired.firstOrNull { obj ->
        obj.dependsOn.all { dep -> dep in completed }
    }

    val minutesPerObjective = if (remainingRequired.isEmpty()) 99f
    else remainingMinutes.toFloat() / remainingRequired.size

    return ObjectiveState(
        completedObjectives = completed,
        remainingRequired = remainingRequired,
        nextObjective = nextObjective,
        nextObjectiveUnlocked = nextObjective != null,
        remainingMinutes = remainingMinutes,
        isBehindSchedule = minutesPerObjective < 4f && remainingRequired.size > 1,
        currentPhaseLabel = inferPhaseLabel(completed),
        allRequiredComplete = remainingRequired.isEmpty(),
    )
}

private fun inferPhaseLabel(completed: List<String>): String = when {
    "wrap_up" in completed -> "WRAP_UP"
    "star_q3" in completed -> "WRAP_UP"
    "tradeoffs_discussed" in completed -> "WRAP_UP"
    "complexity_covered" in completed -> "FOLLOWUP"
    "code_written" in completed -> "REVIEW"
    "approach_discussed" in completed -> "CODING"
    "high_level_design" in completed -> "DESIGN"
    "requirements_gathered" in completed -> "REQUIREMENTS"
    "problem_presented" in completed -> "APPROACH"
    "star_q1" in completed -> "BEHAVIORAL_Q2"
    "intro_done" in completed -> "BEHAVIORAL_Q1"
    else -> "INTRO"
}
