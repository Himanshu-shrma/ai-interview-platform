package com.aiinterview.interview.ws

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val HEARTBEAT_INTERVAL_MS = 30_000L

@Component
class WsSessionRegistry(private val objectMapper: ObjectMapper) {

    private val log = LoggerFactory.getLogger(WsSessionRegistry::class.java)

    /** sessionId → active WebSocketSession */
    private val sessions = ConcurrentHashMap<UUID, WebSocketSession>()

    /** sessionId → outbound message sink (pushes JSON strings to the WS send stream) */
    private val sinks = ConcurrentHashMap<UUID, Sinks.Many<String>>()

    private val scope = CoroutineScope(Dispatchers.IO)
    private lateinit var heartbeatJob: Job

    @PostConstruct
    fun startHeartbeat() {
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                pruneDeadSessions()
                sendPingToAll()
            }
        }
    }

    @PreDestroy
    fun shutdown() {
        scope.cancel()
    }

    /**
     * Registers a session and creates its outbound sink.
     * Returns a [Flux] that the handler must wire to [WebSocketSession.send].
     */
    fun register(sessionId: UUID, wsSession: WebSocketSession): Flux<String> {
        sessions[sessionId] = wsSession
        val sink = Sinks.many().unicast().onBackpressureBuffer<String>()
        sinks[sessionId] = sink
        log.debug("WS registered: sessionId={}", sessionId)
        return sink.asFlux()
    }

    fun deregister(sessionId: UUID) {
        sessions.remove(sessionId)
        sinks.remove(sessionId)?.tryEmitComplete()
        log.debug("WS deregistered: sessionId={}", sessionId)
    }

    fun get(sessionId: UUID): WebSocketSession? = sessions[sessionId]

    fun isConnected(sessionId: UUID): Boolean = sessions.containsKey(sessionId)

    /**
     * Pushes a message to the session's outbound sink.
     * The sink is drained by the single [WebSocketSession.send] subscription
     * wired in the handler — no per-message [session.send()] calls, so no
     * ByteBuf reference-count issues.
     */
    suspend fun sendMessage(sessionId: UUID, message: OutboundMessage): Boolean {
        val sink = sinks[sessionId] ?: return false
        return try {
            val json = objectMapper.writeValueAsString(message)
            val result = sink.tryEmitNext(json)
            if (result.isFailure) {
                log.warn("Failed to emit message to sessionId={}: {}", sessionId, result)
                false
            } else {
                true
            }
        } catch (e: Exception) {
            log.warn("Failed to send message to sessionId={}: {}", sessionId, e.message)
            false
        }
    }

    private fun pruneDeadSessions() {
        val dead = sessions.entries.filter { !it.value.isOpen }.map { it.key }
        dead.forEach { id ->
            deregister(id)
            log.debug("WS pruned dead session: sessionId={}", id)
        }
    }

    private fun sendPingToAll() {
        sessions.keys.toList().forEach { id ->
            scope.launch {
                sendMessage(id, OutboundMessage.Pong())
            }
        }
    }

    fun sessionCount(): Int = sessions.size
}
