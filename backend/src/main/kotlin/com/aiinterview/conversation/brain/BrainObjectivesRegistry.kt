package com.aiinterview.conversation.brain

/**
 * Fixed objectives per interview type using Goal data class.
 * Defines WHAT must be covered (consistency guarantee).
 * Does NOT define HOW or WHEN (that's the AI's job).
 *
 * This is the brain-based replacement for objectives/ObjectivesRegistry.
 * The old ObjectivesRegistry is kept for backward compatibility.
 */
object BrainObjectivesRegistry {

    val CODING = InterviewGoals(
        required = listOf(
            Goal("problem_shared", "Problem presented to candidate", "AI sent complete problem description", emptyList(), 1, GoalCategory.FOUNDATION),
            Goal("approach_understood", "Candidate described their approach", "Candidate explained a specific algorithm or strategy", listOf("problem_shared"), 3, GoalCategory.TECHNICAL),
            Goal("approach_justified", "Candidate explained WHY their approach works", "Candidate gave justification not just description", listOf("approach_understood"), 2, GoalCategory.TECHNICAL, 4),
            Goal("solution_implemented", "Candidate wrote a real working solution", "hasMeaningfulCode is true", listOf("approach_understood"), 8, GoalCategory.TECHNICAL),
            Goal("complexity_owned", "Time AND space complexity stated and justified", "Candidate stated Big-O with explanation", listOf("solution_implemented"), 2, GoalCategory.EVALUATION, 4),
            Goal("edge_cases_explored", "At least 2 edge cases identified", "2+ edge cases discussed or tested", listOf("solution_implemented"), 3, GoalCategory.EVALUATION),
            Goal("reasoning_depth_assessed", "HOW they think assessed not just WHAT they know", "2+ probing questions asked and answered", listOf("approach_understood"), 4, GoalCategory.EVALUATION, 4),
            Goal("mental_simulation_tested", "Candidate traced code with specific input", "Step-by-step execution walkthrough done", listOf("solution_implemented"), 2, GoalCategory.EVALUATION),
            Goal("interview_closed", "Professional closing exchange complete", "AI sent closing message", listOf("complexity_owned"), 2, GoalCategory.CLOSURE),
        ),
        optional = listOf(
            Goal("optimization_explored", "Further optimization discussed", "Alternative approach or trade-offs discussed", emptyList(), 3, GoalCategory.EVALUATION, 5),
            Goal("follow_up_variant", "A harder related variant introduced", "AI asked a meaningful follow-up question", emptyList(), 3, GoalCategory.EVALUATION, 5),
            Goal("reach_evaluate_level", "At least 2 topics reached ANALYZE or EVALUATE level", "bloomsTracker has 2+ entries at level 4+", listOf("approach_understood"), 8, GoalCategory.EVALUATION, 4),
        ),
    )

    val DSA = CODING

    val BEHAVIORAL = InterviewGoals(
        required = listOf(
            Goal("psychological_safety", "Candidate relaxed and engaging authentically", "Genuine responses not formulaic", emptyList(), 2, GoalCategory.FOUNDATION),
            Goal("star_q1_complete", "First STAR story complete", "Situation+Task+Action+Result all present", listOf("psychological_safety"), 5, GoalCategory.TECHNICAL),
            Goal("star_q1_ownership", "Personal ownership confirmed in first story", "Candidate described what THEY did", listOf("star_q1_complete"), 2, GoalCategory.EVALUATION),
            Goal("star_q2_complete", "Second STAR story complete", "Full STAR story on different topic", listOf("star_q1_complete"), 5, GoalCategory.TECHNICAL),
            Goal("star_q2_ownership", "Personal ownership in second story", "Candidate described their contribution", listOf("star_q2_complete"), 2, GoalCategory.EVALUATION),
            Goal("star_q3_complete", "Third STAR story complete", "Full STAR story covering different dimension", listOf("star_q2_complete"), 5, GoalCategory.TECHNICAL),
            Goal("learning_demonstrated", "Self-awareness or growth mindset shown", "Reflected on learnings or what they'd do differently", listOf("star_q2_complete"), 2, GoalCategory.EVALUATION),
            Goal("interview_closed", "Professional closing complete", "AI sent closing message", listOf("star_q2_complete"), 2, GoalCategory.CLOSURE),
        ),
        optional = listOf(
            Goal("star_q4_complete", "Fourth STAR story if time allows", "Full fourth story complete", listOf("star_q3_complete"), 5, GoalCategory.TECHNICAL),
            Goal("situational_judgment", "Scenario-based judgment answered", "Candidate described how they'd handle scenario", listOf("star_q2_complete"), 3, GoalCategory.EVALUATION),
        ),
    )

    val SYSTEM_DESIGN = InterviewGoals(
        required = listOf(
            Goal("problem_shared", "Design problem shared", "AI presented full system design problem", emptyList(), 1, GoalCategory.FOUNDATION),
            Goal("requirements_gathered", "Requirements discussed", "Candidate asked about or stated key requirements", listOf("problem_shared"), 3, GoalCategory.TECHNICAL),
            Goal("high_level_design", "Overall architecture proposed", "Candidate described major system components", listOf("requirements_gathered"), 4, GoalCategory.TECHNICAL),
            Goal("component_deep_dive", "At least one component explored in depth", "Specific implementation details discussed", listOf("high_level_design"), 4, GoalCategory.TECHNICAL, 4),
            Goal("tradeoffs_acknowledged", "Trade-offs of design choices discussed", "Candidate acknowledged pros and cons", listOf("high_level_design"), 3, GoalCategory.EVALUATION, 5),
            Goal("failure_modes_explored", "How system handles failures discussed", "Candidate addressed what breaks and recovery", listOf("high_level_design"), 3, GoalCategory.EVALUATION, 4),
            Goal("scalability_addressed", "Scale numbers and bottlenecks discussed", "Candidate talked about scale and bottlenecks", listOf("high_level_design"), 3, GoalCategory.EVALUATION, 4),
            Goal("interview_closed", "Professional closing complete", "AI sent closing message", listOf("component_deep_dive"), 2, GoalCategory.CLOSURE),
        ),
        optional = listOf(
            Goal("alternative_considered", "Alternative design approach compared", "Different approach and trade-offs discussed", listOf("high_level_design"), 3, GoalCategory.EVALUATION),
            Goal("data_model_defined", "Data model or schema discussed", "Key data structures described", listOf("high_level_design"), 3, GoalCategory.TECHNICAL),
        ),
    )

    fun forCategory(category: String?): InterviewGoals = when (category?.uppercase()?.trim()) {
        "CODING" -> CODING
        "DSA" -> DSA
        "BEHAVIORAL" -> BEHAVIORAL
        "SYSTEM_DESIGN" -> SYSTEM_DESIGN
        else -> CODING
    }
}

fun computeBrainInterviewState(
    brain: InterviewerBrain,
    remainingMinutes: Int,
): InterviewState {
    val goals = brain.interviewGoals
    val completed = goals.completed
    val remainingRequired = goals.remainingRequired()

    val nextObjective = goals.nextUnlockedGoal()

    val minutesPerObjective = if (remainingRequired.isEmpty()) 99f
    else remainingMinutes.toFloat() / remainingRequired.size

    val isBehindSchedule = minutesPerObjective < 4f && remainingRequired.size > 1

    return InterviewState(
        completedObjectives = completed,
        remainingRequired = remainingRequired,
        nextObjective = nextObjective,
        nextObjectiveUnlocked = nextObjective != null,
        remainingMinutes = remainingMinutes,
        isBehindSchedule = isBehindSchedule,
        isOnTrack = !isBehindSchedule,
        currentPhaseLabel = inferPhaseLabel(completed, brain.interviewType),
        allRequiredComplete = remainingRequired.isEmpty(),
        bloomsLevelReached = brain.bloomsTracker,
    )
}

private fun inferPhaseLabel(completed: List<String>, interviewType: String): String = when (interviewType.uppercase()) {
    "BEHAVIORAL" -> when {
        "interview_closed" in completed -> "WRAP_UP"
        "star_q3_complete" in completed -> "FINAL_STORY"
        "star_q2_complete" in completed -> "STORY_3"
        "star_q1_complete" in completed -> "STORY_2"
        "psychological_safety" in completed -> "STORY_1"
        else -> "OPENING"
    }
    "SYSTEM_DESIGN" -> when {
        "interview_closed" in completed -> "WRAP_UP"
        "tradeoffs_acknowledged" in completed -> "DEEP_DIVE"
        "high_level_design" in completed -> "DESIGN"
        "requirements_gathered" in completed -> "ARCHITECTURE"
        "problem_shared" in completed -> "REQUIREMENTS"
        else -> "INTRO"
    }
    else -> when {
        "interview_closed" in completed -> "WRAP_UP"
        "complexity_owned" in completed -> "FOLLOWUP"
        "solution_implemented" in completed -> "REVIEW"
        "approach_understood" in completed -> "CODING"
        "problem_shared" in completed -> "APPROACH"
        else -> "INTRO"
    }
}
