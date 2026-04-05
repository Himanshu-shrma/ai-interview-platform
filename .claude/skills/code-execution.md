# Skill: Judge0 Code Execution

## When This Applies
Any work on CodeExecutionService, Judge0Client, code submission flow, test case handling, language support.

## Judge0 Language IDs (LanguageMap.kt)
| Language | ID | Language | ID |
|----------|-----|----------|-----|
| Python 3 | 71 | JavaScript | 63 |
| Java | 62 | TypeScript | 74 |
| C++ | 54 | C | 50 |
| Go | 60 | Rust | 73 |
| Ruby | 72 | Kotlin | 78 |
| Swift | 83 | Scala | 81 |

## Code Submission Flow
```
Frontend CODE_SUBMIT ->
  InterviewWebSocketHandler ->
    CodeExecutionService.submitCode() ->
      Judge0Client.submit(code, languageId, stdin) ->
        poll every 500ms (30s timeout) ->
          outputMatches() comparison ->
            OutboundMessage.CodeResult (over WS) ->
              Queue brain action based on results
```

## Two Execution Paths
1. **CODE_RUN** (`runCode()`): Single execution with optional stdin. No test cases. No persistence.
2. **CODE_SUBMIT** (`submitCode()`): Runs against ALL test cases concurrently (`coroutineScope { testCases.map { async { ... } } }`). Persists CodeSubmission to DB. Updates session_questions.final_code.

## Test Result Brain Actions
```kotlin
// After CODE_RESULT:
if (!allPassed) {
    brainService.addAction(sessionId, IntendedAction(
        type = ActionType.PROBE_DEPTH,
        description = "Tests FAILING: $passed/$total. Ask: 'I see some tests aren't passing — what do you think might be causing that?'",
        priority = 1, expiresAfterTurn = brain.turnCount + 3, source = ActionSource.ANALYST
    ))
} else if (allPassed) {
    brainService.addAction(sessionId, IntendedAction(
        type = ActionType.ADVANCE_GOAL,
        description = "All tests passing. Ask about time/space complexity.",
        priority = 2, ...
    ))
}
// On all pass: transition to InterviewState.FollowUp
```

## outputMatches() — Flexible Comparison
```kotlin
fun outputMatches(actual: String?, expected: String): Boolean {
    if (actual?.trim() == expected.trim()) return true
    // Normalize: remove brackets/braces/parens, split, sort, compare
    // Handles Python set {1,2,3} vs list [1,2,3]
}
```

## Test Case Parsing
Test cases stored as TEXT (JSON array) in questions table:
```json
[{"input": "1 2 3", "expectedOutput": "6"}, ...]
```
Parser checks for `expectedOutput`, `expected_output`, and `output` field names.

## Judge0 Config (application.yml)
```yaml
judge0:
  base-url: http://localhost:2358
  poll-interval-ms: 500
  poll-timeout-seconds: 30
```

## Memory Sync
Both `runCode()` and `submitCode()` update `redisMemoryService` with latest code and language for EvaluationAgent access.
