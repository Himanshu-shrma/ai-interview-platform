package com.aiinterview.shared.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class LlmProviderRegistry(
    private val providers: List<LlmProvider>,
    @Value("\${llm.provider}") private val primaryProviderName: String,
    @Value("\${llm.fallback-provider:#{null}}") private val fallbackProviderName: String?,
    @Value("\${llm.retry.max-attempts:3}") private val maxRetryAttempts: Int = 3,
    @Value("\${llm.retry.initial-delay-ms:1000}") private val initialDelayMs: Long = 1000L,
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

    suspend fun complete(request: LlmRequest): LlmResponse {
        val startMs = System.currentTimeMillis()
        val response = try {
            retryWithBackoff("complete") {
                primaryProvider.complete(request)
            }
        } catch (e: LlmProviderException.RateLimitException) {
            log.warn("Primary provider {} rate limited after retries, trying fallback", primaryProviderName)
            fallbackProvider?.complete(request) ?: throw e
        } catch (e: LlmProviderException.ProviderUnavailableException) {
            log.warn("Primary provider {} unavailable after retries, trying fallback", primaryProviderName)
            fallbackProvider?.complete(request) ?: throw e
        }
        val latencyMs = System.currentTimeMillis() - startMs
        val cost = LlmCostEstimator.estimateCost(request.model, response.usage.promptTokens, response.usage.completionTokens)
        val sessionId = MDC.get("session_id") ?: ""
        log.info("""{"event":"LLM_CALL_COMPLETE","session_id":"$sessionId","model":"${request.model}","prompt_tokens":${response.usage.promptTokens},"completion_tokens":${response.usage.completionTokens},"latency_ms":$latencyMs,"estimated_cost_usd":${"%.6f".format(cost)}}""")
        return response
    }

    /**
     * Streaming is NOT retried — a mid-stream retry would send duplicate tokens.
     * Stream failures are handled by TheConductor's tryFallback() mechanism which
     * falls back to complete() (which does retry).
     */
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

    // ── Retry with exponential backoff ──────────────────────────────────────

    private suspend fun <T> retryWithBackoff(
        operationName: String,
        block: suspend () -> T,
    ): T {
        repeat(maxRetryAttempts) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                val isRetryable = isRetryableError(e)
                val isLastAttempt = attempt == maxRetryAttempts - 1
                if (!isRetryable || isLastAttempt) throw e
                val delayMs = initialDelayMs * (1L shl attempt) // 1s, 2s, 4s
                log.warn("LLM {} failed (attempt {}/{}), retrying in {}ms: {}",
                    operationName, attempt + 1, maxRetryAttempts, delayMs, e.message)
                delay(delayMs)
            }
        }
        error("unreachable")
    }

    private fun isRetryableError(e: Exception): Boolean {
        if (e is LlmProviderException.RateLimitException) return true
        if (e is LlmProviderException.ProviderUnavailableException) return true
        val msg = e.message?.lowercase() ?: return false
        return msg.contains("429") || msg.contains("rate limit") ||
            msg.contains("timeout") || msg.contains("502") ||
            msg.contains("503") || msg.contains("overloaded")
    }
}
