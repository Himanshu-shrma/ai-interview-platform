# Skill: LLM Integration + Brain System

## When This Applies
Any work touching: TheConductor, TheAnalyst, TheStrategist, NaturalPromptBuilder, BrainService, BrainObjectivesRegistry, EvaluationAgent, or LLM prompt changes.

## LLM Provider Registry — Always Use This
```kotlin
// Non-streaming (TheAnalyst, EvaluationAgent)
val response = llm.complete(LlmRequest.build(
    systemPrompt = systemPrompt, userMessage = userMessage,
    model = modelConfig.backgroundModel, maxTokens = 600,
    responseFormat = ResponseFormat.JSON
))

// Streaming (TheConductor)
llm.stream(request).collect { token -> registry.sendMessage(sessionId, OutboundMessage.AiChunk(delta = token, done = false)) }
```

## Model Selection (ModelConfig.kt)
| Task | Config field | Default |
|------|-------------|---------|
| TheConductor (real-time) | interviewerModel | gpt-4o |
| TheAnalyst (background) | backgroundModel | gpt-4o-mini |
| TheStrategist (periodic) | backgroundModel | gpt-4o-mini |
| EvaluationAgent (scoring) | evaluatorModel | gpt-4o |
| QuestionGenerator | generatorModel | gpt-4o |

## TheAnalyst — JSON Parsing
Requests complex JSON from LLM. ALWAYS handle parse failures:
```kotlin
private fun parseAnalystResponse(json: String): AnalystDecision {
    return try {
        objectMapper.readValue(json, AnalystDecision::class.java)
    } catch (e: Exception) {
        log.warn("Full parse failed: ${e.message}")
        tryPartialParse(json)  // salvages goals + thoughts + action
    }
}
```

### ALL DTOs Must Have
```kotlin
@JsonIgnoreProperties(ignoreUnknown = true)
data class SomeDto(val field: String = "")  // always have defaults
```

### NewClaimDto has a custom deserializer
LLM returns claims as either string or object. `NewClaimDtoDeserializer` handles both:
```kotlin
@JsonDeserialize(using = NewClaimDtoDeserializer::class)
@JsonIgnoreProperties(ignoreUnknown = true)
data class NewClaimDto(val claim: String = "", val topic: String = "general", val correctness: String = "unverified")
```

## Brain State — InterviewerBrain (InterviewerBrain.kt)
Key fields:
- `sessionId`, `userId`, `interviewType` (CODING/BEHAVIORAL/SYSTEM_DESIGN)
- `questionDetails: InterviewQuestion` — title, description, optimalApproach, scoringRubric
- `candidateProfile: CandidateProfile` — 18+ fields (thinkingStyle, anxietyLevel, flowState, etc.)
- `hypothesisRegistry`, `claimRegistry` — AI's mental model of candidate
- `interviewGoals: InterviewGoals` — completed goals list, DAG dependency tracking
- `actionQueue: ActionQueue` — next actions for TheConductor
- `thoughtThread: ThoughtThread` — running commentary with compression
- `rollingTranscript: List<BrainTranscriptTurn>` — last 8 turns
- `exchangeScores`, `bloomsTracker`, `challengeSuccessRate`
- `currentCode`, `programmingLanguage` — updated on CODE_UPDATE

## NaturalPromptBuilder — 13 Sections
Order matters. Each section is gated by brain state:
1. IDENTITY (static ~80 tokens)
2. SITUATION (turn count, time, phase, goals progress, challenge calibration)
3. OPENING_INSTRUCTION (turn 0 only — present problem per interview type)
4. PHASE_RULES (critical — specific rules per phase label)
5. CANDIDATE_PROFILE (after dataPoints >= 2)
6. QUESTION_DETAILS (always — title, description, INTERNAL notes marked private)
7. GOALS (remaining required, next unlocked, bloom's depth)
8. HYPOTHESES (top 2 open)
9. CONTRADICTIONS (top 1 unsurfaced, only after turn 5)
10. STRATEGY (from TheStrategist)
11. ACTION (top from queue)
12. CODE (candidate's actual code, with prompt injection protection)
13. HARD_RULES (static — one question per response, never reveal solution, etc.)

## Action Queue — 15 ActionTypes
```kotlin
ActionType: TEST_HYPOTHESIS, SURFACE_CONTRADICTION, ADVANCE_GOAL, PROBE_DEPTH,
  REDIRECT, WRAP_UP_TOPIC, END_INTERVIEW, EMOTIONAL_ADJUST, REDUCE_LOAD,
  MAINTAIN_FLOW, RESTORE_SAFETY, PRODUCTIVE_UNKNOWN, REDUCE_PRESSURE,
  MENTAL_SIMULATION, FORMATIVE_FEEDBACK
```

Adding an action (TheConductor reads it next turn):
```kotlin
brainService.addAction(sessionId, IntendedAction(
    id = "a_${brain.turnCount}", type = ActionType.PROBE_DEPTH,
    description = "...", priority = 1, expiresAfterTurn = brain.turnCount + 3,
    source = ActionSource.ANALYST
))
```

## BrainObjectivesRegistry Goals
- CODING: 10 required goals (problem_shared -> interview_closed)
- DSA: same as CODING
- BEHAVIORAL: 8 required (psychological_safety -> interview_closed, STAR-based)
- SYSTEM_DESIGN: 8 required (problem_shared -> interview_closed, architecture-based)

Phase labels are inferred from completed goals via `inferPhaseLabel()`:
```kotlin
// CODING: INTRO -> CLARIFICATION -> APPROACH -> CODING -> REVIEW -> FOLLOWUP -> WRAP_UP
// BEHAVIORAL: OPENING -> STORY_1 -> STORY_2 -> STORY_3 -> FINAL_STORY -> WRAP_UP
// SYSTEM_DESIGN: INTRO -> REQUIREMENTS -> ARCHITECTURE -> DESIGN -> DEEP_DIVE -> WRAP_UP
```

## Evaluation — 8 Dimensions + Formula
```kotlin
// ReportService weighted formula (sum = 1.0):
overall = ps*0.20 + algo*0.15 + code*0.15 + comm*0.15 + eff*0.10 + test*0.10 + init*0.10 + la*0.05
```

EvaluationAgent enriches evaluation with 15+ brain signals: exchange scores, anxiety adjustment, productive struggle, reasoning pattern, bloom's levels, confirmed/refuted hypotheses, incorrect claims, ZDP edge, challenge calibration, etc.
