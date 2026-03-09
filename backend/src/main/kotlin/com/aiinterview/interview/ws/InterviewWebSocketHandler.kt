package com.aiinterview.interview.ws

import com.aiinterview.code.service.CodeExecutionService
import com.aiinterview.conversation.ConversationEngine
import com.aiinterview.conversation.HintGenerator
import com.aiinterview.conversation.InterviewState
import com.aiinterview.interview.service.RedisMemoryService
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono
import java.util.UUID

@Component
class InterviewWebSocketHandler(
    private val registry: WsSessionRegistry,
    private val redisMemoryService: RedisMemoryService,
    private val conversationEngine: ConversationEngine,
    private val hintGenerator: HintGenerator,
    private val codeExecutionService: CodeExecutionService,
    private val objectMapper: ObjectMapper,
) : WebSocketHandler {

    /** Fire-and-forget scope for code execution (same pattern as ConversationEngine). */
    private val codeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val log = LoggerFactory.getLogger(InterviewWebSocketHandler::class.java)

    override fun handle(session: WebSocketSession): Mono<Void> {
        // Primary: exchange attributes set by WsAuthHandshakeInterceptor.
        // Fallback: extract sessionId from URI path + lookup userId from auth cache
        // (exchange attribute propagation to WebSocketSession can fail in some configs).
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

        // Register and get the outbound Flux (backed by a Sinks.Many).
        // Messages pushed via registry.sendMessage() flow through this single send() call,
        // preventing ByteBuf refCnt issues from multiple session.send() calls.
        val outboundFlux = registry.register(sessionId, session)

        val outbound = session.send(
            outboundFlux.map { json -> session.textMessage(json) }
        )

        val inbound = mono { onConnect(sessionId, userId) }
            .thenMany(
                session.receive()
                    .flatMap { wsMsg ->
                        // Extract payload text BEFORE entering the coroutine.
                        // The ByteBuf backing wsMsg is released after flatMap returns;
                        // accessing payloadAsText inside mono{} causes refCnt: 0.
                        val text = wsMsg.payloadAsText
                        mono { handleMessage(sessionId, text) }
                    }
            )
            .then()

        return inbound.and(outbound)
            .doOnError { e -> log.error("WS error for session {}: {}", sessionId, e.message) }
            .doFinally { onDisconnect(sessionId) }
    }

    /** Extract sessionId UUID from the WS URI path: /ws/interview/{sessionId} */
    private fun extractSessionIdFromUri(session: WebSocketSession): UUID? {
        val path = session.handshakeInfo.uri.path
        val segments = path.split("/").filter { it.isNotBlank() }
        val idStr = segments.lastOrNull() ?: return null
        return try { UUID.fromString(idStr) } catch (_: IllegalArgumentException) { null }
    }

    // ── Connect / Reconnect ───────────────────────────────────────────────────

    private suspend fun onConnect(sessionId: UUID, userId: UUID) {

        val memoryExists = redisMemoryService.memoryExists(sessionId)
        if (memoryExists) {
            val memory = redisMemoryService.getMemory(sessionId)
            if (memory.state == InterviewState.toString(InterviewState.InterviewStarting)) {
                // First connect — send INTERVIEW_STARTED, then kick off the opening sequence
                registry.sendMessage(
                    sessionId,
                    OutboundMessage.InterviewStarted(sessionId = sessionId.toString(), state = memory.state)
                )
                log.info("WS first connect: sessionId={}, userId={}", sessionId, userId)
                conversationEngine.startInterview(sessionId)
            } else {
                // Reconnect — resume from current state
                registry.sendMessage(sessionId, OutboundMessage.StateChange(state = memory.state))
                log.info("WS reconnected: sessionId={}, userId={}, state={}", sessionId, userId, memory.state)
            }
        } else {
            // Memory not found — session expired or invalid
            registry.sendMessage(sessionId, OutboundMessage.Error("SESSION_NOT_FOUND", "Session not found or expired"))
            log.warn("WS connect with no memory: sessionId={}", sessionId)
        }
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
            "PING" -> registry.sendMessage(sessionId, OutboundMessage.Pong())

            "CANDIDATE_MESSAGE" -> {
                val msg = objectMapper.treeToValue(tree, InboundMessage.CandidateMessage::class.java)
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
                redisMemoryService.updateMemory(sessionId) { mem ->
                    mem.copy(currentCode = msg.code, programmingLanguage = msg.language)
                }
            }

            "REQUEST_HINT" -> {
                try {
                    val memory = redisMemoryService.getMemory(sessionId)
                    hintGenerator.generateHint(memory)
                } catch (e: Exception) {
                    log.error("Failed to generate hint for session {}: {}", sessionId, e.message)
                    registry.sendMessage(sessionId, OutboundMessage.Error("HINT_ERROR", "Failed to generate hint"))
                }
            }

            "END_INTERVIEW" -> {
                val msg = objectMapper.treeToValue(tree, InboundMessage.EndInterview::class.java)
                log.info("END_INTERVIEW from {}: reason={}", sessionId, msg.reason)
                // Transitions to Evaluating → fires report generation → sends SESSION_END via WS
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
        WsAuthHandshakeInterceptor.removeAuthenticatedSession(sessionId)
        log.info("WS disconnected: sessionId={}", sessionId)
    }
}
