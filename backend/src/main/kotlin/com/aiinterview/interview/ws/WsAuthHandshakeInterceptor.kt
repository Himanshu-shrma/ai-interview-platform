package com.aiinterview.interview.ws

import com.aiinterview.auth.JwksValidator
import com.aiinterview.interview.repository.InterviewSessionRepository
import com.aiinterview.user.repository.UserRepository
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.util.UUID

// Keys stored in WebSocket session attributes (set in exchange, carried into WebSocketSession)
const val ATTR_USER_ID    = "userId"
const val ATTR_SESSION_ID = "sessionId"

private const val WS_PATH_PREFIX = "/ws/"

/**
 * WebFilter that authenticates WebSocket upgrade requests for /ws/interview/{sessionId}.
 * Runs at -100 (after ClerkJwtAuthFilter at -200 but before Spring Security at -100 → set slightly later).
 *
 * Reads ?token= query param, validates JWT, checks session ownership, then stores
 * userId and sessionId as exchange attributes so they are available in WebSocketSession.attributes.
 */
@Component
@Order(-100)
class WsAuthHandshakeInterceptor(
    private val jwksValidator: JwksValidator,
    private val userRepository: UserRepository,
    private val sessionRepository: InterviewSessionRepository,
) : WebFilter {

    private val log = LoggerFactory.getLogger(WsAuthHandshakeInterceptor::class.java)

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.path.pathWithinApplication().value()
        if (!path.startsWith(WS_PATH_PREFIX)) {
            return chain.filter(exchange)
        }

        return mono { authenticate(exchange) }
            .flatMap { allowed ->
                if (allowed) chain.filter(exchange)
                else {
                    exchange.response.statusCode = HttpStatus.UNAUTHORIZED
                    exchange.response.setComplete()
                }
            }
    }

    private suspend fun authenticate(exchange: ServerWebExchange): Boolean {
        val request = exchange.request

        // 1. Token from ?token= query param (HTTP headers unavailable on WS upgrade)
        val token = request.queryParams.getFirst("token")
        if (token.isNullOrBlank()) {
            log.debug("WS upgrade rejected — missing ?token= param")
            return false
        }

        // 2. Validate JWT
        val claims = try {
            jwksValidator.validate(token)
        } catch (e: Exception) {
            log.debug("WS upgrade rejected — invalid JWT: {}", e.message)
            return false
        }

        // 3. Resolve internal userId from clerkUserId
        val user = userRepository.findByClerkUserId(claims.userId).awaitSingleOrNull()
        if (user == null) {
            log.debug("WS upgrade rejected — unknown clerkUserId: {}", claims.userId)
            return false
        }
        val internalUserId = requireNotNull(user.id) { "User has null id" }

        // 4. Extract sessionId from path: /ws/interview/{sessionId}
        val pathSegments = request.path.pathWithinApplication().value()
            .split("/")
            .filter { it.isNotBlank() }
        val sessionIdStr = pathSegments.lastOrNull()
        val sessionId = try {
            UUID.fromString(sessionIdStr)
        } catch (e: IllegalArgumentException) {
            log.debug("WS upgrade rejected — invalid sessionId in path: {}", sessionIdStr)
            return false
        }

        // 5. Verify session ownership
        val session = sessionRepository.findByIdAndUserId(sessionId, internalUserId).awaitSingleOrNull()
        if (session == null) {
            log.debug("WS upgrade rejected — session {} not owned by user {}", sessionId, internalUserId)
            return false
        }

        // 6. Stash resolved values; WebSocketSession.attributes inherits from exchange.attributes
        exchange.attributes[ATTR_USER_ID]    = internalUserId
        exchange.attributes[ATTR_SESSION_ID] = sessionId
        log.debug("WS upgrade allowed: sessionId={}, userId={}", sessionId, internalUserId)
        return true
    }
}
