package com.aiinterview.conversation

import com.aiinterview.interview.service.InterviewMemory
import com.aiinterview.interview.service.RedisMemoryService
import com.aiinterview.shared.ai.LlmProviderRegistry
import com.aiinterview.shared.ai.LlmRequest
import com.aiinterview.shared.ai.ModelConfig
import com.aiinterview.shared.ai.ResponseFormat
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
data class StageReflection(
    val demonstrated: String = "",
    val gaps: String? = null,
    val focusFor: String = "",
    val carryForward: String = "",
)

/**
 * Runs ONLY when stage changes (3-4x per interview).
 * Analyzes completed stage, plans approach for next.
 * Saves structured note to Redis for use in future turns.
 */
@Component
class StageReflectionAgent(
    private val llm: LlmProviderRegistry,
    private val redisMemoryService: RedisMemoryService,
    private val objectMapper: ObjectMapper,
    private val modelConfig: ModelConfig,
) {
    private val log = LoggerFactory.getLogger(StageReflectionAgent::class.java)

    suspend fun reflectOnTransition(
        sessionId: UUID,
        fromStage: String,
        toStage: String,
        memory: InterviewMemory,
    ) {
        try {
            val recentMessages = memory.rollingTranscript
                .takeLast(8)
                .joinToString("\n") { turn ->
                    "${turn.role}: ${turn.content.take(200)}"
                }

            if (recentMessages.isBlank()) return

            val response = llm.complete(
                LlmRequest.build(
                    systemPrompt = """
You just finished stage "$fromStage" and entering "$toStage".
Analyze what happened and plan for next stage.
Return ONLY valid JSON:
{
  "demonstrated": "what candidate showed well (max 20 words)",
  "gaps": "what was weak or missing (max 20 words or null)",
  "focusFor": "what to probe in $toStage (max 20 words)",
  "carryForward": "one key thing to remember (max 20 words)"
}
                    """.trimIndent(),
                    userMessage = "Recent exchange:\n$recentMessages",
                    model = modelConfig.backgroundModel,
                    maxTokens = 200,
                    responseFormat = ResponseFormat.JSON,
                ),
            )

            val cleaned = response.content.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()

            val reflection = objectMapper.readValue(cleaned, StageReflection::class.java)

            val note = "[$toStage PLAN] ${reflection.carryForward}. " +
                "Focus: ${reflection.focusFor}." +
                (reflection.gaps?.let { " Gap noted: $it." } ?: "")

            redisMemoryService.appendAgentNote(sessionId, note)
            log.debug("Stage reflection saved for session {} ({}->{})", sessionId, fromStage, toStage)
        } catch (e: Exception) {
            log.warn("Reflection failed for {}: {}", sessionId, e.message)
        }
    }
}
