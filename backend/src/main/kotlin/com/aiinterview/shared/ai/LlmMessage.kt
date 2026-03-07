package com.aiinterview.shared.ai

data class LlmMessage(
    val role: LlmRole,
    val content: String,
)

enum class LlmRole {
    SYSTEM, USER, ASSISTANT;

    fun toOpenAiRole(): String = when (this) {
        SYSTEM -> "system"
        USER -> "user"
        ASSISTANT -> "assistant"
    }
}
