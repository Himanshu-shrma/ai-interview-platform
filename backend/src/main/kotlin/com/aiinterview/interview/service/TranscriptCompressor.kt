package com.aiinterview.interview.service

import org.springframework.stereotype.Component

@Component
class TranscriptCompressor {
    /**
     * Compresses a list of transcript turns into a summary string.
     * STUB: Real GPT-4o-mini compression wired in Prompt 9.
     */
    fun compress(turns: List<TranscriptTurn>): String =
        turns.joinToString(" | ") { "${it.role}: ${it.content.take(200)}" }
}
