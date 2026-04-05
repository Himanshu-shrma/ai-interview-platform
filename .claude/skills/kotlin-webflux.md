# Skill: Kotlin + Spring WebFlux Patterns

## When This Applies
Any backend work: new endpoints, services, repositories, WebSocket handlers, Redis operations.

## Coroutines — Non-Negotiable Rules

### Event Loop Safety
- NEVER call `.block()` on a reactive type in a suspend function
- NEVER use `Thread.sleep()` — use `delay()` instead
- ALWAYS use `awaitSingle()` / `awaitSingleOrNull()` to bridge Reactor -> Coroutines

```kotlin
// CORRECT
suspend fun getSession(id: UUID): InterviewSession? =
    sessionRepository.findById(id).awaitSingleOrNull()

// WRONG — blocks Netty event loop
fun getSession(id: UUID): InterviewSession? =
    sessionRepository.findById(id).block()
```

### Coroutine Scope
Each interview session has its own scope:
```kotlin
scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
```
- `launch { }` for fire-and-forget (TheAnalyst, TheStrategist)
- `async { } + awaitAll()` for parallel calls (evaluation, test cases)

### Mutex for Redis Read-Modify-Write
```kotlin
// BrainService pattern — always use updateBrain(), never raw read+write
private val mutexes = ConcurrentHashMap<UUID, Mutex>()
suspend fun updateBrain(id: UUID, transform: (InterviewerBrain) -> InterviewerBrain) {
    getMutex(id).withLock {
        val brain = getBrain(id)
        saveBrain(id, transform(brain))
    }
}
```

## R2DBC Patterns

### JSONB stored as TEXT
R2DBC doesn't support JSONB natively. Store as TEXT, parse with ObjectMapper:
```kotlin
// Entity field
val config: String?  // JSON string — parse in service layer
// Reading
val config = objectMapper.readValue(session.config ?: "{}", InterviewConfig::class.java)
```

### Enums stored as VARCHAR
All enums stored as VARCHAR for R2DBC compat (see V8/V9 migrations).

### Repository Pattern
```kotlin
@Repository
interface InterviewSessionRepository : R2dbcRepository<InterviewSession, UUID> {
    @Query("SELECT * FROM interview_sessions WHERE user_id = :userId ORDER BY created_at DESC LIMIT :limit")
    fun findByUserId(userId: UUID, limit: Int): Flow<InterviewSession>
}
// NEVER use Mono/Flux return types — use Flow or suspend
```

## Spring WebFlux Controllers
```kotlin
@RestController
@RequestMapping("/api/v1/interviews")
class InterviewController(private val sessionService: InterviewSessionService) {
    @PostMapping("/sessions")
    suspend fun startSession(
        @RequestBody request: StartSessionRequest,
        authentication: Authentication
    ): ResponseEntity<SessionDto> {
        val user = authentication.principal as User
        val session = sessionService.startSession(user, request.config)
        return ResponseEntity.ok(session.toDto())
    }
}
```

## Flyway Migrations
- File naming: `V{N}__{snake_case_description}.sql`
- Current max: V15. Next available: V16
- Always use `IF NOT EXISTS` for new columns/tables
- Always use `UUID` with `gen_random_uuid()` — never SERIAL
- Check: `ls backend/src/main/resources/db/migration/`

## Adding a New Service
1. Add `@Service` annotation
2. Inject via constructor
3. Use suspend functions for async operations
4. Add to Spring component scan (already scans `com.aiinterview`)
