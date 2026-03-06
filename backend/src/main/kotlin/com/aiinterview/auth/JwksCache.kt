package com.aiinterview.auth

import com.nimbusds.jose.jwk.JWKSet
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import java.time.Instant

/**
 * Fetches and caches the Clerk JWKS with a 1-hour TTL.
 * Thread-safe via Mutex (double-checked pattern).
 */
@Component
class JwksCache(
    @Value("\${clerk.jwks-url}") private val jwksUrl: String,
    webClientBuilder: WebClient.Builder,
) {
    private val webClient: WebClient = webClientBuilder.build()
    private val cacheTtl: Duration = Duration.ofHours(1)
    private val mutex = Mutex()

    @Volatile private var cachedSet: JWKSet? = null
    @Volatile private var fetchedAt: Instant = Instant.EPOCH

    suspend fun getJwkSet(): JWKSet {
        val now = Instant.now()
        // Fast path: valid cached value
        cachedSet?.takeIf { now.isBefore(fetchedAt.plus(cacheTtl)) }?.let { return it }

        // Slow path: re-fetch under lock (prevents thundering herd)
        return mutex.withLock {
            val recheck = cachedSet
            val recheckTime = fetchedAt
            if (recheck != null && now.isBefore(recheckTime.plus(cacheTtl))) {
                recheck
            } else {
                fetchJwkSet().also {
                    cachedSet = it
                    fetchedAt = Instant.now()
                }
            }
        }
    }

    private suspend fun fetchJwkSet(): JWKSet {
        val json = webClient.get()
            .uri(jwksUrl)
            .retrieve()
            .bodyToMono(String::class.java)
            .awaitSingle()
        return JWKSet.parse(json)
    }
}
