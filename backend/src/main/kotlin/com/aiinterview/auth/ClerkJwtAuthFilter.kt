package com.aiinterview.auth

import com.aiinterview.user.service.UserBootstrapService
import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import kotlinx.coroutines.reactor.mono
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.text.ParseException
import java.util.Date

/**
 * Runs before Spring Security (-100) to validate Clerk JWTs and populate the
 * ReactiveSecurityContextHolder.
 *
 * - No token  → pass through; Spring Security handles 401 for protected paths.
 * - Valid JWT  → bootstrap user, set SecurityContext, continue chain.
 * - Invalid JWT → return 401 JSON immediately.
 */
@Component
@Order(-200)
class ClerkJwtAuthFilter(
    private val jwksCache: JwksCache,
    private val userBootstrapService: UserBootstrapService,
) : WebFilter {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val authHeader = exchange.request.headers.getFirst(HttpHeaders.AUTHORIZATION)

        // No token — pass through; Spring Security enforces auth on protected paths
        if (authHeader.isNullOrBlank() || !authHeader.startsWith("Bearer ")) {
            return chain.filter(exchange)
        }

        val token = authHeader.removePrefix("Bearer ").trim()

        return mono {
            val jwkSet = jwksCache.getJwkSet()
            val claims = validateJwt(token, jwkSet)

            val clerkUserId = claims.subject ?: throw JOSEException("Missing sub claim")
            val email = claims.getStringClaim("email") ?: ""
            val fullName = claims.getStringClaim("name")

            val user = userBootstrapService.getOrCreateUser(clerkUserId, email, fullName)
            UsernamePasswordAuthenticationToken(
                user, null, listOf(SimpleGrantedAuthority("ROLE_USER"))
            )
        }
            .flatMap { auth ->
                val ctx = SecurityContextImpl(auth)
                chain.filter(exchange)
                    .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(ctx)))
            }
            .onErrorResume { e ->
                when (e) {
                    is JOSEException, is ParseException -> unauthorized(exchange, e.message ?: "Invalid token")
                    else -> Mono.error(e)  // non-auth errors (DB, Redis, etc.) propagate as 500
                }
            }
    }

    private fun validateJwt(token: String, jwkSet: JWKSet): JWTClaimsSet {
        val signed = SignedJWT.parse(token)
        val keyId = signed.header.keyID

        val jwk = if (keyId != null) jwkSet.getKeyByKeyId(keyId) else jwkSet.keys.firstOrNull()
            ?: throw JOSEException("No matching key found in JWKS")

        val verifier = when (jwk) {
            is RSAKey -> RSASSAVerifier(jwk.toRSAPublicKey())
            is ECKey -> ECDSAVerifier(jwk.toECPublicKey())
            else -> throw JOSEException("Unsupported JWK type: ${jwk.keyType}")
        }

        if (!signed.verify(verifier)) throw JOSEException("JWT signature invalid")

        val claims = signed.jwtClaimsSet
        val expiry = claims.expirationTime
        if (expiry == null || expiry.before(Date())) throw JOSEException("JWT is expired")

        return claims
    }

    private fun unauthorized(exchange: ServerWebExchange, message: String): Mono<Void> {
        val response = exchange.response
        response.statusCode = HttpStatus.UNAUTHORIZED
        response.headers.contentType = MediaType.APPLICATION_JSON
        val sanitized = message.replace("\"", "'")
        val body = """{"error":"Unauthorized","message":"$sanitized"}"""
        val buffer = response.bufferFactory().wrap(body.toByteArray())
        return response.writeWith(Mono.just(buffer))
    }
}
