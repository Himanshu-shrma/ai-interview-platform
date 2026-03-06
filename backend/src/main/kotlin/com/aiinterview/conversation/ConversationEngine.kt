package com.aiinterview.conversation

import com.aiinterview.interview.model.ConversationMessage
import com.aiinterview.interview.repository.ConversationMessageRepository
import com.aiinterview.interview.service.RedisMemoryService
import com.aiinterview.interview.ws.OutboundMessage
import com.aiinterview.interview.ws.WsSessionRegistry
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Central orchestrator for the AI interview conversation.
 *
 * Responsibilities:
 * - State machine transitions (updating Redis + sending STATE_CHANGE WS frames)
 * - Routing candidate messages to the Interviewer Agent
 * - Kicking off background analysis (AgentOrchestrator — fire-and-forget)
 * - Driving the interview start sequence (opening question presentation)
 *
 * [agentOrchestrator] uses @Lazy to break the circular dependency:
 *   ConversationEngine → AgentOrchestrator → InterviewerAgent ✓
 *   AgentOrchestrator does NOT inject ConversationEngine ✓
 */
@Service
class ConversationEngine(
    private val redisMemoryService: RedisMemoryService,
    private val interviewerAgent: InterviewerAgent,
    private val registry: WsSessionRegistry,
    private val conversationMessageRepository: ConversationMessageRepository,
    @Lazy private val agentOrchestrator: AgentOrchestrator,
) {
    private val log = LoggerFactory.getLogger(ConversationEngine::class.java)

    /** Background scope for fire-and-forget agent tasks (analysis, hint generation). */
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Main entry point when the candidate sends a message.
     *
     * Flow:
     * 1. Persist candidate message to transcript + DB
     * 2. Transition → CandidateResponding
     * 3. Stream AI response (InterviewerAgent)
     * 4. Transition → AiAnalyzing
     * 5. Launch background AgentOrchestrator.analyze() (fire-and-forget)
     */
    suspend fun handleCandidateMessage(sessionId: UUID, content: String) {
        val memory = try {
            redisMemoryService.getMemory(sessionId)
        } catch (e: Exception) {
            log.error("Session memory not found for {}: {}", sessionId, e.message)
            registry.sendMessage(sessionId, OutboundMessage.Error("SESSION_ERROR", "Session not found"))
            return
        }

        // Persist candidate message
        redisMemoryService.appendTranscriptTurn(sessionId, "CANDIDATE", content)
        persistCandidateMessage(sessionId, content)

        // State: CANDIDATE_RESPONDING
        transition(sessionId, InterviewState.CandidateResponding)

        // Stream AI response
        interviewerAgent.streamResponse(sessionId, memory, content)

        // State: AI_ANALYZING
        transition(sessionId, InterviewState.AiAnalyzing)

        // Background analysis (fire-and-forget — do NOT await)
        val handler = CoroutineExceptionHandler { _, e ->
            log.error("AgentOrchestrator failed for session {}", sessionId, e)
        }
        backgroundScope.launch(handler) {
            agentOrchestrator.analyzeAndTransition(sessionId, content)
        }
    }

    /**
     * Called on first WebSocket connect (when memory state == INTERVIEW_STARTING).
     *
     * Flow:
     * 1. Transition → QuestionPresented
     * 2. Build and stream the opening interviewer message
     * 3. Append AI opening to transcript
     */
    suspend fun startInterview(sessionId: UUID) {
        val memory = try {
            redisMemoryService.getMemory(sessionId)
        } catch (e: Exception) {
            log.error("Cannot start interview — memory missing for {}: {}", sessionId, e.message)
            registry.sendMessage(sessionId, OutboundMessage.Error("SESSION_ERROR", "Session not found"))
            return
        }

        transition(sessionId, InterviewState.QuestionPresented)

        val q = memory.currentQuestion
        val openingMessage = if (q != null) {
            "Hello! I'm your interviewer today. Let's start with this problem:\n\n" +
            "**${q.title}**\n\n${q.description}\n\n" +
            "Take a moment to read through it, and then walk me through your initial thoughts."
        } else {
            "Hello! I'm your interviewer today. Let's get started. " +
            "Tell me about your background and what you're hoping to work on today."
        }

        // Stream the opening message via InterviewerAgent
        val updatedMemory = redisMemoryService.getMemory(sessionId)
        interviewerAgent.streamResponse(sessionId, updatedMemory, openingMessage)

        log.info("Interview started: sessionId={}", sessionId)
    }

    /**
     * Transitions the session to [newState]:
     * 1. Updates Redis memory with the new state string
     * 2. Sends STATE_CHANGE WebSocket frame
     */
    suspend fun transition(sessionId: UUID, newState: InterviewState) {
        val stateName = InterviewState.toString(newState)
        try {
            redisMemoryService.updateMemory(sessionId) { mem ->
                mem.copy(state = stateName)
            }
            registry.sendMessage(sessionId, OutboundMessage.StateChange(state = stateName))
            log.info("Session {} transitioned to {}", sessionId, stateName)
        } catch (e: Exception) {
            log.error("Failed to transition session {} to {}: {}", sessionId, stateName, e.message)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun persistCandidateMessage(sessionId: UUID, content: String) {
        try {
            withContext(Dispatchers.IO) {
                conversationMessageRepository.save(
                    ConversationMessage(sessionId = sessionId, role = "CANDIDATE", content = content)
                ).block()
            }
        } catch (e: Exception) {
            log.warn("Failed to persist candidate message to DB for session {}: {}", sessionId, e.message)
        }
    }
}
