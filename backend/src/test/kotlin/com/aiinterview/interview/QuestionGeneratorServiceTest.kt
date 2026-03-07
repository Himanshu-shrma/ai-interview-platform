package com.aiinterview.interview

import com.aiinterview.interview.service.QuestionGenerationParams
import com.aiinterview.interview.service.QuestionGeneratorService
import com.aiinterview.shared.ai.LlmProviderRegistry
import com.aiinterview.shared.ai.LlmResponse
import com.aiinterview.shared.ai.ModelConfig
import com.aiinterview.shared.domain.Difficulty
import com.aiinterview.shared.domain.InterviewCategory
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class QuestionGeneratorServiceTest {

    private val llm          = mockk<LlmProviderRegistry>()
    private val modelConfig  = ModelConfig()
    private val objectMapper = jacksonObjectMapper()
    private val service      = QuestionGeneratorService(llm, modelConfig, objectMapper)

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private fun stubLlm(json: String) {
        coEvery { llm.complete(any()) } returns LlmResponse(
            content = json, model = "gpt-4o", provider = "openai",
        )
    }

    // ── Tests ────────────────────────────────────────────────────────────────────

    @Test
    fun `CODING category generates question with exactly 3 hints and 3+ test cases`() = runTest {
        val json = """
        {
          "title": "Binary Tree Level Order Traversal",
          "description": "Given the root of a binary tree, return its level order traversal.",
          "examples": [{"input": "[3,9,20,null,null,15,7]", "output": "[[3],[9,20],[15,7]]", "explanation": "BFS per level"}],
          "constraints": "- 0 <= number of nodes <= 2000\n- -1000 <= Node.val <= 1000",
          "optimal_approach": "BFS with a queue, tracking level boundaries.",
          "solution_hints": [
            "Think about processing nodes level by level",
            "A queue (FIFO) naturally models BFS traversal",
            "Track how many nodes are in the current level before starting the next"
          ],
          "follow_up_prompts": ["Can you do it iteratively?", "What is the space complexity?"],
          "test_cases": [
            {"input": "[3,9,20,null,null,15,7]", "output": "[[3],[9,20],[15,7]]", "is_hidden": false},
            {"input": "[1]", "output": "[[1]]", "is_hidden": false},
            {"input": "[]", "output": "[]", "is_hidden": true}
          ],
          "time_complexity": "O(n)",
          "space_complexity": "O(n)"
        }
        """.trimIndent()

        stubLlm(json)

        val params = QuestionGenerationParams(
            category   = InterviewCategory.CODING,
            difficulty = Difficulty.MEDIUM,
            topic      = "binary trees",
        )
        val question = service.generateQuestion(params)

        assertEquals("CODING", question.category)
        assertEquals("CODING", question.type)
        assertNotNull(question.solutionHints)
        assertNotNull(question.testCases)
        assertEquals("O(n)", question.timeComplexity)
        assertEquals("O(n)", question.spaceComplexity)
        assertNotNull(question.slug)
        assertEquals("AI_GENERATED", question.source)

        val hints = objectMapper.readTree(question.solutionHints!!)
        assertEquals(3, hints.size())

        val testCases = objectMapper.readTree(question.testCases!!)
        assertTrue(testCases.size() >= 3)
    }

    @Test
    fun `DSA category maps to type DSA`() = runTest {
        val json = """
        {
          "title": "Find K Closest Points to Origin",
          "description": "Given a list of points, find the K closest to origin.",
          "examples": [{"input": "points=[[1,3],[-2,2]], k=1", "output": "[[-2,2]]", "explanation": "Distance sqrt(8) < sqrt(10)"}],
          "constraints": "- 1 <= k <= points.length <= 10000",
          "optimal_approach": "Min-heap of size K or quickselect.",
          "solution_hints": [
            "Euclidean distance formula — no need for square root",
            "A max-heap of size K works: pop when heap exceeds K",
            "Quickselect achieves O(n) average — similar to QuickSort partition"
          ],
          "follow_up_prompts": ["Can you solve in O(n log k)?", "What about streaming data?"],
          "test_cases": [
            {"input": "[[1,3],[-2,2]], k=1", "output": "[[-2,2]]", "is_hidden": false},
            {"input": "[[3,3],[5,-1],[-2,4]], k=2", "output": "[[3,3],[-2,4]]", "is_hidden": false},
            {"input": "[[0,0]], k=1", "output": "[[0,0]]", "is_hidden": true}
          ],
          "time_complexity": "O(n log k)",
          "space_complexity": "O(k)"
        }
        """.trimIndent()

        stubLlm(json)

        val question = service.generateQuestion(
            QuestionGenerationParams(InterviewCategory.DSA, Difficulty.MEDIUM, "heap")
        )

        assertEquals("DSA", question.category)
        assertEquals("DSA", question.type)
    }

    @Test
    fun `BEHAVIORAL category generates question without test cases`() = runTest {
        val json = """
        {
          "title": "Tell me about a time you disagreed with your manager",
          "description": "Assesses conflict resolution, communication, and professionalism.",
          "examples": {
            "strong_answer": "I once disagreed with my manager about the release timeline. I prepared data, presented it respectfully, and we reached a compromise that satisfied both engineering and business needs.",
            "weak_answer": "I just went along with what my manager said even though I thought they were wrong."
          },
          "follow_up_prompts": ["What was the outcome?", "Would you handle it differently now?"],
          "evaluation_criteria": {
            "criteria": ["Respectful communication", "Data-driven approach", "Outcome focus"],
            "red_flags": ["Never disagreed with manager", "Went behind manager's back"]
          }
        }
        """.trimIndent()

        stubLlm(json)

        val params = QuestionGenerationParams(
            category   = InterviewCategory.BEHAVIORAL,
            difficulty = Difficulty.MEDIUM,
            topic      = "conflict resolution",
        )
        val question = service.generateQuestion(params)

        assertEquals("BEHAVIORAL", question.category)
        assertEquals("BEHAVIORAL", question.type)
        assertNull(question.testCases)
        assertNull(question.solutionHints)
        assertNotNull(question.evaluationCriteria)
    }

    @Test
    fun `retries once on invalid JSON and succeeds on second call`() = runTest {
        val badJson  = "not valid json at all"
        val goodJson = """
        {
          "title": "Two Sum",
          "description": "Find two numbers that add up to target.",
          "examples": [{"input": "nums=[2,7,11,15], target=9", "output": "[0,1]", "explanation": "2+7=9"}],
          "constraints": "- 2 <= nums.length <= 10000",
          "optimal_approach": "Hash map for O(n) lookup.",
          "solution_hints": [
            "Brute force is O(n^2) — can you do better?",
            "Complement lookup: target - nums[i]",
            "Store each element in a hash map as you iterate"
          ],
          "follow_up_prompts": ["What if there are multiple solutions?"],
          "test_cases": [
            {"input": "[2,7,11,15], 9", "output": "[0,1]", "is_hidden": false},
            {"input": "[3,2,4], 6", "output": "[1,2]", "is_hidden": false},
            {"input": "[3,3], 6", "output": "[0,1]", "is_hidden": true}
          ],
          "time_complexity": "O(n)",
          "space_complexity": "O(n)"
        }
        """.trimIndent()

        coEvery { llm.complete(any()) } returnsMany listOf(
            LlmResponse(content = badJson, model = "gpt-4o", provider = "openai"),
            LlmResponse(content = goodJson, model = "gpt-4o", provider = "openai"),
        )

        val question = service.generateQuestion(
            QuestionGenerationParams(InterviewCategory.CODING, Difficulty.EASY, "arrays")
        )

        assertNotNull(question.title)
        assertEquals("Two Sum", question.title)
        coVerify(exactly = 2) { llm.complete(any()) }
    }

    @Test
    fun `throws after two consecutive bad JSON responses`() = runTest {
        coEvery { llm.complete(any()) } returns LlmResponse(
            content = "bad json", model = "gpt-4o", provider = "openai",
        )

        assertThrows<IllegalStateException> {
            service.generateQuestion(
                QuestionGenerationParams(InterviewCategory.CODING, Difficulty.EASY, "sorting")
            )
        }
        coVerify(exactly = 2) { llm.complete(any()) }
    }

    @Test
    fun `generateSlug converts title to kebab-case`() {
        assertEquals("binary-tree-level-order-traversal", service.generateSlug("Binary Tree Level Order Traversal"))
        assertEquals("two-sum", service.generateSlug("Two Sum"))
        assertEquals("find-k-closest-points", service.generateSlug("Find K Closest Points!"))
    }

    @Test
    fun `generateSlug truncates to 100 chars`() {
        val longTitle = "A".repeat(200)
        val slug = service.generateSlug(longTitle)
        assertTrue(slug.length <= 100)
    }

    @Test
    fun `company tailoring is reflected in user prompt for google`() = runTest {
        val json = """
        {
          "title": "Graph BFS",
          "description": "BFS traversal of a graph.",
          "examples": [{"input": "graph=[[1,2],[3],[4],[],[]]", "output": "[0,1,2,3,4]", "explanation": "BFS order"}],
          "constraints": "- 1 <= n <= 1000",
          "optimal_approach": "Standard BFS with visited set.",
          "solution_hints": [
            "Use a queue for BFS",
            "Track visited nodes to avoid cycles",
            "Process neighbors level by level"
          ],
          "follow_up_prompts": ["How does this differ from DFS?"],
          "test_cases": [
            {"input": "[[1,2],[3],[4],[],[]]", "output": "[0,1,2,3,4]", "is_hidden": false},
            {"input": "[[]]", "output": "[0]", "is_hidden": false},
            {"input": "[[1],[2],[0]]", "output": "[0,1,2]", "is_hidden": true}
          ],
          "time_complexity": "O(V+E)",
          "space_complexity": "O(V)"
        }
        """.trimIndent()

        stubLlm(json)

        val question = service.generateQuestion(
            QuestionGenerationParams(
                category      = InterviewCategory.CODING,
                difficulty    = Difficulty.HARD,
                topic         = "graphs",
                targetCompany = "google",
            )
        )
        assertNotNull(question)
        assertEquals("CODING", question.category)
    }
}
