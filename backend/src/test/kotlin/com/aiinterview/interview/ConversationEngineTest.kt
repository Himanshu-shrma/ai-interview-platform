package com.aiinterview.interview

import com.aiinterview.conversation.ConversationEngine
import com.aiinterview.conversation.InterviewState
import com.aiinterview.conversation.InterviewerAgent
import com.aiinterview.interview.model.ConversationMessage
import com.aiinterview.interview.repository.ConversationMessageRepository
import com.aiinterview.interview.service.InterviewMemory
import com.aiinterview.interview.service.RedisMemoryService
import com.aiinterview.interview.ws.OutboundMessage
import com.aiinterview.interview.ws.WsSessionRegistry
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

    private val redisMemoryService     = mockk<RedisMemoryService>()
    private val interviewerAgent       = mockk<InterviewerAgent>(relaxed = true)
    private val registry               = mockk<WsSessionRegistry>(relaxed = true)
    private val messageRepository      = mockk<ConversationMessageRepository>()

    private val engine = ConversationEngine(
        redisMemoryService        = redisMemoryService,
        interviewerAgent          = interviewerAgent,
        registry                  = registry,
        conversationMessageRepository = messageRepository,
    )

    private val sessionId = UUID.randomUUID()
    private val userId    = UUID.randomUUID()

    // ── handleCandidateMessage ────────────────────────────────────────────────

    @Test
    fun `handleCandidateMessage transitions to CandidateResponding`() {
        coEvery { redisMemoryService.getMemory(sessionId) } returns buildMemory("QUESTION_PRESENTED")
        coEvery { redisMemoryService.appendTranscriptTurn(sessionId, "CANDIDATE", any()) } returns buildMemory("QUESTION_PRESENTED")
        coEvery { redisMemoryService.updateMemory(sessionId, any()) } returns buildMemory("CANDIDATE_RESPONDING")
        coEvery { registry.sendMessage(sessionId, any()) } returns true
        every { messageRepository.save(any<ConversationMessage>()) } returns Mono.just(
            ConversationMessage(sessionId = sessionId, role = "CANDIDATE", content = "test")
        )

        runTest {
            engine.handleCandidateMessage(sessionId, "I'd use a hash map")
        }

        coVerify {
            registry.sendMessage(sessionId, match {
                it is OutboundMessage.StateChange && it.state == "CANDIDATE_RESPONDING"
            })
        }
    }

    @Test
    fun `handleCandidateMessage calls InterviewerAgent`() {
        coEvery { redisMemoryService.getMemory(sessionId) } returns buildMemory("QUESTION_PRESENTED")
        coEvery { redisMemoryService.appendTranscriptTurn(sessionId, "CANDIDATE", any()) } returns buildMemory("QUESTION_PRESENTED")
        coEvery { redisMemoryService.updateMemory(sessionId, any()) } returns buildMemory("CANDIDATE_RESPONDING")
        coEvery { registry.sendMessage(sessionId, any()) } returns true
        every { messageRepository.save(any<ConversationMessage>()) } returns Mono.just(
            ConversationMessage(sessionId = sessionId, role = "CANDIDATE", content = "test")
        )

        runTest {
            engine.handleCandidateMessage(sessionId, "I'd use a hash map")
        }

        coVerify { interviewerAgent.streamResponse(sessionId, any(), "I'd use a hash map") }
    }

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
        coEvery { registry.sendMessage(sessionId, any()) } returns true

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
        sessionId         = sessionId,
        userId            = userId,
        state             = state,
        category          = "CODING",
        personality       = "friendly_mentor",
        currentQuestion   = null,
        candidateAnalysis = null,
        createdAt         = Instant.now(),
        lastActivityAt    = Instant.now(),
    )
}
