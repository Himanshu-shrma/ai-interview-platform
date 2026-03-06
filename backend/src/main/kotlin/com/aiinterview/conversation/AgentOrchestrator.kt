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
                transition(sessionId, InterviewState.Evaluating)
                launchReportGeneration(sessionId)
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
                    transition(sessionId, InterviewState.Evaluating)
                    launchReportGeneration(sessionId)
                } else {
                    transition(sessionId, InterviewState.FollowUp)
                    val updatedMemory = redisMemoryService.getMemory(sessionId)
                    interviewerAgent.streamResponse(sessionId, updatedMemory, question)
                }
            }

            null -> log.debug("No state transition needed for session {}", sessionId)

            else -> log.warn("Unexpected transition suggestion {} for session {}", result.suggestedTransition, sessionId)
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
