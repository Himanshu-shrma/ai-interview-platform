package com.aiinterview.interview.service

import com.aiinterview.shared.ai.LlmProviderRegistry
import com.aiinterview.shared.ai.LlmRequest
import com.aiinterview.shared.ai.ModelConfig
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class TranscriptCompressor(
    private val llm: LlmProviderRegistry,
    private val modelConfig: ModelConfig,
) {
    private val log = LoggerFactory.getLogger(TranscriptCompressor::class.java)

    suspend fun compress(turns: List<TranscriptTurn>): String {
        if (turns.isEmpty()) return ""
        val dialog = turns.joinToString("\n") { "${it.role}: ${it.content}" }
        return try {
            val response = llm.complete(
                LlmRequest.build(
                    systemPrompt = "You are a concise interview transcript summarizer. " +
                        "Summarize the exchange in 2-3 sentences, preserving key technical points " +
                        "and the candidate's approach. Be brief and factual.",
                    userMessage = "Summarize this interview exchange:\n$dialog",
                    model = modelConfig.backgroundModel,
                    maxTokens = 150,
                ),
            )
            response.content
        } catch (e: Exception) {
            log.warn("TranscriptCompressor LLM call failed, using fallback: {}", e.message)
            fallback(turns)
        }
    }

    private fun fallback(turns: List<TranscriptTurn>): String =
        turns.joinToString(" | ") { "${it.role}: ${it.content.take(200)}" }
}
