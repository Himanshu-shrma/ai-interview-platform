---
name: database-migration
description: >
  USE THIS SKILL when adding DB columns, new tables, new queries, schema changes,
  Flyway migrations, R2DBC repository work, or Redis key patterns. Also use when
  troubleshooting migration failures or R2DBC JSONB issues.
  Files: backend/src/main/resources/db/migration/V*.sql,
  backend/src/main/kotlin/com/aiinterview/**/repository/*.kt,
  backend/src/main/kotlin/com/aiinterview/**/model/*.kt
---

# Database Migration

## Finding the Next Migration Version

```bash
ls backend/src/main/resources/db/migration/ | sort | tail -5
```

Current max: **V15**. Next available: **V16__.sql**

## SQL Rules — Non-Negotiable

### Always Use IF NOT EXISTS
```sql
ALTER TABLE questions ADD COLUMN IF NOT EXISTS embedding VECTOR(1536);
CREATE TABLE IF NOT EXISTS new_table (...);
CREATE INDEX IF NOT EXISTS idx_name ON table(column);
CREATE EXTENSION IF NOT EXISTS vector;
```

### Always UUID, Never SERIAL
```sql
-- CORRECT
id UUID PRIMARY KEY DEFAULT gen_random_uuid()

-- WRONG — breaks R2DBC auto-generation
id SERIAL PRIMARY KEY
```

### JSON as TEXT (R2DBC Limitation)
```sql
-- R2DBC cannot read JSONB. Use TEXT.
config TEXT,
test_results TEXT,
dimension_feedback TEXT
```

### Enums as VARCHAR
```sql
-- V8/V9 converted all enums. New columns follow the same pattern.
status VARCHAR(50) NOT NULL DEFAULT 'PENDING'
difficulty VARCHAR(50) NOT NULL DEFAULT 'MEDIUM'
```

## Migration Template

```sql
-- V16__add_candidate_memory_profiles.sql
CREATE TABLE IF NOT EXISTS candidate_memory_profiles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    persistent_weaknesses TEXT DEFAULT '[]',
    persistent_strengths TEXT DEFAULT '[]',
    previous_question_ids TEXT DEFAULT '[]',
    score_history TEXT DEFAULT '{}',
    session_count INT NOT NULL DEFAULT 0,
    last_updated TIMESTAMP DEFAULT NOW(),
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_memory_profiles_user_id
    ON candidate_memory_profiles(user_id);
```

## R2DBC Entity Pattern

```kotlin
@Table("interview_sessions")
data class InterviewSession(
    @Id val id: UUID? = null,           // null = new row (Spring generates)
    val userId: UUID,
    val status: String = "PENDING",      // VARCHAR enum
    val type: String,
    val config: String? = null,          // TEXT (JSON string)
    val startedAt: OffsetDateTime? = null,
    val endedAt: OffsetDateTime? = null,
    val durationSecs: Int? = null,
    val createdAt: OffsetDateTime? = null,
)
```

### JSON Read/Write in Service Layer
```kotlin
// Read JSON field
val config = objectMapper.readValue(session.config ?: "{}", InterviewConfig::class.java)

// Write JSON field
val configJson = objectMapper.writeValueAsString(config)
interviewSessionRepository.save(session.copy(config = configJson)).awaitSingle()
```

## Repository Patterns

```kotlin
@Repository
interface InterviewSessionRepository : R2dbcRepository<InterviewSession, UUID> {
    // Spring Data method names
    fun findByUserId(userId: UUID): Flux<InterviewSession>
    fun findByUserIdOrderByCreatedAtDesc(userId: UUID): Flux<InterviewSession>

    // Complex queries
    @Query("SELECT COUNT(*) FROM interview_sessions WHERE user_id = :userId")
    fun countByUserId(userId: UUID): Mono<Long>

    @Query("SELECT COUNT(*) FROM interview_sessions WHERE user_id = :userId AND status = :status")
    fun countByUserIdAndStatus(userId: UUID, status: String): Mono<Long>
}
```

## Redis Key Patterns

| Pattern | TTL | Purpose | Service |
|---------|-----|---------|---------|
| `brain:{sessionId}` | 3h | InterviewerBrain (primary) | BrainService |
| `interview:session:{id}:memory` | 2h | InterviewMemory (legacy) | RedisMemoryService |
| `user:clerk:{clerkId}` | 5min | User cache | UserBootstrapService |
| `ratelimit:{userId}:{minute}` | 2min | Rate limit counter | RateLimitFilter |
| `usage:{userId}:interviews:{YYYY-MM}` | 35d | Monthly usage | UsageLimitService |

## Current Schema Summary

### organizations
`id UUID PK, name, type, plan VARCHAR, seats_limit, created_at`

### users
`id UUID PK, org_id FK, clerk_user_id UNIQUE, email, full_name, role VARCHAR, subscription_tier VARCHAR, created_at`

### questions
`id UUID PK, title, description TEXT, type VARCHAR, difficulty VARCHAR, interview_category VARCHAR, test_cases TEXT, solution_hints TEXT, optimal_approach TEXT, evaluation_criteria TEXT, code_templates TEXT, time_complexity VARCHAR(100), space_complexity VARCHAR(100), topic_tags TEXT, created_at`

### interview_sessions
`id UUID PK, user_id FK, status VARCHAR, type, config TEXT, started_at, ended_at, duration_secs, last_heartbeat, integrity_signals TEXT, created_at`

### session_questions
`id UUID PK, session_id FK, question_id FK, order_index, final_code TEXT, language_used, submitted_at, created_at`

### conversation_messages
`id UUID PK, session_id FK, role VARCHAR, content TEXT, metadata TEXT, created_at`

### code_submissions
`id UUID PK, session_question_id FK, user_id FK, code TEXT, language, status VARCHAR, judge0_token, test_results TEXT, runtime_ms, submitted_at, created_at`

### evaluation_reports
`id UUID PK, session_id FK, user_id FK, overall_score NUMERIC(4,2), problem_solving_score, algorithm_score, code_quality_score, communication_score, efficiency_score, testing_score, initiative_score, learning_agility_score, anxiety_level, anxiety_adjustment_applied, strengths TEXT, weaknesses TEXT, suggestions TEXT, narrative_summary TEXT, dimension_feedback TEXT, next_steps TEXT, hints_used, completed_at, created_at`

### interview_templates
`id UUID PK, name, config TEXT, created_at`

### org_invitations
`id UUID PK, org_id FK, email, role VARCHAR, status VARCHAR, created_at`

## Flyway Configuration (application.yml)

```yaml
spring:
  flyway:
    url: jdbc:postgresql://localhost:5432/aiinterview  # JDBC, not R2DBC
    user: aiinterview
    password: changeme
    locations: classpath:db/migration
    baseline-on-migrate: true
```

Flyway uses JDBC (separate from the app's R2DBC connection). Migrations run automatically on Spring Boot startup.
