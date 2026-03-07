package com.aiinterview.conversation

import com.aiinterview.interview.model.ConversationMessage
import com.aiinterview.interview.repository.ConversationMessageRepository
import com.aiinterview.interview.service.InterviewMemory
import com.aiinterview.interview.service.RedisMemoryService
import com.aiinterview.interview.ws.OutboundMessage
import com.aiinterview.interview.ws.WsSessionRegistry
import com.aiinterview.shared.ai.LlmMessage
import com.aiinterview.shared.ai.LlmProviderRegistry
import com.aiinterview.shared.ai.LlmRequest
import com.aiinterview.shared.ai.LlmRole
import com.aiinterview.shared.ai.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

private const val STREAM_TIMEOUT_MS = 10_000L

@Component
class InterviewerAgent(
    private val llm: LlmProviderRegistry,
    private val modelConfig: ModelConfig,
    private val promptBuilder: PromptBuilder,
    private val registry: WsSessionRegistry,
    private val redisMemoryService: RedisMemoryService,
    private val conversationMessageRepository: ConversationMessageRepository,
) {
    private val log = LoggerFactory.getLogger(InterviewerAgent::class.java)

    suspend fun streamResponse(
        sessionId: UUID,
        memory: InterviewMemory,
        userMessage: String,
    ) {
        val systemPrompt = promptBuilder.buildSystemPrompt(memory)
        val fullResponse = StringBuilder()

        val success = tryStreaming(sessionId, systemPrompt, userMessage, fullResponse)

        if (!success) {
            log.warn("Streaming timed out or failed for session {}, falling back to complete()", sessionId)
            val ok = tryFallback(sessionId, systemPrompt, userMessage, fullResponse)
            if (!ok) {
                registry.sendMessage(sessionId, OutboundMessage.Error("AI_ERROR", "Interview assistant unavailable"))
                return
            }
        }

        registry.sendMessage(sessionId, OutboundMessage.AiChunk(delta = "", done = true))

        val responseText = fullResponse.toString()
        if (responseText.isNotBlank()) {
            persistResponse(sessionId, responseText)
        }
    }

    private suspend fun tryStreaming(
        sessionId: UUID,
        systemPrompt: String,
        userMessage: String,
        fullResponse: StringBuilder,
    ): Boolean = try {
        val request = LlmRequest(
            messages = listOf(
                LlmMessage(LlmRole.SYSTEM, systemPrompt),
                LlmMessage(LlmRole.USER, userMessage),
            ),
            model = modelConfig.interviewerModel,
            maxTokens = 800,
        )

        withTimeout(STREAM_TIMEOUT_MS) {
            llm.stream(request)
                .catch { e ->
                    log.error("Stream error for session {}: {}", sessionId, e.message)
                }
                .collect { token ->
                    fullResponse.append(token)
                    registry.sendMessage(sessionId, OutboundMessage.AiChunk(delta = token, done = false))
                }
        }
        fullResponse.isNotEmpty()
    } catch (e: TimeoutCancellationException) {
        log.warn("First-token timeout for session {}", sessionId)
        false
    } catch (e: Exception) {
        log.error("Streaming error for session {}: {}", sessionId, e.message)
        false
    }

    private suspend fun tryFallback(
        sessionId: UUID,
        systemPrompt: String,
        userMessage: String,
        fullResponse: StringBuilder,
    ): Boolean = try {
        val request = LlmRequest.build(
            systemPrompt = systemPrompt,
            userMessage = userMessage,
            model = modelConfig.backgroundModel,
            maxTokens = 800,
        )
        val response = llm.complete(request)
        fullResponse.append(response.content)
        registry.sendMessage(sessionId, OutboundMessage.AiChunk(delta = response.content, done = false))
        true
    } catch (e: Exception) {
        log.error("Fallback model error for session {}: {}", sessionId, e.message)
        false
    }

    private suspend fun persistResponse(sessionId: UUID, responseText: String) {
        try {
            redisMemoryService.appendTranscriptTurn(sessionId, "AI", responseText)
        } catch (e: Exception) {
            log.warn("Failed to append AI transcript turn for session {}: {}", sessionId, e.message)
        }
        try {
            withContext(Dispatchers.IO) {
                conversationMessageRepository.save(
                    ConversationMessage(sessionId = sessionId, role = "AI", content = responseText),
                ).awaitSingle()
            }
        } catch (e: Exception) {
            log.warn("Failed to persist AI message to DB for session {}: {}", sessionId, e.message)
        }
    }
}
