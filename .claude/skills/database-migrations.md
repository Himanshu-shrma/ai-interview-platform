# Skill: PostgreSQL + R2DBC + Flyway

## When This Applies
Any work adding DB columns, new tables, new queries, or schema changes.

## Migration Rules

### Naming
`V{N}__{snake_case_description}.sql`
Current max: V15. Next available: **V16**
Check: `ls backend/src/main/resources/db/migration/`

### Always Safe
```sql
ALTER TABLE questions ADD COLUMN IF NOT EXISTS embedding VECTOR(1536);
CREATE TABLE IF NOT EXISTS new_table (...);
CREATE INDEX IF NOT EXISTS idx_name ON table(column);
CREATE EXTENSION IF NOT EXISTS vector;
```

### NEVER
- Drop columns without explicit discussion
- Use SERIAL — always UUID with `gen_random_uuid()`
- Alter column types without IF EXISTS check

## R2DBC Specific

### JSONB → TEXT
R2DBC doesn't support JSONB. Store as TEXT, parse in service layer:
```kotlin
data class InterviewSession(@Id val id: UUID, val config: String?)
// Service:
val config = objectMapper.readValue(session.config ?: "{}", InterviewConfig::class.java)
```

### Enum → VARCHAR
All enums stored as VARCHAR(50) (V8/V9 migrations converted these).

### Repository Queries
```kotlin
// Simple: Spring Data method names
fun findByUserId(userId: UUID): Flow<InterviewSession>

// Complex: @Query annotation
@Query("SELECT * FROM questions WHERE interview_category = :category AND difficulty = :difficulty ORDER BY RANDOM() LIMIT :limit")
fun findByCategoryAndDifficulty(category: String, difficulty: String, limit: Int): Flow<Question>
```

## Redis Key Patterns
| Pattern | TTL | Purpose |
|---------|-----|---------|
| `brain:{sessionId}` | 3h | InterviewerBrain (primary state) |
| `interview:session:{id}:memory` | 2h | InterviewMemory (legacy — migrate away) |
| `user:clerk:{clerkId}` | 5min | User cache |
| `ratelimit:{userId}:{minute}` | 2min | Rate limit counter |
| `usage:{userId}:interviews:{YYYY-MM}` | 35d | Monthly usage |

## Current Schema (10 tables)
- **organizations**: id, name, type, plan, seats_limit
- **users**: id, org_id, clerk_user_id, email, role, subscription_tier
- **questions**: id, title, description, type, difficulty, interview_category, test_cases(TEXT), solution_hints, optimal_approach, evaluation_criteria, code_templates, time_complexity, space_complexity(VARCHAR 100)
- **interview_sessions**: id, user_id, status(VARCHAR), type, config(TEXT), started_at, ended_at, duration_secs, integrity_signals
- **session_questions**: id, session_id, question_id, order_index, final_code, language_used, submitted_at
- **conversation_messages**: id, session_id, role, content, metadata
- **code_submissions**: id, session_question_id, user_id, code, language, status, judge0_token, test_results(TEXT), runtime_ms, submitted_at
- **evaluation_reports**: id, session_id, user_id, overall_score, problem_solving_score, algorithm_score, code_quality_score, communication_score, efficiency_score, testing_score, initiative_score, learning_agility_score, anxiety_level, anxiety_adjustment_applied, strengths, weaknesses, suggestions, narrative_summary, dimension_feedback(TEXT), next_steps(TEXT), hints_used
- **interview_templates**: id, name, config
- **org_invitations**: id, org_id, email, role, status
