# Natural AI Interviewer — Master Task Tracker

> ALL WORK IN THIS FILE HAPPENS ON BRANCH: `feature/natural-interviewer`
> Never commit this work to master directly. See BRANCH_CONTEXT.md for merge strategy.

## How to Use This Document

**BEFORE STARTING ANY TASK:**
```bash
git branch --show-current
# Must show: feature/natural-interviewer
# If not: git checkout feature/natural-interviewer
```

**STATUS MARKERS:**
- `[N]` NOT STARTED
- `[P]` IN PROGRESS
- `[D]` COMPLETED
- `[B]` BLOCKED
- `[X]` CANCELLED

**WORKFLOW:** Verify branch -> Find first `[N]` with no blocked deps -> Change to `[P]` -> Implement -> Verify -> Change to `[D]` -> Commit: `feat(TASK-NNN): description` -> Push

**EFFORT:** S=half day, M=1 day, L=2-3 days, XL=4-5 days, XXL=1 week

---

## Progress Summary

| Phase | Tasks | Done | Status |
|-------|-------|------|--------|
| 1 — Foundation Brain | 8 | 8 | COMPLETE |
| 2 — Natural Intelligence | 10 | 10 | COMPLETE |
| 3 — Scientific Validity | 12 | 0 | Not started |
| 4 — Advanced Cognition | 8 | 0 | Not started |
| 5 — Deep Research Fixes | 10 | 0 | Not started |
| **TOTAL** | **48** | **18** | **38%** |

Minimum for merge to master: Phase 1 + Phase 2 complete.

---

## PHASE 1 — Foundation Brain
> Replace stage machine with unified cognitive architecture.
> Must be completed in order. Everything else builds on these.

### TASK-001 [D] XXL — InterviewerBrain Data Structure
**Requires:** None
**Replaces:** InterviewMemory data class
**File:** `conversation/brain/InterviewerBrain.kt`

Create unified cognitive state containing:
- `CandidateProfile`: thinkingStyle, reasoningPattern, knowledgeMap, communicationStyle, pressureResponse, avoidancePatterns, overallSignal, currentState, anxietyLevel, flowState, trajectory, psychologicalSafety, linguisticPattern, abstractionLevel, selfRepairCount, cognitiveLoadSignal
- `HypothesisRegistry`: List<Hypothesis> with id, claim, confidence, evidence, status, testStrategy, bloomsLevel
- `ClaimRegistry`: List<Claim> + List<Contradiction> for contradiction detection
- `InterviewGoals`: required/optional Goal lists with dependencies, completion tracking
- `ThoughtThread`: continuous stream-of-consciousness (500 char cap)
- `InterviewStrategy`: approach, toneGuidance, timeGuidance, avoidance, recommendedTokens, selfCritique
- `ActionQueue`: prioritized intended actions with types (TEST_HYPOTHESIS, SURFACE_CONTRADICTION, ADVANCE_GOAL, PROBE_DEPTH, REDIRECT, WRAP_UP_TOPIC, END_INTERVIEW, EMOTIONAL_ADJUST, REDUCE_LOAD, MAINTAIN_FLOW, RESTORE_SAFETY, PRODUCTIVE_UNKNOWN, REDUCE_PRESSURE, MENTAL_SIMULATION)

All enums: ThinkingStyle, ReasoningPattern, CommunicationStyle, PressureResponse, CandidateSignal, EmotionalState, PerformanceTrajectory, LinguisticPattern, CognitiveLoad, HypothesisStatus, ClaimCorrectness, GoalCategory, ActionType, ActionSource.

**Verify:** `cd backend && mvn compile -q 2>&1 | tail -5`

---

### TASK-002 [D] XL — BrainService + Redis Persistence
**Requires:** TASK-001
**Replaces:** RedisMemoryService (extend, don't delete)
**File:** `conversation/brain/BrainService.kt`

Redis key: `brain:{sessionId}`, TTL: 3 hours. Per-session `kotlinx.coroutines.sync.Mutex`.

Methods: `initBrain()`, `getBrain()`, `getBrainWithFallback()`, `updateBrain()` (atomic with Mutex), `appendThought()`, `addHypothesis()`, `updateHypothesis()`, `addClaim()`, `addContradiction()`, `markGoalComplete()`, `addAction()`, `completeTopAction()`, `updateStrategy()`, `updateCandidateProfile()`, `incrementTurnCount()`, `deleteBrain()`. `@PreDestroy` cleans up mutexes.

---

### TASK-003 [D] XL — ObjectivesRegistry — All Interview Types
**Requires:** TASK-001
**Replaces:** Stage machine as behavioral controller
**File:** `conversation/objectives/ObjectivesRegistry.kt` (rewrite existing)

CODING/DSA goals (9 required): problem_shared, approach_understood, approach_justified, solution_implemented, complexity_owned, edge_cases_explored, reasoning_depth_assessed, mental_simulation_tested, interview_closed. Optional: optimization_explored, follow_up_attempted.

BEHAVIORAL goals (8 required): psychological_safety, star_q1_complete, star_q1_ownership, star_q2_complete, star_q2_ownership, star_q3_complete, learning_demonstrated, interview_closed. Optional: star_q4_complete, situational_judgment.

SYSTEM_DESIGN goals (8 required): problem_shared, requirements_gathered, high_level_design, component_deep_dive, tradeoffs_acknowledged, failure_modes_explored, scalability_addressed, interview_closed. Optional: alternative_considered, mental_model_tested.

Also implement `computeInterviewState()` returning `InterviewState` with bloomsLevelReached tracking.

---

### TASK-004 [D] L — KnowledgeAdjacencyMap
**Requires:** TASK-001
**File:** `conversation/knowledge/KnowledgeAdjacencyMap.kt`

Maps known topics to adjacent unknowns for predictive probing. 8+ topic groups: hash_map_usage, bfs_algorithm, recursion_correct, dp_pattern_recognized, binary_tree_traversal, time_complexity_stated, high_level_design_done, gave_action. Each has 3+ adjacent topics with: probeQuestion (open, non-leading), bloomsLevel (1-6), diagnosticValue (0-1), isOrthogonal flag. Includes `toHypothesis()` helper.

---

### TASK-005 [D] L — FlowGuard (Rewrite)
**Requires:** TASK-001, TASK-003
**Replaces:** Current FlowGuard
**File:** `conversation/objectives/FlowGuard.kt` (rewrite existing)

Exactly 4 rules unchanged. But now returns `IntendedAction` with `source = ActionSource.FLOW_GUARD` instead of a string. FlowGuard injects into ActionQueue, never sends messages directly.

---

### TASK-006 [D] XL — TheAnalyst (Single Background Agent)
**Requires:** TASK-001, TASK-002, TASK-003, TASK-004
**Replaces:** SmartOrchestrator + ReasoningAnalyzer + FollowUpGenerator + AgentOrchestrator + StageReflectionAgent + CandidateModelUpdater + ObjectiveTracker (all 7 → 1)
**File:** `conversation/brain/TheAnalyst.kt`

ONE background agent, ONE LLM call (gpt-4o-mini) per exchange. Fires fire-and-forget after every candidate message. Updates entire InterviewerBrain in one pass.

JSON output: candidateProfileUpdate (12 fields), newHypothesis, hypothesisUpdates, newClaims, contradictionFound, goalsCompleted, thoughtThreadAppend, nextAction, exchangeScore, signalDepletedTopics, adjacentTopicsToProbe. KnowledgeAdjacencyMap integrated. Silent failure (never breaks interview).

---

### TASK-007 [D] L — TheStrategist (Meta-Cognitive Reviewer)
**Requires:** TASK-001, TASK-002, TASK-006
**File:** `conversation/brain/TheStrategist.kt`

Runs every 5 turns (`turnCount % 5 == 0`). Reviews full InterviewerBrain. Updates `InterviewStrategy` including `selfCritique`. Uses backgroundModel. Asks: Is approach yielding signal? What to change? Time allocation? What went wrong? Most important for next 5 turns?

---

### TASK-008 [D] XL — TheConductor + NaturalPromptBuilder
**Requires:** TASK-001 through TASK-007
**Replaces:** InterviewerAgent + PromptBuilder
**Files:** `conversation/brain/TheConductor.kt`, `conversation/brain/NaturalPromptBuilder.kt`

NaturalPromptBuilder 13 sections in order: INTERVIEWER_IDENTITY (static 5 lines), SITUATION, CANDIDATE (after turn 2), THOUGHT_THREAD (500 chars), GOALS, HYPOTHESES (top 2), CONTRADICTIONS (top 1 unsurfaced), STRATEGY, ACTION (top from queue), CODE (REVIEW only), TESTS (REVIEW only), HISTORY (last 6 turns), HARD_RULES (static 5 lines).

TheConductor: SilenceIntelligence (RESPOND/SILENT/WAIT_THEN_RESPOND). Feature flag: `interview.use-new-brain: true/false` in application.yml. pendingAction cleared after use. Tokens = strategy.recommendedTokens.

---

## PHASE 2 — Natural Intelligence
> Phase 1 must be complete. Within this phase: tasks can be in any order.

### TASK-009 [D] L — Thought Thread Quality + Compression
**Requires:** TASK-006, TASK-002
When > 600 chars, compress oldest 200 to 50-char summary via backgroundModel.

### TASK-010 [D] L — Claim Registry + Contradiction Detection
**Requires:** TASK-006
Verify claim extraction. Add contradiction surfacing to NaturalPromptBuilder. Surfaced exactly once per pair.

### TASK-011 [D] L — Hypothesis Testing Loop End-to-End
**Requires:** TASK-004, TASK-006, TASK-008
Full loop: form -> queue -> test -> confirm/refute. Max 5 open. KnowledgeAdjacencyMap auto-hypothesis.

### TASK-012 [D] L — Silence Intelligence
**Requires:** TASK-008
Three decisions: RESPOND / SILENT / WAIT_THEN_RESPOND. Silent when coding without text. Wait 3s on approach with no action. Always respond to questions/help/done.

### TASK-013 [D] L — Adaptive Personality Engine
**Requires:** TASK-006, TASK-008
Dynamic tone from CandidateProfile. STRONG->challenge. STRUGGLING->patient. Anxiety->slow down. Flow->deepen. Safety<0.4->restore.

### TASK-014 [D] L — "I Don't Know" Protocol
**Requires:** TASK-008
PRODUCTIVE_UNKNOWN action. Never give answer. Ask how they'd figure it out. Track unknownHandlingPattern: REASONS_FROM_PRINCIPLES / ADMITS_AND_STOPS / PANICS / GUESSES_BLINDLY.

### TASK-015 [D] L — Mental Simulation Testing
**Requires:** TASK-003, TASK-008
MENTAL_SIMULATION action after code written. Trace code with specific input, no running. Track abstractionLevel.

### TASK-016 [D] L — Bloom's Taxonomy Tracking
**Requires:** TASK-001, TASK-006, TASK-008
bloomsTracker: Map<String, Int>. TheAnalyst outputs bloomsLevelReached per topic. EvaluationAgent uses for depth scoring.

### TASK-017 [D] M — Natural Imperfection Injection
**Requires:** TASK-008
13+ acknowledgment variants. usedAcknowledgments in brain. Never repeat same phrase. Occasional rephrase (max 2x).

### TASK-018 [D] M — Wire Phase 1+2 into ConversationEngine
**Requires:** TASK-001 through TASK-017
Feature flag: `interview.use-new-brain`. Old agents kept as dead code. Background: TheAnalyst + TheStrategist + FlowGuard. EvaluationAgent receives InterviewerBrain.

---

## PHASE 3 — Scientific Validity
> Tier 1 tasks (019-023) before others.

### TASK-019 [N] L — Cognitive Load Detection + Reduction (Tier 1)
**Requires:** TASK-006, TASK-013
Overload signals: within-turn contradiction, repetition, confusion. REDUCE_LOAD action.

### TASK-020 [N] L — Anxiety Detection + Score Adjustment (Tier 1)
**Requires:** TASK-006, TASK-013
anxietyLevel tracked. If avg > 0.5: +0.5 all scores. Report notes adjustment. DB migration: anxiety_adjustment column.

### TASK-021 [N] M — Productive Struggle Recognition (Tier 1)
**Requires:** TASK-006
Correct after struggle = +0.5 bonus. selfRepairCount tracked.

### TASK-022 [N] L — Anti-Halo Evaluation Architecture (Tier 1)
**Requires:** TASK-001
ExchangeScore per turn. Dimension scores = weighted average of exchange scores. Holistic transcript for narrative only.

### TASK-023 [N] M — Initiative + Learning Agility Dimensions (Tier 1)
**Requires:** TASK-022
Two new scoring dimensions. Updated formula (8 dims). DB migration. Report page update.

### TASK-024 [N] L — Linguistic Analysis (Tier 2)
**Requires:** TASK-006
Confidence, justification, specificity, self-repair, dismissal detection. Dismissal -> probe action.

### TASK-025 [N] L — Signal Depletion + Topic Orthogonality (Tier 2)
**Requires:** TASK-006
topicSignalBudget. MOVE_ON at 80%. Orthogonal topic preference.

### TASK-026 [N] L — Psychological Safety Protocol (Tier 2)
**Requires:** TASK-006
psychological_safety goal. RESTORE_SAFETY action. Safety-building opening.

### TASK-027 [N] M — Observer Effect Pressure Reduction (Tier 2)
**Requires:** TASK-006
90-second coding silence -> "Take your time." 4+ variants.

### TASK-028 [N] L — Dynamic Assessment — Hint Quality (Tier 2)
**Requires:** TASK-006
HintOutcome: applied + generalized tracking. learningAgility influenced.

### TASK-029 [N] L — Forward vs Backward Reasoning Detection (Tier 2)
**Requires:** TASK-006
SCHEMA_DRIVEN vs SEARCH_DRIVEN. +1.0 to algorithm_score for schema-driven.

### TASK-030 [N] M — Situational Judgment Questions (Tier 3)
**Requires:** TASK-003
5+ engineering scenarios. One per behavioral interview.

---

## PHASE 4 — Advanced Cognition

### TASK-031 [N] L — Topic Interleaving (Tier 3)
After 4 turns on topic A: switch to B then return. Tests generalization.

### TASK-032 [N] L — Optimal Challenge Calibration — 70% Rule (Tier 3)
Track success rate. Target: 70%. Above 80%: harder. Below 60%: easier.

### TASK-033 [N] L — STAR Ownership Detection (Tier 3)
"we" vs "I" detection. ownership_clarity per story. Auto-probe when "we" dominates.

### TASK-034 [N] M — Anchoring Prevention (Tier 3)
Post-process: "or" between options -> open question.

### TASK-035 [N] L — Consistency Scoring — Rubric-Based (Tier 3)
Per-question rubrics. Same rubric all candidates.

### TASK-036 [N] L — Code Abstraction Level Analysis (Tier 2)
Algorithm-level vs syntax-level narration. Added to code_quality dimension.

### TASK-037 [N] L — Zone of Proximal Development (Tier 3)
Formal ZPD: alone vs with prompting. All probing targets ZPD edge.

### TASK-038 [N] L — Desirable Difficulty Calibration (Tier 3)
Formal 70% success rate target. KnowledgeAdjacencyMap selection adjusted.

---

## PHASE 5 — Deep Research Fixes

### TASK-039 [N] M — Struggle Reward in Scoring (Tier 1)
Correct after struggle: +0.5 exchange score bonus.

### TASK-040 [N] M — Rapport Building Protocol (Tier 3)
Genuine curiosity in opening 2-3 exchanges. Primes thinking AND builds safety.

### TASK-041 [N] M — Evaluation Dimension Independence (Tier 3)
Refactor 6 dimensions to be truly independent. Separate algorithm + efficiency.

### TASK-042 [N] L — Interleaved Topic Assessment (Tier 3)
After topic A + B: return to A variant. Measures generalization.

### TASK-043 [N] L — Formative Feedback Mid-Interview (Tier 4)
Occasional "perspective" without answer.

### TASK-044 [N] L — Full Socratic Question Type Distribution (Tier 2)
Track question types. Target: Type 2=40%, Type 5=25%, Type 1=20%.

### TASK-045 [N] M — Observer Effect Detection (Tier 3)
High anxiety + correct when given space = observer effect. Score adjustment.

### TASK-046 [N] L — Cross-Turn Pattern Analysis (Tier 4)
Patterns across turns: meta-patterns are highly diagnostic.

### TASK-047 [N] M — Report Enhancement — Research-Grounded (Tier 1)
**Requires:** TASK-020 through TASK-029
Surface: anxiety adjustment, productive struggle, learning agility, Bloom's levels, hypothesis summary, reasoning pattern, psychological safety.

### TASK-048 [N] M — Update SPEC_DEV.md + Create PR
**Requires:** All tasks complete
Update SPEC_DEV.md for full new architecture. Final compile + build. Create PR to master.
