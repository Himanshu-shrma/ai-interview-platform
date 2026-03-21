package com.aiinterview.conversation.objectives

data class Objective(
    val id: String,
    val description: String,
    val completionSignal: String,
    val dependsOn: List<String> = emptyList(),
)

data class InterviewObjectives(
    val required: List<Objective>,
    val optional: List<Objective> = emptyList(),
)

object ObjectivesRegistry {

    val CODING = InterviewObjectives(
        required = listOf(
            Objective("problem_presented", "Problem shared with candidate", "AI sent full problem description"),
            Objective("approach_discussed", "Candidate described their approach", "Candidate explained algorithm or strategy", listOf("problem_presented")),
            Objective("code_written", "Candidate wrote a real solution", "hasMeaningfulCode is true", listOf("approach_discussed")),
            Objective("complexity_covered", "Time and space complexity discussed", "Candidate stated Big-O", listOf("code_written")),
            Objective("edge_case_covered", "At least one edge case explored", "Edge case discussed or tested", listOf("code_written")),
            Objective("wrap_up", "Interview closed professionally", "AI sent closing message", listOf("complexity_covered")),
        ),
        optional = listOf(
            Objective("optimization_discussed", "Further optimization explored", "Trade-offs or better approach discussed"),
            Objective("follow_up_variant", "Harder follow-up introduced", "AI asked a related harder question"),
        ),
    )

    val DSA = CODING

    val BEHAVIORAL = InterviewObjectives(
        required = listOf(
            Objective("intro_done", "Candidate warmed up", "Opening exchange complete"),
            Objective("star_q1", "First STAR story complete", "Situation+Task+Action+Result all present", listOf("intro_done")),
            Objective("star_q2", "Second STAR story complete", "Full STAR story collected", listOf("star_q1")),
            Objective("star_q3", "Third STAR story complete", "Full STAR story collected", listOf("star_q2")),
            Objective("wrap_up", "Interview closed professionally", "AI sent closing message", listOf("star_q2")),
        ),
        optional = listOf(
            Objective("star_q4", "Fourth STAR story if time allows", "Fourth story complete", listOf("star_q3")),
        ),
    )

    val SYSTEM_DESIGN = InterviewObjectives(
        required = listOf(
            Objective("problem_presented", "Design problem shared", "AI shared the system design problem"),
            Objective("requirements_gathered", "Requirements discussed", "Candidate asked about or stated requirements", listOf("problem_presented")),
            Objective("high_level_design", "Overall architecture proposed", "Candidate described major components", listOf("requirements_gathered")),
            Objective("deep_dive", "At least one component explored in depth", "Specific component details discussed", listOf("high_level_design")),
            Objective("tradeoffs_discussed", "Trade-offs acknowledged", "Candidate discussed pros/cons", listOf("high_level_design")),
            Objective("wrap_up", "Interview closed professionally", "AI sent closing message", listOf("deep_dive")),
        ),
        optional = listOf(
            Objective("scalability_probed", "Scale discussed in depth", "Scale numbers and bottlenecks addressed"),
            Objective("failure_modes", "Failure handling covered", "Candidate discussed failure scenarios"),
        ),
    )

    fun forCategory(category: String?): InterviewObjectives = when (category?.uppercase()) {
        "CODING" -> CODING
        "DSA" -> DSA
        "BEHAVIORAL" -> BEHAVIORAL
        "SYSTEM_DESIGN" -> SYSTEM_DESIGN
        else -> CODING
    }
}
