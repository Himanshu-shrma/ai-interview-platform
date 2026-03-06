package com.aiinterview.auth

import com.aiinterview.user.model.User
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.springframework.core.annotation.Order
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.time.Duration

// Rate limit: 60 req/min per authenticated user.
// Redis key: ratelimit:{userId}:{epochMinute} — TTL 60s.
// Skips /health and /actuator paths.
@Component
@Order(-150)
class RateLimitFilter(
    private val redisTemplate: ReactiveStringRedisTemplate,
) : WebFilter {

    companion object {
        private const val MAX_PER_MINUTE = 60
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.uri.path
        if (path == "/health" || path.startsWith("/actuator")) {
            return chain.filter(exchange)
        }

        return ReactiveSecurityContextHolder.getContext()
            .flatMap { ctx ->
                val principal = ctx.authentication?.principal
                if (principal is User && principal.id != null) {
                    mono { checkRateLimit(exchange, chain, principal.id.toString()) }.flatMap { it }
                } else {
                    chain.filter(exchange)
                }
            }
            .switchIfEmpty(chain.filter(exchange))
    }

    private suspend fun checkRateLimit(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
        userId: String,
    ): Mono<Void> {
        val minute = System.currentTimeMillis() / 60_000L
        val key = "ratelimit:$userId:$minute"
        val count = redisTemplate.opsForValue().increment(key).awaitSingle()
        if (count == 1L) {
            redisTemplate.expire(key, Duration.ofSeconds(60)).awaitSingleOrNull()
        }
        return if (count > MAX_PER_MINUTE) tooManyRequests(exchange) else chain.filter(exchange)
    }

    private fun tooManyRequests(exchange: ServerWebExchange): Mono<Void> {
        val response = exchange.response
        response.statusCode = HttpStatus.TOO_MANY_REQUESTS
        response.headers.contentType = MediaType.APPLICATION_JSON
        response.headers["Retry-After"] = "60"
        val body = """{"error":"Too Many Requests","message":"Rate limit exceeded"}"""
        val buffer = response.bufferFactory().wrap(body.toByteArray())
        return response.writeWith(Mono.just(buffer))
    }
}
