package com.aiinterview.interview

import com.aiinterview.code.service.CodeExecutionService
import com.aiinterview.conversation.ConversationEngine
import com.aiinterview.conversation.HintGenerator
import com.aiinterview.interview.repository.ConversationMessageRepository
import com.aiinterview.interview.repository.InterviewSessionRepository
import com.aiinterview.interview.service.InterviewMemory
import com.aiinterview.interview.service.RedisMemoryService
import com.aiinterview.interview.ws.ATTR_SESSION_ID
import com.aiinterview.interview.ws.ATTR_USER_ID
import com.aiinterview.interview.ws.InterviewWebSocketHandler
import com.aiinterview.interview.ws.OutboundMessage
import com.aiinterview.interview.ws.WsSessionRegistry
import com.aiinterview.report.repository.EvaluationReportRepository
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.time.Instant
import java.util.UUID

class InterviewWebSocketHandlerTest {

    private val objectMapper: ObjectMapper = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
        disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private val registry                    = mockk<WsSessionRegistry>(relaxed = true)
    private val memoryService               = mockk<RedisMemoryService>()
    private val conversationEngine          = mockk<ConversationEngine>(relaxed = true)
    private val hintGenerator               = mockk<HintGenerator>(relaxed = true)
    private val codeExecutionService        = mockk<CodeExecutionService>(relaxed = true)
    private val conversationMessageRepo     = mockk<ConversationMessageRepository>(relaxed = true)
    private val interviewSessionRepo        = mockk<InterviewSessionRepository>(relaxed = true)
    private val evaluationReportRepo        = mockk<EvaluationReportRepository>(relaxed = true)
    private val handler                     = InterviewWebSocketHandler(registry, memoryService, conversationEngine, hintGenerator, codeExecutionService, objectMapper, conversationMessageRepo, interviewSessionRepo, evaluationReportRepo)

    private val sessionId = UUID.randomUUID()
    private val userId    = UUID.randomUUID()

    private lateinit var wsSession: WebSocketSession
    private lateinit var inboundSink: Sinks.Many<WebSocketMessage>

    @BeforeEach
    fun setup() {
        wsSession   = mockk(relaxed = true)
        inboundSink = Sinks.many().unicast().onBackpressureBuffer()

        every { wsSession.attributes } returns mutableMapOf<String, Any>(
            ATTR_SESSION_ID to sessionId,
            ATTR_USER_ID    to userId,
        )
        every { wsSession.receive() } returns inboundSink.asFlux()
        every { wsSession.close()   } returns Mono.empty()
        every { wsSession.isOpen    } returns true
    }

    // ── connect / reconnect ───────────────────────────────────────────────────

    @Test
    fun `connect with memory in INTERVIEW_STARTING state sends INTERVIEW_STARTED`() {
        coEvery { memoryService.memoryExists(sessionId) } returns true
        coEvery { memoryService.getMemory(sessionId) } returns buildMemory("INTERVIEW_STARTING")
        coEvery { registry.sendMessage(sessionId, any()) } returns true

        inboundSink.tryEmitComplete()
        handler.handle(wsSession).block()

        coVerify {
            registry.sendMessage(sessionId, match { it is OutboundMessage.InterviewStarted })
        }
    }

    @Test
    fun `connect with no existing memory sends SESSION_NOT_FOUND error`() {
        coEvery { memoryService.memoryExists(sessionId) } returns false
        coEvery { registry.sendMessage(sessionId, any()) } returns true

        inboundSink.tryEmitComplete()
        handler.handle(wsSession).block()

        coVerify {
            registry.sendMessage(sessionId, match {
                it is OutboundMessage.Error && it.code == "SESSION_NOT_FOUND"
            })
        }
    }

    @Test
    fun `reconnect with existing memory sends STATE_CHANGE`() {
        coEvery { memoryService.memoryExists(sessionId) } returns true
        coEvery { memoryService.getMemory(sessionId) } returns buildMemory("CANDIDATE_RESPONSE")
        coEvery { registry.sendMessage(sessionId, any()) } returns true

        inboundSink.tryEmitComplete()
        handler.handle(wsSession).block()

        coVerify {
            registry.sendMessage(sessionId, match {
                it is OutboundMessage.StateChange && it.state == "CANDIDATE_RESPONSE"
            })
        }
    }

    // ── PING ────────────────────────────────────────────────────────────────

    @Test
    fun `PING message returns PONG`() {
        coEvery { memoryService.memoryExists(sessionId) } returns false
        coEvery { registry.sendMessage(sessionId, any()) } returns true

        inboundSink.tryEmitNext(mockMessage("""{"type":"PING"}"""))
        inboundSink.tryEmitComplete()
        handler.handle(wsSession).block()

        coVerify { registry.sendMessage(sessionId, match { it is OutboundMessage.Pong }) }
    }

    // ── CODE_UPDATE ──────────────────────────────────────────────────────────

    @Test
    fun `CODE_UPDATE persists code snapshot in Redis memory`() {
        coEvery { memoryService.memoryExists(sessionId) } returns false
        coEvery { registry.sendMessage(sessionId, any()) } returns true
        coEvery { memoryService.updateMemory(sessionId, any()) } returns buildMemory("CODING")

        inboundSink.tryEmitNext(mockMessage("""{"type":"CODE_UPDATE","code":"val x = 1","language":"kotlin"}"""))
        inboundSink.tryEmitComplete()
        handler.handle(wsSession).block()

        coVerify { memoryService.updateMemory(sessionId, any()) }
    }

    // ── END_INTERVIEW ────────────────────────────────────────────────────────

    @Test
    fun `END_INTERVIEW triggers forceEndInterview on conversation engine`() {
        coEvery { memoryService.memoryExists(sessionId) } returns false
        coEvery { registry.sendMessage(sessionId, any()) } returns true

        inboundSink.tryEmitNext(mockMessage("""{"type":"END_INTERVIEW","reason":"CANDIDATE_ENDED"}"""))
        inboundSink.tryEmitComplete()
        handler.handle(wsSession).block()

        coVerify { conversationEngine.forceEndInterview(sessionId) }
    }

    // ── invalid message ──────────────────────────────────────────────────────

    @Test
    fun `unparseable message sends ERROR and continues`() {
        coEvery { memoryService.memoryExists(sessionId) } returns false
        coEvery { registry.sendMessage(sessionId, any()) } returns true

        inboundSink.tryEmitNext(mockMessage("""not valid json at all"""))
        inboundSink.tryEmitComplete()
        handler.handle(wsSession).block()

        coVerify { registry.sendMessage(sessionId, match { it is OutboundMessage.Error }) }
    }

    // ── registry lifecycle ───────────────────────────────────────────────────

    @Test
    fun `session is registered on connect and deregistered on disconnect`() {
        coEvery { memoryService.memoryExists(sessionId) } returns false
        coEvery { registry.sendMessage(sessionId, any()) } returns true

        inboundSink.tryEmitComplete()
        handler.handle(wsSession).block()

        coVerify { registry.register(sessionId, wsSession) }
        coVerify { registry.deregister(sessionId) }
    }

    // ── missing attributes ───────────────────────────────────────────────────

    @Test
    fun `missing ATTR_SESSION_ID closes session immediately`() {
        every { wsSession.attributes } returns mutableMapOf<String, Any>()  // no sessionId

        handler.handle(wsSession).block()

        coVerify(exactly = 0) { registry.register(any(), any()) }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun buildMemory(state: String) = InterviewMemory(
        sessionId         = sessionId,
        userId            = userId,
        state             = state,
        category          = "CODING",
        personality       = "friendly",
        currentQuestion   = null,
        candidateAnalysis = null,
        createdAt         = Instant.now(),
        lastActivityAt    = Instant.now(),
    )

    private fun mockMessage(text: String): WebSocketMessage =
        mockk<WebSocketMessage>().also { every { it.payloadAsText } returns text }
}
