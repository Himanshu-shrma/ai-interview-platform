package com.aiinterview.shared.ai

sealed class LlmProviderException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause) {

    class AuthenticationException(provider: String, cause: Throwable? = null) :
        LlmProviderException("API key invalid or missing for provider: $provider", cause)

    class RateLimitException(provider: String, val retryAfterSeconds: Long? = null) :
        LlmProviderException(
            "Rate limit exceeded for provider: $provider" +
                (retryAfterSeconds?.let { ". Retry after ${it}s" } ?: ""),
        )

    class ModelNotFoundException(model: String, provider: String) :
        LlmProviderException("Model '$model' not found for provider: $provider")

    class ContextLengthException(provider: String) :
        LlmProviderException("Context length exceeded for provider: $provider")

    class ProviderUnavailableException(provider: String, cause: Throwable? = null) :
        LlmProviderException("Provider unavailable: $provider", cause)

    class InvalidResponseException(provider: String, response: String) :
        LlmProviderException("Invalid response from provider $provider: ${response.take(200)}")
}
