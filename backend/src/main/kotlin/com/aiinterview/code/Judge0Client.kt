package com.aiinterview.code

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.util.Base64

// ── Request / Response DTOs ───────────────────────────────────────────────────

data class Judge0SubmissionRequest(
    @JsonProperty("source_code") val sourceCode: String,
    @JsonProperty("language_id") val languageId: Int,
    @JsonProperty("stdin")       val stdin: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Judge0Status(
    val id: Int,
    val description: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Judge0Result(
    val token: String?,
    val status: Judge0Status?,
    val stdout: String?,
    val stderr: String?,
    @JsonProperty("compile_output") val compileOutput: String?,
    val time: String?,
    val memory: Int?,
)

class Judge0TimeoutException(token: String) :
    RuntimeException("Judge0 polling timed out for token: $token")

// ── Client ────────────────────────────────────────────────────────────────────

/**
 * Thin reactive wrapper around the Judge0 REST API.
 *
 * - [submit] encodes source + stdin as Base64, posts to `/submissions`.
 * - [pollResult] polls until a terminal status (id >= 3) or [pollTimeoutMs] elapses.
 * - stdout / stderr / compile_output are Base64-decoded before returning.
 */
@Component
class Judge0Client(
    webClientBuilder: WebClient.Builder,
    @Value("\${judge0.base-url:http://localhost:2358}")        baseUrl: String,
    @Value("\${judge0.auth-token:}")              private val authToken: String,
    @Value("\${judge0.auth-header:X-Auth-Token}") private val authHeader: String,
    @Value("\${judge0.poll-interval-ms:500}")    private val pollIntervalMs: Long,
    @Value("\${judge0.poll-timeout-seconds:30}") private val pollTimeoutSecs: Long,
) {
    private val log       = LoggerFactory.getLogger(Judge0Client::class.java)
    private val webClient = webClientBuilder
        .baseUrl(baseUrl)
        .apply {
            if (authToken.isNotBlank()) {
                it.defaultHeader(authHeader, authToken)
            }
        }
        .build()

    /**
     * Submits code to Judge0 asynchronously and returns the submission token.
     */
    suspend fun submit(
        code: String,
        languageId: Int,
        stdin: String? = null,
    ): String {
        val request = Judge0SubmissionRequest(
            sourceCode = encode(code),
            languageId = languageId,
            stdin      = stdin?.let { encode(it) },
        )
        val response = webClient.post()
            .uri("/submissions?base64_encoded=true&wait=false")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .bodyToMono(Map::class.java)
            .awaitSingle()

        @Suppress("UNCHECKED_CAST")
        return (response as Map<String, Any?>)["token"] as? String
            ?: error("Judge0 response missing token field")
    }

    /**
     * Polls Judge0 until the submission reaches a terminal state (id >= 3)
     * or [pollTimeoutSecs] seconds have elapsed.
     *
     * Judge0 status codes:
     *   1=In Queue, 2=Processing → keep polling
     *   3=Accepted, 4=Wrong Answer, 5=TLE, 6=Compilation Error, 7-14=Runtime/Exec errors
     */
    suspend fun pollResult(token: String): Judge0Result {
        return try {
            withTimeout(pollTimeoutSecs * 1_000L) {
                while (true) {
                    val result = fetchResult(token)
                    val statusId = result.status?.id ?: 0
                    log.debug("Judge0 poll token={} status={}", token, result.status?.description)
                    if (statusId >= 3) return@withTimeout decodeFields(result)
                    delay(pollIntervalMs)
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
        } catch (e: TimeoutCancellationException) {
            throw Judge0TimeoutException(token)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun fetchResult(token: String): Judge0Result =
        webClient.get()
            .uri("/submissions/$token?base64_encoded=true&fields=*")
            .retrieve()
            .bodyToMono(Judge0Result::class.java)
            .awaitSingle()

    private fun decodeFields(result: Judge0Result): Judge0Result = result.copy(
        stdout        = result.stdout?.let { decode(it) },
        stderr        = result.stderr?.let { decode(it) },
        compileOutput = result.compileOutput?.let { decode(it) },
    )

    private fun encode(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun decode(encoded: String): String =
        String(Base64.getDecoder().decode(encoded.trim()), Charsets.UTF_8)
}
