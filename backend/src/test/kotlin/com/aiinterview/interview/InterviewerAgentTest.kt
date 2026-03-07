package com.aiinterview.interview

import com.aiinterview.conversation.InterviewerAgent
import com.aiinterview.conversation.PromptBuilder
import com.aiinterview.interview.model.ConversationMessage
import com.aiinterview.interview.repository.ConversationMessageRepository
import com.aiinterview.interview.service.InterviewMemory
import com.aiinterview.interview.service.RedisMemoryService
import com.aiinterview.interview.ws.OutboundMessage
import com.aiinterview.interview.ws.WsSessionRegistry
import com.aiinterview.shared.ai.LlmProviderRegistry
import com.aiinterview.shared.ai.LlmResponse
import com.aiinterview.shared.ai.ModelConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.UUID

class InterviewerAgentTest {

    private val llm               = mockk<LlmProviderRegistry>()
    private val modelConfig       = ModelConfig()
    private val promptBuilder     = mockk<PromptBuilder>()
    private val registry          = mockk<WsSessionRegistry>(relaxed = true)
    private val redisMemoryService = mockk<RedisMemoryService>()
    private val messageRepository = mockk<ConversationMessageRepository>()

    private val agent = InterviewerAgent(
        llm                           = llm,
        modelConfig                   = modelConfig,
        promptBuilder                 = promptBuilder,
        registry                      = registry,
        redisMemoryService            = redisMemoryService,
        conversationMessageRepository = messageRepository,
    )

    private val sessionId = UUID.randomUUID()
    private val memory    = buildMemory()

    // ── streamResponse — happy path ───────────────────────────────────────────

    @Test
    fun `streamResponse sends AiChunk tokens and done signal`() {
        every { promptBuilder.buildSystemPrompt(memory) } returns "System prompt"
        every { llm.stream(any()) } returns flowOf("Hello", ", world", "!")
        coEvery { redisMemoryService.appendTranscriptTurn(sessionId, "AI", any()) } returns memory
        every { messageRepository.save(any<ConversationMessage>()) } returns Mono.just(
            ConversationMessage(sessionId = sessionId, role = "AI", content = "Hello, world!")
        )
        coEvery { registry.sendMessage(sessionId, any()) } returns true

        runBlocking { agent.streamResponse(sessionId, memory, "How would you solve Two Sum?") }

        // Verify individual token chunks sent
        coVerify { registry.sendMessage(sessionId, match { it is OutboundMessage.AiChunk && !it.done }) }
        // Verify done signal
        coVerify { registry.sendMessage(sessionId, match { it is OutboundMessage.AiChunk && it.done }) }
    }

    @Test
    fun `streamResponse persists full AI message to DB`() {
        every { promptBuilder.buildSystemPrompt(memory) } returns "System prompt"
        every { llm.stream(any()) } returns flowOf("Great approach!")
        coEvery { redisMemoryService.appendTranscriptTurn(sessionId, "AI", any()) } returns memory
        every { messageRepository.save(any<ConversationMessage>()) } returns Mono.just(
            ConversationMessage(sessionId = sessionId, role = "AI", content = "Great approach!")
        )
        coEvery { registry.sendMessage(sessionId, any()) } returns true

        runBlocking { agent.streamResponse(sessionId, memory, "I'd use a hash map") }

        coVerify { redisMemoryService.appendTranscriptTurn(sessionId, "AI", "Great approach!") }
    }

    @Test
    fun `streamResponse falls back to complete on empty stream`() {
        every { promptBuilder.buildSystemPrompt(memory) } returns "System prompt"
        // Primary streaming returns empty flow
        every { llm.stream(any()) } returns flowOf()
        coEvery { llm.complete(any()) } returns LlmResponse(
            content = "Fallback answer", model = "gpt-4o-mini", provider = "openai",
        )
        coEvery { redisMemoryService.appendTranscriptTurn(sessionId, "AI", any()) } returns memory
        every { messageRepository.save(any<ConversationMessage>()) } returns Mono.just(
            ConversationMessage(sessionId = sessionId, role = "AI", content = "Fallback answer")
        )
        coEvery { registry.sendMessage(sessionId, any()) } returns true

        runBlocking { agent.streamResponse(sessionId, memory, "Hi") }

        // Done signal must still be sent
        coVerify { registry.sendMessage(sessionId, match { it is OutboundMessage.AiChunk && it.done }) }
    }

    @Test
    fun `streamResponse sends ErrorFrame on streaming exception`() {
        every { promptBuilder.buildSystemPrompt(memory) } returns "System prompt"
        every { llm.stream(any()) } throws RuntimeException("Network error")
        coEvery { llm.complete(any()) } throws RuntimeException("Fallback also failed")
        coEvery { registry.sendMessage(sessionId, any()) } returns true

        runBlocking { agent.streamResponse(sessionId, memory, "Hi") }

        coVerify { registry.sendMessage(sessionId, match { it is OutboundMessage.Error }) }
    }

    @Test
    fun `streamResponse appends response to Redis transcript`() {
        every { promptBuilder.buildSystemPrompt(memory) } returns "System prompt"
        every { llm.stream(any()) } returns flowOf("Nice solution!")
        coEvery { redisMemoryService.appendTranscriptTurn(sessionId, "AI", "Nice solution!") } returns memory
        every { messageRepository.save(any<ConversationMessage>()) } returns Mono.just(
            ConversationMessage(sessionId = sessionId, role = "AI", content = "Nice solution!")
        )
        coEvery { registry.sendMessage(sessionId, any()) } returns true

        runBlocking { agent.streamResponse(sessionId, memory, "I'd use dynamic programming") }

        coVerify { redisMemoryService.appendTranscriptTurn(sessionId, "AI", "Nice solution!") }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun buildMemory() = InterviewMemory(
        sessionId         = sessionId,
        userId            = UUID.randomUUID(),
        state             = "QUESTION_PRESENTED",
        category          = "CODING",
        personality       = "friendly_mentor",
        currentQuestion   = null,
        candidateAnalysis = null,
        createdAt         = Instant.now(),
        lastActivityAt    = Instant.now(),
    )
}
