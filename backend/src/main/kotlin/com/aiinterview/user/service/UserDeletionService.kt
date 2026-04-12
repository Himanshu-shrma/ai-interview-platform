package com.aiinterview.user.service

import com.aiinterview.conversation.brain.BrainService
import com.aiinterview.interview.repository.InterviewSessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Handles GDPR data deletion for a user account.
 *
 * Soft-delete flow (deleteUser):
 *  1. Load all session IDs → clean up Redis brains
 *  2. Soft-delete messages, reports, submissions, sessions via bulk UPDATE
 *
 * Hard-delete purge (purgeExpiredSoftDeletes):
 *  Runs every Sunday at 2am UTC — hard-deletes records soft-deleted > 365 days ago.
 */
@Service
class UserDeletionService(
    private val sessionRepository: InterviewSessionRepository,
    private val brainService: BrainService,
    private val databaseClient: DatabaseClient,
) {
    private val log = LoggerFactory.getLogger(UserDeletionService::class.java)
    private val purgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun deleteUser(userId: UUID) {
        // 1. Get active session IDs to clean up Redis brains
        val sessionIds = sessionRepository.findByUserId(userId)
            .collectList().awaitSingle()
            .mapNotNull { it.id }

        // 2. Clean up Redis brains (best-effort — TTL handles the rest)
        sessionIds.forEach { sid ->
            try { brainService.deleteBrain(sid) }
            catch (e: Exception) { log.debug("Brain cleanup skipped for session={}: {}", sid, e.message) }
        }

        // 3. Soft-delete messages (cascade through sessions subquery)
        databaseClient.sql("""
            UPDATE conversation_messages SET deleted_at = NOW()
            WHERE session_id IN (SELECT id FROM interview_sessions WHERE user_id = :userId)
              AND deleted_at IS NULL
        """.trimIndent())
            .bind("userId", userId)
            .fetch().rowsUpdated().awaitSingle()

        // 4. Soft-delete evaluation reports
        databaseClient.sql("""
            UPDATE evaluation_reports SET deleted_at = NOW()
            WHERE user_id = :userId AND deleted_at IS NULL
        """.trimIndent())
            .bind("userId", userId)
            .fetch().rowsUpdated().awaitSingle()

        // 5. Soft-delete code submissions
        databaseClient.sql("""
            UPDATE code_submissions SET deleted_at = NOW()
            WHERE user_id = :userId AND deleted_at IS NULL
        """.trimIndent())
            .bind("userId", userId)
            .fetch().rowsUpdated().awaitSingle()

        // 6. Soft-delete sessions last (so the subquery in step 3 still works)
        databaseClient.sql("""
            UPDATE interview_sessions SET deleted_at = NOW()
            WHERE user_id = :userId AND deleted_at IS NULL
        """.trimIndent())
            .bind("userId", userId)
            .fetch().rowsUpdated().awaitSingle()

        log.info("""{"event":"USER_DELETED","user_id":"$userId","sessions_deleted":${sessionIds.size}}""")
    }

    /** Scheduled hard-delete of soft-deleted records older than 365 days. */
    @Scheduled(cron = "0 0 2 * * SUN")
    fun purgeExpiredSoftDeletes() {
        purgeScope.launch {
            try {
                val cutoff = OffsetDateTime.now().minusDays(365)
                purgeTable("conversation_messages", cutoff)
                purgeTable("evaluation_reports", cutoff)
                purgeTable("code_submissions", cutoff)
                purgeTable("interview_sessions", cutoff)
                log.info("Purged soft-deleted records older than 365 days (cutoff={})", cutoff)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                log.warn("Scheduled purge failed: {}", e.message)
            }
        }
    }

    private suspend fun purgeTable(table: String, cutoff: OffsetDateTime) {
        databaseClient.sql("DELETE FROM $table WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff")
            .bind("cutoff", cutoff)
            .fetch().rowsUpdated().awaitSingleOrNull()
    }
}
