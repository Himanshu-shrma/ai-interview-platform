package com.aiinterview.interview

import com.aiinterview.conversation.ConversationEngine
import com.aiinterview.conversation.InterviewState
import com.aiinterview.conversation.brain.BrainFlowGuard
import com.aiinterview.conversation.brain.BrainService
import com.aiinterview.conversation.brain.TheAnalyst
import com.aiinterview.conversation.brain.TheConductor
import com.aiinterview.conversation.brain.TheStrategist
import com.aiinterview.interview.model.ConversationMessage
import com.aiinterview.interview.model.InterviewSession
import com.aiinterview.interview.model.SessionQuestion
import com.aiinterview.interview.model.Question
import com.aiinterview.interview.repository.ConversationMessageRepository
import com.aiinterview.interview.repository.InterviewSessionRepository
import com.aiinterview.interview.repository.SessionQuestionRepository
import com.aiinterview.interview.service.QuestionService
import com.aiinterview.interview.ws.OutboundMessage
import com.aiinterview.interview.ws.WsSessionRegistry
import com.aiinterview.memory.service.CandidateMemoryService
import com.aiinterview.report.service.ReportService
import com.aiinterview.user.repository.UserRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.OffsetDateTime
import java.util.UUID

class ConversationEngineTest {

    private val registry = mockk<WsSessionRegistry>(relaxed = true)
    private val messageRepository = mockk<ConversationMessageRepository>()
    private val sessionQuestionRepository = mockk<SessionQuestionRepository>(relaxed = true)
    private val sessionRepository = mockk<InterviewSessionRepository>(relaxed = true)
    private val questionService = mockk<QuestionService>(relaxed = true)
    private val objectMapper = jacksonObjectMapper().apply { findAndRegisterModules() }
    private val reportService = mockk<ReportService>(relaxed = true)
    private val brainService = mockk<BrainService>(relaxed = true)
    private val theConductor = mockk<TheConductor>(relaxed = true)
    private val theAnalyst = mockk<TheAnalyst>(relaxed = true)
    private val theStrategist = mockk<TheStrategist>(relaxed = true)
    private val brainFlowGuard = mockk<BrainFlowGuard>(relaxed = true)
    private val candidateMemoryService = mockk<CandidateMemoryService>(relaxed = true)
    private val userRepository = mockk<UserRepository>(relaxed = true)

    private val engine = ConversationEngine(
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
        candidateMemoryService = candidateMemoryService,
        userRepository = userRepository,
    )

    private val sessionId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val questionId = UUID.randomUUID()

    // ── transition ────────────────────────────────────────────────────────────

    @Test
    fun `transition sends STATE_CHANGE WS message`() {
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
    fun `startInterview reads from DB and transitions to QuestionPresented`() {
        // Mock DB session with config JSON
        val session = InterviewSession(
            id = sessionId, userId = userId, status = "ACTIVE", type = "CODING",
            config = """{"category":"CODING","difficulty":"MEDIUM","personality":"friendly"}""",
            startedAt = OffsetDateTime.now(),
        )
        every { sessionRepository.findById(sessionId) } returns Mono.just(session)

        // Mock session question + question lookup
        val sq = SessionQuestion(id = UUID.randomUUID(), sessionId = sessionId, questionId = questionId, orderIndex = 0)
        every { sessionQuestionRepository.findBySessionIdOrderByOrderIndex(sessionId) } returns Flux.just(sq)
        coEvery { questionService.getQuestionById(questionId) } returns Question(
            id = questionId, title = "Two Sum", description = "Find two numbers...",
            type = "CODING", difficulty = "MEDIUM", category = "CODING",
        )

        // Mock message persistence
        every { messageRepository.save(any<ConversationMessage>()) } returns Mono.just(
            ConversationMessage(sessionId = sessionId, role = "AI", content = "test")
        )
        // userRepository.findById must return a completing Mono (relaxed mock returns non-completing mock Mono)
        every { userRepository.findById(any<UUID>()) } returns Mono.empty()
        // loadProfile returns null: relaxed mock returns a CandidateMemoryProfile mock by default (not null)
        coEvery { candidateMemoryService.loadProfile(any()) } returns null

        runTest {
            engine.startInterview(sessionId)
        }

        // Verify: transition to QUESTION_PRESENTED happened
        coVerify {
            registry.sendMessage(sessionId, match {
                it is OutboundMessage.StateChange && it.state == "QUESTION_PRESENTED"
            })
        }

        // Verify: brain was initialized (not memory)
        coVerify {
            brainService.initBrain(
                sessionId = sessionId, userId = userId,
                interviewType = "CODING", question = any(), goals = any(),
                personality = "friendly", targetCompany = null,
                experienceLevel = null, programmingLanguage = null,
                configuredDurationMinutes = any(),
                candidateMemory = null,
            )
        }
    }

}
