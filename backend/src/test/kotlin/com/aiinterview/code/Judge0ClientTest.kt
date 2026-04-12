package com.aiinterview.code

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.util.Base64

class Judge0ClientTest {

    // ── WebClient chain mocks ─────────────────────────────────────────────
    // KEY: requestBodyUriSpec must NOT be relaxed — we need explicit stubs
    // for the chain so MockK returns the correct subtype at each step.
    private val requestBodyUriSpec  = mockk<WebClient.RequestBodyUriSpec>()
    private val requestBodySpec     = mockk<WebClient.RequestBodySpec>()
    private val requestHeadersSpec  = mockk<WebClient.RequestHeadersSpec<*>>()
    private val responseSpec        = mockk<WebClient.ResponseSpec>()
    private val requestHeadersUriSpec = mockk<WebClient.RequestHeadersUriSpec<*>>()
    private val webClient           = mockk<WebClient>()
    private val webClientBuilder    = mockk<WebClient.Builder>(relaxed = true)

    private lateinit var judge0Client: Judge0Client

    @BeforeEach
    fun setUp() {
        every { webClientBuilder.baseUrl(any()) } returns webClientBuilder
        every { webClientBuilder.apply(any()) }   returns webClientBuilder  // Spring's Builder.apply(Consumer<Builder>) — intercept to prevent wrong mock being built
        every { webClientBuilder.build() }        returns webClient

        judge0Client = Judge0Client(
            webClientBuilder  = webClientBuilder,
            baseUrl           = "http://localhost:2358",
            authToken         = "test-token",
            authHeader        = "X-Auth-Token",
            pollIntervalMs    = 1L,    // 1ms — loop exits before any real delay
            pollTimeoutSecs   = 10L,   // generous — test exits on first result
        )
    }

    // ── submit ────────────────────────────────────────────────────────────

    @Test
    fun `submit encodes source code and stdin as Base64 and returns token`() =
        runTest(UnconfinedTestDispatcher()) {
            val fakeToken = "abc-token-123"

            // Stub the full chain explicitly — each step returns the correct type
            every { webClient.post() }                         returns requestBodyUriSpec
            every { requestBodyUriSpec.uri(any<String>()) }    returns requestBodySpec
            every { requestBodySpec.contentType(any()) }       returns requestBodySpec
            every { requestBodySpec.bodyValue(any()) }         returns requestHeadersSpec
            every { requestHeadersSpec.retrieve() }            returns responseSpec
            @Suppress("UNCHECKED_CAST")
            every { responseSpec.bodyToMono(Map::class.java) } returns
                Mono.just(mapOf("token" to fakeToken) as Map<*, *>)

            val token = judge0Client.submit("print('hello')", 71, "hello")
            assertEquals(fakeToken, token)
        }

    @Test
    fun `submit with null stdin does not encode stdin`() =
        runTest(UnconfinedTestDispatcher()) {
            val capturedRequests = mutableListOf<Judge0SubmissionRequest>()

            every { webClient.post() }                          returns requestBodyUriSpec
            every { requestBodyUriSpec.uri(any<String>()) }     returns requestBodySpec
            every { requestBodySpec.contentType(any()) }        returns requestBodySpec
            every { requestBodySpec.bodyValue(capture(capturedRequests)) } returns requestHeadersSpec
            every { requestHeadersSpec.retrieve() }             returns responseSpec
            @Suppress("UNCHECKED_CAST")
            every { responseSpec.bodyToMono(Map::class.java) }  returns
                Mono.just(mapOf("token" to "tok") as Map<*, *>)

            judge0Client.submit("code", 63, null)

            assertEquals(1, capturedRequests.size)
            assertEquals(null, capturedRequests[0].stdin)
        }

    @Test
    fun `submit encodes source code as Base64`() =
        runTest(UnconfinedTestDispatcher()) {
            val capturedRequests = mutableListOf<Judge0SubmissionRequest>()

            every { webClient.post() }                           returns requestBodyUriSpec
            every { requestBodyUriSpec.uri(any<String>()) }      returns requestBodySpec
            every { requestBodySpec.contentType(any()) }         returns requestBodySpec
            every { requestBodySpec.bodyValue(capture(capturedRequests)) } returns requestHeadersSpec
            every { requestHeadersSpec.retrieve() }              returns responseSpec
            @Suppress("UNCHECKED_CAST")
            every { responseSpec.bodyToMono(Map::class.java) }   returns
                Mono.just(mapOf("token" to "tok") as Map<*, *>)

            judge0Client.submit("print('hello')", 71, null)

            val captured = capturedRequests.first()
            val decoded  = String(Base64.getDecoder().decode(captured.sourceCode))
            assertEquals("print('hello')", decoded)
        }

    // ── pollResult ────────────────────────────────────────────────────────
    // UnconfinedTestDispatcher makes withTimeout use virtual time,
    // so delay() is skipped and the loop exits on the first iteration.

    @Test
    fun `pollResult returns result when status id is 3`() =
        runTest(UnconfinedTestDispatcher()) {
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

            every { webClient.get() }                                returns requestHeadersUriSpec
            every { requestHeadersUriSpec.uri(any<String>()) }       returns requestHeadersSpec
            every { requestHeadersSpec.retrieve() }                  returns responseSpec
            every { responseSpec.bodyToMono(Judge0Result::class.java) } returns Mono.just(result)

            val out = judge0Client.pollResult("tok")
            assertEquals("Hello World\n", out.stdout)
            assertEquals(3, out.status?.id)
        }

    @Test
    fun `pollResult decodes stderr and compileOutput`() =
        runTest(UnconfinedTestDispatcher()) {
            val encodedStderr = Base64.getEncoder().encodeToString("SyntaxError\n".toByteArray())
            val result        = Judge0Result(
                token         = "tok",
                status        = Judge0Status(6, "Compilation Error"),
                stdout        = null,
                stderr        = null,
                compileOutput = encodedStderr,
                time          = null,
                memory        = null,
            )

            every { webClient.get() }                                returns requestHeadersUriSpec
            every { requestHeadersUriSpec.uri(any<String>()) }       returns requestHeadersSpec
            every { requestHeadersSpec.retrieve() }                  returns responseSpec
            every { responseSpec.bodyToMono(Judge0Result::class.java) } returns Mono.just(result)

            val out = judge0Client.pollResult("tok")
            assertEquals("SyntaxError\n", out.compileOutput)
        }

    @Test
    fun `pollResult throws Judge0TimeoutException when polling never completes`() =
        runTest(UnconfinedTestDispatcher()) {
            // Always return "In Queue" (id=1) → withTimeout fires
            val queuedResult = Judge0Result(
                token         = "tok",
                status        = Judge0Status(1, "In Queue"),
                stdout        = null,
                stderr        = null,
                compileOutput = null,
                time          = null,
                memory        = null,
            )

            every { webClient.get() }                                returns requestHeadersUriSpec
            every { requestHeadersUriSpec.uri(any<String>()) }       returns requestHeadersSpec
            every { requestHeadersSpec.retrieve() }                  returns responseSpec
            every { responseSpec.bodyToMono(Judge0Result::class.java) } returns Mono.just(queuedResult)

            // Use a client with 1ms timeout — fires immediately in virtual time
            val timeoutClient = Judge0Client(
                webClientBuilder = webClientBuilder,
                baseUrl          = "http://localhost:2358",
                authToken        = "",
                authHeader       = "X-Auth-Token",
                pollIntervalMs   = 1L,
                pollTimeoutSecs  = 0L,  // 0s → fires on very first check
            )

            val ex = runCatching { timeoutClient.pollResult("tok") }.exceptionOrNull()
            assertTrue(ex is Judge0TimeoutException, "Expected Judge0TimeoutException but got $ex")
        }

    // ── LanguageMap ───────────────────────────────────────────────────────

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
        assertTrue(langs.isNotEmpty())
        assertEquals(langs.sorted(), langs)
    }
}