package com.aiinterview.shared.ai

data class LlmResponse(
    val content: String,
    val model: String,
    val provider: String,
    val usage: LlmUsage = LlmUsage(),
)

data class LlmUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
)
