package com.aiinterview.interview

import com.aiinterview.conversation.InterviewerAgent
import com.aiinterview.conversation.PromptBuilder
import com.aiinterview.interview.model.ConversationMessage
import com.aiinterview.interview.repository.ConversationMessageRepository
import com.aiinterview.interview.service.InterviewMemory
import com.aiinterview.interview.service.RedisMemoryService
import com.aiinterview.interview.ws.OutboundMessage
import com.aiinterview.interview.ws.WsSessionRegistry
import com.openai.client.OpenAIClient
import com.openai.core.http.StreamResponse
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionChunk
import com.openai.models.chat.completions.ChatCompletionCreateParams
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.UUID
import java.util.stream.Stream

class InterviewerAgentTest {

    private val openAIClient      = mockk<OpenAIClient>()
    private val promptBuilder     = mockk<PromptBuilder>()
    private val registry          = mockk<WsSessionRegistry>(relaxed = true)
    private val redisMemoryService = mockk<RedisMemoryService>()
    private val messageRepository = mockk<ConversationMessageRepository>()

    private val agent = InterviewerAgent(
        openAIClient                  = openAIClient,
        promptBuilder                 = promptBuilder,
        registry                      = registry,
        redisMemoryService            = redisMemoryService,
        conversationMessageRepository = messageRepository,
        fallbackModel                 = "gpt-4o-mini",
    )

    private val sessionId = UUID.randomUUID()
    private val memory    = buildMemory()

    // ── streamResponse — happy path ───────────────────────────────────────────

    @Test
    fun `streamResponse sends AiChunk tokens and done signal`() {
        every { promptBuilder.buildSystemPrompt(memory) } returns "System prompt"
        every { openAIClient.chat() } returns mockk {
            every { completions() } returns mockk {
                every { createStreaming(any<ChatCompletionCreateParams>()) } returns mockStreamResponse(listOf("Hello", ", world", "!"))
            }
        }
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
        every { openAIClient.chat() } returns mockk {
            every { completions() } returns mockk {
                every { createStreaming(any<ChatCompletionCreateParams>()) } returns mockStreamResponse(listOf("Great approach!"))
            }
        }
        coEvery { redisMemoryService.appendTranscriptTurn(sessionId, "AI", any()) } returns memory
        val savedMessage = slot<ConversationMessage>()
        every { messageRepository.save(capture(savedMessage)) } returns Mono.just(
            ConversationMessage(sessionId = sessionId, role = "AI", content = "Great approach!")
        )
        coEvery { registry.sendMessage(sessionId, any()) } returns true

        runBlocking { agent.streamResponse(sessionId, memory, "I'd use a hash map") }

        coVerify { redisMemoryService.appendTranscriptTurn(sessionId, "AI", "Great approach!") }
    }

    @Test
    fun `streamResponse falls back to mini model on empty stream`() {
        every { promptBuilder.buildSystemPrompt(memory) } returns "System prompt"
        // Primary streaming returns empty
        every { openAIClient.chat() } returns mockk {
            every { completions() } returns mockk {
                every { createStreaming(any<ChatCompletionCreateParams>()) } returns mockStreamResponse(emptyList())
                every { create(any<ChatCompletionCreateParams>()) } returns mockChatCompletion("Fallback answer")
            }
        }
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
        every { openAIClient.chat() } returns mockk {
            every { completions() } returns mockk {
                every { createStreaming(any<ChatCompletionCreateParams>()) } throws RuntimeException("Network error")
                every { create(any<ChatCompletionCreateParams>()) } throws RuntimeException("Fallback also failed")
            }
        }
        coEvery { registry.sendMessage(sessionId, any()) } returns true

        runBlocking { agent.streamResponse(sessionId, memory, "Hi") }

        coVerify { registry.sendMessage(sessionId, match { it is OutboundMessage.Error }) }
    }

    @Test
    fun `streamResponse appends response to Redis transcript`() {
        every { promptBuilder.buildSystemPrompt(memory) } returns "System prompt"
        every { openAIClient.chat() } returns mockk {
            every { completions() } returns mockk {
                every { createStreaming(any<ChatCompletionCreateParams>()) } returns mockStreamResponse(listOf("Nice solution!"))
            }
        }
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

    /** Creates a mock StreamResponse that emits the given tokens. */
    private fun mockStreamResponse(tokens: List<String>): StreamResponse<ChatCompletionChunk> {
        val chunks = tokens.map { token ->
            mockk<ChatCompletionChunk> {
                every { choices() } returns listOf(
                    mockk {
                        every { delta() } returns mockk {
                            every { content() } returns java.util.Optional.of(token)
                        }
                    }
                )
            }
        }
        val stream: Stream<ChatCompletionChunk> = chunks.stream()
        return mockk<StreamResponse<ChatCompletionChunk>> {
            every { stream() } returns stream
            every { close() } returns Unit
        }
    }

    /** Creates a mock non-streaming ChatCompletion. */
    private fun mockChatCompletion(text: String): ChatCompletion = mockk {
        every { choices() } returns listOf(
            mockk {
                every { message() } returns mockk {
                    every { content() } returns java.util.Optional.of(text)
                }
            }
        )
    }
}
