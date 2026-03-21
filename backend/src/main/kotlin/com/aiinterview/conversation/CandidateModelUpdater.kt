package com.aiinterview.conversation

import com.aiinterview.interview.service.CandidateModel
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
 * Background agent that builds an evolving mental model of the candidate.
 * Runs every 2 turns (cost-efficient) to give the AI accumulated understanding.
 */
@Component
class CandidateModelUpdater(
    private val llm: LlmProviderRegistry,
    private val redisMemoryService: RedisMemoryService,
    private val objectMapper: ObjectMapper,
    private val config: ModelConfig,
) {
    private val log = LoggerFactory.getLogger(CandidateModelUpdater::class.java)

    suspend fun update(sessionId: UUID, memory: InterviewMemory) {
        val turnCount = memory.turnCount
        // Update every 2 turns, starting from turn 2
        if (turnCount < 2 || turnCount % 2 != 0) return

        try {
            val transcript = memory.rollingTranscript
                .takeLast(6)
                .joinToString("\n") { "${it.role}: ${it.content.take(300)}" }
            if (transcript.isBlank()) return

            val existing = memory.candidateModel
            val interviewType = memory.category.uppercase()
            val isCodingType = interviewType in setOf("CODING", "DSA")

            val response = llm.complete(
                LlmRequest.build(
                    systemPrompt = buildString {
                        appendLine("You build a mental model of an interview candidate.")
                        appendLine("Analyze recent conversation and update the model.")
                        appendLine("Return ONLY valid JSON matching this structure:")
                        appendLine("""{
  "thinkingStyle": "bottom-up|top-down|intuitive|methodical|unknown",
  "knowledgeSignals": {"topic": 0.0},
  "behaviorPatterns": ["pattern1"],
  "activeHypotheses": ["hypothesis1"],
  "interviewNarrative": "2-3 sentence theory",
  "stateSignal": "confident|nervous|stuck|flowing|neutral",
  "nextProbeTheory": "what to probe next and why",
  "overallSignal": "strong|solid|average|struggling|unknown"
}""")
                        appendLine("Rules: max 3 patterns, max 3 hypotheses, be honest.")
                        appendLine("Interview type: $interviewType, Turn: $turnCount")
                        appendLine("Previous: thinking=${existing.thinkingStyle}, signal=${existing.overallSignal}")
                        if (existing.interviewNarrative.isNotBlank()) {
                            appendLine("Previous narrative: ${existing.interviewNarrative.take(150)}")
                        }
                    },
                    userMessage = buildString {
                        appendLine("RECENT CONVERSATION:")
                        appendLine(transcript)
                        if (isCodingType) {
                            val hasCode = !memory.currentCode.isNullOrBlank() && (memory.currentCode?.trim()?.length ?: 0) > 50
                            appendLine("Has meaningful code: $hasCode")
                        }
                        appendLine("Eval scores: ps=${memory.evalScores.problemSolving}, alg=${memory.evalScores.algorithmChoice}, comm=${memory.evalScores.communication}")
                    },
                    model = config.backgroundModel,
                    maxTokens = 400,
                    responseFormat = ResponseFormat.JSON,
                ),
            )

            val cleaned = response.content.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()

            val updatedModel = runCatching {
                objectMapper.readValue(cleaned, CandidateModel::class.java)
                    .copy(lastUpdatedTurn = turnCount)
            }.getOrElse {
                log.warn("CandidateModelUpdater parse failed for {}: {}", sessionId, it.message)
                return
            }

            redisMemoryService.updateMemory(sessionId) { mem ->
                mem.copy(candidateModel = updatedModel)
            }

            // If strong probe theory and no pending probe: set as pending
            if (updatedModel.nextProbeTheory.isNotBlank()
                && turnCount >= 4
                && memory.pendingProbe.isNullOrBlank()
            ) {
                redisMemoryService.updateMemory(sessionId) { mem ->
                    mem.copy(pendingProbe = updatedModel.nextProbeTheory)
                }
            }

            log.debug("CandidateModel updated for session {} at turn {}", sessionId, turnCount)
        } catch (e: Exception) {
            log.warn("CandidateModelUpdater failed for {}: {}", sessionId, e.message)
        }
    }
}
