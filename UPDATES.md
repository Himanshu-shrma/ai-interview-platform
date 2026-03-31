# Interview Platform — Update Task List

**Generated from:** Architecture Review + Staff Engineer Review + Live Testing
**Branch:** feature/natural-interviewer
**Date:** 2026-03-22
**How to use:** Tell Claude Code "Execute TASK-XXX from UPDATES.md"

---

## Status Legend

| Status | Meaning |
|--------|---------|
| FIXED | Already resolved in recent commits |
| OPEN | Needs to be done |
| PARTIAL | Partially addressed, needs completion |

---

## Already Fixed (no action needed)

| ID | Issue | Fixed In |
|----|-------|----------|
| C1 | Score formula broken (0.5/10) | `bb3ec51` — 8-dim formula with logging |
| C4 | initiative+learningAgility not in formula | `bb3ec51` — added to weighted sum + persisted to DB |
| C5 | TheAnalyst JSON parse failures | `abab289` — tryPartialParse + failure rate tracking |
| C6 | Phase stuck at 0 | `abab289` — partial parse salvages goals+thoughts |
| C7 | No alerting on TheAnalyst failures | `de86c7b` — failure rate logged + zero-goals warning |
| C8 | Evaluation criteria leaked | `62fac18` — INTERNAL NOTES section + HARD_RULE |
| C9 | Wrong question type served | `3256ee8` — interview_category filter enforced |
| H1 | Dual state drift (code) | `de86c7b` — CODE_UPDATE syncs to brain |
| H5 | Code injection in prompt | `de86c7b` — `<candidate_code>` XML tags |
| M3 | Code editor shown for behavioral | `04441bb` — isBehavioral hides editor |
| M4 | Hint button for all types | `62fac18` — showHints prop gated by category |
| M5 | Markdown not rendered | `04441bb` — renderMarkdown in ConversationPanel |
| M8 | Test case format mismatch | `de86c7b` — outputMatches() with normalization |

---

## OPEN TASKS — Execute in this order

---

### TASK-C2: Verify Radar Chart Data Binding
**Priority:** CRITICAL
**Status:** OPEN (may be fixed by C1 score formula fix — needs verification)
**Files to read:**
- `frontend/src/pages/ReportPage.tsx` (lines 64-90)
- `frontend/src/types/index.ts` (ScoresDto interface)
- `backend/src/main/kotlin/com/aiinterview/report/dto/ReportDto.kt` (ScoresDto)

**Problem:**
Radar chart showed empty hexagon. Root cause was likely the score formula returning 0.5 (all dimensions near-zero from defaultResult). With C1 fixed, real scores should flow through.

**Verification needed:**
1. Run an interview end-to-end
2. Check backend log: `Score formula session=X: ps=Y algo=Z ...`
3. All dimension values should be 3.0-10.0 (not 0.0)
4. Radar chart should show filled hexagon

**If still broken after C1:**
The issue is field name mismatch. Backend `ScoresDto` has `algorithmChoice` but frontend type also has `algorithmChoice` — these match. Verify `report.scores.problemSolving` is not `undefined` in browser console:
```javascript
// Add to ReportContent component temporarily:
console.log('Report scores:', report.scores)
console.log('Radar data:', buildRadarData(report.scores))
```

**Commit message:** `fix(TASK-C2): verify radar chart data after score formula fix`

---

### TASK-C3: Verify Score Label Thresholds
**Priority:** CRITICAL (but likely fixed by C1)
**Status:** OPEN (verification needed)
**Files to read:**
- `frontend/src/pages/ReportPage.tsx` (lines 57-62)

**Problem:**
Score label showed "Good" for 0.5/10. The thresholds are correct (>=9 Excellent, >=7 Good, >=5 Average, <5 Needs Work). The bug was the score VALUE being 0.5 (from broken formula), not the thresholds.

**Verification:** After C1 fix, a score of 7.2 should show "Good". A score of 4.1 should show "Needs Work". No code change needed if C1 is confirmed working.

**Commit message:** `chore(TASK-C3): verified score labels correct after formula fix`

---

### TASK-H2: Replace Remaining Silent Catches
**Priority:** HIGH
**Status:** PARTIAL (most fixed in `de86c7b`, 2 remain)
**Files to read:**
- `backend/src/main/kotlin/com/aiinterview/conversation/ConversationEngine.kt`

**Problem:**
2 remaining `catch (_: Exception)` in ConversationEngine. These swallow errors silently — if transcript persistence fails, the evaluation gets partial data.

**Fix:**
```bash
grep -n "catch (_: Exception)" backend/src/main/kotlin/com/aiinterview/conversation/ConversationEngine.kt
```
Replace each with:
```kotlin
catch (e: Exception) { log.warn("Non-critical: {}", e.message) }
```

**Verification:**
```bash
cd backend && mvn compile -q
grep -c "catch (_: Exception)" backend/src/main/kotlin/com/aiinterview/conversation/ConversationEngine.kt
# Should be 0
```

**Commit message:** `fix(TASK-H2): replace remaining silent catches with logging`

---

### TASK-H3: Verify Redis Persistence
**Priority:** HIGH
**Status:** OPEN (verify docker config)
**Files to read:**
- `docker-compose.yml`

**Problem:**
Redis needs AOF persistence to survive restarts. Docker-compose already has `--appendonly yes` (confirmed in audit). Verify the volume is correctly mounted.

**Fix:** Check docker-compose.yml has a volume for Redis data:
```yaml
redis:
  volumes:
    - redis_data:/data
```
If volume is missing, add it. If present, this task is FIXED.

**Commit message:** `fix(TASK-H3): verify Redis AOF persistence volume`

---

### TASK-H4: Add LLM Call Retry with Backoff
**Priority:** HIGH
**Status:** OPEN
**Files to read:**
- `backend/src/main/kotlin/com/aiinterview/shared/ai/LlmProviderRegistry.kt`
- `backend/src/main/kotlin/com/aiinterview/shared/ai/LlmProviderException.kt`

**Problem:**
`LlmProviderRegistry.complete()` and `stream()` have no retry logic. A single rate limit or timeout kills the interview turn. `RateLimitException` already has `retryAfterSeconds` but it's never used.

**Fix:**
Add retry wrapper in LlmProviderRegistry:
```kotlin
private suspend fun <T> withRetry(
    maxRetries: Int = 2,
    block: suspend () -> T
): T {
    var lastException: Exception? = null
    repeat(maxRetries + 1) { attempt ->
        try {
            return block()
        } catch (e: LlmProviderException.RateLimitException) {
            lastException = e
            val delayMs = (e.retryAfterSeconds ?: (attempt + 1).toLong()) * 1000
            log.warn("Rate limited, retrying in {}ms (attempt {}/{})", delayMs, attempt + 1, maxRetries + 1)
            kotlinx.coroutines.delay(delayMs)
        } catch (e: LlmProviderException.TimeoutException) {
            lastException = e
            log.warn("Timeout, retrying (attempt {}/{})", attempt + 1, maxRetries + 1)
            kotlinx.coroutines.delay(1000L * (attempt + 1))
        }
    }
    throw lastException ?: RuntimeException("Retry exhausted")
}
```
Wrap `complete()` call: `return withRetry { primary.complete(request) }`

**Verification:**
```bash
cd backend && mvn compile -q
```

**Commit message:** `feat(TASK-H4): add retry with backoff for LLM calls`

---

### TASK-H6: Document JWT in WS Query Param Risk
**Priority:** HIGH (but fix requires frontend + backend change)
**Status:** OPEN (document only — fix is multi-sprint)

**Problem:**
JWT token passed as WS query parameter `?token=eyJ...` is visible in server access logs. Industry standard is a short-lived ticket endpoint.

**Fix for now:** Add log filter to redact tokens in access logs. Full fix (ticket endpoint) is a separate feature.

**Commit message:** `docs(TASK-H6): document JWT WS security risk + log redaction`

---

### TASK-H7: Add Error Tracking Setup Guide
**Priority:** HIGH
**Status:** OPEN (infrastructure, not code)

**Problem:**
No Sentry or equivalent. Production errors invisible.

**Fix:** Add Sentry Spring Boot dependency + configuration placeholder:
```xml
<!-- pom.xml -->
<dependency>
    <groupId>io.sentry</groupId>
    <artifactId>sentry-spring-boot-starter-jakarta</artifactId>
    <version>7.0.0</version>
</dependency>
```
Add to application.yml:
```yaml
sentry:
  dsn: ${SENTRY_DSN:}
  traces-sample-rate: 0.1
```

**Commit message:** `feat(TASK-H7): add Sentry dependency and config placeholder`

---

### TASK-H8: Add LLM Cost Tracking Per Session
**Priority:** HIGH
**Status:** OPEN
**Files to read:**
- `backend/src/main/kotlin/com/aiinterview/shared/ai/LlmResponse.kt` (LlmUsage)
- `backend/src/main/kotlin/com/aiinterview/conversation/brain/TheAnalyst.kt`
- `backend/src/main/kotlin/com/aiinterview/conversation/brain/TheConductor.kt`

**Problem:**
No per-session cost tracking. Cannot budget or optimize.

**Fix:**
Add to InterviewerBrain:
```kotlin
val llmCallCount: Int = 0,
val estimatedTokensUsed: Int = 0,
```
After each LLM call in TheConductor and TheAnalyst, increment:
```kotlin
brainService.updateBrain(sessionId) { b ->
    b.copy(
        llmCallCount = b.llmCallCount + 1,
        estimatedTokensUsed = b.estimatedTokensUsed + promptLength / 4
    )
}
```
Log at interview end: `log.info("Session {} cost: {} calls, ~{} tokens", sessionId, brain.llmCallCount, brain.estimatedTokensUsed)`

**Commit message:** `feat(TASK-H8): track LLM call count and token estimate per session`

---

### TASK-H9: TheAnalyst Failure Rate Monitoring (verify)
**Priority:** HIGH
**Status:** FIXED in `abab289` — verify only
**Files to read:**
- `backend/src/main/kotlin/com/aiinterview/conversation/brain/TheAnalyst.kt` (lines 217-235)

**Verification:**
```bash
grep -n "FAILURE RATE HIGH\|failureCount\|callCount" backend/src/main/kotlin/com/aiinterview/conversation/brain/TheAnalyst.kt
```
Should show failure rate tracking. If present: mark as FIXED.

---

### TASK-M1: Warm-Up Before Problem Presentation
**Priority:** MEDIUM
**Status:** PARTIAL (behavioral has warm-up, coding dumps problem immediately)
**Files to read:**
- `backend/src/main/kotlin/com/aiinterview/conversation/ConversationEngine.kt` (startInterview, lines 150-236)

**Problem:**
For CODING interviews, `startInterview()` sends greeting + full problem in one message (line 181). Real interviews have 1-2 warm exchanges before the problem.

**Current code (line 181):**
```kotlin
questionDesc.isNotBlank() -> "$greeting\n\n**$questionTitle**\n\n$questionDesc\n\nTake a moment to read through it."
```

**Fix option A (simple — recommended):**
Keep current behavior but add a brief warm transition:
```kotlin
questionDesc.isNotBlank() -> "$greeting\n\nHere's your problem:\n\n**$questionTitle**\n\n$questionDesc\n\nTake your time reading through it. When you're ready, walk me through your initial thoughts."
```
This is realistic — many interviewers do present the problem quickly after a brief greeting.

**Fix option B (more natural but complex):**
Send greeting only. TheConductor presents problem on turn 1 based on `problem_shared` not in completed goals.

**Commit message:** `feat(TASK-M1): improve problem presentation transition`

---

### TASK-M2: Behavioral Question Natural Framing (verify)
**Priority:** MEDIUM
**Status:** FIXED in `62fac18` + ConversationEngine line 179
**Files to read:**
- `backend/src/main/kotlin/com/aiinterview/conversation/ConversationEngine.kt` (line 179)

**Verification:**
Line 179: `isBehavioral -> "$greeting Tell me a bit about what you've been working on lately."`
This is conversational, not "problem statement" format. Mark as FIXED if confirmed.

---

### TASK-M6: AI Asks to Explain Code While Still Coding
**Priority:** MEDIUM
**Status:** PARTIAL (silence rules added in `9c0282f`)
**Files to read:**
- `backend/src/main/kotlin/com/aiinterview/conversation/brain/TheConductor.kt`
- `backend/src/main/kotlin/com/aiinterview/conversation/brain/NaturalPromptBuilder.kt`

**Problem:**
TheConductor has silence intelligence but the prompt still sometimes generates code-review questions during CODING phase.

**Fix:** In NaturalPromptBuilder phase rules for CODING phase, add explicit rule:
```
"Do NOT ask to explain code while candidate is still writing it.
 Only move to review after 'done', 'finished', 'submitted' signal."
```

**Commit message:** `fix(TASK-M6): block code review questions during coding phase`

---

### TASK-M7: AI Reacts to Failing Test Results
**Priority:** MEDIUM
**Status:** PARTIAL (added in `04441bb` — verify it works)
**Files to read:**
- `backend/src/main/kotlin/com/aiinterview/code/service/CodeExecutionService.kt`

**Problem:**
When candidate submits code and tests fail, AI should prompt debugging. Verify that CODE_RESULT triggers an action in the brain.

**Fix if not working:**
In CodeExecutionService after sending CODE_RESULT with failures:
```kotlin
if (!allPassed) {
    try {
        brainService.addAction(sessionId, IntendedAction(
            id = "test_fail_${System.currentTimeMillis()}",
            type = ActionType.PROBE_DEPTH,
            description = "Tests failing. Ask: 'I see some tests aren't passing — what do you think might be causing that?'",
            priority = 1, expiresAfterTurn = brain.turnCount + 3,
            source = ActionSource.ANALYST
        ))
    } catch (_: Exception) {}
}
```

**Commit message:** `fix(TASK-M7): queue debug action when tests fail`

---

### TASK-Q1: Brain System Unit Tests
**Priority:** MEDIUM
**Status:** OPEN (zero test files for brain package)
**Files to create:**
- `backend/src/test/kotlin/com/aiinterview/conversation/brain/BrainServiceTest.kt`
- `backend/src/test/kotlin/com/aiinterview/conversation/brain/NaturalPromptBuilderTest.kt`
- `backend/src/test/kotlin/com/aiinterview/conversation/objectives/BrainObjectivesRegistryTest.kt`

**Tests needed:**

BrainServiceTest:
- `initBrain creates correct default state`
- `markGoalComplete adds to completed list`
- `addHypothesis enforces 5-cap`
- `incrementTurnCount removes expired actions`

NaturalPromptBuilderTest:
- `build includes question title`
- `build does NOT include eval criteria as spoken text`
- `build includes HARD_RULES`
- `CODING phase includes silence rules`
- `BEHAVIORAL phase has no code references`

BrainObjectivesRegistryTest:
- `CODING returns 9+ required goals`
- `BEHAVIORAL returns 8+ required goals`
- `SYSTEM_DESIGN returns 8+ required goals`
- `forCategory DSA returns CODING goals`

**Commit message:** `test(TASK-Q1): add brain system unit tests`

---

### TASK-Q2: Delete Old Test Files
**Priority:** MEDIUM
**Status:** OPEN
**Files to delete:**
```bash
find backend/src/test -name "*.kt" | xargs grep -l "InterviewerAgent\|ReasoningAnalyzer\|FollowUpGenerator\|AgentOrchestrator\|SmartOrchestrator" 2>/dev/null
```

**Fix:** Delete any test file that references deleted classes. Update ConversationEngineTest to use brain mocks instead of old agent mocks.

**Verification:**
```bash
cd backend && mvn test-compile -q
```

**Commit message:** `chore(TASK-Q2): remove test files referencing deleted classes`

---

### TASK-Q3: Split ConversationEngine (document only)
**Priority:** LOW
**Status:** OPEN (design task, not code)

**Problem:**
ConversationEngine has 12+ dependencies, 400+ lines. This is a god class.

**Recommendation (for future):**
Split into:
- `InterviewLifecycle` — start, end, transition
- `MessageHandler` — handleCandidateMessage
- `QuestionTransitioner` — multi-question logic

This is a refactoring task for a future sprint. Document the plan only.

**Commit message:** `docs(TASK-Q3): document ConversationEngine split plan`

---

### TASK-L1: Remove Dead Field crossTurnPatterns
**Priority:** LOW
**Status:** OPEN
**Files:** `InterviewerBrain.kt`

**Fix:** Remove `crossTurnPatterns` if never populated by TheAnalyst or TheStrategist. Check with grep first.

---

### TASK-L2: Simplify zdpEdge (document)
**Priority:** LOW — keep for now, revisit after 50 interviews of data

---

### TASK-L3: Bloom's Levels — Keep at 6
**Priority:** LOW — research-grounded at 6, no change needed

---

### TASK-L4: Consolidate ActionTypes (document)
**Priority:** LOW — 15 types is manageable, consolidation would break TheAnalyst prompt

---

### TASK-L5: topicSignalBudget — Keep
**Priority:** LOW — low overhead, useful for topic rotation

---

## Task Execution Order

| Order | Task | Priority | Est. | Status | Dependencies |
|-------|------|----------|------|--------|--------------|
| 1 | TASK-C2 | CRITICAL | verify | OPEN | C1 must be fixed (it is) |
| 2 | TASK-C3 | CRITICAL | verify | OPEN | C1 must be fixed (it is) |
| 3 | TASK-H2 | HIGH | 15min | PARTIAL | none |
| 4 | TASK-H3 | HIGH | verify | OPEN | docker-compose access |
| 5 | TASK-H4 | HIGH | 2h | OPEN | none |
| 6 | TASK-H7 | HIGH | 1h | OPEN | none |
| 7 | TASK-H8 | HIGH | 2h | OPEN | none |
| 8 | TASK-M1 | MEDIUM | 30min | PARTIAL | none |
| 9 | TASK-M6 | MEDIUM | 30min | PARTIAL | none |
| 10 | TASK-M7 | MEDIUM | 1h | PARTIAL | none |
| 11 | TASK-Q1 | MEDIUM | 4h | OPEN | none |
| 12 | TASK-Q2 | MEDIUM | 1h | OPEN | none |
| 13 | TASK-L1 | LOW | 15min | OPEN | none |

---

## Quick Reference — All Issues

| ID | Description | Status |
|----|-------------|--------|
| C1 | Score formula broken | FIXED |
| C2 | Radar chart empty | VERIFY |
| C3 | Score label wrong | VERIFY |
| C4 | initiative/learningAgility missing | FIXED |
| C5 | TheAnalyst JSON failures | FIXED |
| C6 | Phase stuck at 0 | FIXED |
| C7 | No failure alerting | FIXED |
| C8 | Eval criteria leaked | FIXED |
| C9 | Wrong question type | FIXED |
| H1 | Dual state drift (code) | FIXED |
| H2 | Silent catches | PARTIAL |
| H3 | Redis persistence | VERIFY |
| H4 | No LLM retry | OPEN |
| H5 | Code injection | FIXED |
| H6 | JWT in WS param | OPEN (doc only) |
| H7 | No error tracking | OPEN |
| H8 | No cost tracking | OPEN |
| H9 | Failure monitoring | FIXED |
| M1 | No warm-up | PARTIAL |
| M2 | Behavioral formatting | FIXED |
| M3 | Editor for behavioral | FIXED |
| M4 | Hint for all types | FIXED |
| M5 | Markdown rendering | FIXED |
| M6 | Code review during coding | PARTIAL |
| M7 | No test fail reaction | PARTIAL |
| M8 | Format mismatch | FIXED |
| Q1 | Zero brain tests | OPEN |
| Q2 | Old dead tests | OPEN |
| Q3 | God class | OPEN (doc) |
| L1-L5 | Simplification | LOW |
