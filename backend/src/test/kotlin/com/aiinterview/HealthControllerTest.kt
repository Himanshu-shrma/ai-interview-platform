package com.aiinterview

import com.aiinterview.shared.HealthController
import io.mockk.every
import io.mockk.mockk
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * Pure unit test for HealthController.
 * No Spring context. ConnectionFactory and Redis template are mocked with MockK.
 */
class HealthControllerTest {

    private val connectionFactory = mockk<ConnectionFactory>()
    private val redisTemplate     = mockk<ReactiveStringRedisTemplate>()
    private val valueOps          = mockk<ReactiveValueOperations<String, String>>()
    private val controller        = HealthController(connectionFactory, redisTemplate)

    private val connection = mockk<Connection>()

    @BeforeEach
    fun setup() {
        every { redisTemplate.opsForValue() } returns valueOps
    }

    // ── both UP ───────────────────────────────────────────────────────────────

    @Test
    fun `health returns UP when both DB and Redis are available`() = runTest {
        every { connectionFactory.create() } returns Mono.just(connection)
        every { connection.close() } returns Mono.empty()
        every { valueOps.set(any(), any<String>(), any<Duration>()) } returns Mono.just(true)

        val body = controller.health().body!!

        assertEquals("UP", body["status"])
        assertEquals("UP", body["db"])
        assertEquals("UP", body["redis"])
    }

    // ── DB DOWN ───────────────────────────────────────────────────────────────

    @Test
    fun `health returns DOWN when DB connection is refused`() = runTest {
        every { connectionFactory.create() } returns Mono.error(RuntimeException("Connection refused"))
        every { valueOps.set(any(), any<String>(), any<Duration>()) } returns Mono.just(true)

        val body = controller.health().body!!

        assertEquals("DOWN", body["status"])
        assertEquals("DOWN", body["db"])
        assertEquals("UP", body["redis"])
    }

    // ── Redis DOWN ────────────────────────────────────────────────────────────

    @Test
    fun `health returns DOWN when Redis is unavailable`() = runTest {
        every { connectionFactory.create() } returns Mono.just(connection)
        every { connection.close() } returns Mono.empty()
        every { valueOps.set(any(), any<String>(), any<Duration>()) } returns Mono.error(RuntimeException("Redis timeout"))

        val body = controller.health().body!!

        assertEquals("DOWN", body["status"])
        assertEquals("UP", body["db"])
        assertEquals("DOWN", body["redis"])
    }

    // ── both DOWN ─────────────────────────────────────────────────────────────

    @Test
    fun `health returns DOWN when both DB and Redis are unavailable`() = runTest {
        every { connectionFactory.create() } returns Mono.error(RuntimeException("DB unavailable"))
        every { valueOps.set(any(), any<String>(), any<Duration>()) } returns Mono.error(RuntimeException("Redis unavailable"))

        val body = controller.health().body!!

        assertEquals("DOWN", body["status"])
        assertEquals("DOWN", body["db"])
        assertEquals("DOWN", body["redis"])
    }

    // ── response shape ────────────────────────────────────────────────────────

    @Test
    fun `health response always contains status db and redis keys`() = runTest {
        every { connectionFactory.create() } returns Mono.just(connection)
        every { connection.close() } returns Mono.empty()
        every { valueOps.set(any(), any<String>(), any<Duration>()) } returns Mono.just(true)

        val body = controller.health().body!!

        assertTrue(body.containsKey("status"))
        assertTrue(body.containsKey("db"))
        assertTrue(body.containsKey("redis"))
        assertEquals(3, body.size)
    }
}
