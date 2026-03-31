package com.aiinterview.conversation.brain

/**
 * Transforms leading/anchoring questions into open ones.
 * Applied to AI responses before sending to prevent anchoring bias.
 * Research: Tversky & Kahneman — anchoring effect.
 */
object OpenQuestionTransformer {

    fun transform(text: String): String {
        val sentences = splitSentences(text)
        return sentences.joinToString(" ") { s ->
            if (s.trimEnd().endsWith("?")) transformQuestion(s) else s
        }
    }

    private fun transformQuestion(question: String): String {
        val lower = question.lowercase()

        // "Is it X or Y?" → open question
        if (Regex("is it (.+?) or (.+?)\\?", RegexOption.IGNORE_CASE).containsMatchIn(question)) {
            return when {
                lower.contains("complex") || lower.contains("o(") -> "What's the time complexity of this approach?"
                lower.contains("correct") || lower.contains("right") -> "Walk me through how this handles the constraints."
                lower.contains("better") || lower.contains("prefer") -> "Which approach would you choose and why?"
                lower.contains("memory") || lower.contains("space") -> "What are the memory implications?"
                else -> "How would you think through this?"
            }
        }

        // "Don't you think...?" → open question
        if (lower.contains("don't you think") || lower.contains("wouldn't you say") || lower.contains("isn't it")) {
            return when {
                lower.contains("bfs") || lower.contains("dfs") -> "What graph traversal approach would you use here?"
                lower.contains("hash") -> "What data structure would you use here?"
                lower.contains("sort") -> "How would you approach ordering these elements?"
                else -> "How would you approach this?"
            }
        }

        // "Did you use X?" → open question
        if (Regex("did you (use|choose|pick|select) (.+?)\\?", RegexOption.IGNORE_CASE).containsMatchIn(question)) {
            return "Walk me through your implementation approach."
        }

        return question
    }

    private fun splitSentences(text: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for ((i, c) in text.withIndex()) {
            current.append(c)
            if (c in listOf('.', '?', '!') && i + 1 < text.length && text[i + 1] == ' ') {
                result.add(current.toString().trim())
                current.clear()
            }
        }
        if (current.isNotBlank()) result.add(current.toString().trim())
        return result.ifEmpty { listOf(text) }
    }
}
