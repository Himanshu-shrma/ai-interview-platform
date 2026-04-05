---
name: evaluation-scoring
description: >
  USE THIS SKILL when working on EvaluationAgent, ReportService score formula,
  8-dimension scoring, brain signal enrichment, report generation, ReportPage
  display, score labels, or radar chart. Also use when the overall score seems
  wrong.
  Files: report/service/EvaluationAgent.kt, report/service/ReportService.kt,
  report/dto/ReportDto.kt, frontend/src/pages/ReportPage.tsx
---

# Evaluation & Scoring

## The Exact 8-Dimension Formula

**File**: `ReportService.kt` companion object

```kotlin
private const val W_PROBLEM_SOLVING  = 0.20
private const val W_ALGORITHM_CHOICE = 0.15
private const val W_CODE_QUALITY     = 0.15
private const val W_COMMUNICATION    = 0.15
private const val W_EFFICIENCY       = 0.10
private const val W_TESTING          = 0.10
private const val W_INITIATIVE       = 0.10
private const val W_LEARNING_AGILITY = 0.05
// SUM = 1.00 ← MUST always equal 1.0
```

```kotlin
val overallScore = (
    s.problemSolving  * W_PROBLEM_SOLVING  +
    s.algorithmChoice * W_ALGORITHM_CHOICE +
    s.codeQuality     * W_CODE_QUALITY     +
    s.communication   * W_COMMUNICATION    +
    s.efficiency      * W_EFFICIENCY       +
    s.testing         * W_TESTING          +
    s.initiative      * W_INITIATIVE       +
    s.learningAgility * W_LEARNING_AGILITY
).coerceIn(0.0, 10.0)
```

**If you change any weight, verify the sum equals 1.0.** A sum > 1.0 inflates scores; < 1.0 deflates them. This was previously broken and showed 0.5/10 for all candidates.

## Report Generation Flow

```
ConversationEngine.forceEndInterview(sessionId)
  → transition to EVALUATING
  → sessionScope.launch {
      ReportService.generateAndSaveReport(sessionId)
        1. Idempotency check (existing report?)
        2. Load InterviewMemory from Redis
        3. Load session from DB
        4. EvaluationAgent.evaluate(memory, brain?)
        ⚠️ RACE CONDITION RISK: Brain is deleted in the finally block
           of forceEndInterview() AFTER this flow completes. If evaluation
           is slow and brain TTL expires first, enrichment signals may
           be missing. Do not move brain deletion before evaluation.
        5. Compute overallScore with weighted formula
        6. Persist EvaluationReport to DB
        7. Update session status = COMPLETED
        8. Increment usage counter
        9. Send SESSION_END WS message
       10. Delete Redis memory
    }
```

## EvaluationAgent — LLM-Based Scoring

**File**: `report/service/EvaluationAgent.kt`

### Type-Specific Criteria

**CODING/DSA**:
- problemSolving: understanding, breaking down, edge cases
- algorithmChoice: data structures, algorithm selection
- codeQuality: readability, correctness, error handling
- communication: explaining thought process
- efficiency: complexity awareness, optimization
- testing: edge cases, debugging, verification

**BEHAVIORAL** (maps to STAR):
- problemSolving → SITUATION: clear context
- algorithmChoice → TASK: defined role/goals
- codeQuality → ACTION: specific, detailed, impactful
- communication → RESULT: quantified outcomes, reflections
- efficiency → DEPTH: multiple examples
- testing → GROWTH: self-awareness, learning

**SYSTEM_DESIGN**:
- problemSolving → requirements, scope, functional + non-functional
- algorithmChoice → architecture, component selection, tech choices
- codeQuality → data modeling, API design
- communication → trade-offs, driving discussion
- efficiency → scalability, bottlenecks, capacity
- testing → reliability, fault tolerance, monitoring

### Scoring Rules (in prompt)
- Each dimension: 0.0-10.0 (one decimal)
- 0 = not demonstrated, 5 = average, 8+ = strong, 10 = exceptional
- Each hint ≈ -0.5 from relevant dimensions
- Most candidates score 3-7

## Brain Signal Enrichment (15 Signals)

`buildBrainEnrichment()` injects brain-derived insights into the evaluation prompt:

| Signal | Source | Effect |
|--------|--------|--------|
| Exchange scores | `brain.exchangeScores` | PRIMARY scoring input (anti-halo) |
| Avg anxiety | `candidateProfile.avgAnxietyLevel` | >0.7: +0.75 all dims; >0.5: +0.5 |
| Productive struggle | `candidateProfile.selfRepairCount` | >2: reward metacognitive awareness |
| Reasoning pattern | `candidateProfile.reasoningPattern` | SCHEMA_DRIVEN: +1.0 to algorithm |
| Linguistic pattern | `candidateProfile.linguisticPattern` | HEDGED_UNDERSTANDER: adjust upward |
| Psychological safety | `candidateProfile.psychologicalSafety` | <0.5: "may underestimate ability" |
| Bloom's levels | `brain.bloomsTracker` | Topics reaching level 4+ highlighted |
| Confirmed hypotheses | `hypothesisRegistry` | Confirmed gaps flagged |
| Incorrect claims | `claimRegistry` | Technical errors listed |
| Challenge rate | `brain.challengeSuccessRate` | >85%: "too easy"; <50%: "too hard" |
| ZDP edge | `brain.zdpEdge` | Topics where can-do-with-help (growth areas) |
| Topic interleaving | `brain.topicHistory` | Return-visit performance |
| STAR ownership | Goal completion | Behavioral: ownership/$stories ratio |
| Formative feedback | `brain.formativeFeedbackGiven` | "Does not penalize candidate" |
| Scoring rubric | `questionDetails.scoringRubric` | Algorithm indicators |

### Research-Grounded Score Adjustments
```
Anxiety (Lupien 2007, Picard 1997):
  avgAnxietyLevel > 0.7 → +0.75 all dimensions
  avgAnxietyLevel > 0.5 → +0.50 all dimensions

Productive Struggle (Bjork 1994):
  selfRepairDetected + correct answer → +0.5 per exchange (cap 10.0)

Dimension Independence:
  Score each dimension INDEPENDENTLY
  algorithm_depth = WHY it works (not just correct choice)
  code_quality = readability (not complexity)
```

## Score Display (Frontend)

### ReportPage.tsx Score Labels
```typescript
function scoreLabel(score: number): string {
    if (score >= 9) return 'Excellent'
    if (score >= 7) return 'Good'
    if (score >= 5) return 'Average'
    return 'Needs Work'
}
```

### Radar Chart Data Format
```typescript
const radarData = [
    { dimension: 'Problem Solving', score: 7.5, fullMark: 10 },
    { dimension: 'Algorithm', score: 8.0, fullMark: 10 },
    // ... 6 dimensions total (no initiative/learningAgility in radar)
]
```

### Dimension Labels (6 shown in radar, not 8)
```typescript
const dimensionLabels = {
    problemSolving: 'Problem Solving',
    algorithmChoice: 'Algorithm',
    codeQuality: 'Code Quality',
    communication: 'Communication',
    efficiency: 'Efficiency',
    testing: 'Testing',
}
```

Note: `initiative` and `learningAgility` are NOT shown in the radar chart or dimension bars. They only affect the overall weighted score.

## Known Issues

1. **EvaluationAgent reads InterviewMemory, not Brain exclusively** — The `generateAndSaveReport` call in ConversationEngine's `forceEndInterview` deletes the brain in the `finally` block. The evaluation may lose brain enrichment data if brain deletion races with evaluation.

2. **No parallel evaluation** — Currently a single LLM call. Could parallelize per-dimension evaluation for speed.

3. **Default scores on failure**: If both LLM attempts fail, defaults to 3.0 for all dimensions except initiative (5.0) and learningAgility (5.0).
