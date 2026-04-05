# Skill: Testing Patterns

## When This Applies
Writing any test file: unit tests, integration tests, coroutine tests.

## Test Stack
- JUnit 5 (jupiter)
- MockK (Kotlin mocking — NOT Mockito)
- kotlinx-coroutines-test (runTest)
- Spring Boot Test
- Testcontainers (for Redis integration tests)

## Existing Test Files
```
backend/src/test/kotlin/com/aiinterview/
  HealthControllerTest.kt
  auth/ClerkJwtAuthFilterTest.kt
  code/CodeExecutionServiceTest.kt
  code/Judge0ClientTest.kt
  conversation/brain/BrainObjectivesRegistryTest.kt
  interview/ConversationEngineTest.kt
  interview/HintGeneratorTest.kt
  interview/InterviewSessionServiceTest.kt
  interview/InterviewWebSocketHandlerTest.kt
  interview/QuestionGeneratorServiceTest.kt
  interview/QuestionServiceTest.kt
  interview/RedisMemoryServiceTest.kt
  report/EvaluationAgentTest.kt
  report/ReportServiceTest.kt
  shared/ai/LlmProviderRegistryTest.kt
  user/UserBootstrapServiceTest.kt
```

## MockK Patterns
```kotlin
import io.mockk.*

class BrainServiceTest {
    @MockK lateinit var redisTemplate: ReactiveStringRedisTemplate
    @MockK lateinit var objectMapper: ObjectMapper

    @BeforeEach fun setup() { MockKAnnotations.init(this) }

    @Test
    fun `initBrain stores correct default state`() = runTest {
        coEvery { redisTemplate.opsForValue().set(any(), any(), any()) } returns Mono.just(true)
        brainService.initBrain(sessionId, mockBrain())
        coVerify { redisTemplate.opsForValue().set("brain:$sessionId", any(), Duration.ofHours(3)) }
    }
}
```

## Coroutine Test Pattern
```kotlin
import kotlinx.coroutines.test.runTest

@Test
fun `markGoalComplete adds to completed list`() = runTest {
    val result = brainService.markGoalComplete(sessionId, "problem_shared")
    assertTrue(result.interviewGoals.completed.contains("problem_shared"))
}
```

## Running Tests
```bash
cd backend && mvn test                    # All tests
cd backend && mvn test -Dtest=ClassName   # Specific test class
cd backend && mvn test -Dtest=ClassName#methodName  # Specific method
```

## Critical Tests to Prioritize
1. **BrainObjectivesRegistryTest** — goal counts, phase inference
2. **BrainService** — initBrain, updateBrain mutex, markGoalComplete
3. **TheAnalyst** — parseAnalystResponse, tryPartialParse, NewClaimDto string/object handling
4. **NaturalPromptBuilder** — section injection, phase rules, prompt injection protection
5. **ReportService** — score formula correctness (weighted sum = 1.0)
6. **CodeExecutionService** — outputMatches() normalization
