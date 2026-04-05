package com.aiinterview.interview

import com.aiinterview.conversation.ConversationEngine
import com.aiinterview.conversation.InterviewState
import com.aiinterview.conversation.brain.BrainFlowGuard
import com.aiinterview.conversation.brain.BrainService
import com.aiinterview.conversation.brain.TheAnalyst
import com.aiinterview.conversation.brain.TheConductor
import com.aiinterview.conversation.brain.TheStrategist
import com.aiinterview.interview.model.ConversationMessage
import com.aiinterview.interview.repository.ConversationMessageRepository
import com.aiinterview.interview.repository.InterviewSessionRepository
import com.aiinterview.interview.repository.SessionQuestionRepository
import com.aiinterview.interview.service.InterviewMemory
import com.aiinterview.interview.service.QuestionService
import com.aiinterview.interview.service.RedisMemoryService
import com.aiinterview.interview.ws.OutboundMessage
import com.aiinterview.interview.ws.WsSessionRegistry
import com.aiinterview.report.service.ReportService
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.UUID

class ConversationEngineTest {

    private val redisMemoryService = mockk<RedisMemoryService>()
    private val registry = mockk<WsSessionRegistry>(relaxed = true)
    private val messageRepository = mockk<ConversationMessageRepository>()
    private val sessionQuestionRepository = mockk<SessionQuestionRepository>(relaxed = true)
    private val sessionRepository = mockk<InterviewSessionRepository>(relaxed = true)
    private val questionService = mockk<QuestionService>(relaxed = true)
    private val objectMapper = ObjectMapper()
    private val reportService = mockk<ReportService>(relaxed = true)
    private val brainService = mockk<BrainService>(relaxed = true)
    private val theConductor = mockk<TheConductor>(relaxed = true)
    private val theAnalyst = mockk<TheAnalyst>(relaxed = true)
    private val theStrategist = mockk<TheStrategist>(relaxed = true)
    private val brainFlowGuard = mockk<BrainFlowGuard>(relaxed = true)

    private val engine = ConversationEngine(
        redisMemoryService = redisMemoryService,
        registry = registry,
        conversationMessageRepository = messageRepository,
        sessionQuestionRepository = sessionQuestionRepository,
        sessionRepository = sessionRepository,
        questionService = questionService,
        objectMapper = objectMapper,
        reportService = reportService,
        brainService = brainService,
        theConductor = theConductor,
        theAnalyst = theAnalyst,
        theStrategist = theStrategist,
        brainFlowGuard = brainFlowGuard,
    )

    private val sessionId = UUID.randomUUID()
    private val userId = UUID.randomUUID()

    // ── transition ────────────────────────────────────────────────────────────

    @Test
    fun `transition sends STATE_CHANGE WS message`() {
        coEvery { redisMemoryService.updateMemory(sessionId, any()) } returns buildMemory("CANDIDATE_RESPONDING")
        coEvery { registry.sendMessage(sessionId, any()) } returns true

        runTest {
            engine.transition(sessionId, InterviewState.CandidateResponding)
        }

        coVerify {
            registry.sendMessage(sessionId, match {
                it is OutboundMessage.StateChange && it.state == "CANDIDATE_RESPONDING"
            })
        }
    }

    // ── startInterview ────────────────────────────────────────────────────────

    @Test
    fun `startInterview transitions to QuestionPresented`() {
        coEvery { redisMemoryService.getMemory(sessionId) } returns buildMemory("INTERVIEW_STARTING")
        coEvery { redisMemoryService.updateMemory(sessionId, any()) } returns buildMemory("QUESTION_PRESENTED")
        coEvery { redisMemoryService.appendTranscriptTurn(sessionId, any(), any()) } returns buildMemory("QUESTION_PRESENTED")
        coEvery { registry.sendMessage(sessionId, any()) } returns true
        every { messageRepository.save(any<ConversationMessage>()) } returns Mono.just(
            ConversationMessage(sessionId = sessionId, role = "AI", content = "test")
        )
        every { sessionRepository.findById(sessionId) } returns Mono.empty()

        runTest {
            engine.startInterview(sessionId)
        }

        coVerify {
            registry.sendMessage(sessionId, match {
                it is OutboundMessage.StateChange && it.state == "QUESTION_PRESENTED"
            })
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun buildMemory(state: String) = InterviewMemory(
        sessionId = sessionId,
        userId = userId,
        state = state,
        category = "CODING",
        personality = "friendly_mentor",
        currentQuestion = null,
        candidateAnalysis = null,
        createdAt = Instant.now(),
        lastActivityAt = Instant.now(),
    )
}
