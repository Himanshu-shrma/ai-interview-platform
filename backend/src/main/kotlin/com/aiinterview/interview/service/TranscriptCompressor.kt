package com.aiinterview.interview.service

import com.openai.client.OpenAIClient
import com.openai.models.ChatModel
import com.openai.models.chat.completions.ChatCompletionCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class TranscriptCompressor(
    private val openAIClient: OpenAIClient,
    @Value("\${openai.model.background:gpt-4o-mini}") private val model: String,
) {
    private val log = LoggerFactory.getLogger(TranscriptCompressor::class.java)

    /**
     * Summarizes a list of transcript turns into 2-3 sentences using GPT-4o-mini,
     * preserving key technical points and the candidate's approach.
     *
     * Falls back to simple concatenation on any error to avoid crashing the session.
     */
    suspend fun compress(turns: List<TranscriptTurn>): String {
        if (turns.isEmpty()) return ""
        val dialog = turns.joinToString("\n") { "${it.role}: ${it.content}" }
        return try {
            withContext(Dispatchers.IO) {
                val params = ChatCompletionCreateParams.builder()
                    .model(ChatModel.of(model))
                    .addSystemMessage(
                        "You are a concise interview transcript summarizer. " +
                        "Summarize the exchange in 2-3 sentences, preserving key technical points " +
                        "and the candidate's approach. Be brief and factual."
                    )
                    .addUserMessage("Summarize this interview exchange:\n$dialog")
                    .maxCompletionTokens(150)
                    .build()
                val response = openAIClient.chat().completions().create(params)
                response.choices().firstOrNull()?.message()?.content()?.orElse(null)
                    ?: fallback(turns)
            }
        } catch (e: Exception) {
            log.warn("TranscriptCompressor GPT-4o-mini call failed, using fallback: {}", e.message)
            fallback(turns)
        }
    }

    private fun fallback(turns: List<TranscriptTurn>): String =
        turns.joinToString(" | ") { "${it.role}: ${it.content.take(200)}" }
}
