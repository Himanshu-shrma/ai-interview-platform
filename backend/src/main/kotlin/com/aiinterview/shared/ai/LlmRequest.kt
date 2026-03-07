package com.aiinterview.shared.ai

data class LlmRequest(
    val messages: List<LlmMessage>,
    val model: String,
    val maxTokens: Int = 1000,
    val temperature: Double = 0.7,
    val responseFormat: ResponseFormat = ResponseFormat.TEXT,
    val stream: Boolean = false,
) {
    companion object {
        fun build(
            systemPrompt: String,
            userMessage: String,
            model: String,
            maxTokens: Int = 1000,
            temperature: Double = 0.7,
            responseFormat: ResponseFormat = ResponseFormat.TEXT,
        ) = LlmRequest(
            messages = listOf(
                LlmMessage(LlmRole.SYSTEM, systemPrompt),
                LlmMessage(LlmRole.USER, userMessage),
            ),
            model = model,
            maxTokens = maxTokens,
            temperature = temperature,
            responseFormat = responseFormat,
        )
    }
}

enum class ResponseFormat {
    TEXT,
    JSON,
}
