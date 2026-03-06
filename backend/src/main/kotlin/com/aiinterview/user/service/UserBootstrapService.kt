package com.aiinterview.user.service

import com.aiinterview.user.model.Organization
import com.aiinterview.user.model.User
import com.aiinterview.user.repository.OrganizationRepository
import com.aiinterview.user.repository.UserRepository
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * Bootstraps users on first login:
 *   1. Redis cache (5-min TTL at key user:clerk:{clerkUserId})
 *   2. DB lookup
 *   3. Create User + PERSONAL Organization in a single transaction
 *
 * Race conditions on concurrent first-login are handled by catching
 * DataIntegrityViolationException and falling back to a fresh DB read.
 */
@Service
class UserBootstrapService(
    private val userRepository: UserRepository,
    private val organizationRepository: OrganizationRepository,
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val transactionalOperator: TransactionalOperator,
) {
    companion object {
        private val CACHE_TTL = Duration.ofMinutes(5)
        fun cacheKey(clerkUserId: String) = "user:clerk:$clerkUserId"
    }

    suspend fun getOrCreateUser(clerkUserId: String, email: String, fullName: String?): User {
        val key = cacheKey(clerkUserId)

        // 1. Redis cache (fast path)
        redisTemplate.opsForValue().get(key).awaitSingleOrNull()?.let { cached ->
            return objectMapper.readValue(cached, User::class.java)
        }

        // 2. DB lookup
        userRepository.findByClerkUserId(clerkUserId).awaitSingleOrNull()?.let { existing ->
            cacheUser(key, existing)
            return existing
        }

        // 3. Create (in transaction) — handle race condition
        val user = try {
            createUserWithOrg(clerkUserId, email, fullName)
        } catch (e: DataIntegrityViolationException) {
            // Another concurrent request created the user first — just fetch it
            userRepository.findByClerkUserId(clerkUserId).awaitSingle()
        }

        cacheUser(key, user)
        return user
    }

    private suspend fun createUserWithOrg(
        clerkUserId: String,
        email: String,
        fullName: String?,
    ): User =
        mono {
            val org = organizationRepository.save(
                Organization(
                    name = fullName ?: email.substringBefore("@"),
                    type = "PERSONAL",
                    plan = "FREE",
                    seatsLimit = 1,
                )
            ).awaitSingle()

            userRepository.save(
                User(
                    orgId = requireNotNull(org.id) { "Organization save returned null id" },
                    clerkUserId = clerkUserId,
                    email = email,
                    fullName = fullName,
                )
            ).awaitSingle()
        }
            .`as` { publisher -> transactionalOperator.transactional(publisher) }
            .awaitSingle()

    private suspend fun cacheUser(key: String, user: User) {
        runCatching {
            val json = objectMapper.writeValueAsString(user)
            redisTemplate.opsForValue().set(key, json, CACHE_TTL).awaitSingleOrNull()
        }
        // Cache failure is non-fatal — the DB is the source of truth
    }

    /** Evict cache on user update (called by other services if needed). */
    suspend fun evictCache(clerkUserId: String) {
        redisTemplate.delete(cacheKey(clerkUserId)).awaitSingleOrNull()
    }
}
