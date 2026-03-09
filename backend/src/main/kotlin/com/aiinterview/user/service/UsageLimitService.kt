package com.aiinterview.user.service

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.YearMonth
import java.util.UUID

/**
 * Tracks monthly interview usage per user in Redis.
 *
 * Key: usage:{userId}:interviews:{YYYY-MM}
 * TTL: 35 days (covers the full current month + buffer)
 *
 * FREE plan limit: configured via interview.free-tier-limit (default: 3).
 * PRO plan: unlimited.
 */
@Service
class UsageLimitService(
    private val redisTemplate: ReactiveStringRedisTemplate,
    @Value("\${interview.free-tier-limit:3}") private val freeTierLimit: Int,
) {
    companion object {
        private val KEY_TTL = Duration.ofDays(35)
    }

    fun usageKey(userId: UUID): String = "usage:$userId:interviews:${YearMonth.now()}"

    /**
     * Returns true if the user is allowed to start another interview.
     * Does NOT increment — usage is only incremented when a report is saved
     * via [incrementUsage], so abandoned sessions do not consume quota.
     * Returns false (no side effects) when the FREE tier limit is reached.
     */
    suspend fun checkUsageAllowed(userId: UUID, plan: String): Boolean {
        if (plan.uppercase() == "PRO") return true
        val current = redisTemplate.opsForValue().get(usageKey(userId)).awaitSingleOrNull()?.toIntOrNull() ?: 0
        return current < freeTierLimit
    }

    /**
     * Atomically increments the monthly usage counter after a completed interview.
     * Sets a 35-day TTL on first use so the key auto-expires after the month.
     */
    suspend fun incrementUsage(userId: UUID) {
        val key   = usageKey(userId)
        val count = redisTemplate.opsForValue().increment(key).awaitSingle()
        if (count == 1L) {
            redisTemplate.expire(key, KEY_TTL).awaitSingleOrNull()
        }
    }

    /** Returns the number of interviews used this month (0 if none). */
    suspend fun getUsageThisMonth(userId: UUID): Int =
        redisTemplate.opsForValue()
            .get(usageKey(userId))
            .awaitSingleOrNull()
            ?.toIntOrNull()
            ?: 0
}
