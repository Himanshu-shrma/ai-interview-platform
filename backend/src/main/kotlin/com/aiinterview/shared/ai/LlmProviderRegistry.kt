package com.aiinterview.shared.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class LlmProviderRegistry(
    private val providers: List<LlmProvider>,
    @Value("\${llm.provider}") private val primaryProviderName: String,
    @Value("\${llm.fallback-provider:#{null}}") private val fallbackProviderName: String?,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val primaryProvider: LlmProvider by lazy {
        providers.find { it.providerName() == primaryProviderName }
            ?: throw IllegalStateException(
                "No LlmProvider found for: $primaryProviderName. " +
                    "Available: ${providers.map { it.providerName() }}",
            )
    }

    private val fallbackProvider: LlmProvider? by lazy {
        fallbackProviderName?.let { name ->
            providers.find { it.providerName() == name }
        }
    }

    suspend fun complete(request: LlmRequest): LlmResponse = try {
        primaryProvider.complete(request)
    } catch (e: LlmProviderException.RateLimitException) {
        log.warn("Primary provider {} rate limited, trying fallback", primaryProviderName)
        fallbackProvider?.complete(request) ?: throw e
    } catch (e: LlmProviderException.ProviderUnavailableException) {
        log.warn("Primary provider {} unavailable, trying fallback", primaryProviderName)
        fallbackProvider?.complete(request) ?: throw e
    }

    fun stream(request: LlmRequest): Flow<String> =
        primaryProvider.stream(request)
            .catch { e ->
                if (e is LlmProviderException && fallbackProvider != null) {
                    log.warn("Streaming failed for {}, falling back to complete()", primaryProviderName)
                    val response = fallbackProvider!!.complete(request)
                    emit(response.content)
                } else throw e
            }

    fun primary(): LlmProvider = primaryProvider
    fun getProviderName(): String = primaryProviderName
}
