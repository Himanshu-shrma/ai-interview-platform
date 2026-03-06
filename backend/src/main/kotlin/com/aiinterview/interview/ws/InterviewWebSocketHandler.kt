package com.aiinterview.interview.ws

import com.aiinterview.conversation.ConversationEngine
import com.aiinterview.conversation.InterviewState
import com.aiinterview.interview.service.RedisMemoryService
import com.fasterxml.jackson.databind.ObjectMapper
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
    private val objectMapper: ObjectMapper,
) : WebSocketHandler {

    private val log = LoggerFactory.getLogger(InterviewWebSocketHandler::class.java)

    override fun handle(session: WebSocketSession): Mono<Void> {
        val sessionId = session.attributes[ATTR_SESSION_ID] as? UUID ?: return session.close()
        val userId    = session.attributes[ATTR_USER_ID]    as? UUID ?: return session.close()

        return mono { onConnect(sessionId, userId, session) }
            .flatMap {
                session.receive()
                    .flatMap { wsMsg ->
                        mono { handleMessage(sessionId, wsMsg.payloadAsText) }
                    }
                    .then()
            }
            .doFinally { onDisconnect(sessionId) }
    }

    // ── Connect / Reconnect ───────────────────────────────────────────────────

    private suspend fun onConnect(sessionId: UUID, userId: UUID, session: WebSocketSession) {
        registry.register(sessionId, session)

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
                // TODO (Prompt 10): route to Judge0 code execution
                log.debug("CODE_RUN from {}: language={}", sessionId, msg.language)
            }

            "CODE_UPDATE" -> {
                val msg = objectMapper.treeToValue(tree, InboundMessage.CodeUpdate::class.java)
                redisMemoryService.updateMemory(sessionId) { mem ->
                    mem.copy(currentCode = msg.code, programmingLanguage = msg.language)
                }
            }

            "REQUEST_HINT" -> {
                val msg = objectMapper.treeToValue(tree, InboundMessage.RequestHint::class.java)
                // TODO (Prompt 10): route to HintGenerator agent
                log.debug("REQUEST_HINT from {}: level={}", sessionId, msg.hintLevel)
            }

            "END_INTERVIEW" -> {
                val msg = objectMapper.treeToValue(tree, InboundMessage.EndInterview::class.java)
                // TODO (Prompt 11): trigger evaluation pipeline
                log.info("END_INTERVIEW from {}: reason={}", sessionId, msg.reason)
                conversationEngine.transition(sessionId, InterviewState.InterviewEnd)
                registry.sendMessage(
                    sessionId,
                    OutboundMessage.InterviewEnded(reason = msg.reason, overallScore = 0.0)
                )
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
        log.info("WS disconnected: sessionId={}", sessionId)
    }
}
