package com.aiinterview.shared.filter

import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * Workaround for Spring WebFlux 6.2.x bug where [EncoderHttpMessageWriter]
 * calls [HttpHeaders.setContentLength] on an already-committed response,
 * throwing [UnsupportedOperationException] on read-only headers.
 *
 * The response body IS delivered (200 OK); the exception is a post-commit
 * header-write attempt that fails. This filter catches such errors via
 * [onErrorResume] and logs them at DEBUG instead of ERROR.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class CommittedResponseFilter : WebFilter {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ): Mono<Void> {
        // Skip WebSocket upgrades — they have their own error handling
        val path = exchange.request.path.pathWithinApplication().value()
        if (path.startsWith("/ws/")) {
            return chain.filter(exchange)
        }
        return chain.filter(exchange)
            .onErrorResume { throwable ->
                val message = throwable.message ?: ""

                if (isCommittedResponseError(throwable, message)) {
                    // Response already sent to client — this is fine.
                    // Log at DEBUG level only (not ERROR — it's expected).
                    logger.debug(
                        "Response already committed for {} {} — ignoring header write attempt",
                        exchange.request.method,
                        exchange.request.path,
                    )
                    Mono.empty()
                } else {
                    Mono.error(throwable)
                }
            }
    }

    private fun isCommittedResponseError(throwable: Throwable, message: String): Boolean {
        // The specific bug: UnsupportedOperationException from ReadOnlyHttpHeaders.set()
        if (throwable is UnsupportedOperationException) return true

        return message.contains("Response already committed") ||
            (message.contains("Content-Length") && message.contains("committed")) ||
            (message.contains("read-only") && message.contains("headers")) ||
            message.contains("HttpHeaders is read-only") ||
            message.contains("Cannot set response header") ||
            message.contains("EncoderHttpMessageWriter")
    }
}
