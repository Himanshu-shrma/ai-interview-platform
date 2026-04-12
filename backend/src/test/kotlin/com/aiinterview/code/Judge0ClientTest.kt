package com.aiinterview.code

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.util.Base64

class Judge0ClientTest {

    // ── WebClient chain mocks ─────────────────────────────────────────────────

    private val requestBodyUriSpec  = mockk<WebClient.RequestBodyUriSpec>(relaxed = true)
    private val requestBodySpec     = mockk<WebClient.RequestBodySpec>(relaxed = true)
    private val requestHeadersSpec  = mockk<WebClient.RequestHeadersSpec<*>>(relaxed = true)
    private val responseSpec        = mockk<WebClient.ResponseSpec>(relaxed = true)
    private val requestHeadersUriSpec = mockk<WebClient.RequestHeadersUriSpec<*>>(relaxed = true)
    private val webClient           = mockk<WebClient>(relaxed = true)
    private val webClientBuilder    = mockk<WebClient.Builder>(relaxed = true)

    private lateinit var judge0Client: Judge0Client

    @BeforeEach
    fun setUp() {
        every { webClientBuilder.baseUrl(any()) } returns webClientBuilder
        every { webClientBuilder.build() } returns webClient

        judge0Client = Judge0Client(
            webClientBuilder    = webClientBuilder,
            baseUrl             = "http://localhost:2358",
            authToken           = "test-token",
            authHeader          = "X-Auth-Token",
            pollIntervalMs      = 10L,
            pollTimeoutSecs     = 5L,
        )
    }

    // ── submit ────────────────────────────────────────────────────────────────

    @Test
    fun `submit encodes source code and stdin as Base64 and returns token`() = runTest {
        val fakeToken = "abc-token-123"

        every { webClient.post() } returns requestBodyUriSpec
        every { requestBodyUriSpec.uri(any<String>()) } returns requestBodySpec
        every { requestBodySpec.contentType(any()) } returns requestBodySpec
        every { requestBodySpec.bodyValue(any()) } returns requestHeadersSpec
        every { requestHeadersSpec.retrieve() } returns responseSpec
        @Suppress("UNCHECKED_CAST")
        every { responseSpec.bodyToMono(Map::class.java) } returns
            Mono.just(mapOf("token" to fakeToken) as Map<*, *>)

        val token = judge0Client.submit("print('hello')", 71, "hello")
        assertEquals(fakeToken, token)
    }

    @Test
    fun `submit with null stdin does not encode stdin`() = runTest {
        every { webClient.post() } returns requestBodyUriSpec
        every { requestBodyUriSpec.uri(any<String>()) } returns requestBodySpec
        every { requestBodySpec.contentType(any()) } returns requestBodySpec

        val capturedRequest = mutableListOf<Judge0SubmissionRequest>()
        every { requestBodySpec.bodyValue(capture(capturedRequest)) } returns requestHeadersSpec
        every { requestHeadersSpec.retrieve() } returns responseSpec
        @Suppress("UNCHECKED_CAST")
        every { responseSpec.bodyToMono(Map::class.java) } returns
            Mono.just(mapOf("token" to "tok") as Map<*, *>)

        judge0Client.submit("code", 63, null)

        assertEquals(1, capturedRequest.size)
        assertEquals(null, capturedRequest[0].stdin)
    }

    // ── pollResult ────────────────────────────────────────────────────────────

    @Test
    fun `pollResult returns result when status id is 3`() = runTest {
        val encoded = Base64.getEncoder().encodeToString("Hello World\n".toByteArray())
        val result  = Judge0Result(
            token         = "tok",
            status        = Judge0Status(3, "Accepted"),
            stdout        = encoded,
            stderr        = null,
            compileOutput = null,
            time          = "0.05",
            memory        = 1024,
        )

        every { webClient.get() } returns requestHeadersUriSpec
        every { requestHeadersUriSpec.uri(any<String>()) } returns requestHeadersSpec
        every { requestHeadersSpec.retrieve() } returns responseSpec
        every { responseSpec.bodyToMono(Judge0Result::class.java) } returns Mono.just(result)

        val out = judge0Client.pollResult("tok")
        assertEquals("Hello World\n", out.stdout)
        assertEquals(3, out.status?.id)
    }

    @Test
    fun `pollResult decodes stderr and compileOutput`() = runTest {
        val encodedStderr = Base64.getEncoder().encodeToString("SyntaxError\n".toByteArray())
        val result = Judge0Result(
            token         = "tok",
            status        = Judge0Status(6, "Compilation Error"),
            stdout        = null,
            stderr        = null,
            compileOutput = encodedStderr,
            time          = null,
            memory        = null,
        )

        every { webClient.get() } returns requestHeadersUriSpec
        every { requestHeadersUriSpec.uri(any<String>()) } returns requestHeadersSpec
        every { requestHeadersSpec.retrieve() } returns responseSpec
        every { responseSpec.bodyToMono(Judge0Result::class.java) } returns Mono.just(result)

        val out = judge0Client.pollResult("tok")
        assertEquals("SyntaxError\n", out.compileOutput)
    }

    @Test
    fun `pollResult throws Judge0TimeoutException when timeout exceeded`() = runTest {
        // Return status id=1 (In Queue) forever — will trigger timeout
        val queuedResult = Judge0Result(
            token = "tok", status = Judge0Status(1, "In Queue"),
            stdout = null, stderr = null, compileOutput = null, time = null, memory = null,
        )

        every { webClient.get() } returns requestHeadersUriSpec
        every { requestHeadersUriSpec.uri(any<String>()) } returns requestHeadersSpec
        every { requestHeadersSpec.retrieve() } returns responseSpec
        every { responseSpec.bodyToMono(Judge0Result::class.java) } returns Mono.just(queuedResult)

        // Client was constructed with 5s timeout and 10ms poll — virtual time fast-forwards delay()
        val ex = kotlin.runCatching { judge0Client.pollResult("tok") }.exceptionOrNull()
        assertTrue(ex is Judge0TimeoutException, "Expected Judge0TimeoutException but got $ex")
    }

    // ── LanguageMap ───────────────────────────────────────────────────────────

    @Test
    fun `LanguageMap returns correct id for python`() {
        assertEquals(71, LanguageMap.getLanguageId("python"))
    }

    @Test
    fun `LanguageMap is case insensitive`() {
        assertEquals(63, LanguageMap.getLanguageId("JavaScript"))
        assertEquals(63, LanguageMap.getLanguageId("JAVASCRIPT"))
    }

    @Test
    fun `LanguageMap throws for unsupported language`() {
        assertThrows<UnsupportedLanguageException> {
            LanguageMap.getLanguageId("brainfuck")
        }
    }

    @Test
    fun `LanguageMap getSupportedLanguages returns non-empty sorted list`() {
        val langs = LanguageMap.getSupportedLanguages()
        assert(langs.isNotEmpty())
        assertEquals(langs.sorted(), langs)
    }
}
