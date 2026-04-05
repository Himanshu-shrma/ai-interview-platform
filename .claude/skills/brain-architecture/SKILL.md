---
name: brain-architecture
description: >
  USE THIS SKILL when working on TheConductor, TheAnalyst, TheStrategist,
  BrainService, BrainFlowGuard, NaturalPromptBuilder, BrainObjectivesRegistry,
  InterviewerBrain, ActionQueue, or any brain state mutations. Also use when
  debugging phase tracking, goal completion, or AI behavior issues.
  Files: conversation/brain/*.kt, conversation/ConversationEngine.kt
---

# Brain Architecture — The 3-Agent System

## Architecture Overview

The AI interviewer runs via 3 cooperating agents, orchestrated by `ConversationEngine.kt`:

```
Candidate Message → ConversationEngine.handleCandidateMessage()
  1. Load brain from Redis (BrainService.getBrainOrNull)
  2. Persist message to DB + brain transcript
  3. Compute interview state (computeBrainInterviewState)
  4. FlowGuard safety check (BrainFlowGuard.check)
  5. Increment turn count
  6. TheConductor.respond() ← STREAMS RESPONSE to client
  7. Fire-and-forget on session scope:
     a. TheAnalyst.analyze() ← EVERY turn
     b. TheStrategist.review() ← EVERY 5 turns
```

### Agent Responsibilities

| Agent | File | Model | Trigger | Purpose |
|-------|------|-------|---------|---------|
| TheConductor | `TheConductor.kt` | `modelConfig.interviewerModel` (gpt-4o) | Every candidate message | Generate and stream AI response |
| TheAnalyst | `TheAnalyst.kt` | `modelConfig.backgroundModel` (gpt-4o-mini) | Every turn (fire-and-forget) | Update brain state: profile, goals, hypotheses, claims, actions |
| TheStrategist | `TheStrategist.kt` | `modelConfig.backgroundModel` | Every 5 turns (fire-and-forget) | Meta-review: update InterviewStrategy, abandon stale hypotheses |

## BrainService — The ONLY Way to Touch Brain State

**File**: `conversation/brain/BrainService.kt`

### Core Rule: ALWAYS use BrainService methods. NEVER read + write without mutex.

```kotlin
// CORRECT — atomic read-modify-write with per-session Mutex
brainService.updateBrain(sessionId) { brain ->
    brain.copy(turnCount = brain.turnCount + 1)
}

// CORRECT — convenience methods (all use updateBrain internally)
brainService.markGoalComplete(sessionId, "problem_shared")
brainService.addAction(sessionId, action)
brainService.appendThought(sessionId, "Candidate knows BFS")
brainService.updateCandidateProfile(sessionId) { p -> p.copy(anxietyLevel = 0.6f) }

// WRONG — race condition, data loss
val brain = brainService.getBrain(sessionId)
val updated = brain.copy(turnCount = brain.turnCount + 1)
brainService.saveBrain(sessionId, updated)  // saveBrain is private anyway
```

### Mutex Pattern (per-session, non-blocking)
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

### Redis Storage
- Key: `brain:{sessionId}`
- TTL: 3 hours
- Serialization: JSON via ObjectMapper
- All reads: `awaitSingleOrNull()` (never `.block()`)

## ActionQueue — Influencing the Next AI Response

TheConductor reads the top action from the queue each turn and includes it in the prompt as "YOUR INTENDED ACTION". After generating the response, it calls `completeTopAction()`.

### Adding an Action
```kotlin
brainService.addAction(sessionId, IntendedAction(
    id = "test_fail_${brain.turnCount}",
    type = ActionType.PROBE_DEPTH,
    description = "Tests FAILING: 3/5 passed. Ask: 'Some tests aren't passing — what might be causing that?'",
    priority = 1,                          // lower = higher priority
    expiresAfterTurn = brain.turnCount + 3, // auto-cleanup
    source = ActionSource.ANALYST,          // who queued it
    bloomsLevel = 3,                       // cognitive depth target
))
```

### 15 ActionTypes
```
TEST_HYPOTHESIS, SURFACE_CONTRADICTION, ADVANCE_GOAL, PROBE_DEPTH,
REDIRECT, WRAP_UP_TOPIC, END_INTERVIEW, EMOTIONAL_ADJUST, REDUCE_LOAD,
MAINTAIN_FLOW, RESTORE_SAFETY, PRODUCTIVE_UNKNOWN, REDUCE_PRESSURE,
MENTAL_SIMULATION, FORMATIVE_FEEDBACK
```

### ActionSource (who queued it)
```
FLOW_GUARD, HYPOTHESIS, CONTRADICTION, GOAL, META_STRATEGY,
COGNITIVE_LOAD, SAFETY, ANALYST
```

### Dedup Rule
`addAction()` silently skips if an action of the same `type` already exists in pending.

## Goal System — BrainObjectivesRegistry

**File**: `BrainObjectivesRegistry.kt`

Goals form a dependency DAG. A goal can only be completed when all `dependsOn` goals are complete. Phase labels are inferred from completed goals via `inferPhaseLabel()`.

### CODING Goals (10 required)
```
problem_shared → clarifying_questions_handled → approach_understood
  → approach_justified, solution_implemented → complexity_owned,
    edge_cases_explored, reasoning_depth_assessed, mental_simulation_tested
      → interview_closed
```

### BEHAVIORAL Goals (8 required)
```
psychological_safety → star_q1_complete → star_q1_ownership
  → star_q2_complete → star_q2_ownership → star_q3_complete
    → learning_demonstrated → interview_closed
```

### SYSTEM_DESIGN Goals (8 required)
```
problem_shared → requirements_gathered → high_level_design
  → component_deep_dive, tradeoffs_acknowledged, failure_modes_explored,
    scalability_addressed → interview_closed
```

### Phase Inference
```kotlin
// CODING: INTRO → CLARIFICATION → APPROACH → CODING → REVIEW → FOLLOWUP → WRAP_UP
// BEHAVIORAL: OPENING → STORY_1 → STORY_2 → STORY_3 → FINAL_STORY → WRAP_UP
// SYSTEM_DESIGN: INTRO → REQUIREMENTS → ARCHITECTURE → DESIGN → DEEP_DIVE → WRAP_UP
```

## NaturalPromptBuilder — 13 Sections

**File**: `NaturalPromptBuilder.kt`

The system prompt is built dynamically from brain state. Section order matters:

| # | Section | Gate Condition | Purpose |
|---|---------|---------------|---------|
| 1 | IDENTITY | Always | Static interviewer persona (~80 tokens) |
| 2 | SITUATION | Always | Turn count, time, phase, goals, challenge rate |
| 3 | OPENING_INSTRUCTION | Turn 0 + problem not shared | Present problem per type |
| 4 | PHASE_RULES | Always | Phase-specific AI behavior rules |
| 5 | CANDIDATE_PROFILE | dataPoints >= 2 | Thinking style, signal, anxiety, etc. |
| 6 | QUESTION_DETAILS | Always | Title, description, INTERNAL notes |
| 7 | GOALS | Remaining goals exist | Next unlocked + bloom's depth |
| 8 | HYPOTHESES | Open hypotheses exist | Top 2 with test strategies |
| 9 | CONTRADICTIONS | Turn >= 5 + unsurfaced exist | Top 1 unsurfaced |
| 10 | STRATEGY | Strategy not blank | From TheStrategist |
| 11 | ACTION | Action in queue | Top action to execute |
| 12 | CODE | Code exists for coding types | Candidate's code with injection protection |
| 13 | HARD_RULES | Always | Universal rules, acknowledgment tracking |

### CRITICAL: INTERNAL vs Spoken
Section 6 contains `=== INTERNAL NOTES (NEVER share with candidate) ===` with optimalApproach and evaluationCriteria. This MUST stay clearly marked. The AI revealing evaluation criteria is a critical bug.

## BrainFlowGuard — 4 Safety Rules

**File**: `BrainFlowGuard.kt`

Returns `null` (all fine) or an `IntendedAction` injected into the queue:

1. **Problem not shared by turn 4** → ADVANCE_GOAL (priority 1)
2. **Overtime (0 minutes left)** → END_INTERVIEW (priority 1)
3. **Stalled 8+ attempts on same goal** → ADVANCE_GOAL (priority 2)
4. **Behind schedule** → ADVANCE_GOAL (priority 2)

## Common Failure Modes — Diagnosis Guide

### Phase stuck at 0 / goals never advance
**Cause**: TheAnalyst JSON parse failures
**Check**: `grep "FAILURE RATE HIGH" logs` or `grep "TheAnalyst: no goals completed"`
**Fix**: Ensure all DTOs have `@JsonIgnoreProperties(ignoreUnknown = true)` and defaults

### AI reveals evaluation criteria
**Cause**: INTERNAL marker missing or unclear in NaturalPromptBuilder section 6
**Fix**: Verify `=== INTERNAL NOTES (NEVER share) ===` wraps optimalApproach

### AI reviews code while candidate is still coding
**Cause**: CODING phase rules not strict enough
**Fix**: Check `buildPhaseRules()` CODING section — must have FORBIDDEN phrases list

### Goals complete but wrong phase inferred
**Cause**: `inferPhaseLabel()` doesn't check the right goals
**Fix**: Check goal ID strings in `inferPhaseLabel()` match `BrainObjectivesRegistry`

### "POSSIBLE ANALYST FAILURE" warning
**Cause**: 5+ turns with 0 goals completed
**Where**: TheConductor.kt line ~111
**Fix**: TheAnalyst is not parsing LLM responses. Check `parseAnalystResponse()` and `tryPartialParse()`

## Critical Rules

1. **NEVER mutate brain directly** — always via BrainService methods
2. **NEVER call LLM directly** — always via LlmProviderRegistry
3. **NEVER add silent catches** — `catch (_: Exception) {}` hides failures
4. **NEVER skip @JsonIgnoreProperties** on any DTO TheAnalyst returns
5. **All DTO fields must have defaults** — LLM may omit any field
6. **Fire-and-forget agents use session scope** — `getSessionScope(sessionId).launch { }`
7. **Re-throw CancellationException** — coroutine contract requirement
