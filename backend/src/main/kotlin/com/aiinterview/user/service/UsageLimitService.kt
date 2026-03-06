package com.aiinterview.user.service

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
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
 * FREE plan limit: 3 interviews/month.
 * PRO plan: unlimited.
 */
@Service
class UsageLimitService(
    private val redisTemplate: ReactiveStringRedisTemplate,
) {
    companion object {
        private const val FREE_LIMIT = 3
        private val KEY_TTL = Duration.ofDays(35)
    }

    fun usageKey(userId: UUID): String = "usage:$userId:interviews:${YearMonth.now()}"

    /**
     * Returns true if the user is allowed to start another interview,
     * then atomically increments the counter.
     * Returns false (without incrementing) if the FREE limit is reached.
     */
    suspend fun checkAndIncrementUsage(userId: UUID, plan: String): Boolean {
        if (plan.uppercase() == "PRO") return true

        val key = usageKey(userId)
        val current = redisTemplate.opsForValue().get(key).awaitSingleOrNull()?.toIntOrNull() ?: 0
        if (current >= FREE_LIMIT) return false

        redisTemplate.opsForValue().increment(key).awaitSingle()
        redisTemplate.expire(key, KEY_TTL).awaitSingleOrNull()
        return true
    }

    /** Returns the number of interviews used this month (0 if none). */
    suspend fun getUsageThisMonth(userId: UUID): Int =
        redisTemplate.opsForValue()
            .get(usageKey(userId))
            .awaitSingleOrNull()
            ?.toIntOrNull()
            ?: 0
}
