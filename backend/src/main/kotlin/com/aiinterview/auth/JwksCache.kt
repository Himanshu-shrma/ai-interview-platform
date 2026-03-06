package com.aiinterview.auth

import com.nimbusds.jose.jwk.JWKSet
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import java.time.Instant

/**
 * Fetches and caches the Clerk JWKS.
 * TTL configured via clerk.jwks-cache-ttl-minutes (default: 60).
 * Thread-safe via Mutex (double-checked pattern).
 * Logs on cache miss so JWKS fetches are visible in app logs.
 */
@Component
class JwksCache(
    @Value("\${clerk.jwks-url}") private val jwksUrl: String,
    @Value("\${clerk.jwks-cache-ttl-minutes:60}") private val cacheTtlMinutes: Long,
    webClientBuilder: WebClient.Builder,
) {
    private val log = LoggerFactory.getLogger(JwksCache::class.java)
    private val webClient: WebClient = webClientBuilder.build()
    private val cacheTtl: Duration = Duration.ofMinutes(cacheTtlMinutes)
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
                log.info("JWKS cache miss — fetching from {}", jwksUrl)
                fetchJwkSet().also {
                    cachedSet = it
                    fetchedAt = Instant.now()
                    log.debug("JWKS cache refreshed, next refresh in {} minutes", cacheTtlMinutes)
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
