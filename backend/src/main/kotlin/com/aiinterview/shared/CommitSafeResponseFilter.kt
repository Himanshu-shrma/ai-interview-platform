package com.aiinterview.shared

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.http.server.reactive.ServerHttpResponseDecorator
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * Workaround for Spring WebFlux 6.2.x bug where EncoderHttpMessageWriter
 * calls HttpHeaders.setContentLength on an already-committed response,
 * throwing UnsupportedOperationException on read-only headers.
 *
 * The response body IS delivered (200 OK); the exception is a post-commit
 * header mutation that fails. This filter wraps the response so that
 * header mutations on a committed response silently no-op instead of throwing.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CommitSafeResponseFilter : WebFilter {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        // Skip WebSocket upgrades — wrapping their response breaks frame handling
        val path = exchange.request.path.pathWithinApplication().value()
        if (path.startsWith("/ws/")) {
            return chain.filter(exchange)
        }
        return chain.filter(
            exchange.mutate()
                .response(CommitSafeResponse(exchange.response))
                .build()
        )
    }
}

private class CommitSafeResponse(
    delegate: ServerHttpResponse,
) : ServerHttpResponseDecorator(delegate) {

    override fun getHeaders(): HttpHeaders {
        val original = super.getHeaders()
        return if (delegate.isCommitted) {
            // Response already committed — return a writable copy so that
            // setContentLength() and similar calls don't throw.
            // Mutations go to the copy (discarded), which is fine because
            // the response is already on the wire.
            HttpHeaders(original)
        } else {
            original
        }
    }
}
