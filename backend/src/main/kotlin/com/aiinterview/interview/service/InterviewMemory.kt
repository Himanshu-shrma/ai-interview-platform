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
    val interviewStage: String = "PROBLEM_PRESENTED",
    val currentQuestionIndex: Int = 0,
    val totalQuestions: Int = 1,
    val createdAt: Instant = Instant.now(),
    val lastActivityAt: Instant = Instant.now(),
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
