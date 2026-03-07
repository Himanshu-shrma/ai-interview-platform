package com.aiinterview.shared.ai.providers

import com.aiinterview.shared.ai.*
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.ChatModel
import com.openai.models.ResponseFormatJsonObject
import com.openai.models.chat.completions.ChatCompletionCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["llm.provider"], havingValue = "openai")
class OpenAiProvider(
    @Value("\${llm.openai.api-key}") private val apiKey: String,
    @Value("\${llm.openai.base-url:https://api.openai.com/v1}") private val baseUrl: String,
) : LlmProvider {

    private val log = LoggerFactory.getLogger(javaClass)

    private val client: OpenAIClient by lazy {
        OpenAIOkHttpClient.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .build()
    }

    override suspend fun complete(request: LlmRequest): LlmResponse =
        withContext(Dispatchers.IO) {
            try {
                val params = buildParams(request)
                val completion = client.chat().completions().create(params)
                val content = completion.choices().firstOrNull()
                    ?.message()?.content()?.orElse(null)
                    ?: throw LlmProviderException.InvalidResponseException("openai", "empty choices")

                log.debug("OpenAI complete: model={}, tokens={}", request.model, completion.usage()?.orElse(null)?.totalTokens())

                LlmResponse(
                    content = content,
                    model = request.model,
                    provider = "openai",
                    usage = LlmUsage(
                        promptTokens = completion.usage()?.orElse(null)?.promptTokens()?.toInt() ?: 0,
                        completionTokens = completion.usage()?.orElse(null)?.completionTokens()?.toInt() ?: 0,
                        totalTokens = completion.usage()?.orElse(null)?.totalTokens()?.toInt() ?: 0,
                    ),
                )
            } catch (e: LlmProviderException) {
                throw e
            } catch (e: Exception) {
                throw mapException(e, request.model)
            }
        }

    override fun stream(request: LlmRequest): Flow<String> = flow {
        val params = buildParams(request)
        client.chat().completions().createStreaming(params).use { stream ->
            val iterator = stream.stream().iterator()
            while (iterator.hasNext()) {
                val chunk = iterator.next()
                val token = chunk.choices().firstOrNull()
                    ?.delta()?.content()?.orElse(null)
                if (!token.isNullOrEmpty()) {
                    emit(token)
                }
            }
        }
    }.flowOn(Dispatchers.IO)
        .catch { e ->
            if (e is LlmProviderException) throw e
            throw mapException(e, request.model)
        }

    override fun providerName() = "openai"

    override suspend fun healthCheck(): Boolean = try {
        complete(
            LlmRequest.build(
                systemPrompt = "respond with ok",
                userMessage = "ok",
                model = "gpt-4o-mini",
                maxTokens = 1,
            ),
        )
        true
    } catch (e: Exception) {
        log.warn("OpenAI health check failed: {}", e.message)
        false
    }

    private fun buildParams(request: LlmRequest): ChatCompletionCreateParams {
        val builder = ChatCompletionCreateParams.builder()
            .model(ChatModel.of(request.model))
            .maxCompletionTokens(request.maxTokens.toLong())

        for (msg in request.messages) {
            when (msg.role) {
                LlmRole.SYSTEM -> builder.addSystemMessage(msg.content)
                LlmRole.USER -> builder.addUserMessage(msg.content)
                LlmRole.ASSISTANT -> builder.addAssistantMessage(msg.content)
            }
        }

        if (request.responseFormat == ResponseFormat.JSON) {
            builder.responseFormat(ResponseFormatJsonObject.builder().build())
        }

        return builder.build()
    }

    private fun mapException(e: Throwable, model: String): LlmProviderException {
        val msg = e.message?.lowercase() ?: ""
        return when {
            "401" in msg || "unauthorized" in msg || "api key" in msg ->
                LlmProviderException.AuthenticationException("openai", e as? Exception)
            "429" in msg || "rate limit" in msg ->
                LlmProviderException.RateLimitException("openai")
            "context" in msg || ("token" in msg && "limit" in msg) ->
                LlmProviderException.ContextLengthException("openai")
            "model" in msg && ("not found" in msg || "does not exist" in msg) ->
                LlmProviderException.ModelNotFoundException(model, "openai")
            else -> LlmProviderException.ProviderUnavailableException("openai", e as? Exception)
        }
    }
}
