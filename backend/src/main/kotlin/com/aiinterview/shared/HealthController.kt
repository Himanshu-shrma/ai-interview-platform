package com.aiinterview.shared

import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import java.time.Duration

@RestController
@RequestMapping("/health")
class HealthController(
    private val connectionFactory: ConnectionFactory,
    private val redisTemplate: ReactiveStringRedisTemplate,
) {

    @GetMapping
    suspend fun health(): ResponseEntity<Map<String, String>> {
        val dbStatus = checkDb()
        val redisStatus = checkRedis()
        val overall = if (dbStatus == "UP" && redisStatus == "UP") "UP" else "DOWN"
        return ResponseEntity.ok(
            mapOf(
                "status" to overall,
                "db" to dbStatus,
                "redis" to redisStatus,
            )
        )
    }

    private suspend fun checkDb(): String =
        try {
            Mono.from(connectionFactory.create())
                .flatMap { conn -> Mono.from(conn.close()).thenReturn("UP") }
                .awaitSingle()
        } catch (e: Exception) {
            "DOWN"
        }

    private suspend fun checkRedis(): String =
        try {
            redisTemplate.opsForValue()
                .set("health:ping", "1", Duration.ofSeconds(5))
                .awaitSingle()
            "UP"
        } catch (e: Exception) {
            "DOWN"
        }
}
