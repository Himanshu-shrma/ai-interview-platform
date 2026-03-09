package com.aiinterview.interview.service

import com.aiinterview.interview.model.Question
import com.aiinterview.shared.ai.LlmProviderRegistry
import com.aiinterview.shared.ai.LlmRequest
import com.aiinterview.shared.ai.ModelConfig
import com.aiinterview.shared.ai.ResponseFormat
import com.aiinterview.shared.domain.Difficulty
import com.aiinterview.shared.domain.InterviewCategory
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

data class QuestionGenerationParams(
    val category: InterviewCategory,
    val difficulty: Difficulty,
    val topic: String,
    val targetRole: String? = null,
    val targetCompany: String? = null,
    val customInstructions: String? = null,
)

@Service
class QuestionGeneratorService(
    private val llm: LlmProviderRegistry,
    private val modelConfig: ModelConfig,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(QuestionGeneratorService::class.java)

    suspend fun generateQuestion(params: QuestionGenerationParams): Question {
        val systemPrompt = systemPromptFor(params.category)
        val userPrompt = buildUserPrompt(params)

        val parsed = callWithRetry(systemPrompt, userPrompt, params.category)
        val question = buildQuestion(parsed, params)

        // Generate code templates for coding/DSA questions
        if (params.category in listOf(InterviewCategory.CODING, InterviewCategory.DSA)) {
            val templates = generateCodeTemplates(question.title, question.description)
            if (templates != null) {
                return question.copy(codeTemplates = templates)
            }
        }

        return question
    }

    fun generateSlug(title: String): String =
        title.lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(100)
            .ifEmpty { "question" }

    private fun buildUserPrompt(params: QuestionGenerationParams): String = buildString {
        appendLine("Generate a ${params.difficulty.name} ${params.category.name} question about: ${params.topic}")
        params.targetRole?.let { appendLine("Target role: $it") }
        params.customInstructions?.let { appendLine("Additional instructions: $it") }
        params.targetCompany?.let { appendLine(companyTailoring(it.lowercase())) }
    }.trim()

    private fun companyTailoring(company: String): String = when (company) {
        "google" -> "Company focus: algorithmic depth, graph/tree heavy, Big-O analysis required."
        "amazon" -> "Company focus: practical + scalable; weave in leadership principles (ownership, bias for action)."
        "meta" -> "Company focus: graph algorithms, product thinking, scale to billions."
        "microsoft" -> "Company focus: OOP design patterns, practical and clean solutions."
        "startup" -> "Company focus: pragmatic, ship-fast, discuss trade-offs explicitly."
        else -> "Company focus: balanced general software engineering."
    }

    private suspend fun callWithRetry(
        systemPrompt: String,
        userPrompt: String,
        category: InterviewCategory,
    ): JsonNode {
        val raw1 = callLlm(systemPrompt, userPrompt)
        val parsed1 = tryParse(raw1, category)
        if (parsed1 != null) return parsed1

        log.warn("First LLM response failed validation, retrying…")
        val raw2 = callLlm(systemPrompt, "Return valid JSON only. $userPrompt")
        return tryParse(raw2, category)
            ?: throw IllegalStateException(
                "Question generation failed after retry: invalid JSON or schema violation",
            )
    }

    private suspend fun callLlm(system: String, user: String): String {
        val response = llm.complete(
            LlmRequest.build(
                systemPrompt = system,
                userMessage = user,
                model = modelConfig.generatorModel,
                maxTokens = 1000,
                responseFormat = ResponseFormat.JSON,
            ),
        )
        return response.content
    }

    private fun tryParse(raw: String, category: InterviewCategory): JsonNode? {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return runCatching {
            val node = objectMapper.readTree(cleaned)
            validate(node, category)
            node
        }.onFailure { log.warn("JSON validation failed: {}", it.message) }.getOrNull()
    }

    private fun validate(node: JsonNode, category: InterviewCategory) {
        require(node.has("title") && node.get("title").asText().isNotBlank()) { "Missing 'title'" }
        require(node.has("description") && node.get("description").asText().isNotBlank()) { "Missing 'description'" }

        if (category == InterviewCategory.CODING || category == InterviewCategory.DSA) {
            val hints = node.get("solution_hints")
            require(hints != null && hints.isArray && hints.size() == 3) {
                "solution_hints must have exactly 3 entries, got ${hints?.size() ?: 0}"
            }
            val testCases = node.get("test_cases")
            require(testCases != null && testCases.isArray && testCases.size() >= 3) {
                "test_cases must have at least 3 entries, got ${testCases?.size() ?: 0}"
            }
        }
    }

    private fun buildQuestion(node: JsonNode, params: QuestionGenerationParams): Question {
        val title = node.get("title")?.asText()?.trim() ?: params.topic

        val dbType = when (params.category) {
            InterviewCategory.CODING -> "CODING"
            InterviewCategory.DSA -> "DSA"
            InterviewCategory.BEHAVIORAL -> "BEHAVIORAL"
            InterviewCategory.SYSTEM_DESIGN -> "SYSTEM_DESIGN"
            InterviewCategory.CASE_STUDY -> "CODING"
        }

        return Question(
            title = title,
            description = node.get("description")?.asText() ?: "",
            type = dbType,
            difficulty = params.difficulty.name,
            topicTags = arrayOf(params.topic),
            examples = node.get("examples")?.let { objectMapper.writeValueAsString(it) },
            constraintsText = node.get("constraints")?.asText(),
            testCases = node.get("test_cases")?.let { objectMapper.writeValueAsString(it) },
            solutionHints = node.get("solution_hints")?.let { objectMapper.writeValueAsString(it) },
            optimalApproach = node.get("optimal_approach")?.asText(),
            followUpPrompts = node.get("follow_up_prompts")?.let { objectMapper.writeValueAsString(it) },
            source = "AI_GENERATED",
            generationParams = objectMapper.writeValueAsString(params),
            spaceComplexity = node.get("space_complexity")?.asText(),
            timeComplexity = node.get("time_complexity")?.asText(),
            evaluationCriteria = node.get("evaluation_criteria")?.let { objectMapper.writeValueAsString(it) },
            slug = generateSlug(title),
            category = params.category.name,
        )
    }

    private fun systemPromptFor(category: InterviewCategory): String = when (category) {
        InterviewCategory.CODING, InterviewCategory.DSA -> CODING_DSA_PROMPT
        InterviewCategory.BEHAVIORAL -> BEHAVIORAL_PROMPT
        InterviewCategory.SYSTEM_DESIGN -> SYSTEM_DESIGN_PROMPT
        InterviewCategory.CASE_STUDY -> CASE_STUDY_PROMPT
    }

    /**
     * Generates starter code templates for 5 languages.
     * Templates contain the correct function signature with a "// your code here" placeholder.
     */
    private suspend fun generateCodeTemplates(title: String, description: String): String? = try {
        val templatePrompt = """
For this coding problem:
"$title: ${description.take(300)}"

Generate starter code templates. Each template should have:
- Correct class/function signature for the problem
- Correct parameter names and types
- A single comment: // your code here  (or # your code here for Python)
- For Java: use class Main (Judge0 requirement), not Solution

Return ONLY valid JSON:
{
  "python": "def function_name(params):\\n    # your code here\\n    pass",
  "java": "import java.util.*;\\n\\npublic class Main {\\n    public static ReturnType functionName(params) {\\n        // your code here\\n    }\\n\\n    public static void main(String[] args) {\\n        // test your solution here\\n    }\\n}",
  "javascript": "function functionName(params) {\\n    // your code here\\n}",
  "cpp": "#include <vector>\\nusing namespace std;\\n\\nReturnType functionName(params) {\\n    // your code here\\n}",
  "typescript": "function functionName(params: types): ReturnType {\\n    // your code here\\n}"
}
""".trimIndent()

        val raw = callLlm(
            "You generate code templates for interview problems. Return only JSON, no markdown.",
            templatePrompt,
        )
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        // Validate it's valid JSON
        objectMapper.readTree(cleaned)
        cleaned
    } catch (e: Exception) {
        log.warn("Code template generation failed for '{}': {}", title, e.message)
        null
    }

    companion object {

        private val CODING_DSA_PROMPT = """
            You are an expert technical interviewer creating a coding/DSA interview question.
            Return ONLY valid JSON — no markdown fences, no preamble, no explanation.
            The JSON must contain exactly these fields:
            {
              "title": "string — concise problem title",
              "description": "string — full problem statement in markdown",
              "examples": [{"input": "string", "output": "string", "explanation": "string"}],
              "constraints": "string — constraints as markdown bullet list",
              "optimal_approach": "string — description of the optimal algorithm",
              "solution_hints": [
                "Hint 1: conceptual direction only",
                "Hint 2: data structure or technique nudge",
                "Hint 3: near-explicit approach"
              ],
              "follow_up_prompts": ["follow-up question 1", "follow-up question 2"],
              "test_cases": [
                {"input": "string", "output": "string", "is_hidden": false},
                {"input": "string", "output": "string", "is_hidden": false},
                {"input": "string", "output": "string", "is_hidden": true}
              ],
              "time_complexity": "O(...)",
              "space_complexity": "O(...)"
            }
            RULES: solution_hints MUST have EXACTLY 3 entries. test_cases MUST have AT LEAST 3 entries.
        """.trimIndent()

        private val BEHAVIORAL_PROMPT = """
            You are an expert behavioral interviewer using STAR methodology.
            Return ONLY valid JSON — no markdown fences, no preamble, no explanation.
            The JSON must contain exactly these fields:
            {
              "title": "string — the behavioral question itself (e.g. 'Tell me about a time you disagreed with your manager')",
              "description": "string — context and what competencies the interviewer is assessing",
              "examples": {
                "strong_answer": "string — example of a strong STAR answer",
                "weak_answer": "string — example of a weak answer and why it fails"
              },
              "follow_up_prompts": ["follow-up question 1", "follow-up question 2"],
              "evaluation_criteria": {
                "criteria": ["what to assess 1", "what to assess 2"],
                "red_flags": ["red flag to watch for 1"]
              }
            }
        """.trimIndent()

        private val SYSTEM_DESIGN_PROMPT = """
            You are an expert system design interviewer.
            Return ONLY valid JSON — no markdown fences, no preamble, no explanation.
            The JSON must contain exactly these fields:
            {
              "title": "string — the design challenge (e.g. 'Design Twitter')",
              "description": "string — scope, functional and non-functional requirements in markdown",
              "constraints": "string — scale requirements in markdown (e.g. 100M users, 1B events/day, 99.9% uptime)",
              "follow_up_prompts": [
                "How would you handle scalability?",
                "What are the bottlenecks?",
                "What trade-offs did you make?"
              ],
              "evaluation_criteria": {
                "areas": ["area to evaluate 1", "area to evaluate 2"],
                "key_concepts": ["key concept 1", "key concept 2"]
              }
            }
        """.trimIndent()

        private val CASE_STUDY_PROMPT = """
            You are an expert case study interviewer.
            Return ONLY valid JSON — no markdown fences, no preamble, no explanation.
            The JSON must contain exactly these fields:
            {
              "title": "string — business scenario title",
              "description": "string — full business context and problem statement in markdown",
              "examples": {
                "context": "string — additional context or background",
                "sample_data": "string — example metrics or data points"
              },
              "follow_up_prompts": ["follow-up question 1", "follow-up question 2"],
              "evaluation_criteria": {
                "framework": "string — recommended analysis framework",
                "key_metrics": ["metric to evaluate 1", "metric to evaluate 2"]
              }
            }
        """.trimIndent()
    }
}
