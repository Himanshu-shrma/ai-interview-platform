---
name: code-execution-judge0
description: >
  USE THIS SKILL when working on code submission flow, test case handling,
  Judge0 integration, language support, result processing, CodeExecutionService,
  Judge0Client, LanguageMap, or outputMatches.
  Files: code/service/CodeExecutionService.kt, code/Judge0Client.kt,
  code/LanguageMap.kt, code/controller/CodeController.kt
---

# Code Execution — Judge0 Integration

## Judge0 Language IDs (LanguageMap.kt)

| Language | Judge0 ID | Language | Judge0 ID |
|----------|-----------|----------|-----------|
| Python 3 | 71 | JavaScript (Node.js) | 63 |
| Java | 62 | TypeScript | 74 |
| C++ | 54 | C | 50 |
| Go | 60 | Rust | 73 |
| Ruby | 72 | Kotlin | 78 |
| Swift | 83 | Scala | 81 |

## Two Execution Paths

### CODE_RUN (Ad-Hoc Run)
```
WS CODE_RUN → CodeExecutionService.runCode() → Judge0Client.submit(code, langId, stdin)
  → poll every 500ms → OutboundMessage.CodeResult(status, stdout, stderr, runtimeMs)
```
- No test cases, no persistence
- Optional stdin input
- Updates `redisMemoryService.currentCode`

### CODE_SUBMIT (Test Case Run)
```
WS CODE_SUBMIT → CodeExecutionService.submitCode() → load question test cases
  → coroutineScope { testCases.map { async { runSingleTestCase() } } }  // CONCURRENT
  → outputMatches() per test → OutboundMessage.CodeResult(status, testResults)
  → persist CodeSubmission → queue brain action → transition on full pass
```
- Requires `sessionQuestionId` (from session.questions[].sessionQuestionId)
- Runs ALL test cases concurrently via `async { }`
- Persists to `code_submissions` table + updates `session_questions.final_code`
- 30-second polling timeout per test case

## Test Case Format

Stored in `questions.test_cases` as TEXT (JSON array):
```json
[
    {"input": "1 2 3\n", "expectedOutput": "6"},
    {"input": "0\n", "expected_output": "0"},
    {"input": "-1 5\n", "output": "-1 5"}
]
```

Parser checks three field names: `expectedOutput`, `expected_output`, `output`.

## outputMatches() — Flexible Comparison

```kotlin
fun outputMatches(actual: String?, expected: String): Boolean {
    if (actual?.trim() == expected.trim()) return true
    // Normalize: remove brackets/braces/parens, split on comma/whitespace,
    // trim quotes, filter blanks, sort, compare
    val normalize = { s: String ->
        s.removePrefix("[").removeSuffix("]")
            .removePrefix("{").removeSuffix("}")
            .removePrefix("(").removeSuffix(")")
            .split(Regex("[,\\s]+"))
            .map { it.trim().trim('"').trim('\'') }
            .filter { it.isNotBlank() }
            .sorted()
    }
    return normalize(actual!!) == normalize(expected)
}
```

**Why**: Python `set` prints `{1, 2, 3}` but expected might be `[1, 2, 3]`.
**Known issue**: Sorting means `[3, 1, 2]` matches `[1, 2, 3]` — false positive when order matters.

## Brain Actions After Results

**CRITICAL**: After code execution, queue brain actions so the AI responds appropriately:

```kotlin
// Tests FAILED → probe without revealing which tests
if (!allPassed && total > 0) {
    brainService.addAction(sessionId, IntendedAction(
        id = "test_fail_${brain.turnCount}",
        type = ActionType.PROBE_DEPTH,
        description = "Tests FAILING: $passed/$total passed. " +
            "Do NOT reveal which tests or the issue. " +
            "Ask: 'I see some tests aren't passing — what do you think might be causing that?'",
        priority = 1,
        expiresAfterTurn = brain.turnCount + 3,
        source = ActionSource.ANALYST,
    ))
}

// ALL PASSED → ask about complexity
if (allPassed && total > 0) {
    brainService.addAction(sessionId, IntendedAction(
        id = "tests_pass_${brain.turnCount}",
        type = ActionType.ADVANCE_GOAL,
        description = "All $total tests passing. " +
            "Ask: 'All tests pass. What's the time and space complexity?'",
        priority = 2,
        expiresAfterTurn = brain.turnCount + 3,
        source = ActionSource.ANALYST,
    ))
}

// Full pass → transition to FOLLOW_UP
if (allPassed) {
    conversationEngine.transition(sessionId, InterviewState.FollowUp)
}
```

## NZEC Error

"Non-Zero Exit Code" — code threw an exception at runtime.
Common causes: Scanner reading wrong format, array out of bounds, NullPointerException.
This is a **correct failure** — do not hide it. The candidate's code crashed.

## Judge0 Configuration

```yaml
# application.yml
judge0:
  base-url: ${JUDGE0_BASE_URL:http://localhost:2358}
  auth-token: ${JUDGE0_AUTH_TOKEN:judge0_dev_token}
  auth-header: ${JUDGE0_AUTH_HEADER:X-Auth-Token}
  poll-interval-ms: 500
  poll-timeout-seconds: 30
```

## Docker Setup (docker-compose.yml)

Judge0 requires:
- Its own PostgreSQL (`judge0-db`)
- Its own Redis (`judge0-redis` with password)
- `judge0-server` on port 2358
- `judge0-worker` for Resque job processing
- Both server + worker need `privileged: true` for seccomp sandbox

## Memory Sync

Both `runCode()` and `submitCode()` update `redisMemoryService` with latest code + language. This ensures EvaluationAgent has the final code when generating the report.

The `InterviewWebSocketHandler` also syncs code to brain on `CODE_UPDATE`:
```kotlin
brainService.updateBrain(sessionId) { b -> b.copy(currentCode = msg.code, programmingLanguage = msg.language) }
```

## HintGenerator — Known Bug

**File**: `conversation/HintGenerator.kt`

Currently reads `InterviewMemory` (old system), NOT `InterviewerBrain`. The hint generator:
- 3 hints max per question
- Level 1 = abstract, Level 2 = names DS, Level 3 = describes approach
- Deductions: L1=-0.5, L2=-1.0, L3=-1.5 from problemSolving score
- Uses `redisMemoryService` (old) not `brainService` (new)

**Do not depend on brain state in hints until HintGenerator is migrated.**
