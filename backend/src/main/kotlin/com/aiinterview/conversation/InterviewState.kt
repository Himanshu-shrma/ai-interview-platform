package com.aiinterview.conversation

/**
 * State machine states for the Conversation Engine.
 * String names match the values stored in Redis memory and sent via STATE_CHANGE messages.
 *
 * Transition path (happy path):
 * INTERVIEW_STARTING → QUESTION_PRESENTED → CANDIDATE_RESPONDING → AI_ANALYZING
 *   → FOLLOW_UP → CODING_CHALLENGE → QUESTION_TRANSITION → QUESTION_PRESENTED (Q2)
 *   → ... → EVALUATING → INTERVIEW_END
 * Any state → EXPIRED (on TTL expiry / timeout)
 */
sealed class InterviewState {
    object InterviewStarting    : InterviewState()
    object QuestionPresented    : InterviewState()
    object CandidateResponding  : InterviewState()
    object AiAnalyzing          : InterviewState()
    object FollowUp             : InterviewState()
    object CodingChallenge      : InterviewState()
    object QuestionTransition    : InterviewState()
    object Evaluating           : InterviewState()
    object InterviewEnd         : InterviewState()
    object Expired              : InterviewState()

    companion object {
        fun fromString(name: String): InterviewState = when (name) {
            "INTERVIEW_STARTING"    -> InterviewStarting
            "QUESTION_PRESENTED"    -> QuestionPresented
            "CANDIDATE_RESPONDING"  -> CandidateResponding
            "AI_ANALYZING"          -> AiAnalyzing
            "FOLLOW_UP"             -> FollowUp
            "CODING_CHALLENGE"      -> CodingChallenge
            "QUESTION_TRANSITION"   -> QuestionTransition
            "EVALUATING"            -> Evaluating
            "INTERVIEW_END"         -> InterviewEnd
            "EXPIRED"               -> Expired
            else                    -> throw IllegalArgumentException("Unknown InterviewState: $name")
        }

        fun toString(state: InterviewState): String = when (state) {
            is InterviewStarting    -> "INTERVIEW_STARTING"
            is QuestionPresented    -> "QUESTION_PRESENTED"
            is CandidateResponding  -> "CANDIDATE_RESPONDING"
            is AiAnalyzing          -> "AI_ANALYZING"
            is FollowUp             -> "FOLLOW_UP"
            is CodingChallenge      -> "CODING_CHALLENGE"
            is QuestionTransition   -> "QUESTION_TRANSITION"
            is Evaluating           -> "EVALUATING"
            is InterviewEnd         -> "INTERVIEW_END"
            is Expired              -> "EXPIRED"
        }
    }
}
