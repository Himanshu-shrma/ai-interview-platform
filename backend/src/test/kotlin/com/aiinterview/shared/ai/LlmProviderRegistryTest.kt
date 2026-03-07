package com.aiinterview.shared.ai

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LlmProviderRegistryTest {

    private val request = LlmRequest.build(
        systemPrompt = "You are helpful.",
        userMessage = "Hi",
        model = "gpt-4o",
    )

    private fun provider(name: String): LlmProvider = mockk {
        every { providerName() } returns name
    }

    // ── complete — primary success ────────────────────────────────────────────

    @Test
    fun `complete uses primary provider`() = runTest {
        val primary = provider("openai")
        val response = LlmResponse(content = "Hello!", model = "gpt-4o", provider = "openai")
        coEvery { primary.complete(any()) } returns response

        val registry = LlmProviderRegistry(listOf(primary), "openai", null)
        val result = registry.complete(request)

        assertEquals("Hello!", result.content)
    }

    // ── complete — fallback on rate limit ─────────────────────────────────────

    @Test
    fun `complete falls back on RateLimitException`() = runTest {
        val primary = provider("openai")
        val fallback = provider("groq")
        coEvery { primary.complete(any()) } throws LlmProviderException.RateLimitException("Rate limited")
        coEvery { fallback.complete(any()) } returns LlmResponse(
            content = "Fallback!", model = "llama3", provider = "groq",
        )

        val registry = LlmProviderRegistry(listOf(primary, fallback), "openai", "groq")
        val result = registry.complete(request)

        assertEquals("Fallback!", result.content)
    }

    // ── complete — no fallback throws ─────────────────────────────────────────

    @Test
    fun `complete throws when rate limited with no fallback`() = runTest {
        val primary = provider("openai")
        coEvery { primary.complete(any()) } throws LlmProviderException.RateLimitException("Rate limited")

        val registry = LlmProviderRegistry(listOf(primary), "openai", null)

        assertThrows<LlmProviderException.RateLimitException> {
            registry.complete(request)
        }
    }

    // ── complete — auth exception not caught ──────────────────────────────────

    @Test
    fun `complete does not fallback on AuthenticationException`() = runTest {
        val primary = provider("openai")
        val fallback = provider("groq")
        coEvery { primary.complete(any()) } throws LlmProviderException.AuthenticationException("Bad key")

        val registry = LlmProviderRegistry(listOf(primary, fallback), "openai", "groq")

        assertThrows<LlmProviderException.AuthenticationException> {
            registry.complete(request)
        }
    }

    // ── stream — primary success ──────────────────────────────────────────────

    @Test
    fun `stream uses primary provider`() = runTest {
        val primary = provider("openai")
        every { primary.stream(any()) } returns flowOf("Hello", " world")

        val registry = LlmProviderRegistry(listOf(primary), "openai", null)
        val tokens = registry.stream(request).toList()

        assertEquals(listOf("Hello", " world"), tokens)
    }

    // ── provider not found ────────────────────────────────────────────────────

    @Test
    fun `throws when primary provider not found`() {
        val other = provider("gemini")
        val registry = LlmProviderRegistry(listOf(other), "openai", null)

        assertThrows<IllegalStateException> {
            registry.primary()
        }
    }
}
