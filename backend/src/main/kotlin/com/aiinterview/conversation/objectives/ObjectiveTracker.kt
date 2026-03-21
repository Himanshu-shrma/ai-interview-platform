package com.aiinterview.conversation.objectives

import com.aiinterview.interview.service.InterviewMemory
import com.aiinterview.interview.service.RedisMemoryService
import com.aiinterview.shared.ai.LlmProviderRegistry
import com.aiinterview.shared.ai.LlmRequest
import com.aiinterview.shared.ai.ModelConfig
import com.aiinterview.shared.ai.ResponseFormat
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Background agent that marks objectives as complete based on LLM understanding
 * of each exchange. Runs fire-and-forget after every candidate message.
 */
@Component
class ObjectiveTracker(
    private val llm: LlmProviderRegistry,
    private val redisMemoryService: RedisMemoryService,
    private val objectMapper: ObjectMapper,
    private val config: ModelConfig,
) {
    private val log = LoggerFactory.getLogger(ObjectiveTracker::class.java)

    suspend fun update(
        sessionId: UUID,
        candidateMessage: String,
        aiResponse: String,
        memory: InterviewMemory,
    ) {
        try {
            val objectives = ObjectivesRegistry.forCategory(memory.category)
            val completed = memory.completedObjectives
            val incomplete = objectives.required.filter { it.id !in completed }
            if (incomplete.isEmpty()) return

            // Only check objectives whose dependencies are met
            val checkable = incomplete.filter { obj ->
                obj.dependsOn.all { dep -> dep in completed }
            }
            if (checkable.isEmpty()) return

            val isCodingType = memory.category.uppercase() in setOf("CODING", "DSA")

            val response = llm.complete(
                LlmRequest.build(
                    systemPrompt = "You track interview objective completion. " +
                        "Return ONLY valid JSON: {\"completed\": [\"id1\", \"id2\"]}. " +
                        "Empty array if nothing was completed. " +
                        "Only mark complete if CLEARLY demonstrated this exchange. Be conservative.",
                    userMessage = buildString {
                        appendLine("EXCHANGE:")
                        appendLine("Candidate: \"${candidateMessage.take(500)}\"")
                        appendLine("AI: \"${aiResponse.take(300)}\"")
                        appendLine("Has real code: ${!memory.currentCode.isNullOrBlank() && (memory.currentCode?.trim()?.length ?: 0) > 50}")
                        if (isCodingType) {
                            memory.lastTestResult?.let {
                                appendLine("Tests passing: ${it.passed}/${it.total}")
                            }
                        }
                        appendLine()
                        appendLine("OBJECTIVES TO CHECK:")
                        checkable.forEach { obj ->
                            appendLine("- ${obj.id}: ${obj.completionSignal}")
                        }
                    },
                    model = config.backgroundModel,
                    maxTokens = 80,
                    responseFormat = ResponseFormat.JSON,
                ),
            )

            val cleaned = response.content.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()

            val result = runCatching {
                objectMapper.readTree(cleaned)
                    .get("completed")
                    ?.map { it.asText() }
                    ?.filter { id -> checkable.any { it.id == id } }
                    ?: emptyList()
            }.getOrElse { emptyList() }

            if (result.isNotEmpty()) {
                redisMemoryService.markObjectivesComplete(sessionId, result)
                log.info("Objectives completed session={} ids={}", sessionId, result)
            }

            // Track stall detection
            val nextObj = checkable.firstOrNull()
            if (nextObj != null) {
                redisMemoryService.updateMemory(sessionId) { mem ->
                    if (mem.stalledObjectiveId == nextObj.id) {
                        mem.copy(stalledTurnCount = mem.stalledTurnCount + 1)
                    } else {
                        mem.copy(stalledObjectiveId = nextObj.id, stalledTurnCount = 0)
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("ObjectiveTracker failed for {}: {}", sessionId, e.message)
        }
    }
}
