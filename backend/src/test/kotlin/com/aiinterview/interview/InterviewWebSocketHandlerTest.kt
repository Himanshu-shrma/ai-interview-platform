package com.aiinterview.interview

import com.aiinterview.code.service.CodeExecutionService
import com.aiinterview.conversation.ConversationEngine
import com.aiinterview.conversation.HintGenerator
import com.aiinterview.conversation.brain.BrainService
import com.aiinterview.conversation.brain.InterviewQuestion
import com.aiinterview.conversation.brain.InterviewerBrain
import com.aiinterview.interview.repository.ConversationMessageRepository
import com.aiinterview.interview.repository.InterviewSessionRepository
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
import java.util.UUID

class InterviewWebSocketHandlerTest {

    private val objectMapper: ObjectMapper = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
        disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private val registry                    = mockk<WsSessionRegistry>(relaxed = true)
    private val memoryService               = mockk<RedisMemoryService>(relaxed = true)
    private val conversationEngine          = mockk<ConversationEngine>(relaxed = true)
    private val hintGenerator               = mockk<HintGenerator>(relaxed = true)
    private val codeExecutionService        = mockk<CodeExecutionService>(relaxed = true)
    private val conversationMessageRepo     = mockk<ConversationMessageRepository>(relaxed = true)
    private val interviewSessionRepo        = mockk<InterviewSessionRepository>(relaxed = true)
    private val evaluationReportRepo        = mockk<EvaluationReportRepository>(relaxed = true)
    private val sessionQuestionRepo         = mockk<com.aiinterview.interview.repository.SessionQuestionRepository>(relaxed = true)
    private val questionRepo                = mockk<com.aiinterview.interview.repository.QuestionRepository>(relaxed = true)
    private val brainService                = mockk<BrainService>(relaxed = true)
    private val handler                     = InterviewWebSocketHandler(registry, memoryService, conversationEngine, hintGenerator, codeExecutionService, objectMapper, conversationMessageRepo, interviewSessionRepo, evaluationReportRepo, sessionQuestionRepo, questionRepo, brainService)

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
    fun `connect with brain at turn 0 sends INTERVIEW_STARTED`() {
        val freshBrain = buildBrain(turnCount = 0, emptyTranscript = true)
        coEvery { brainService.getBrainOrNull(sessionId) } returns freshBrain

        inboundSink.tryEmitComplete()
        handler.handle(wsSession).block()

        coVerify {
            registry.sendMessage(sessionId, match { it is OutboundMessage.InterviewStarted })
        }
    }

    @Test
    fun `connect with no brain and no memory sends SESSION_NOT_FOUND`() {
        coEvery { brainService.getBrainOrNull(sessionId) } returns null
        coEvery { memoryService.memoryExists(sessionId) } returns false

        inboundSink.tryEmitComplete()
        handler.handle(wsSession).block()

        coVerify {
            registry.sendMessage(sessionId, match {
                it is OutboundMessage.Error && it.code == "SESSION_NOT_FOUND"
            })
        }
    }

    @Test
    fun `reconnect with brain at turn 5 sends STATE_SYNC`() {
        val existingBrain = buildBrain(turnCount = 5, emptyTranscript = false)
        coEvery { brainService.getBrainOrNull(sessionId) } returns existingBrain

        inboundSink.tryEmitComplete()
        handler.handle(wsSession).block()

        coVerify {
            registry.sendMessage(sessionId, match { it is OutboundMessage.StateSync })
        }
    }

    // ── PING ────────────────────────────────────────────────────────────────

    @Test
    fun `PING message returns PONG`() {
        coEvery { brainService.getBrainOrNull(sessionId) } returns null
        coEvery { memoryService.memoryExists(sessionId) } returns false

        inboundSink.tryEmitNext(mockMessage("""{"type":"PING"}"""))
        inboundSink.tryEmitComplete()
        handler.handle(wsSession).block()

        coVerify { registry.sendMessage(sessionId, match { it is OutboundMessage.Pong }) }
    }

    // ── CODE_UPDATE ──────────────────────────────────────────────────────────

    @Test
    fun `CODE_UPDATE syncs code to brain`() {
        coEvery { brainService.getBrainOrNull(sessionId) } returns null
        coEvery { memoryService.memoryExists(sessionId) } returns false

        inboundSink.tryEmitNext(mockMessage("""{"type":"CODE_UPDATE","code":"val x = 1","language":"kotlin"}"""))
        inboundSink.tryEmitComplete()
        handler.handle(wsSession).block()

        coVerify { brainService.updateBrain(sessionId, any()) }
    }

    // ── END_INTERVIEW ────────────────────────────────────────────────────────

    @Test
    fun `END_INTERVIEW triggers forceEndInterview on conversation engine`() {
        coEvery { brainService.getBrainOrNull(sessionId) } returns null
        coEvery { memoryService.memoryExists(sessionId) } returns false

        inboundSink.tryEmitNext(mockMessage("""{"type":"END_INTERVIEW","reason":"CANDIDATE_ENDED"}"""))
        inboundSink.tryEmitComplete()
        handler.handle(wsSession).block()

        coVerify { conversationEngine.forceEndInterview(sessionId) }
    }

    // ── invalid message ──────────────────────────────────────────────────────

    @Test
    fun `unparseable message sends ERROR and continues`() {
        coEvery { brainService.getBrainOrNull(sessionId) } returns null
        coEvery { memoryService.memoryExists(sessionId) } returns false

        inboundSink.tryEmitNext(mockMessage("""not valid json at all"""))
        inboundSink.tryEmitComplete()
        handler.handle(wsSession).block()

        coVerify { registry.sendMessage(sessionId, match { it is OutboundMessage.Error }) }
    }

    // ── registry lifecycle ───────────────────────────────────────────────────

    @Test
    fun `session is registered on connect and deregistered on disconnect`() {
        coEvery { brainService.getBrainOrNull(sessionId) } returns null
        coEvery { memoryService.memoryExists(sessionId) } returns false

        inboundSink.tryEmitComplete()
        handler.handle(wsSession).block()

        coVerify { registry.register(sessionId, wsSession) }
        coVerify { registry.deregister(sessionId) }
    }

    // ── missing attributes ───────────────────────────────────────────────────

    @Test
    fun `missing ATTR_SESSION_ID closes session immediately`() {
        every { wsSession.attributes } returns mutableMapOf<String, Any>()

        handler.handle(wsSession).block()

        coVerify(exactly = 0) { registry.register(any(), any()) }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun buildBrain(turnCount: Int, emptyTranscript: Boolean) = InterviewerBrain(
        sessionId = sessionId,
        userId = userId,
        interviewType = "CODING",
        questionDetails = InterviewQuestion(title = "Two Sum", description = "Find two numbers...", difficulty = "MEDIUM", category = "CODING"),
        turnCount = turnCount,
        rollingTranscript = if (emptyTranscript) emptyList() else listOf(
            com.aiinterview.conversation.brain.BrainTranscriptTurn(role = "AI", content = "Hello"),
        ),
    )

    private fun mockMessage(text: String): WebSocketMessage =
        mockk<WebSocketMessage>().also { every { it.payloadAsText } returns text }
}
