package com.aiinterview.auth

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Mono

@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    @Bean
    fun securityWebFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain =
        http
            .csrf { it.disable() }
            // Stateless: do not persist SecurityContext in the WebSession
            .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
            .authorizeExchange { exchanges ->
                exchanges
                    .pathMatchers("/health", "/actuator/**", "/ws/**").permitAll()
                    .pathMatchers("/api/**").authenticated()
                    .anyExchange().permitAll()
            }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .exceptionHandling { exceptions ->
                exceptions.authenticationEntryPoint { exchange, _ ->
                    val response = exchange.response
                    response.statusCode = HttpStatus.UNAUTHORIZED
                    response.headers.contentType = MediaType.APPLICATION_JSON
                    val body = """{"error":"Unauthorized","message":"Authentication required"}"""
                    val buffer = response.bufferFactory().wrap(body.toByteArray())
                    response.writeWith(Mono.just(buffer))
                }
                exceptions.accessDeniedHandler { exchange, _ ->
                    val response = exchange.response
                    response.statusCode = HttpStatus.FORBIDDEN
                    response.headers.contentType = MediaType.APPLICATION_JSON
                    val body = """{"error":"Forbidden","message":"Access denied"}"""
                    val buffer = response.bufferFactory().wrap(body.toByteArray())
                    response.writeWith(Mono.just(buffer))
                }
            }
            .build()

    /** Programmatic transaction management for reactive (R2DBC) code. */
    @Bean
    fun transactionalOperator(transactionManager: ReactiveTransactionManager): TransactionalOperator =
        TransactionalOperator.create(transactionManager)
}
