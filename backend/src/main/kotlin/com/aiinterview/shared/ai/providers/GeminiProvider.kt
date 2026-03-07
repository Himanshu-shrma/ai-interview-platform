package com.aiinterview.shared.ai.providers

import com.aiinterview.shared.ai.*
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

@Component
@ConditionalOnProperty(name = ["llm.provider"], havingValue = "gemini")
class GeminiProvider(
    @Value("\${llm.gemini.api-key}") private val apiKey: String,
    @Value("\${llm.gemini.base-url:https://generativelanguage.googleapis.com}") private val baseUrl: String,
    private val objectMapper: ObjectMapper,
    webClientBuilder: WebClient.Builder,
) : LlmProvider {

    private val log = LoggerFactory.getLogger(javaClass)

    private val webClient: WebClient = webClientBuilder
        .baseUrl(baseUrl)
        .defaultHeader("Content-Type", "application/json")
        .build()

    // ── Gemini API DTOs ─────────────────────────────────────────────────────

    private data class GeminiRequest(
        val contents: List<GeminiContent>,
        val systemInstruction: GeminiSystemInstruction? = null,
        val generationConfig: GeminiGenerationConfig,
    )

    private data class GeminiContent(
        val role: String, // "user" or "model"
        val parts: List<GeminiPart>,
    )

    private data class GeminiPart(val text: String)
    private data class GeminiSystemInstruction(val parts: List<GeminiPart>)

    private data class GeminiGenerationConfig(
        val maxOutputTokens: Int,
        val temperature: Double,
        val responseMimeType: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class GeminiResponse(
        val candidates: List<GeminiCandidate>? = null,
        val usageMetadata: GeminiUsage? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class GeminiCandidate(val content: GeminiContent? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class GeminiUsage(
        @JsonProperty("promptTokenCount") val promptTokenCount: Int = 0,
        @JsonProperty("candidatesTokenCount") val candidatesTokenCount: Int = 0,
        @JsonProperty("totalTokenCount") val totalTokenCount: Int = 0,
    )

    // ── LlmProvider implementation ──────────────────────────────────────────

    override suspend fun complete(request: LlmRequest): LlmResponse =
        withContext(Dispatchers.IO) {
            try {
                val geminiRequest = buildRequest(request)
                val modelName = request.model

                val response = webClient.post()
                    .uri("/v1beta/models/$modelName:generateContent?key=$apiKey")
                    .bodyValue(geminiRequest)
                    .retrieve()
                    .bodyToMono<String>()
                    .awaitSingle()

                val parsed = objectMapper.readValue(response, GeminiResponse::class.java)
                val content = parsed.candidates
                    ?.firstOrNull()?.content?.parts
                    ?.firstOrNull()?.text
                    ?: throw LlmProviderException.InvalidResponseException("gemini", response.take(200))

                log.debug("Gemini complete: model={}, tokens={}", request.model, parsed.usageMetadata?.totalTokenCount)

                LlmResponse(
                    content = content,
                    model = request.model,
                    provider = "gemini",
                    usage = LlmUsage(
                        promptTokens = parsed.usageMetadata?.promptTokenCount ?: 0,
                        completionTokens = parsed.usageMetadata?.candidatesTokenCount ?: 0,
                        totalTokens = parsed.usageMetadata?.totalTokenCount ?: 0,
                    ),
                )
            } catch (e: LlmProviderException) {
                throw e
            } catch (e: Exception) {
                throw mapException(e)
            }
        }

    override fun stream(request: LlmRequest): Flow<String> = flow {
        val geminiRequest = buildRequest(request)
        val modelName = request.model

        val rawResponse = webClient.post()
            .uri("/v1beta/models/$modelName:streamGenerateContent?key=$apiKey&alt=sse")
            .bodyValue(geminiRequest)
            .retrieve()
            .bodyToMono<String>()
            .awaitSingle()

        // SSE format: lines starting with "data: " contain JSON chunks
        for (line in rawResponse.lines()) {
            if (line.startsWith("data: ")) {
                val json = line.removePrefix("data: ").trim()
                if (json == "[DONE]" || json.isBlank()) continue
                try {
                    val chunk = objectMapper.readValue(json, GeminiResponse::class.java)
                    val token = chunk.candidates
                        ?.firstOrNull()?.content?.parts
                        ?.firstOrNull()?.text
                    if (!token.isNullOrEmpty()) emit(token)
                } catch (e: Exception) {
                    log.debug("Failed to parse Gemini SSE chunk: {}", e.message)
                }
            }
        }
    }.flowOn(Dispatchers.IO)
        .catch { e ->
            if (e is LlmProviderException) throw e
            throw mapException(e)
        }

    override fun providerName() = "gemini"

    override suspend fun healthCheck(): Boolean = try {
        complete(
            LlmRequest.build(
                systemPrompt = "respond with ok",
                userMessage = "ok",
                model = "gemini-1.5-flash",
                maxTokens = 1,
            ),
        )
        true
    } catch (e: Exception) {
        log.warn("Gemini health check failed: {}", e.message)
        false
    }

    // ── Request building ────────────────────────────────────────────────────

    private fun buildRequest(request: LlmRequest): GeminiRequest {
        val systemMessages = request.messages.filter { it.role == LlmRole.SYSTEM }
        val conversationMessages = request.messages.filter { it.role != LlmRole.SYSTEM }

        val systemInstruction = if (systemMessages.isNotEmpty()) {
            GeminiSystemInstruction(
                parts = listOf(GeminiPart(systemMessages.joinToString("\n") { it.content })),
            )
        } else null

        // Gemini requires alternating user/model turns — merge consecutive same-role messages
        val contents = mergeConsecutiveRoles(conversationMessages).map { msg ->
            GeminiContent(
                role = if (msg.role == LlmRole.USER) "user" else "model",
                parts = listOf(GeminiPart(msg.content)),
            )
        }

        return GeminiRequest(
            contents = contents,
            systemInstruction = systemInstruction,
            generationConfig = GeminiGenerationConfig(
                maxOutputTokens = request.maxTokens,
                temperature = request.temperature,
                responseMimeType = if (request.responseFormat == ResponseFormat.JSON) "application/json" else null,
            ),
        )
    }

    private fun mergeConsecutiveRoles(messages: List<LlmMessage>): List<LlmMessage> {
        if (messages.isEmpty()) return messages
        val merged = mutableListOf<LlmMessage>()
        var current = messages[0]
        for (i in 1 until messages.size) {
            current = if (messages[i].role == current.role) {
                current.copy(content = "${current.content}\n${messages[i].content}")
            } else {
                merged.add(current)
                messages[i]
            }
        }
        merged.add(current)
        return merged
    }

    // ── Exception mapping ───────────────────────────────────────────────────

    private fun mapException(e: Throwable): LlmProviderException {
        if (e is LlmProviderException) return e
        val msg = e.message?.lowercase() ?: ""
        return when {
            "401" in msg || "403" in msg || "api key" in msg ->
                LlmProviderException.AuthenticationException("gemini", e as? Exception)
            "429" in msg || "quota" in msg || "rate" in msg ->
                LlmProviderException.RateLimitException("gemini")
            "404" in msg ->
                LlmProviderException.ModelNotFoundException("unknown", "gemini")
            else -> LlmProviderException.ProviderUnavailableException("gemini", e as? Exception)
        }
    }
}
