# Quick Reference — Copy-Paste Templates

## New Suspend Service Method
```kotlin
suspend fun doWork(sessionId: UUID): Result {
    val session = withContext(Dispatchers.IO) {
        interviewSessionRepository.findById(sessionId).awaitSingleOrNull()
    } ?: throw NoSuchElementException("Session $sessionId not found")
    // ... work
}
```

## New Repository Method
```kotlin
@Query("SELECT * FROM table WHERE column = :value ORDER BY created_at DESC")
fun findByColumn(value: String): Flow<Entity>

// For count queries
@Query("SELECT COUNT(*) FROM table WHERE user_id = :userId")
fun countByUserId(userId: UUID): Mono<Long>
```

## Fire-and-Forget Pattern
```kotlin
scope.launch {
    try {
        // work
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { log.warn("Failed: {}", e.message) }
}
```

## Redis Read-Modify-Write
```kotlin
brainService.updateBrain(sessionId) { brain ->
    brain.copy(fieldToUpdate = newValue)
}
```

## LLM Call (Non-Streaming)
```kotlin
val response = llm.complete(LlmRequest.build(
    systemPrompt = "...",
    userMessage = "...",
    model = modelConfig.backgroundModel,
    maxTokens = 600,
    responseFormat = ResponseFormat.JSON,
))
val content = response.content
```

## LLM Call (Streaming)
```kotlin
val request = LlmRequest(
    messages = listOf(LlmMessage(LlmRole.SYSTEM, systemPrompt), LlmMessage(LlmRole.USER, userMessage)),
    model = modelConfig.interviewerModel, maxTokens = 200,
)
llm.stream(request).collect { token ->
    fullResponse.append(token)
    registry.sendMessage(sessionId, OutboundMessage.AiChunk(delta = token, done = false))
}
registry.sendMessage(sessionId, OutboundMessage.AiChunk(delta = "", done = true))
```

## New Migration
```sql
-- V16__add_new_feature.sql
ALTER TABLE table_name ADD COLUMN IF NOT EXISTS new_col VARCHAR(255);
CREATE TABLE IF NOT EXISTS new_table (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_name ON table(column);
```

## WS Message Send
```kotlin
registry.sendMessage(sessionId, OutboundMessage.AiChunk(delta = token, done = false))
registry.sendMessage(sessionId, OutboundMessage.StateChange(state = "CODING_CHALLENGE"))
registry.sendMessage(sessionId, OutboundMessage.Error("CODE", "message"))
registry.sendMessage(sessionId, OutboundMessage.SessionEnd(reportId = reportId))
```

## JSON Parse with Safety
```kotlin
val cleaned = response.content.trim()
    .removePrefix("```json").removePrefix("```")
    .removeSuffix("```").trim()
val result = try {
    objectMapper.readValue(cleaned, TargetDto::class.java)
} catch (e: Exception) {
    log.warn("Parse failed: {}", e.message)
    fallbackResult
}
```
