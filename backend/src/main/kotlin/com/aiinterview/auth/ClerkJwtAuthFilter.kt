package com.aiinterview.auth

import com.aiinterview.user.service.UserBootstrapService
import com.nimbusds.jose.JOSEException
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
    private val jwksValidator: JwksValidator,
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
            val claims = jwksValidator.validate(token)
            val user = userBootstrapService.getOrCreateUser(claims.userId, claims.email, claims.fullName)
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
