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
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val HEARTBEAT_INTERVAL_MS = 30_000L

@Component
class WsSessionRegistry(private val objectMapper: ObjectMapper) {

    private val log = LoggerFactory.getLogger(WsSessionRegistry::class.java)

    /** sessionId → active WebSocketSession */
    private val sessions = ConcurrentHashMap<UUID, WebSocketSession>()

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

    fun register(sessionId: UUID, wsSession: WebSocketSession) {
        sessions[sessionId] = wsSession
        log.debug("WS registered: sessionId={}", sessionId)
    }

    fun deregister(sessionId: UUID) {
        sessions.remove(sessionId)
        log.debug("WS deregistered: sessionId={}", sessionId)
    }

    fun get(sessionId: UUID): WebSocketSession? = sessions[sessionId]

    fun isConnected(sessionId: UUID): Boolean = sessions.containsKey(sessionId)

    suspend fun sendMessage(sessionId: UUID, message: OutboundMessage): Boolean {
        val session = sessions[sessionId] ?: return false
        if (!session.isOpen) {
            deregister(sessionId)
            return false
        }
        return try {
            val json = objectMapper.writeValueAsString(message)
            val wsMessage = session.textMessage(json)
            session.send(Mono.just(wsMessage)).awaitSingleOrNull()
            true
        } catch (e: Exception) {
            log.warn("Failed to send message to sessionId={}: {}", sessionId, e.message)
            false
        }
    }

    private fun pruneDeadSessions() {
        val dead = sessions.entries.filter { !it.value.isOpen }.map { it.key }
        dead.forEach { id ->
            sessions.remove(id)
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
