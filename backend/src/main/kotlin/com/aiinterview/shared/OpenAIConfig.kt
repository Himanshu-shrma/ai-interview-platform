package com.aiinterview.shared

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Creates a singleton OpenAI blocking client.
 * Injected by QuestionGeneratorService (Prompt 5), InterviewerAgent (Prompt 9),
 * background agents (Prompt 10), and EvaluationAgent (Prompt 12).
 *
 * NEVER create a new OpenAIOkHttpClient instance outside this class.
 * All callers must wrap blocking SDK calls in withContext(Dispatchers.IO).
 */
@Configuration
class OpenAIConfig {

    @Bean
    fun openAIClient(@Value("\${openai.api-key}") apiKey: String): OpenAIClient =
        OpenAIOkHttpClient.builder()
            .apiKey(apiKey)
            .build()
}
