package com.aiinterview.interview

import com.aiinterview.interview.model.Question
import com.aiinterview.interview.repository.QuestionRepository
import com.aiinterview.interview.service.QuestionGenerationParams
import com.aiinterview.interview.service.QuestionGeneratorService
import com.aiinterview.interview.service.QuestionService
import com.aiinterview.shared.domain.Difficulty
import com.aiinterview.shared.domain.InterviewCategory
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.UUID

class QuestionServiceTest {

    private val questionRepository       = mockk<QuestionRepository>()
    private val questionGeneratorService = mockk<QuestionGeneratorService>()
    private val objectMapper             = jacksonObjectMapper()
    private val service                  = QuestionService(questionRepository, questionGeneratorService, objectMapper)

    private val codingParams = QuestionGenerationParams(
        category   = InterviewCategory.CODING,
        difficulty = Difficulty.MEDIUM,
        topic      = "binary trees",
    )

    private fun makeQuestion(
        id: UUID = UUID.randomUUID(),
        slug: String = "test-slug",
        category: String = "CODING",
    ) = Question(
        id          = id,
        title       = "Test Question",
        description = "Description",
        type        = category,
        difficulty  = "MEDIUM",
        category    = category,
        slug        = slug,
    )

    // ── getOrGenerateQuestion ─────────────────────────────────────────────────────

    @Test
    fun `returns DB question without calling generator when match found`() = runTest {
        val existing = makeQuestion()
        every { questionRepository.findRandom("CODING", "MEDIUM") } returns Mono.just(existing)

        val result = service.getOrGenerateQuestion(codingParams)

        assertEquals(existing.id, result.id)
        coVerify(exactly = 0) { questionGeneratorService.generateQuestion(any()) }
    }

    @Test
    fun `generates and saves question when DB has no match`() = runTest {
        val generated = makeQuestion(id = UUID.randomUUID(), slug = "generated-slug")
        val saved     = generated.copy(id = UUID.randomUUID())

        every { questionRepository.findRandom("CODING", "MEDIUM") } returns Mono.empty()
        coEvery { questionGeneratorService.generateQuestion(any()) } returns generated
        every { questionRepository.countBySlug("generated-slug") } returns Mono.just(0L)
        every { questionRepository.save(any()) } returns Mono.just(saved)

        val result = service.getOrGenerateQuestion(codingParams)

        assertEquals(saved.id, result.id)
        verify(exactly = 1) { questionRepository.save(any()) }
    }

    @Test
    fun `appends counter suffix when slug already exists`() = runTest {
        val generated = makeQuestion(slug = "two-sum")
        val saved     = generated.copy(id = UUID.randomUUID(), slug = "two-sum-2")

        every { questionRepository.findRandom("CODING", "MEDIUM") } returns Mono.empty()
        coEvery { questionGeneratorService.generateQuestion(any()) } returns generated
        // first slug "two-sum" exists, "two-sum-2" is free
        every { questionRepository.countBySlug("two-sum") }   returns Mono.just(1L)
        every { questionRepository.countBySlug("two-sum-2") } returns Mono.just(0L)
        every { questionRepository.save(any()) } returns Mono.just(saved)

        val result = service.getOrGenerateQuestion(codingParams)

        assertEquals("two-sum-2", result.slug)
    }

    @Test
    fun `excludes already-selected IDs when fetching candidates`() = runTest {
        val usedId = UUID.randomUUID()
        val usedQ  = makeQuestion(id = usedId, slug = "q1")
        val newQ   = makeQuestion(id = UUID.randomUUID(), slug = "q2")

        // findRandom not called when excludeIds non-empty; findRandomCandidates is used
        every {
            questionRepository.findRandomCandidates("CODING", "MEDIUM")
        } returns Flux.just(usedQ, newQ)

        val result = service.getOrGenerateQuestion(codingParams, excludeIds = listOf(usedId))

        assertEquals(newQ.id, result.id)
    }

    @Test
    fun `getOrGenerateQuestion generates when all candidates are excluded`() = runTest {
        val usedId    = UUID.randomUUID()
        val usedQ     = makeQuestion(id = usedId, slug = "q1")
        val generated = makeQuestion(id = UUID.randomUUID(), slug = "generated-new")
        val saved     = generated.copy()

        every { questionRepository.findRandomCandidates("CODING", "MEDIUM") } returns Flux.just(usedQ)
        coEvery { questionGeneratorService.generateQuestion(any()) } returns generated
        every { questionRepository.countBySlug("generated-new") } returns Mono.just(0L)
        every { questionRepository.save(any()) } returns Mono.just(saved)

        val result = service.getOrGenerateQuestion(codingParams, excludeIds = listOf(usedId))

        assertNotNull(result)
        verify(exactly = 1) { questionRepository.save(any()) }
    }

    // ── selectQuestionsForSession ─────────────────────────────────────────────────

    @Test
    fun `selectQuestionsForSession returns no duplicate IDs`() = runTest {
        val q1 = makeQuestion(id = UUID.randomUUID(), slug = "q1")
        val q2 = makeQuestion(id = UUID.randomUUID(), slug = "q2")

        // First call: findRandom with no exclusions → q1
        // Second call: findRandomCandidates with q1 excluded → q2
        every { questionRepository.findRandom("CODING", "MEDIUM") }           returns Mono.just(q1)
        every { questionRepository.findRandomCandidates("CODING", "MEDIUM") } returns Flux.just(q1, q2)

        val results = service.selectQuestionsForSession(
            category   = InterviewCategory.CODING,
            difficulty = Difficulty.MEDIUM,
            count      = 2,
            topic      = "arrays",
        )

        assertEquals(2, results.size)
        assertEquals(setOf(q1.id, q2.id), results.map { it.id }.toSet())
    }

    // ── softDeleteQuestion ────────────────────────────────────────────────────────

    @Test
    fun `softDeleteQuestion sets deletedAt`() = runTest {
        val id = UUID.randomUUID()
        val q  = makeQuestion(id = id)

        every { questionRepository.findById(id) } returns Mono.just(q)
        every { questionRepository.save(any()) } answers {
            val saved = firstArg<Question>()
            assertNotNull(saved.deletedAt)
            Mono.just(saved)
        }

        service.softDeleteQuestion(id)

        verify(exactly = 1) { questionRepository.save(any()) }
    }

    @Test
    fun `getQuestionById throws NoSuchElementException for missing ID`() = runTest {
        val id = UUID.randomUUID()
        every { questionRepository.findById(id) } returns Mono.empty()

        assertThrows<NoSuchElementException> {
            service.getQuestionById(id)
        }
    }
}
