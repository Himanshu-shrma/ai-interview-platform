package com.aiinterview.interview.ws

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.HandlerMapping
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter

@Configuration
class WebSocketConfig(
    private val interviewWebSocketHandler: InterviewWebSocketHandler,
) {

    /**
     * Maps /ws/interview/{sessionId} to InterviewWebSocketHandler.
     * Auth is handled by WsAuthHandshakeInterceptor (a WebFilter at -100).
     * Priority -1 ensures it is checked before any lower-priority mappings.
     */
    @Bean
    fun webSocketHandlerMapping(): HandlerMapping =
        SimpleUrlHandlerMapping(
            mapOf("/ws/interview/**" to interviewWebSocketHandler),
            /* order = */ -1,
        )

    @Bean
    fun webSocketHandlerAdapter(): WebSocketHandlerAdapter = WebSocketHandlerAdapter()
}
