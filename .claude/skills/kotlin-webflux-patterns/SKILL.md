---
name: kotlin-webflux-patterns
description: >
  USE THIS SKILL for ANY backend Kotlin work: controllers, services, repositories,
  Redis operations, coroutine code, WebSocket handlers, error handling, or new endpoints.
  Covers R2DBC, WebFlux, coroutine safety, and all Spring patterns in this codebase.
  Files: backend/src/main/kotlin/com/aiinterview/**/*.kt
---

# Kotlin + Spring WebFlux Patterns

## Event Loop Safety — The #1 Rule

Spring WebFlux runs on Netty's event loop. Blocking it silently degrades all concurrent requests.

```kotlin
// CORRECT — non-blocking bridge from Reactor to Coroutines
suspend fun getSession(id: UUID): InterviewSession? =
    sessionRepository.findById(id).awaitSingleOrNull()

// CORRECT — explicit IO dispatcher for blocking operations
val result = withContext(Dispatchers.IO) {
    conversationMessageRepository.save(message).awaitSingle()
}

// WRONG — blocks the Netty event loop thread
fun getSession(id: UUID): InterviewSession? =
    sessionRepository.findById(id).block()

// WRONG ��� blocks event loop
Thread.sleep(2000)

// CORRECT alternative
delay(2000)  // suspends, doesn't block
```

## Coroutine Scope Pattern

Each interview session gets its own scope for fire-and-forget work:

```kotlin
// In ConversationEngine.kt
private val sessionScopes = ConcurrentHashMap<UUID, CoroutineScope>()

private fun getSessionScope(sessionId: UUID): CoroutineScope =
    sessionScopes.getOrPut(sessionId) { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

// Fire-and-forget (TheAnalyst, TheStrategist)
scope.launch {
    try {
        theAnalyst.analyze(sessionId, content, aiResponse, freshBrain)
    } catch (e: CancellationException) { throw e }  // MUST re-throw
    catch (e: Exception) { log.warn("TheAnalyst failed: {}", e.message) }
}
```

**Rules**:
- `SupervisorJob()` — child failure doesn't cancel siblings
- `Dispatchers.IO` — appropriate for DB/Redis/HTTP work
- Always re-throw `CancellationException` — coroutine contract
- Cancel scope on session end: `sessionScopes.remove(sessionId)?.cancel()`

## Redis Mutex Pattern (BrainService)

```kotlin
private val sessionMutexes = ConcurrentHashMap<UUID, Mutex>()
private fun getMutex(id: UUID): Mutex = sessionMutexes.getOrPut(id) { Mutex() }

suspend fun updateBrain(sessionId: UUID, updater: (InterviewerBrain) -> InterviewerBrain) {
    getMutex(sessionId).withLock {
        val current = getBrain(sessionId)
        val updated = updater(current)
        saveBrain(sessionId, updated)
    }
}
```

## R2DBC Patterns

### JSONB stored as TEXT
R2DBC doesn't support JSONB. Store as TEXT, parse in service layer:
```kotlin
// Entity
data class InterviewSession(@Id val id: UUID, val config: String?)

// Service — read
val config = objectMapper.readValue(session.config ?: "{}", InterviewConfig::class.java)

// Service — write
val configJson = objectMapper.writeValueAsString(config)
```

### Enums as VARCHAR
All enums are VARCHAR(50) in DB. V8/V9 migrations converted these.
```sql
status VARCHAR(50) NOT NULL DEFAULT 'PENDING'
```

### Repository Queries
```kotlin
// Simple — Spring Data method name
fun findByUserId(userId: UUID): Flow<InterviewSession>
fun findBySessionIdOrderByOrderIndex(sessionId: UUID): Flux<SessionQuestion>

// Complex — @Query annotation
@Query("SELECT * FROM questions WHERE interview_category = :category AND difficulty = :difficulty ORDER BY RANDOM() LIMIT :limit")
fun findByCategoryAndDifficulty(category: String, difficulty: String, limit: Int): Flow<Question>

// NEVER use Mono/Flux return types in new code — use Flow or suspend
// Exception: existing repositories that return Flux for collectList()
```

## LLM Calls — Always Through Registry

```kotlin
// CORRECT — retry + fallback handled by registry
val response = llmProviderRegistry.complete(
    LlmRequest.build(systemPrompt, userMessage, modelConfig.backgroundModel, maxTokens = 600)
)

// CORRECT — streaming
llm.stream(request).collect { token ->
    registry.sendMessage(sessionId, OutboundMessage.AiChunk(delta = token, done = false))
}

// WRONG — bypasses retry and fallback
val response = openAiProvider.complete(request)
```

## WebSocket Message Send

```kotlin
// Always through WsSessionRegistry — handles serialization + ByteBuf lifecycle
registry.sendMessage(sessionId, OutboundMessage.AiChunk(delta = token, done = false))
registry.sendMessage(sessionId, OutboundMessage.StateChange(state = "CODING_CHALLENGE"))
registry.sendMessage(sessionId, OutboundMessage.Error("SESSION_ERROR", "Session not found"))
```

## Error Handling

```kotlin
// WRONG — silent failure hides production bugs
catch (_: Exception) {}

// CORRECT — log with context
catch (e: Exception) {
    log.warn("Failed to update brain for session={}: {}", sessionId, e.message)
}

// For non-critical operations (dual-state sync, etc.)
try { ... } catch (e: Exception) { log.debug("Non-critical: {}", e.message) }
```

## Controller Pattern

```kotlin
@RestController
@RequestMapping("/api/v1/interviews")
class InterviewController(private val sessionService: InterviewSessionService) {
    @PostMapping("/sessions")
    suspend fun startSession(
        @RequestBody request: StartSessionRequest,
        authentication: Authentication
    ): ResponseEntity<SessionDto> {
        val user = authentication.principal as User  // Set by ClerkJwtAuthFilter
        val session = sessionService.startSession(user, request.config)
        return ResponseEntity.ok(session.toDto())
    }
}
```

## Flyway Migrations

- Location: `backend/src/main/resources/db/migration/`
- Naming: `V{N}__{snake_case_description}.sql`
- Current max: V15. Next: V16
- Uses JDBC (not R2DBC) �� separate connection config in application.yml
- Always: `IF NOT EXISTS`, `UUID` with `gen_random_uuid()`, never SERIAL

## Package Organization
```
com.aiinterview/
  auth/          — ClerkJwtAuthFilter, SecurityConfig, RateLimitFilter
  code/          — Judge0Client, CodeExecutionService, LanguageMap
  conversation/  — ConversationEngine, HintGenerator, InterviewState
    brain/       — TheConductor, TheAnalyst, TheStrategist, BrainService, etc.
    knowledge/   — KnowledgeAdjacencyMap
  interview/
    controller/  — InterviewController, QuestionController
    dto/         — SessionDto, QuestionDto, ApiError
    model/       — InterviewSession, Question, ConversationMessage, SessionQuestion
    repository/  — All R2DBC repositories
    service/     — InterviewSessionService, QuestionService, RedisMemoryService
    ws/          — InterviewWebSocketHandler, WsSessionRegistry, WsMessageTypes
  report/
    controller/  — ReportController
    service/     — ReportService, EvaluationAgent
    model/       — EvaluationReport
  shared/
    ai/          — LlmProviderRegistry, LlmRequest, ModelConfig, providers/
    domain/      — Enums
  user/          — UserBootstrapService, UsageLimitService
```
