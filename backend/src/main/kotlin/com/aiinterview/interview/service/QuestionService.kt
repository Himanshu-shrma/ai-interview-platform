package com.aiinterview.interview.service

import com.aiinterview.interview.model.Question
import com.aiinterview.interview.repository.QuestionRepository
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

@Service
class QuestionService(
    private val questionRepository: QuestionRepository,
    private val questionGeneratorService: QuestionGeneratorService,
) {
    private val log = LoggerFactory.getLogger(QuestionService::class.java)

    /**
     * Returns a question from the DB if one matches category + difficulty (random).
     * Falls back to GPT-4o generation if none found.
     * [excludeIds] prevents returning already-selected questions in a session.
     */
    suspend fun getOrGenerateQuestion(
        params: QuestionGenerationParams,
        excludeIds: List<UUID> = emptyList(),
    ): Question {
        val existing = findFromDb(params.category.name, params.difficulty.name, excludeIds)
        if (existing != null) {
            log.debug("Returning existing DB question [{}] for category={} difficulty={}",
                existing.id, params.category, params.difficulty)
            return existing
        }

        log.info("No DB question found for category={} difficulty={}, generating via GPT-4o…",
            params.category, params.difficulty)
        val generated = questionGeneratorService.generateQuestion(params)
        return saveWithUniqueSlug(generated)
    }

    /**
     * Selects [count] distinct questions for a session, calling getOrGenerateQuestion
     * with progressive exclusion to prevent duplicates.
     */
    suspend fun selectQuestionsForSession(config: InterviewConfig, count: Int): List<Question> {
        val selected = mutableListOf<Question>()
        val excludeIds = mutableListOf<UUID>()
        val params = QuestionGenerationParams(
            category      = config.category,
            difficulty    = config.difficulty,
            topic         = "general",
            targetCompany = config.targetCompany,
            targetRole    = config.targetRole,
        )
        repeat(count) {
            val q = getOrGenerateQuestion(params, excludeIds)
            selected.add(q)
            q.id?.let { excludeIds.add(it) }
        }
        return selected
    }

    suspend fun getQuestionById(id: UUID): Question =
        questionRepository.findById(id).awaitSingleOrNull()
            ?: throw NoSuchElementException("Question not found: $id")

    suspend fun getQuestionBySlug(slug: String): Question =
        questionRepository.findBySlug(slug).awaitSingleOrNull()
            ?: throw NoSuchElementException("Question not found: $slug")

    suspend fun softDeleteQuestion(id: UUID) {
        val q = getQuestionById(id)
        questionRepository.save(q.copy(deletedAt = OffsetDateTime.now())).awaitSingle()
    }

    suspend fun listAll(): List<Question> =
        questionRepository.findAll()
            .filter { it.deletedAt == null }
            .collectList()
            .awaitSingle()

    // ── Private helpers ───────────────────────────────────────────────────────────

    private suspend fun findFromDb(
        category: String,
        difficulty: String,
        excludeIds: List<UUID>,
    ): Question? =
        if (excludeIds.isEmpty()) {
            questionRepository.findRandom(category, difficulty).awaitSingleOrNull()
        } else {
            // Fetch candidates and filter in Kotlin — avoids R2DBC empty-IN-list issue
            questionRepository.findRandomCandidates(category, difficulty)
                .filter { it.id != null && it.id !in excludeIds }
                .next()
                .awaitSingleOrNull()
        }

    /**
     * Saves the question, ensuring slug uniqueness.
     * If the slug already exists, appends -2, -3, … until unique.
     */
    private suspend fun saveWithUniqueSlug(question: Question): Question {
        val baseSlug = question.slug
            ?: questionGeneratorService.generateSlug(question.title)
        var slug = baseSlug
        var counter = 2

        while (questionRepository.countBySlug(slug).awaitSingle() > 0L) {
            val trimmed = baseSlug.take(97)
            slug = "$trimmed-${counter++}"
        }

        return questionRepository.save(question.copy(slug = slug)).awaitSingle()
    }
}
