package com.aiinterview.conversation

import com.aiinterview.interview.dto.toInternalDto
import com.aiinterview.interview.model.ConversationMessage
import com.aiinterview.interview.repository.ConversationMessageRepository
import com.aiinterview.interview.repository.SessionQuestionRepository
import com.aiinterview.interview.service.QuestionService
import com.aiinterview.interview.service.RedisMemoryService
import com.aiinterview.interview.ws.OutboundMessage
import com.aiinterview.interview.ws.WsSessionRegistry
import com.aiinterview.report.service.ReportService
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
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
 *
 * [reportService] uses @Lazy as a safety guard against Spring init ordering issues.
 */
@Service
class ConversationEngine(
    private val redisMemoryService: RedisMemoryService,
    private val interviewerAgent: InterviewerAgent,
    private val registry: WsSessionRegistry,
    private val conversationMessageRepository: ConversationMessageRepository,
    private val sessionQuestionRepository: SessionQuestionRepository,
    private val questionService: QuestionService,
    private val objectMapper: ObjectMapper,
    @Lazy private val agentOrchestrator: AgentOrchestrator,
    @Lazy private val reportService: ReportService,
) {
    private val log = LoggerFactory.getLogger(ConversationEngine::class.java)

    /** Background scope for fire-and-forget agent tasks (analysis, hint generation, reports). */
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
     */
    suspend fun startInterview(sessionId: UUID) {
        val memory = try {
            redisMemoryService.getMemory(sessionId)
        } catch (e: Exception) {
            log.error("Cannot start interview — memory missing for {}: {}", sessionId, e.message)
            registry.sendMessage(sessionId, OutboundMessage.Error("SESSION_ERROR", "Session not found"))
            return
        }

        // Start in SMALL_TALK stage — warm greeting, don't present the problem yet.
        // The problem is presented when interviewStage transitions to PROBLEM_PRESENTED
        // (triggered after the candidate's first response).
        transition(sessionId, InterviewState.QuestionPresented)

        val openingMessage = "Hey! How's it going? I'll be your interviewer today. Whenever you're ready, we can jump in."

        registry.sendMessage(sessionId, OutboundMessage.AiChunk(delta = openingMessage, done = false))
        registry.sendMessage(sessionId, OutboundMessage.AiChunk(delta = "", done = true))

        redisMemoryService.appendTranscriptTurn(sessionId, "AI", openingMessage)
        try {
            withContext(Dispatchers.IO) {
                conversationMessageRepository.save(
                    ConversationMessage(sessionId = sessionId, role = "AI", content = openingMessage)
                ).awaitSingle()
            }
        } catch (e: Exception) {
            log.warn("Failed to persist AI opening message for session {}: {}", sessionId, e.message)
        }

        log.info("Interview started (SMALL_TALK): sessionId={}", sessionId)
    }

    /**
     * Forces the interview to end (e.g., timer expiry or explicit end).
     * Transitions to [InterviewState.Evaluating] then fires report generation.
     */
    suspend fun forceEndInterview(sessionId: UUID) {
        transition(sessionId, InterviewState.Evaluating)
        val handler = CoroutineExceptionHandler { _, e ->
            log.error("Report generation failed for force-ended session {}", sessionId, e)
        }
        backgroundScope.launch(handler) {
            reportService.generateAndSaveReport(sessionId)
        }
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

    /**
     * Transitions to the next question in a multi-question interview.
     * Called by AgentOrchestrator when Q1 evaluation is done and more questions remain.
     *
     * Flow:
     * 1. Load next question from DB (by orderIndex)
     * 2. Update Redis memory: new question, reset per-question state, bump questionIndex
     * 3. Send QUESTION_TRANSITION WS event
     * 4. Transition → QUESTION_PRESENTED
     * 5. Stream the next question presentation message
     */
    suspend fun transitionToNextQuestion(sessionId: UUID) {
        val memory = redisMemoryService.getMemory(sessionId)
        val nextIndex = memory.currentQuestionIndex + 1

        // Load session questions ordered by index
        val sessionQuestions = sessionQuestionRepository
            .findBySessionIdOrderByOrderIndex(sessionId)
            .collectList()
            .awaitSingle()

        if (nextIndex >= sessionQuestions.size) {
            log.warn("No more questions for session {} (index={}), proceeding to evaluation", sessionId, nextIndex)
            forceEndInterview(sessionId)
            return
        }

        val nextSq = sessionQuestions[nextIndex]
        val nextQuestion = questionService.getQuestionById(nextSq.questionId).toInternalDto(objectMapper)

        // Update memory: new question, reset per-question fields
        transition(sessionId, InterviewState.QuestionTransition)
        redisMemoryService.updateMemory(sessionId) { mem ->
            mem.copy(
                currentQuestion      = nextQuestion,
                currentQuestionIndex = nextIndex,
                currentCode          = null,
                candidateAnalysis    = null,
                hintsGiven           = 0,
                followUpsAsked       = emptyList(),
                interviewStage       = "PROBLEM_PRESENTED",  // Skip SMALL_TALK for subsequent questions
            )
        }

        // Parse code templates if available
        val templates = nextQuestion.codeTemplates?.let { ct ->
            try {
                @Suppress("UNCHECKED_CAST")
                objectMapper.readValue(ct.toString(), Map::class.java) as? Map<String, String>
            } catch (_: Exception) { null }
        }

        // Notify frontend of question transition
        registry.sendMessage(sessionId, OutboundMessage.QuestionTransition(
            questionIndex       = nextIndex,
            questionTitle       = nextQuestion.title,
            questionDescription = nextQuestion.description,
            codeTemplates       = templates,
        ))

        // Present the next question
        transition(sessionId, InterviewState.QuestionPresented)

        val transitionMessage = "Good work on that problem. Let's move on to the next one:\n\n" +
            "**${nextQuestion.title}**\n\n${nextQuestion.description}\n\n" +
            "Take a moment and walk me through your initial thoughts."

        registry.sendMessage(sessionId, OutboundMessage.AiChunk(delta = transitionMessage, done = false))
        registry.sendMessage(sessionId, OutboundMessage.AiChunk(delta = "", done = true))

        // Persist to transcript and DB
        redisMemoryService.appendTranscriptTurn(sessionId, "AI", transitionMessage)
        try {
            withContext(Dispatchers.IO) {
                conversationMessageRepository.save(
                    ConversationMessage(sessionId = sessionId, role = "AI", content = transitionMessage)
                ).awaitSingle()
            }
        } catch (e: Exception) {
            log.warn("Failed to persist Q{} transition message for session {}: {}", nextIndex + 1, sessionId, e.message)
        }

        log.info("Session {} transitioned to question {} of {}", sessionId, nextIndex + 1, memory.totalQuestions)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun persistCandidateMessage(sessionId: UUID, content: String) {
        try {
            withContext(Dispatchers.IO) {
                conversationMessageRepository.save(
                    ConversationMessage(sessionId = sessionId, role = "CANDIDATE", content = content)
                ).awaitSingle()
            }
        } catch (e: Exception) {
            log.warn("Failed to persist candidate message to DB for session {}: {}", sessionId, e.message)
        }
    }
}
