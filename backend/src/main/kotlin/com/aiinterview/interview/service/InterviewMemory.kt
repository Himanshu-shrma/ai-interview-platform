package com.aiinterview.interview.service

import com.aiinterview.interview.dto.InternalQuestionDto
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.Instant
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
data class InterviewMemory(
    val sessionId: UUID,
    val userId: UUID,
    val state: String,                          // InterviewState name
    val category: String,                       // InterviewCategory name
    val personality: String,
    val currentQuestion: InternalQuestionDto?,
    val candidateAnalysis: CandidateAnalysis?,
    val hintsGiven: Int = 0,
    val followUpsAsked: List<String> = emptyList(),
    val timeElapsedSec: Long = 0,
    val currentCode: String? = null,
    val programmingLanguage: String? = null,
    val rollingTranscript: List<TranscriptTurn> = emptyList(),
    val earlierContext: String = "",
    val evalScores: EvalScores = EvalScores(),
    val interviewStage: String = "SMALL_TALK",
    val currentQuestionIndex: Int = 0,
    val totalQuestions: Int = 1,
    val targetCompany: String? = null,
    val targetRole: String? = null,
    val experienceLevel: String? = null,
    val background: String? = null,
    val complexityDiscussed: Boolean = false,
    val edgeCasesCovered: Int = 0,
    val agentNotes: String = "",
    val lastTestResult: TestResultCache? = null,
    // Legacy fields — kept for Redis backward compatibility.
    // Brain system uses InterviewerBrain instead.
    @Deprecated("Use InterviewerBrain.interviewGoals.completed", level = DeprecationLevel.WARNING)
    val completedObjectives: List<String> = emptyList(),
    @Deprecated("Use InterviewerBrain state tracking", level = DeprecationLevel.WARNING)
    val stalledObjectiveId: String? = null,
    @Deprecated("Use InterviewerBrain state tracking", level = DeprecationLevel.WARNING)
    val stalledTurnCount: Int = 0,
    @Deprecated("Use InterviewerBrain.turnCount", level = DeprecationLevel.WARNING)
    val turnCount: Int = 0,
    @Deprecated("Use InterviewerBrain.actionQueue", level = DeprecationLevel.WARNING)
    val pendingProbe: String? = null,
    @Deprecated("Use InterviewerBrain.candidateProfile", level = DeprecationLevel.WARNING)
    val candidateModel: CandidateModel = CandidateModel(),
    val createdAt: Instant = Instant.now(),
    val lastActivityAt: Instant = Instant.now(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CandidateModel(
    val thinkingStyle: String = "unknown",
    val knowledgeSignals: Map<String, Float> = emptyMap(),
    val behaviorPatterns: List<String> = emptyList(),
    val activeHypotheses: List<String> = emptyList(),
    val interviewNarrative: String = "",
    val stateSignal: String = "neutral",
    val nextProbeTheory: String = "",
    val probedTopics: List<String> = emptyList(),
    val overallSignal: String = "unknown",
    val lastUpdatedTurn: Int = 0,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TestResultCache(
    val passed: Int = 0,
    val total: Int = 0,
    val failedCases: List<FailedCase>? = null,
    val ranAt: Instant = Instant.now(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FailedCase(
    val input: String = "",
    val expected: String = "",
    val actual: String = "",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TranscriptTurn(
    val role: String,       // "AI" or "CANDIDATE"
    val content: String,
    val timestamp: Instant = Instant.now(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CandidateAnalysis(
    val approach: String? = null,
    val confidence: String? = null,         // high/medium/low
    val correctness: String? = null,        // correct/partial/incorrect
    val gaps: List<String> = emptyList(),
    val codingSignalDetected: Boolean = false,
    val readyForEvaluation: Boolean = false,
    val lastUpdatedAt: Instant? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EvalScores(
    val problemSolving: Double = 0.0,
    val algorithmChoice: Double = 0.0,
    val codeQuality: Double = 0.0,
    val communication: Double = 0.0,
    val efficiency: Double = 0.0,
    val testing: Double = 0.0,
) {
    fun overallScore(): Double =
        listOf(problemSolving, algorithmChoice, codeQuality,
               communication, efficiency, testing).average()
}
