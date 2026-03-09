package com.aiinterview.conversation

import com.aiinterview.interview.service.RedisMemoryService
import com.aiinterview.interview.ws.OutboundMessage
import com.aiinterview.interview.ws.WsSessionRegistry
import com.aiinterview.report.service.ReportService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AgentOrchestrator(
    private val reasoningAnalyzer: ReasoningAnalyzer,
    private val followUpGenerator: FollowUpGenerator,
    private val interviewerAgent: InterviewerAgent,
    private val redisMemoryService: RedisMemoryService,
    private val registry: WsSessionRegistry,
    @Lazy private val conversationEngine: ConversationEngine,
    @Lazy private val reportService: ReportService,
) {
    private val log = LoggerFactory.getLogger(AgentOrchestrator::class.java)
    private val reportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
                handleQuestionComplete(sessionId)
            }

            InterviewState.FollowUp -> {
                // Analysis identified gaps — store them in Redis so the NEXT candidate turn's
                // prompt incorporates them. Do NOT send a second AI message; the interviewer
                // already responded in this turn. The gaps will inform the next response naturally.
                transition(sessionId, InterviewState.FollowUp)
                log.debug("Gaps stored for session {}: {}", sessionId, result.analysis.gaps)
            }

            null -> log.debug("No state transition needed for session {}", sessionId)

            else -> log.warn("Unexpected transition suggestion {} for session {}", result.suggestedTransition, sessionId)
        }
    }

    /**
     * Called when the current question is considered complete.
     * If more questions remain, transitions to the next one.
     * Otherwise, proceeds to evaluation and report generation.
     */
    private suspend fun handleQuestionComplete(sessionId: UUID) {
        val memory = redisMemoryService.getMemory(sessionId)
        val hasMoreQuestions = memory.currentQuestionIndex + 1 < memory.totalQuestions

        if (hasMoreQuestions) {
            log.info("Question {} of {} done for session {}, transitioning to next",
                memory.currentQuestionIndex + 1, memory.totalQuestions, sessionId)
            conversationEngine.transitionToNextQuestion(sessionId)
        } else {
            transition(sessionId, InterviewState.Evaluating)
            launchReportGeneration(sessionId)
        }
    }

    private fun launchReportGeneration(sessionId: UUID) {
        val handler = CoroutineExceptionHandler { _, e ->
            log.error("Report generation failed for session {}", sessionId, e)
        }
        reportScope.launch(handler) {
            reportService.generateAndSaveReport(sessionId)
        }
    }

    private suspend fun transition(sessionId: UUID, state: InterviewState) {
        val stateName = InterviewState.toString(state)
        try {
            redisMemoryService.updateMemory(sessionId) { it.copy(state = stateName) }
            registry.sendMessage(sessionId, OutboundMessage.StateChange(state = stateName))
            log.info("AgentOrchestrator: session {} -> {}", sessionId, stateName)
        } catch (e: Exception) {
            log.error("Transition failed for session {} -> {}: {}", sessionId, stateName, e.message)
        }
    }
}
