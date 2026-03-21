package com.aiinterview.conversation

import com.aiinterview.interview.repository.InterviewSessionRepository
import com.aiinterview.interview.service.InterviewConfig
import com.aiinterview.interview.service.RedisMemoryService
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class StateContext(
    val hasMeaningfulCode: Boolean,
    val codeLanguage: String?,
    val codeLineCount: Int,
    val elapsedMinutes: Int,
    val remainingMinutes: Int,
    val shouldWrapUp: Boolean,
    val isOvertime: Boolean,
    val stage: String,
    val questionIndex: Int,
    val totalQuestions: Int,
    val hintsGiven: Int,
    val complexityDiscussed: Boolean,
    val edgeCasesCovered: Int,
    val testsPassed: Int?,
    val testsTotal: Int?,
    val agentNotes: String,
    val personality: String,
    val targetCompany: String?,
    val experienceLevel: String?,
)

/**
 * Fetches FRESH state from Redis + DB before every LLM call.
 * Replaces stale snapshot injection — always reads current reality.
 */
@Component
class StateContextBuilder(
    private val redisMemoryService: RedisMemoryService,
    private val sessionRepository: InterviewSessionRepository,
    private val objectMapper: ObjectMapper,
) {
    suspend fun build(sessionId: UUID): StateContext {
        val memory = redisMemoryService.getMemory(sessionId)
        val session = withContext(Dispatchers.IO) {
            sessionRepository.findById(sessionId).awaitSingleOrNull()
        }
        val config = session?.config?.let {
            try {
                objectMapper.readValue(it, InterviewConfig::class.java)
            } catch (_: Exception) { null }
        }

        val elapsed = session?.startedAt?.let {
            Duration.between(it.toInstant(), Instant.now()).toMinutes()
        } ?: 0L
        val remaining = (config?.durationMinutes ?: 45) - elapsed

        val code = memory.currentCode?.trim()
        val hasMeaningfulCode = code != null
            && code.length > 50
            && code != "class {}"
            && !code.trimEnd().endsWith("# your code here")
            && !code.trimEnd().endsWith("// your code here")
            && !code.trimEnd().endsWith("pass")
            && code.lines().count { it.isNotBlank() } > 3

        return StateContext(
            hasMeaningfulCode = hasMeaningfulCode,
            codeLanguage = memory.programmingLanguage,
            codeLineCount = code?.lines()?.size ?: 0,
            elapsedMinutes = elapsed.toInt(),
            remainingMinutes = remaining.toInt(),
            shouldWrapUp = remaining <= 5,
            isOvertime = remaining <= 0,
            stage = memory.interviewStage,
            questionIndex = memory.currentQuestionIndex,
            totalQuestions = memory.totalQuestions,
            hintsGiven = memory.hintsGiven,
            complexityDiscussed = memory.complexityDiscussed,
            edgeCasesCovered = memory.edgeCasesCovered,
            testsPassed = memory.lastTestResult?.passed,
            testsTotal = memory.lastTestResult?.total,
            agentNotes = memory.agentNotes,
            personality = config?.personality ?: memory.personality,
            targetCompany = memory.targetCompany ?: config?.targetCompany,
            experienceLevel = memory.experienceLevel ?: config?.experienceLevel,
        )
    }
}
