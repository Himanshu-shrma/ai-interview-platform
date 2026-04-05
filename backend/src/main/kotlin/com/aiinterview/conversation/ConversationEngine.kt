package com.aiinterview.conversation

import com.aiinterview.conversation.brain.BrainFlowGuard
import com.aiinterview.conversation.brain.BrainObjectivesRegistry
import com.aiinterview.conversation.brain.BrainService
import com.aiinterview.conversation.brain.InterviewQuestion
import com.aiinterview.conversation.brain.TheAnalyst
import com.aiinterview.conversation.brain.TheConductor
import com.aiinterview.conversation.brain.TheStrategist
import com.aiinterview.conversation.brain.computeBrainInterviewState
import com.aiinterview.interview.dto.toInternalDto
import com.aiinterview.interview.model.ConversationMessage
import com.aiinterview.interview.repository.ConversationMessageRepository
import com.aiinterview.interview.repository.InterviewSessionRepository
import com.aiinterview.interview.repository.SessionQuestionRepository
import com.aiinterview.interview.service.InterviewConfig
import com.aiinterview.interview.service.QuestionService
import com.aiinterview.interview.ws.OutboundMessage
import com.aiinterview.interview.ws.WsSessionRegistry
import com.aiinterview.report.service.ReportService
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Central orchestrator for the AI interview conversation.
 *
 * Uses the brain architecture exclusively:
 * - TheConductor for real-time responses
 * - TheAnalyst for background analysis (fire-and-forget)
 * - TheStrategist for meta-cognitive review (every 5 turns)
 * - BrainFlowGuard for safety rules
 *
 * [reportService] uses @Lazy as a safety guard against Spring init ordering issues.
 */
@Service
class ConversationEngine(
    private val registry: WsSessionRegistry,
    private val conversationMessageRepository: ConversationMessageRepository,
    private val sessionQuestionRepository: SessionQuestionRepository,
    private val sessionRepository: InterviewSessionRepository,
    private val questionService: QuestionService,
    private val objectMapper: ObjectMapper,
    @Lazy private val reportService: ReportService,
    private val brainService: BrainService,
    private val theConductor: TheConductor,
    private val theAnalyst: TheAnalyst,
    private val theStrategist: TheStrategist,
    private val brainFlowGuard: BrainFlowGuard,
) {
    private val log = LoggerFactory.getLogger(ConversationEngine::class.java)

    /** Per-session scopes — cancelled when interview ends or WS disconnects. */
    private val sessionScopes = ConcurrentHashMap<UUID, CoroutineScope>()

    private fun getSessionScope(sessionId: UUID): CoroutineScope =
        sessionScopes.getOrPut(sessionId) { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    fun cancelSessionScope(sessionId: UUID) {
        sessionScopes.remove(sessionId)?.cancel()
    }

    @PreDestroy
    fun destroy() {
        sessionScopes.values.forEach { it.cancel() }
        sessionScopes.clear()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Main entry point when the candidate sends a message.
     *
     * Flow:
     * 1. Load brain from Redis
     * 2. Persist candidate message
     * 3. Compute interview state from objectives
     * 4. Check FlowGuard safety rules
     * 5. TheConductor generates and streams response
     * 6. Background: TheAnalyst + TheStrategist (fire-and-forget)
     */
    suspend fun handleCandidateMessage(sessionId: UUID, content: String) {
        val brain = brainService.getBrainOrNull(sessionId)
        if (brain == null) {
            log.error("Brain not found for session {}", sessionId)
            registry.sendMessage(sessionId, OutboundMessage.Error("SESSION_ERROR", "Session not found"))
            return
        }

        // Persist candidate message to DB + brain transcript
        persistCandidateMessage(sessionId, content)
        brainService.appendTranscriptTurn(sessionId, "CANDIDATE", content)

        transition(sessionId, InterviewState.CandidateResponding)

        // Compute interview state from objectives
        val remainingMinutes = calculateRemainingMinutes(sessionId)
        val state = computeBrainInterviewState(brain, remainingMinutes)

        // FlowGuard safety check
        brainFlowGuard.check(brain, state)?.let { guardAction ->
            try { brainService.addAction(sessionId, guardAction) } catch (e: Exception) { log.debug("Non-critical operation failed: {}", e.message) }
        }

        // Increment turn count
        try { brainService.incrementTurnCount(sessionId) } catch (e: Exception) { log.debug("Non-critical operation failed: {}", e.message) }

        // Generate and stream AI response via TheConductor
        val aiResponse = theConductor.respond(sessionId, content, brain, state)

        transition(sessionId, InterviewState.AiAnalyzing)

        // Background tasks (fire-and-forget on per-session scope)
        val scope = getSessionScope(sessionId)

        // TheAnalyst — updates full brain after every exchange
        scope.launch {
            try {
                val freshBrain = brainService.getBrainOrNull(sessionId) ?: return@launch
                theAnalyst.analyze(sessionId, content, aiResponse, freshBrain)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { log.warn("TheAnalyst failed for {}: {}", sessionId, e.message) }
        }

        // TheStrategist — meta-cognitive review every 5 turns
        if ((brain.turnCount + 1) % 5 == 0) {
            scope.launch {
                try {
                    val freshBrain = brainService.getBrainOrNull(sessionId) ?: return@launch
                    theStrategist.review(sessionId, freshBrain)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { log.warn("TheStrategist failed for {}: {}", sessionId, e.message) }
            }
        }
    }

    /**
     * Called on first WebSocket connect (when memory state == INTERVIEW_STARTING).
     * Reads session data from DB (not InterviewMemory), initializes brain, sends greeting.
     */
    suspend fun startInterview(sessionId: UUID) {
        // Load session from DB — the single source of truth
        val session = withContext(Dispatchers.IO) {
            sessionRepository.findById(sessionId).awaitSingleOrNull()
        }
        if (session == null) {
            log.error("Cannot start interview — session not found in DB: {}", sessionId)
            registry.sendMessage(sessionId, OutboundMessage.Error("SESSION_ERROR", "Session not found"))
            return
        }

        val config = try {
            objectMapper.readValue(session.config ?: "{}", InterviewConfig::class.java)
        } catch (e: Exception) {
            log.error("Cannot parse session config for {}: {}", sessionId, e.message)
            registry.sendMessage(sessionId, OutboundMessage.Error("SESSION_ERROR", "Invalid session config"))
            return
        }

        // Load the first question for this session
        val firstSq = withContext(Dispatchers.IO) {
            sessionQuestionRepository.findBySessionIdOrderByOrderIndex(sessionId)
                .next().awaitSingleOrNull()
        }
        val questionDto = firstSq?.let {
            withContext(Dispatchers.IO) {
                questionService.getQuestionById(it.questionId)
            }.toInternalDto(objectMapper)
        }

        val category = config.category.name
        val isCodingType = category in setOf("CODING", "DSA")
        val isBehavioral = category == "BEHAVIORAL"

        transition(sessionId, InterviewState.QuestionPresented)

        val greeting = when (config.personality.uppercase()) {
            "FAANG_SENIOR" -> "Hey. Let's get started."
            "FRIENDLY_MENTOR", "FRIENDLY" -> "Hey! Great to meet you."
            "STARTUP_ENGINEER", "STARTUP" -> "Hey, welcome."
            "ADAPTIVE" -> "Hi! Good to meet you."
            else -> "Hey! I'll be your interviewer today."
        }

        // Send greeting only — problem presented by TheConductor on turn 1 after candidate responds
        val openingMessage = when {
            isBehavioral -> "$greeting Tell me a bit about what you've been working on lately."
            else -> "$greeting Whenever you're ready, we can jump in."
        }

        registry.sendMessage(sessionId, OutboundMessage.AiChunk(delta = openingMessage, done = false))
        registry.sendMessage(sessionId, OutboundMessage.AiChunk(delta = "", done = true))

        // Show code editor for CODING/DSA types only
        if (isCodingType) {
            registry.sendMessage(sessionId, OutboundMessage.StateChange(state = "CODING_CHALLENGE"))
        }

        // Initialize brain from DB session data (NOT from InterviewMemory)
        try {
            val question = InterviewQuestion(
                questionId = questionDto?.id?.toString() ?: "",
                title = questionDto?.title ?: "",
                description = questionDto?.description ?: "",
                optimalApproach = questionDto?.optimalApproach ?: "",
                difficulty = questionDto?.difficulty ?: "MEDIUM",
                category = category,
            )
            val goals = BrainObjectivesRegistry.forCategory(category)
            brainService.initBrain(
                sessionId = sessionId,
                userId = session.userId,
                interviewType = category,
                question = question,
                goals = goals,
                personality = config.personality,
                targetCompany = config.targetCompany,
                experienceLevel = config.experienceLevel,
                programmingLanguage = config.programmingLanguage,
                configuredDurationMinutes = config.durationMinutes,
            )
            log.info("Brain initialized for session {} — question='{}' type={}", sessionId, question.title, category)
            if (question.title.isBlank()) log.error("CRITICAL: Question title is blank for session {}", sessionId)
            if (question.description.isBlank()) log.error("CRITICAL: Question description is blank for session {}", sessionId)
        } catch (e: Exception) {
            log.error("Brain init failed for {}: {}", sessionId, e.message)
        }

        // Persist opening to brain transcript + DB
        brainService.appendTranscriptTurn(sessionId, "AI", openingMessage)
        try {
            withContext(Dispatchers.IO) {
                conversationMessageRepository.save(
                    ConversationMessage(sessionId = sessionId, role = "AI", content = openingMessage)
                ).awaitSingle()
            }
        } catch (e: Exception) {
            log.warn("Failed to persist AI opening message for session {}: {}", sessionId, e.message)
        }

        log.info("Interview started: sessionId={}", sessionId)
    }

    /**
     * Forces the interview to end. Transitions to Evaluating, fires report generation.
     */
    suspend fun forceEndInterview(sessionId: UUID) {
        transition(sessionId, InterviewState.Evaluating)
        getSessionScope(sessionId).launch {
            try {
                reportService.generateAndSaveReport(sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Report generation failed for session {}: {}", sessionId, e.message)
            } finally {
                try { brainService.deleteBrain(sessionId) } catch (e: Exception) { log.debug("Non-critical operation failed: {}", e.message) }
                cancelSessionScope(sessionId)
            }
        }
    }

    /**
     * Transitions the session to [newState] by sending a STATE_CHANGE WebSocket frame.
     * Brain state is derived from interviewGoals.completed — no separate state storage needed.
     */
    suspend fun transition(sessionId: UUID, newState: InterviewState) {
        val stateName = InterviewState.toString(newState)
        registry.sendMessage(sessionId, OutboundMessage.StateChange(state = stateName))
        log.info("Session {} transitioned to {}", sessionId, stateName)
    }

    /**
     * Transitions to the next question in a multi-question interview.
     * Reads question data from DB, updates brain state directly.
     */
    suspend fun transitionToNextQuestion(sessionId: UUID) {
        val brain = brainService.getBrainOrNull(sessionId)
        if (brain == null) {
            log.error("Brain not found for session {} during question transition", sessionId)
            forceEndInterview(sessionId)
            return
        }

        val sessionQuestions = withContext(Dispatchers.IO) {
            sessionQuestionRepository.findBySessionIdOrderByOrderIndex(sessionId)
                .collectList().awaitSingle()
        }

        // Determine next question index from brain's current state
        // Brain doesn't track questionIndex directly — count completed question cycles
        val currentIndex = sessionQuestions.indexOfFirst { sq ->
            sq.questionId.toString() == brain.questionDetails.questionId
        }.coerceAtLeast(0)
        val nextIndex = currentIndex + 1

        if (nextIndex >= sessionQuestions.size) {
            log.warn("No more questions for session {} (index={}), proceeding to evaluation", sessionId, nextIndex)
            forceEndInterview(sessionId)
            return
        }

        val nextSq = sessionQuestions[nextIndex]
        val nextQuestion = withContext(Dispatchers.IO) {
            questionService.getQuestionById(nextSq.questionId)
        }.toInternalDto(objectMapper)

        transition(sessionId, InterviewState.QuestionTransition)

        val templates = nextQuestion.codeTemplates?.let { ct ->
            try {
                @Suppress("UNCHECKED_CAST")
                objectMapper.readValue(ct.toString(), Map::class.java) as? Map<String, String>
            } catch (_: Exception) { null }
        }

        registry.sendMessage(sessionId, OutboundMessage.QuestionTransition(
            questionIndex = nextIndex, questionTitle = nextQuestion.title,
            questionDescription = nextQuestion.description, codeTemplates = templates,
        ))

        transition(sessionId, InterviewState.QuestionPresented)

        val transitionMessage = "Good work on that problem. Let's move on to the next one:\n\n" +
            "**${nextQuestion.title}**\n\n${nextQuestion.description}\n\n" +
            "Take a moment and walk me through your initial thoughts."

        registry.sendMessage(sessionId, OutboundMessage.AiChunk(delta = transitionMessage, done = false))
        registry.sendMessage(sessionId, OutboundMessage.AiChunk(delta = "", done = true))

        // Persist to brain transcript + DB
        brainService.appendTranscriptTurn(sessionId, "AI", transitionMessage)
        try {
            withContext(Dispatchers.IO) {
                conversationMessageRepository.save(
                    ConversationMessage(sessionId = sessionId, role = "AI", content = transitionMessage)
                ).awaitSingle()
            }
        } catch (e: Exception) {
            log.warn("Failed to persist Q{} transition message for {}: {}", nextIndex + 1, sessionId, e.message)
        }

        // Update brain for new question
        try {
            val q = InterviewQuestion(
                questionId = nextQuestion.id?.toString() ?: "", title = nextQuestion.title,
                description = nextQuestion.description, optimalApproach = nextQuestion.optimalApproach ?: "",
                difficulty = nextQuestion.difficulty ?: "MEDIUM", category = brain.interviewType,
            )
            brainService.updateBrain(sessionId) { b ->
                b.copy(questionDetails = q, turnCount = 0,
                    interviewGoals = BrainObjectivesRegistry.forCategory(brain.interviewType))
            }
        } catch (e: Exception) { log.debug("Non-critical operation failed: {}", e.message) }

        log.info("Session {} transitioned to question {} of {}", sessionId, nextIndex + 1, sessionQuestions.size)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun calculateRemainingMinutes(sessionId: UUID): Int = try {
        val session = withContext(Dispatchers.IO) {
            sessionRepository.findById(sessionId).awaitSingleOrNull()
        }
        val durationMinutes = session?.config?.let { configJson ->
            try {
                objectMapper.readValue(configJson, InterviewConfig::class.java).durationMinutes
            } catch (_: Exception) { 45 }
        } ?: 45
        val started = session?.startedAt?.toInstant()
        if (started != null) {
            val elapsed = Duration.between(started, Instant.now()).toMinutes()
            (durationMinutes - elapsed).toInt().coerceAtLeast(0)
        } else 30
    } catch (_: Exception) { 30 }

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
