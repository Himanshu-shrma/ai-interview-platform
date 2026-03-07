package com.aiinterview.conversation

import com.aiinterview.interview.model.ConversationMessage
import com.aiinterview.interview.repository.ConversationMessageRepository
import com.aiinterview.interview.service.InterviewMemory
import com.aiinterview.interview.service.RedisMemoryService
import com.aiinterview.interview.ws.OutboundMessage
import com.aiinterview.interview.ws.WsSessionRegistry
import com.openai.client.OpenAIClient
import com.openai.models.ChatModel
import com.openai.models.chat.completions.ChatCompletionCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID

private const val STREAM_TIMEOUT_MS  = 10_000L   // 10 s before first token → fall back to mini
private const val GPT4O_MODEL        = "gpt-4o"

@Component
class InterviewerAgent(
    private val openAIClient: OpenAIClient,
    private val promptBuilder: PromptBuilder,
    private val registry: WsSessionRegistry,
    private val redisMemoryService: RedisMemoryService,
    private val conversationMessageRepository: ConversationMessageRepository,
    @Value("\${openai.model.background:gpt-4o-mini}") private val fallbackModel: String,
) {
    private val log = LoggerFactory.getLogger(InterviewerAgent::class.java)

    /**
     * Streams a GPT-4o response for the given [userMessage] and [memory].
     *
     * Each token is sent as [OutboundMessage.AiChunk] via the registry.
     * After the stream ends, sends a "done" chunk and persists the full response
     * to the transcript (Redis) and conversation_messages (DB).
     *
     * On first-token timeout (10 s), falls back to GPT-4o-mini (non-streaming).
     * On any error, sends [OutboundMessage.Error] and returns — does NOT crash.
     */
    suspend fun streamResponse(
        sessionId: UUID,
        memory: InterviewMemory,
        userMessage: String,
    ) {
        val systemPrompt = promptBuilder.buildSystemPrompt(memory)
        val fullResponse = StringBuilder()

        val success = tryStreaming(sessionId, systemPrompt, userMessage, fullResponse, GPT4O_MODEL)

        if (!success) {
            log.warn("GPT-4o streaming timed out or failed for session {}, falling back to {}", sessionId, fallbackModel)
            val ok = tryFallback(sessionId, systemPrompt, userMessage, fullResponse)
            if (!ok) {
                registry.sendMessage(sessionId, OutboundMessage.Error("AI_ERROR", "Interview assistant unavailable"))
                return
            }
        }

        // Done signal
        registry.sendMessage(sessionId, OutboundMessage.AiChunk(delta = "", done = true))

        // Persist
        val responseText = fullResponse.toString()
        if (responseText.isNotBlank()) {
            persistResponse(sessionId, responseText)
        }
    }

    // ── Streaming (GPT-4o) ────────────────────────────────────────────────────

    private suspend fun tryStreaming(
        sessionId: UUID,
        systemPrompt: String,
        userMessage: String,
        fullResponse: StringBuilder,
        model: String,
    ): Boolean = try {
        val params = buildParams(systemPrompt, userMessage, model)
        val tokenChannel = Channel<String>(Channel.BUFFERED)

        coroutineScope {
            // Producer: blocking stream on IO thread → channel
            launch(Dispatchers.IO) {
                try {
                    withTimeout(STREAM_TIMEOUT_MS) {
                        openAIClient.chat().completions().createStreaming(params).use { stream ->
                            stream.stream().forEach { chunk ->
                                val token = chunk.choices().firstOrNull()
                                    ?.delta()?.content()?.orElse(null)
                                if (!token.isNullOrEmpty()) {
                                    tokenChannel.trySend(token)
                                }
                            }
                        }
                    }
                } finally {
                    tokenChannel.close()
                }
            }

            // Consumer: send each token to WebSocket
            for (token in tokenChannel) {
                fullResponse.append(token)
                registry.sendMessage(sessionId, OutboundMessage.AiChunk(delta = token, done = false))
            }
        }
        true
    } catch (e: TimeoutCancellationException) {
        log.warn("First-token timeout for session {}", sessionId)
        false
    } catch (e: Exception) {
        log.error("Streaming error for session {}: {}", sessionId, e.message)
        false
    }

    // ── Fallback (GPT-4o-mini, non-streaming) ────────────────────────────────

    private suspend fun tryFallback(
        sessionId: UUID,
        systemPrompt: String,
        userMessage: String,
        fullResponse: StringBuilder,
    ): Boolean {
        return try {
            val params = buildParams(systemPrompt, userMessage, fallbackModel)
            val response = withContext(Dispatchers.IO) {
                openAIClient.chat().completions().create(params)
                    .choices().firstOrNull()?.message()?.content()?.orElse(null)
            } ?: return false

            fullResponse.append(response)
            registry.sendMessage(sessionId, OutboundMessage.AiChunk(delta = response, done = false))
            true
        } catch (e: Exception) {
            log.error("Fallback model error for session {}: {}", sessionId, e.message)
            false
        }
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private suspend fun persistResponse(sessionId: UUID, responseText: String) {
        try {
            redisMemoryService.appendTranscriptTurn(sessionId, "AI", responseText)
        } catch (e: Exception) {
            log.warn("Failed to append AI transcript turn for session {}: {}", sessionId, e.message)
        }
        try {
            withContext(Dispatchers.IO) {
                conversationMessageRepository.save(
                    ConversationMessage(sessionId = sessionId, role = "AI", content = responseText)
                ).awaitSingle()
            }
        } catch (e: Exception) {
            log.warn("Failed to persist AI message to DB for session {}: {}", sessionId, e.message)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildParams(
        systemPrompt: String,
        userMessage: String,
        model: String,
    ): ChatCompletionCreateParams =
        ChatCompletionCreateParams.builder()
            .model(ChatModel.of(model))
            .addSystemMessage(systemPrompt)
            .addUserMessage(userMessage)
            .maxCompletionTokens(800)
            .build()
}
