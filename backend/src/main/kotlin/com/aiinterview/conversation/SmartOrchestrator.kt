package com.aiinterview.conversation

import com.aiinterview.interview.repository.InterviewSessionRepository
import com.aiinterview.interview.service.RedisMemoryService
import com.aiinterview.shared.ai.LlmProviderRegistry
import com.aiinterview.shared.ai.LlmRequest
import com.aiinterview.shared.ai.ModelConfig
import com.aiinterview.shared.ai.ResponseFormat
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
data class OrchestratorDecision(
    val candidateSignal: String = "OTHER",
    val checklistUpdate: ChecklistUpdateDto? = null,
    val suggestedStage: String? = null,
    val stageConfidence: Double = 0.0,
    val noteToSave: String? = null,
    val isQuestionComplete: Boolean = false,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ChecklistUpdateDto(
    val complexityDiscussed: Boolean? = null,
    val edgeCasesCovered: Int? = null,
)

/**
 * LLM-driven orchestrator that replaces rule-based stage transitions.
 *
 * Runs fire-and-forget AFTER AI response is sent to candidate.
 * Never affects streaming or response time.
 */
@Component
class SmartOrchestrator(
    private val llm: LlmProviderRegistry,
    private val redisMemoryService: RedisMemoryService,
    private val sessionRepository: InterviewSessionRepository,
    private val objectMapper: ObjectMapper,
    private val modelConfig: ModelConfig,
    private val stageReflectionAgent: StageReflectionAgent,
) {
    private val log = LoggerFactory.getLogger(SmartOrchestrator::class.java)

    suspend fun orchestrate(
        sessionId: UUID,
        candidateMessage: String,
        aiResponse: String,
        ctx: StateContext,
    ) {
        try {
            val prompt = buildOrchestratorPrompt(candidateMessage, aiResponse, ctx)
            val response = llm.complete(
                LlmRequest.build(
                    systemPrompt = prompt,
                    userMessage = "Analyze and decide now.",
                    model = modelConfig.backgroundModel,
                    maxTokens = 250,
                    responseFormat = ResponseFormat.JSON,
                ),
            )
            val decision = parseDecision(response.content)
            applyDecision(sessionId, decision, ctx)
        } catch (e: Exception) {
            log.warn("Orchestrator failed silently for {}: {}", sessionId, e.message)
        }
    }

    private fun buildOrchestratorPrompt(
        candidateMessage: String,
        aiResponse: String,
        ctx: StateContext,
    ): String = """
You are the interview orchestrator. Analyze this exchange. Update internal state only.
You do NOT write responses to the candidate.

CURRENT STATE:
Stage: ${ctx.stage}
Question: ${ctx.questionIndex + 1}/${ctx.totalQuestions}
Has real code: ${ctx.hasMeaningfulCode}
Complexity discussed: ${ctx.complexityDiscussed}
Edge cases covered: ${ctx.edgeCasesCovered}
Time remaining: ${ctx.remainingMinutes} min
Hints given: ${ctx.hintsGiven}

WHAT JUST HAPPENED:
Candidate: "${candidateMessage.take(500)}"
AI responded: "${aiResponse.take(500)}"

Return ONLY valid JSON:
{
  "candidateSignal": "CONSTRAINT_Q | APPROACH_EXPLAINED | CODING_STARTED | COMPLEXITY_STATED | EDGE_CASE_COVERED | SOLUTION_DONE | STUCK | SMALL_TALK | OTHER",
  "checklistUpdate": {
    "complexityDiscussed": true or false or null,
    "edgeCasesCovered": integer 0-5 or null
  },
  "suggestedStage": "stage name or null",
  "stageConfidence": 0.0 to 1.0,
  "noteToSave": "specific observation about candidate or null",
  "isQuestionComplete": true or false
}

RULES:
- suggestedStage only if stageConfidence > 0.85
- isQuestionComplete = true ONLY when ALL true: hasMeaningfulCode AND complexityDiscussed AND edgeCasesCovered >= 1
- noteToSave must be specific (e.g. "Said O(n) but hasn't tried hash map"), not generic
- Be conservative — return nulls when uncertain
- checklistUpdate only if something CHANGED in this exchange
    """.trimIndent()

    private suspend fun applyDecision(
        sessionId: UUID,
        decision: OrchestratorDecision,
        ctx: StateContext,
    ) {
        // Update checklist signals
        decision.checklistUpdate?.let { update ->
            update.complexityDiscussed?.let { cd ->
                if (cd && !ctx.complexityDiscussed) {
                    redisMemoryService.setComplexityDiscussed(sessionId, true)
                }
            }
            update.edgeCasesCovered?.let { ec ->
                if (ec > ctx.edgeCasesCovered) {
                    redisMemoryService.setEdgeCasesCovered(sessionId, ec)
                }
            }
        }

        // Persist agent note
        decision.noteToSave
            ?.takeIf { it.length > 10 }
            ?.let { note ->
                redisMemoryService.appendAgentNote(sessionId, note)
            }

        // Stage transition — validated by rules, never arbitrary
        if (decision.stageConfidence > 0.85
            && decision.suggestedStage != null
            && isValidTransition(ctx.stage, decision.suggestedStage)
        ) {
            redisMemoryService.updateStage(sessionId, decision.suggestedStage)
            try {
                val session = withContext(Dispatchers.IO) {
                    sessionRepository.findById(sessionId).awaitSingle()
                }
                withContext(Dispatchers.IO) {
                    sessionRepository.save(session.copy(currentStage = decision.suggestedStage)).awaitSingle()
                }
            } catch (e: Exception) {
                log.warn("Failed to update DB stage for {}: {}", sessionId, e.message)
            }

            log.info("SmartOrchestrator: {} -> {} (confidence={})",
                ctx.stage, decision.suggestedStage, decision.stageConfidence)

            // Fire reflection on stage transition (Phase 4)
            try {
                val memory = redisMemoryService.getMemory(sessionId)
                stageReflectionAgent.reflectOnTransition(
                    sessionId = sessionId,
                    fromStage = ctx.stage,
                    toStage = decision.suggestedStage,
                    memory = memory,
                )
            } catch (e: Exception) {
                log.warn("Reflection launch failed for {}: {}", sessionId, e.message)
            }
        }
    }

    private fun isValidTransition(current: String, target: String): Boolean {
        val valid = mapOf(
            "SMALL_TALK" to setOf("PROBLEM_PRESENTED"),
            "PROBLEM_PRESENTED" to setOf("CLARIFYING", "APPROACH"),
            "CLARIFYING" to setOf("APPROACH"),
            "APPROACH" to setOf("CODING"),
            "CODING" to setOf("REVIEW"),
            "REVIEW" to setOf("FOLLOWUP", "WRAP_UP"),
            "FOLLOWUP" to setOf("WRAP_UP"),
            "WRAP_UP" to emptySet<String>(),
        )
        return target in (valid[current] ?: emptySet())
    }

    private fun parseDecision(raw: String): OrchestratorDecision {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        return try {
            objectMapper.readValue(cleaned, OrchestratorDecision::class.java)
        } catch (e: Exception) {
            log.warn("Failed to parse orchestrator decision: {}", e.message)
            OrchestratorDecision()
        }
    }
}
