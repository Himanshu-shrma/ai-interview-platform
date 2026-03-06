package com.aiinterview.auth

import com.aiinterview.user.model.User
import com.aiinterview.user.service.UserBootstrapService
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.client.WebClient
import java.util.Date
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class ClerkJwtAuthFilterTest {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Test
    fun `valid JWT passes authentication - health returns 200`() {
        val token = buildJwt(TEST_RSA_KEY, subject = "user_filter_test", email = "filter@example.com")

        webTestClient.get()
            .uri("/health")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `expired JWT returns 401`() {
        val expired = Date(System.currentTimeMillis() - 10_000)
        val token = buildJwt(TEST_RSA_KEY, subject = "user_filter_test", email = "filter@example.com", expiresAt = expired)

        webTestClient.get()
            .uri("/health")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `missing Authorization header on protected path returns 401`() {
        webTestClient.get()
            .uri("/api/v1/users/me")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `malformed token returns 401`() {
        webTestClient.get()
            .uri("/health")
            .header(HttpHeaders.AUTHORIZATION, "Bearer not.a.real.token")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `health endpoint accessible without any token`() {
        webTestClient.get()
            .uri("/health")
            .exchange()
            .expectStatus().isOk
    }

    // ── Test configuration ─────────────────────────────────────────────────────

    @TestConfiguration
    class TestJwksCacheConfig {
        /**
         * Override JwksValidator so it validates tokens against our test RSA key
         * instead of fetching from the real Clerk JWKS URL.
         */
        @Bean
        @Primary
        fun testJwksValidator(
            @Value("\${clerk.jwks-url}") url: String,
            @Value("\${clerk.jwks-cache-ttl-minutes:60}") cacheTtlMinutes: Long,
            webClientBuilder: WebClient.Builder,
        ): JwksValidator {
            val fakeCache = object : JwksCache(url, cacheTtlMinutes, webClientBuilder) {
                override suspend fun getJwkSet(): JWKSet = JWKSet(TEST_RSA_KEY.toPublicJWK())
            }
            return JwksValidator(fakeCache)
        }

        /**
         * Stub UserBootstrapService — returns a fake user without touching DB or Redis.
         */
        @Bean
        @Primary
        fun testUserBootstrapService(): UserBootstrapService {
            val fakeUser = User(
                id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                orgId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
                clerkUserId = "user_filter_test",
                email = "filter@example.com",
                fullName = "Test User",
            )
            return mockk<UserBootstrapService>().also { mock ->
                coEvery { mock.getOrCreateUser(any(), any(), any()) } returns fakeUser
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    companion object {
        val TEST_RSA_KEY: RSAKey = RSAKeyGenerator(2048).keyID("test-key-filter").generate()

        fun buildJwt(
            key: RSAKey,
            subject: String,
            email: String,
            expiresAt: Date = Date(System.currentTimeMillis() + 3_600_000),
        ): String {
            val claims = JWTClaimsSet.Builder()
                .subject(subject)
                .claim("email", email)
                .claim("name", "Test User")
                .issueTime(Date())
                .expirationTime(expiresAt)
                .build()

            val header = JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build()
            val signed = SignedJWT(header, claims)
            signed.sign(RSASSASigner(key))
            return signed.serialize()
        }
    }
}
