package com.aiinterview.conversation

import com.aiinterview.interview.service.RedisMemoryService
import com.aiinterview.interview.ws.OutboundMessage
import com.aiinterview.interview.ws.WsSessionRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Coordinates the background agents that run after every candidate response.
 *
 * Invoked fire-and-forget from [ConversationEngine.handleCandidateMessage].
 * Does NOT inject [ConversationEngine] (would create a circular dependency);
 * instead it drives state transitions directly via [RedisMemoryService] and [WsSessionRegistry].
 */
@Service
class AgentOrchestrator(
    private val reasoningAnalyzer: ReasoningAnalyzer,
    private val followUpGenerator: FollowUpGenerator,
    private val interviewerAgent: InterviewerAgent,
    private val redisMemoryService: RedisMemoryService,
    private val registry: WsSessionRegistry,
) {
    private val log = LoggerFactory.getLogger(AgentOrchestrator::class.java)

    /**
     * Main entry point.
     *
     * 1. Fetches current memory.
     * 2. Runs [ReasoningAnalyzer] (updates Redis with analysis + eval scores).
     * 3. Determines the next state transition from [AnalysisResult.suggestedTransition].
     * 4. For [InterviewState.FollowUp]: generates question and streams it via [InterviewerAgent].
     * 5. For [InterviewState.CodingChallenge] / [InterviewState.Evaluating]: transitions only.
     */
    suspend fun analyzeAndTransition(sessionId: UUID, candidateMessage: String) {
        val memory = try {
            redisMemoryService.getMemory(sessionId)
        } catch (e: Exception) {
            log.error("Memory not found for background analysis: session={}", sessionId, e)
            return
        }

        val result = try {
            reasoningAnalyzer.analyze(memory, candidateMessage)
        } catch (e: Exception) {
            log.error("ReasoningAnalyzer failed for session {}: {}", sessionId, e.message)
            return
        }

        when (result.suggestedTransition) {
            InterviewState.CodingChallenge -> {
                transition(sessionId, InterviewState.CodingChallenge)
            }

            InterviewState.Evaluating -> {
                transition(sessionId, InterviewState.Evaluating)
            }

            InterviewState.FollowUp -> {
                val currentMemory = redisMemoryService.getMemory(sessionId)
                val question = try {
                    followUpGenerator.generate(currentMemory, result.analysis.gaps)
                } catch (e: Exception) {
                    log.error("FollowUpGenerator failed for session {}: {}", sessionId, e.message)
                    ""
                }
                if (question.isBlank()) {
                    // Follow-up limit reached — go directly to evaluation
                    transition(sessionId, InterviewState.Evaluating)
                } else {
                    transition(sessionId, InterviewState.FollowUp)
                    val updatedMemory = redisMemoryService.getMemory(sessionId)
                    interviewerAgent.streamResponse(sessionId, updatedMemory, question)
                }
            }

            null -> log.debug("No state transition needed for session {}", sessionId)

            else -> log.warn(
                "Unexpected transition suggestion {} for session {}",
                result.suggestedTransition, sessionId,
            )
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun transition(sessionId: UUID, state: InterviewState) {
        val stateName = InterviewState.toString(state)
        try {
            redisMemoryService.updateMemory(sessionId) { it.copy(state = stateName) }
            registry.sendMessage(sessionId, OutboundMessage.StateChange(state = stateName))
            log.info("AgentOrchestrator: session {} → {}", sessionId, stateName)
        } catch (e: Exception) {
            log.error(
                "Transition failed for session {} → {}: {}",
                sessionId, stateName, e.message,
            )
        }
    }
}
