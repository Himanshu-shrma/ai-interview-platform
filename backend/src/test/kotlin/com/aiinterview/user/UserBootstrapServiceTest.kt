package com.aiinterview.user

import com.aiinterview.user.model.Organization
import com.aiinterview.user.model.User
import com.aiinterview.user.repository.OrganizationRepository
import com.aiinterview.user.repository.UserRepository
import com.aiinterview.user.service.UserBootstrapService
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import org.springframework.transaction.ReactiveTransaction
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.UUID

class UserBootstrapServiceTest {

    private val userRepository = mockk<UserRepository>()
    private val organizationRepository = mockk<OrganizationRepository>()
    private val redisTemplate = mockk<ReactiveStringRedisTemplate>()
    private val opsForValue = mockk<ReactiveValueOperations<String, String>>()
    private val objectMapper = jacksonObjectMapper().apply { registerModule(JavaTimeModule()) }

    /**
     * No-op TransactionalOperator: executes the publisher without any actual transaction.
     * Suitable for unit tests where DB operations are already mocked.
     */
    private val transactionalOperator: TransactionalOperator = TransactionalOperator.create(
        object : ReactiveTransactionManager {
            // Explicitly typed as ReactiveTransaction so Mono.just(tx) is Mono<ReactiveTransaction>
            private val tx: ReactiveTransaction = object : ReactiveTransaction {
                override fun isNewTransaction() = false
                override fun setRollbackOnly() {}
                override fun isRollbackOnly() = false
                override fun isCompleted() = false
            }
            override fun getReactiveTransaction(definition: TransactionDefinition?): Mono<ReactiveTransaction> =
                Mono.just(tx)
            override fun commit(transaction: ReactiveTransaction): Mono<Void> = Mono.empty()
            override fun rollback(transaction: ReactiveTransaction): Mono<Void> = Mono.empty()
        }
    )

    private val service = UserBootstrapService(
        userRepository,
        organizationRepository,
        redisTemplate,
        objectMapper,
        transactionalOperator,
    )

    private val orgId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val clerkUserId = "user_clerk_abc"
    private val email = "alice@example.com"

    private val org = Organization(id = orgId, name = "alice", type = "PERSONAL", plan = "FREE")
    private val user = User(id = userId, orgId = orgId, clerkUserId = clerkUserId, email = email, fullName = "Alice")

    @BeforeEach
    fun setup() {
        every { redisTemplate.opsForValue() } returns opsForValue
        every { opsForValue.set(any(), any<String>(), any<Duration>()) } returns Mono.just(true)
    }

    @Test
    fun `existing user returned from Redis cache without hitting DB`() = runTest {
        val json = objectMapper.writeValueAsString(user)
        every { opsForValue.get("user:clerk:$clerkUserId") } returns Mono.just(json)

        val result = service.getOrCreateUser(clerkUserId, email, "Alice")

        assertEquals(user.email, result.email)
        assertEquals(user.clerkUserId, result.clerkUserId)
        verify(exactly = 0) { userRepository.findByClerkUserId(any()) }
    }

    @Test
    fun `new user creates org and user row when not in cache or DB`() = runTest {
        every { opsForValue.get("user:clerk:$clerkUserId") } returns Mono.empty()
        every { userRepository.findByClerkUserId(clerkUserId) } returns Mono.empty()
        every { organizationRepository.save(any()) } returns Mono.just(org)
        every { userRepository.save(any()) } returns Mono.just(user)

        val result = service.getOrCreateUser(clerkUserId, email, "Alice")

        assertEquals(clerkUserId, result.clerkUserId)
        assertEquals(email, result.email)
        verify(exactly = 1) { organizationRepository.save(any()) }
        verify(exactly = 1) { userRepository.save(any()) }
    }

    @Test
    fun `existing DB user returned and cached when Redis cache is empty`() = runTest {
        every { opsForValue.get("user:clerk:$clerkUserId") } returns Mono.empty()
        every { userRepository.findByClerkUserId(clerkUserId) } returns Mono.just(user)

        val result = service.getOrCreateUser(clerkUserId, email, "Alice")

        assertEquals(user.id, result.id)
        verify(exactly = 0) { organizationRepository.save(any()) }
    }

    @Test
    fun `concurrent creation - DataIntegrityViolationException falls back to DB lookup`() = runTest {
        every { opsForValue.get("user:clerk:$clerkUserId") } returns Mono.empty()
        every { userRepository.findByClerkUserId(clerkUserId) } returnsMany listOf(
            Mono.empty(),     // first check: not yet in DB
            Mono.just(user),  // fallback after constraint violation
        )
        every { organizationRepository.save(any()) } returns Mono.just(org)
        every { userRepository.save(any()) } throws DataIntegrityViolationException("duplicate key")

        val result = service.getOrCreateUser(clerkUserId, email, "Alice")

        assertEquals(user.clerkUserId, result.clerkUserId)
    }
}
