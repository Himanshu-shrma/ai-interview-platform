package com.aiinterview.conversation.brain

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.Instant
import java.util.UUID

// ═══════════════════════════════════════════════════════════════
// InterviewerBrain — Unified cognitive state for the AI interviewer
// Replaces InterviewMemory (which is kept for backward compatibility)
// ═══════════════════════════════════════════════════════════════

@JsonIgnoreProperties(ignoreUnknown = true)
data class InterviewerBrain(
    val sessionId: UUID,
    val userId: UUID,
    val candidateProfile: CandidateProfile = CandidateProfile(),
    val hypothesisRegistry: HypothesisRegistry = HypothesisRegistry(),
    val claimRegistry: ClaimRegistry = ClaimRegistry(),
    val interviewGoals: InterviewGoals = InterviewGoals(required = emptyList()),
    val thoughtThread: ThoughtThread = ThoughtThread(),
    val currentStrategy: InterviewStrategy = InterviewStrategy(),
    val actionQueue: ActionQueue = ActionQueue(),
    val interviewType: String,
    val questionDetails: InterviewQuestion,
    val turnCount: Int = 0,
    val usedAcknowledgments: List<String> = emptyList(),
    val topicSignalBudget: Map<String, Float> = emptyMap(),
    val bloomsTracker: Map<String, Int> = emptyMap(),
    val exchangeScores: List<ExchangeScore> = emptyList(),
    val hintOutcomes: List<HintOutcome> = emptyList(),
    val currentCode: String? = null,
    val programmingLanguage: String? = null,
    val personality: String = "friendly",
    val targetCompany: String? = null,
    val experienceLevel: String? = null,
    val rollingTranscript: List<BrainTranscriptTurn> = emptyList(),
    val earlierContext: String = "",
    val hintsGiven: Int = 0,
    val startedAt: Instant = Instant.now(),
    val lastActivityAt: Instant = Instant.now(),
)

// ═══ Candidate Profile ═══

@JsonIgnoreProperties(ignoreUnknown = true)
data class CandidateProfile(
    val thinkingStyle: ThinkingStyle = ThinkingStyle.UNKNOWN,
    val reasoningPattern: ReasoningPattern = ReasoningPattern.UNKNOWN,
    val knowledgeMap: Map<String, Float> = emptyMap(),
    val communicationStyle: CommunicationStyle = CommunicationStyle.UNKNOWN,
    val pressureResponse: PressureResponse = PressureResponse.UNKNOWN,
    val avoidancePatterns: List<String> = emptyList(),
    val overallSignal: CandidateSignal = CandidateSignal.UNKNOWN,
    val currentState: EmotionalState = EmotionalState.NEUTRAL,
    val anxietyLevel: Float = 0.3f,
    val avgAnxietyLevel: Float = 0.3f,
    val flowState: Boolean = false,
    val trajectory: PerformanceTrajectory = PerformanceTrajectory.STABLE,
    val psychologicalSafety: Float = 0.7f,
    val linguisticPattern: LinguisticPattern = LinguisticPattern.UNKNOWN,
    val abstractionLevel: Int = 3,
    val selfRepairCount: Int = 0,
    val cognitiveLoadSignal: CognitiveLoad = CognitiveLoad.NOMINAL,
    val unknownHandlingPattern: UnknownHandling = UnknownHandling.UNKNOWN,
    val mentalSimulationAbility: Float = 0.5f,
    val dataPoints: Int = 0,
)

enum class ThinkingStyle { BOTTOM_UP, TOP_DOWN, INTUITIVE, METHODICAL, UNKNOWN }
enum class ReasoningPattern { SCHEMA_DRIVEN, SEARCH_DRIVEN, UNKNOWN }
enum class CommunicationStyle { VERBOSE, TERSE, CLEAR, CONFUSED, UNKNOWN }
enum class PressureResponse { RISES, FREEZES, STEADY, DEFENSIVE, UNKNOWN }
enum class CandidateSignal { STRONG, SOLID, AVERAGE, STRUGGLING, UNKNOWN }
enum class EmotionalState { CONFIDENT, NERVOUS, STUCK, FLOWING, FRUSTRATED, NEUTRAL }
enum class PerformanceTrajectory { IMPROVING, DECLINING, STABLE }
enum class LinguisticPattern { JUSTIFIED_REASONER, ASSERTIVE_GUESSER, HEDGED_UNDERSTANDER, PATTERN_MATCHER, UNKNOWN }
enum class CognitiveLoad { NOMINAL, ELEVATED, OVERLOADED }
enum class UnknownHandling { REASONS_FROM_PRINCIPLES, ADMITS_AND_STOPS, PANICS, GUESSES_BLINDLY, UNKNOWN }

// ═══ Hypothesis Tracking ═══

@JsonIgnoreProperties(ignoreUnknown = true)
data class HypothesisRegistry(val hypotheses: List<Hypothesis> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
data class Hypothesis(
    val id: String,
    val claim: String,
    val confidence: Float,
    val supportingEvidence: List<String> = emptyList(),
    val contradictingEvidence: List<String> = emptyList(),
    val status: HypothesisStatus = HypothesisStatus.OPEN,
    val testStrategy: String,
    val priority: Int = 3,
    val formedAtTurn: Int,
    val lastTestedTurn: Int? = null,
    val bloomsLevel: Int = 3,
)

enum class HypothesisStatus { OPEN, CONFIRMED, REFUTED, ABANDONED }

// ═══ Claim + Contradiction Tracking ═══

@JsonIgnoreProperties(ignoreUnknown = true)
data class ClaimRegistry(
    val claims: List<Claim> = emptyList(),
    val pendingContradictions: List<Contradiction> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Claim(
    val id: String,
    val turn: Int,
    val claim: String,
    val topic: String,
    val correctness: ClaimCorrectness = ClaimCorrectness.UNVERIFIED,
    val verified: Boolean = false,
    val isSpecific: Boolean = true,
)

enum class ClaimCorrectness { CORRECT, INCORRECT, PARTIALLY_CORRECT, UNVERIFIED }

@JsonIgnoreProperties(ignoreUnknown = true)
data class Contradiction(
    val claim1Id: String,
    val claim2Id: String,
    val claim1Text: String,
    val claim2Text: String,
    val contradictionDescription: String,
    val surfaced: Boolean = false,
    val priority: Int = 2,
)

// ═══ Interview Goals (replaces stage machine) ═══

@JsonIgnoreProperties(ignoreUnknown = true)
data class InterviewGoals(
    val required: List<Goal>,
    val optional: List<Goal> = emptyList(),
    val completed: List<String> = emptyList(),
    val failedAttempts: Map<String, Int> = emptyMap(),
) {
    fun nextUnlockedGoal(): Goal? =
        required.filter { it.id !in completed }
            .firstOrNull { obj -> obj.dependsOn.all { dep -> dep in completed } }

    fun remainingRequired(): List<Goal> =
        required.filter { it.id !in completed }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class Goal(
    val id: String,
    val description: String,
    val completionCriteria: String,
    val dependsOn: List<String> = emptyList(),
    val estimatedTurns: Int = 3,
    val category: GoalCategory = GoalCategory.TECHNICAL,
    val bloomsTargetLevel: Int = 3,
)

enum class GoalCategory { FOUNDATION, TECHNICAL, EVALUATION, CLOSURE }

// ═══ Thought Thread (continuous inner monologue) ═══

@JsonIgnoreProperties(ignoreUnknown = true)
data class ThoughtThread(
    val thread: String = "",
    val compressedHistory: String = "",
    val lastUpdatedTurn: Int = 0,
)

// ═══ Interview Strategy (meta-cognitive) ═══

@JsonIgnoreProperties(ignoreUnknown = true)
data class InterviewStrategy(
    val approach: String = "",
    val toneGuidance: String = "",
    val timeGuidance: String = "",
    val avoidance: String = "",
    val recommendedTokens: Int = 100,
    val updatedAtTurn: Int = 0,
    val selfCritique: String = "",
)

// ═══ Action Queue ═══

@JsonIgnoreProperties(ignoreUnknown = true)
data class ActionQueue(
    val pending: List<IntendedAction> = emptyList(),
    val lastCompleted: IntendedAction? = null,
) {
    fun topAction(): IntendedAction? = pending.minByOrNull { it.priority }

    fun withoutExpired(currentTurn: Int): ActionQueue =
        copy(pending = pending.filter { it.expiresAfterTurn > currentTurn })
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class IntendedAction(
    val id: String,
    val type: ActionType,
    val description: String,
    val priority: Int,
    val expiresAfterTurn: Int,
    val source: ActionSource,
    val bloomsLevel: Int = 3,
)

enum class ActionType {
    TEST_HYPOTHESIS, SURFACE_CONTRADICTION, ADVANCE_GOAL,
    PROBE_DEPTH, REDIRECT, WRAP_UP_TOPIC, END_INTERVIEW,
    EMOTIONAL_ADJUST, REDUCE_LOAD, MAINTAIN_FLOW,
    RESTORE_SAFETY, PRODUCTIVE_UNKNOWN, REDUCE_PRESSURE,
    MENTAL_SIMULATION,
}

enum class ActionSource {
    FLOW_GUARD, HYPOTHESIS, CONTRADICTION, GOAL,
    META_STRATEGY, COGNITIVE_LOAD, SAFETY, ANALYST,
}

// ═══ Question Details ═══

@JsonIgnoreProperties(ignoreUnknown = true)
data class InterviewQuestion(
    val questionId: String = "",
    val title: String = "",
    val description: String = "",
    val optimalApproach: String = "",
    val difficulty: String = "",
    val category: String = "",
    val knowledgeTopics: List<String> = emptyList(),
)

// ═══ Per-Turn Scoring ═══

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExchangeScore(
    val turn: Int,
    val dimension: String,
    val score: Float,
    val evidence: String,
    val bloomsLevel: Int = 3,
)

// ═══ Computed Interview State ═══

data class InterviewState(
    val completedObjectives: List<String>,
    val remainingRequired: List<Goal>,
    val nextObjective: Goal?,
    val nextObjectiveUnlocked: Boolean,
    val remainingMinutes: Int,
    val isBehindSchedule: Boolean,
    val isOnTrack: Boolean,
    val currentPhaseLabel: String,
    val allRequiredComplete: Boolean,
    val bloomsLevelReached: Map<String, Int> = emptyMap(),
)

// ═══ Hint Outcome Tracking ═══

@JsonIgnoreProperties(ignoreUnknown = true)
data class HintOutcome(
    val hintTurn: Int,
    val hintLevel: Int = 1,
    val conceptTaught: String = "",
    val candidateApplied: Boolean = false,
    val candidateGeneralized: Boolean = false,
)

// ═══ Transcript Turn (brain-specific) ═══

@JsonIgnoreProperties(ignoreUnknown = true)
data class BrainTranscriptTurn(
    val role: String,
    val content: String,
    val timestamp: Instant = Instant.now(),
)
