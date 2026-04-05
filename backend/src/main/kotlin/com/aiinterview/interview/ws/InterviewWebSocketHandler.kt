package com.aiinterview.interview.ws

import com.aiinterview.code.service.CodeExecutionService
import com.aiinterview.conversation.ConversationEngine
import com.aiinterview.conversation.brain.BrainService
import com.aiinterview.conversation.brain.InterviewerBrain
import com.aiinterview.conversation.HintGenerator
import com.aiinterview.conversation.InterviewState
import com.aiinterview.interview.repository.ConversationMessageRepository
import com.aiinterview.interview.repository.InterviewSessionRepository
import com.aiinterview.interview.repository.QuestionRepository
import com.aiinterview.interview.repository.SessionQuestionRepository
import com.aiinterview.interview.service.RedisMemoryService
import com.aiinterview.report.repository.EvaluationReportRepository
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono
import java.time.OffsetDateTime
import java.util.UUID

@Component
class InterviewWebSocketHandler(
    private val registry: WsSessionRegistry,
    private val redisMemoryService: RedisMemoryService,
    private val conversationEngine: ConversationEngine,
    private val hintGenerator: HintGenerator,
    private val codeExecutionService: CodeExecutionService,
    private val objectMapper: ObjectMapper,
    private val conversationMessageRepository: ConversationMessageRepository,
    private val interviewSessionRepository: InterviewSessionRepository,
    private val evaluationReportRepository: EvaluationReportRepository,
    private val sessionQuestionRepository: SessionQuestionRepository,
    private val questionRepository: QuestionRepository,
    private val brainService: BrainService,
) : WebSocketHandler {

    /** Fire-and-forget scope for code execution (same pattern as ConversationEngine). */
    private val codeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val log = LoggerFactory.getLogger(InterviewWebSocketHandler::class.java)

    // ── Input validation ──
    companion object {
        private const val MAX_MESSAGE_LENGTH = 2000
        private const val MAX_CODE_LENGTH = 51200  // 50KB
        private const val MESSAGE_RATE_LIMIT_MS = 1000L
    }

    private val messageCooldowns = java.util.concurrent.ConcurrentHashMap<UUID, Long>()

    private fun isRateLimited(sessionId: UUID): Boolean {
        val now = System.currentTimeMillis()
        val lastMessage = messageCooldowns[sessionId] ?: 0L
        if (now - lastMessage < MESSAGE_RATE_LIMIT_MS) return true
        messageCooldowns[sessionId] = now
        return false
    }

    override fun handle(session: WebSocketSession): Mono<Void> {
        val sessionId = session.attributes[ATTR_SESSION_ID] as? UUID
            ?: extractSessionIdFromUri(session)
            ?: run {
                log.error("WS handle: no sessionId — attrs={}", session.attributes.keys)
                return session.close()
            }
        val userId = session.attributes[ATTR_USER_ID] as? UUID
            ?: WsAuthHandshakeInterceptor.getAuthenticatedUserId(sessionId)
            ?: run {
                log.error("WS handle: no userId for session={}", sessionId)
                return session.close()
            }

        val outboundFlux = registry.register(sessionId, session)

        val outbound = session.send(
            outboundFlux.map { json -> session.textMessage(json) }
        )

        val inbound = mono { onConnect(sessionId, userId) }
            .thenMany(
                session.receive()
                    .flatMap { wsMsg ->
                        val text = wsMsg.payloadAsText
                        mono { handleMessage(sessionId, text) }
                    }
            )
            .then()

        return inbound.and(outbound)
            .doOnError { e -> log.error("WS error for session {}: {}", sessionId, e.message) }
            .doFinally { onDisconnect(sessionId) }
    }

    private fun extractSessionIdFromUri(session: WebSocketSession): UUID? {
        val path = session.handshakeInfo.uri.path
        val segments = path.split("/").filter { it.isNotBlank() }
        val idStr = segments.lastOrNull() ?: return null
        return try { UUID.fromString(idStr) } catch (_: IllegalArgumentException) { null }
    }

    // ── Connect / Reconnect ───────────────────────────────────────────────────

    private suspend fun onConnect(sessionId: UUID, userId: UUID) {
        // Check DB first — handles completed/abandoned sessions even if Redis expired
        val dbSession = withContext(Dispatchers.IO) {
            interviewSessionRepository.findById(sessionId).awaitSingleOrNull()
        }

        // Edge case: session already completed → redirect to report
        if (dbSession?.status == "COMPLETED") {
            val report = withContext(Dispatchers.IO) {
                evaluationReportRepository.findBySessionId(sessionId).awaitSingleOrNull()
            }
            if (report?.id != null) {
                registry.sendMessage(sessionId, OutboundMessage.SessionEnd(reportId = report.id))
            } else {
                registry.sendMessage(sessionId, OutboundMessage.Error("SESSION_COMPLETED", "Interview already completed"))
            }
            log.info("WS connect to completed session: sessionId={}", sessionId)
            return
        }

        // Edge case: session abandoned or expired
        if (dbSession?.status == "ABANDONED" || dbSession?.status == "EXPIRED") {
            registry.sendMessage(sessionId, OutboundMessage.Error("SESSION_EXPIRED", "This session has expired. Please start a new interview."))
            log.info("WS connect to {}: sessionId={}", dbSession.status, sessionId)
            return
        }

        // Use brain to detect first-connect vs reconnect
        val brain = brainService.getBrainOrNull(sessionId)

        if (brain != null) {
            if (brain.turnCount == 0 && brain.rollingTranscript.isEmpty()) {
                // First connect — brain exists but no turns yet → interview just started
                registry.sendMessage(
                    sessionId,
                    OutboundMessage.InterviewStarted(sessionId = sessionId.toString(), state = "INTERVIEW_STARTING")
                )
                log.info("WS first connect: sessionId={}, userId={}", sessionId, userId)
                conversationEngine.startInterview(sessionId)
            } else {
                // Reconnect — brain has turns, send full state recovery
                handleReconnectFromBrain(sessionId, brain)
            }
        } else {
            // No brain — check if memory exists (legacy fallback) or session is ACTIVE in DB
            val memoryExists = try { redisMemoryService.memoryExists(sessionId) } catch (_: Exception) { false }
            if (memoryExists) {
                // Legacy: memory exists but no brain — start interview (will create brain)
                registry.sendMessage(
                    sessionId,
                    OutboundMessage.InterviewStarted(sessionId = sessionId.toString(), state = "INTERVIEW_STARTING")
                )
                log.info("WS first connect (legacy memory): sessionId={}", sessionId)
                conversationEngine.startInterview(sessionId)
            } else if (dbSession != null && dbSession.status == "ACTIVE") {
                // Redis expired but DB says ACTIVE — try DB recovery
                val recovered = try {
                    redisMemoryService.getMemoryWithFallback(
                        sessionId, interviewSessionRepository, sessionQuestionRepository,
                        conversationMessageRepository, questionRepository,
                    )
                } catch (_: Exception) { null }
                if (recovered != null) {
                    handleReconnectFromDb(sessionId, recovered)
                    log.info("Recovered session {} from DB after Redis miss", sessionId)
                } else {
                    withContext(Dispatchers.IO) {
                        interviewSessionRepository.save(dbSession.copy(status = "ABANDONED")).awaitSingle()
                    }
                    registry.sendMessage(sessionId, OutboundMessage.Error("SESSION_EXPIRED", "Session memory expired. Please start a new interview."))
                    log.warn("Redis memory expired for active session {}; marked abandoned", sessionId)
                }
            } else {
                registry.sendMessage(sessionId, OutboundMessage.Error("SESSION_NOT_FOUND", "Session not found or expired"))
                log.warn("WS connect with no brain or memory: sessionId={}", sessionId)
            }
        }
    }

    /**
     * Sends STATE_SYNC from Brain state on WS reconnect.
     * Brain is the primary state source. Messages loaded from DB.
     */
    private suspend fun handleReconnectFromBrain(sessionId: UUID, brain: InterviewerBrain) {
        val recentMessages = try {
            withContext(Dispatchers.IO) {
                conversationMessageRepository.findRecentBySessionId(sessionId, 50)
                    .collectList().awaitSingle()
            }.reversed()
        } catch (e: Exception) {
            log.warn("Failed to load messages for reconnect session {}: {}", sessionId, e.message)
            emptyList()
        }

        val isCodingType = brain.interviewType.uppercase() in setOf("CODING", "DSA")

        val stateSync = OutboundMessage.StateSync(
            state                = "QUESTION_PRESENTED",
            currentQuestionIndex = 0,
            totalQuestions       = 1,
            currentQuestion      = QuestionSnapshot(
                title = brain.questionDetails.title,
                description = brain.questionDetails.description,
            ),
            currentCode          = brain.currentCode,
            programmingLanguage  = brain.programmingLanguage,
            hintsGiven           = brain.hintsGiven,
            messages             = recentMessages.map { MessageSnapshot(role = it.role, content = it.content) },
            showCodeEditor       = isCodingType || !brain.currentCode.isNullOrBlank(),
        )

        registry.sendMessage(sessionId, stateSync)
        log.info("WS reconnected with STATE_SYNC (brain): sessionId={}, turns={}, messages={}", sessionId, brain.turnCount, recentMessages.size)
    }

    /**
     * Legacy reconnect from DB-recovered InterviewMemory.
     * Used only when brain is missing but DB session is ACTIVE.
     */
    private suspend fun handleReconnectFromDb(sessionId: UUID, memory: com.aiinterview.interview.service.InterviewMemory) {
        val recentMessages = try {
            withContext(Dispatchers.IO) {
                conversationMessageRepository.findRecentBySessionId(sessionId, 50)
                    .collectList().awaitSingle()
            }.reversed()
        } catch (e: Exception) {
            log.warn("Failed to load messages for reconnect session {}: {}", sessionId, e.message)
            emptyList()
        }

        val codingStates = setOf(
            InterviewState.toString(InterviewState.CodingChallenge),
            "CODING_CHALLENGE",
        )

        val stateSync = OutboundMessage.StateSync(
            state                = memory.state,
            currentQuestionIndex = memory.currentQuestionIndex,
            totalQuestions       = memory.totalQuestions,
            currentQuestion      = memory.currentQuestion?.let {
                val templates = it.codeTemplates?.let { ct ->
                    try {
                        @Suppress("UNCHECKED_CAST")
                        objectMapper.readValue(ct.toString(), Map::class.java) as? Map<String, String>
                    } catch (_: Exception) { null }
                }
                QuestionSnapshot(title = it.title, description = it.description, codeTemplates = templates)
            },
            currentCode          = memory.currentCode,
            programmingLanguage  = memory.programmingLanguage,
            hintsGiven           = memory.hintsGiven,
            messages             = recentMessages.map { MessageSnapshot(role = it.role, content = it.content) },
            showCodeEditor       = memory.state in codingStates || !memory.currentCode.isNullOrBlank(),
        )

        registry.sendMessage(sessionId, stateSync)
        log.info("WS reconnected with STATE_SYNC (db-recovery): sessionId={}, state={}, messages={}", sessionId, memory.state, recentMessages.size)
    }

    // ── Message Routing ───────────────────────────────────────────────────────

    private suspend fun handleMessage(sessionId: UUID, raw: String) {
        val tree = try {
            objectMapper.readTree(raw)
        } catch (e: Exception) {
            log.warn("Unparseable WS message from sessionId={}: {}", sessionId, e.message)
            registry.sendMessage(sessionId, OutboundMessage.Error("PARSE_ERROR", "Invalid message format"))
            return
        }

        val type = tree.get("type")?.asText()
        when (type) {
            "PING" -> {
                registry.sendMessage(sessionId, OutboundMessage.Pong())
                codeScope.launch {
                    try {
                        val session = interviewSessionRepository.findById(sessionId).awaitSingleOrNull()
                        if (session != null && session.status == "ACTIVE") {
                            interviewSessionRepository.save(session.copy(lastHeartbeat = OffsetDateTime.now())).awaitSingle()
                        }
                    } catch (e: Exception) {
                        log.debug("Heartbeat persist failed for session {}: {}", sessionId, e.message)
                    }
                }
            }

            "CANDIDATE_MESSAGE" -> {
                val msg = objectMapper.treeToValue(tree, InboundMessage.CandidateMessage::class.java)

                if (msg.text.isNullOrBlank()) {
                    registry.sendMessage(sessionId, OutboundMessage.Error("INVALID_MESSAGE", "Message cannot be empty"))
                    return
                }
                if (msg.text.length > MAX_MESSAGE_LENGTH) {
                    registry.sendMessage(sessionId, OutboundMessage.Error("INVALID_MESSAGE", "Message too long (max $MAX_MESSAGE_LENGTH characters)"))
                    return
                }
                msg.codeSnapshot?.content?.let { code ->
                    if (code.length > MAX_CODE_LENGTH) {
                        registry.sendMessage(sessionId, OutboundMessage.Error("INVALID_MESSAGE", "Code content too large (max 50KB)"))
                        return
                    }
                }
                if (isRateLimited(sessionId)) {
                    registry.sendMessage(sessionId, OutboundMessage.Error("RATE_LIMITED", "Please wait before sending another message"))
                    return
                }

                // Sync code snapshot into brain (if provided)
                msg.codeSnapshot?.let { snap ->
                    if (snap.hasMeaningfulCode && !snap.content.isNullOrBlank()) {
                        try {
                            brainService.updateBrain(sessionId) { b ->
                                b.copy(
                                    currentCode = snap.content,
                                    programmingLanguage = snap.language ?: b.programmingLanguage,
                                )
                            }
                        } catch (e: Exception) {
                            log.debug("Failed to sync code snapshot for session {}: {}", sessionId, e.message)
                        }
                    }
                }
                conversationEngine.handleCandidateMessage(sessionId, msg.text)
            }

            "CODE_RUN" -> {
                val msg = objectMapper.treeToValue(tree, InboundMessage.CodeRun::class.java)
                val handler = CoroutineExceptionHandler { _, e ->
                    log.error("CODE_RUN failed for session {}: {}", sessionId, e.message)
                }
                codeScope.launch(handler) {
                    codeExecutionService.runCode(sessionId, msg.code, msg.language, msg.stdin)
                }
            }

            "CODE_SUBMIT" -> {
                val msg = objectMapper.treeToValue(tree, InboundMessage.CodeSubmit::class.java)
                val handler = CoroutineExceptionHandler { _, e ->
                    log.error("CODE_SUBMIT failed for session {}: {}", sessionId, e.message)
                }
                codeScope.launch(handler) {
                    codeExecutionService.submitCode(sessionId, msg.sessionQuestionId, msg.code, msg.language)
                }
            }

            "CODE_UPDATE" -> {
                val msg = objectMapper.treeToValue(tree, InboundMessage.CodeUpdate::class.java)
                // Sync code to brain for AI code awareness
                try {
                    brainService.updateBrain(sessionId) { b ->
                        b.copy(currentCode = msg.code, programmingLanguage = msg.language)
                    }
                } catch (e: Exception) {
                    log.warn("Failed to sync code to brain for session={}: {}", sessionId, e.message)
                }
            }

            "REQUEST_HINT" -> {
                try {
                    hintGenerator.generateHint(sessionId)
                } catch (e: Exception) {
                    log.error("Failed to generate hint for session {}: {}", sessionId, e.message)
                    registry.sendMessage(sessionId, OutboundMessage.Error("HINT_ERROR", "Failed to generate hint"))
                }
            }

            "END_INTERVIEW" -> {
                val msg = objectMapper.treeToValue(tree, InboundMessage.EndInterview::class.java)
                log.info("END_INTERVIEW from {}: reason={}", sessionId, msg.reason)
                conversationEngine.forceEndInterview(sessionId)
            }

            else -> {
                log.warn("Unknown WS message type from sessionId={}: {}", sessionId, type)
                registry.sendMessage(sessionId, OutboundMessage.Error("UNKNOWN_TYPE", "Unknown message type: $type"))
            }
        }
    }

    // ── Disconnect ────────────────────────────────────────────────────────────

    private fun onDisconnect(sessionId: UUID) {
        registry.deregister(sessionId)
        messageCooldowns.remove(sessionId)
        WsAuthHandshakeInterceptor.removeAuthenticatedSession(sessionId)
        log.info("WS disconnected: sessionId={}", sessionId)
    }
}
