# AI Interview Platform — Complete Developer Specification

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [System Architecture](#2-system-architecture)
3. [Tech Stack](#3-tech-stack)
4. [Backend Package Structure](#4-backend-package-structure)
5. [Conversation Layer — Deep Dive](#5-conversation-layer--deep-dive)
6. [Interview Memory & Brain Architecture](#6-interview-memory--brain-architecture)
7. [LLM Provider Layer](#7-llm-provider-layer)
8. [WebSocket Protocol](#8-websocket-protocol)
9. [Database Schema](#9-database-schema)
10. [Authentication & Security](#10-authentication--security)
11. [Code Execution (Judge0)](#11-code-execution-judge0)
12. [Report Generation & Evaluation](#12-report-generation--evaluation)
13. [Interview Objectives System](#13-interview-objectives-system)
14. [Knowledge Adjacency Map](#14-knowledge-adjacency-map)
15. [Interview Flow — End to End](#15-interview-flow--end-to-end)
16. [API Reference](#16-api-reference)
17. [Configuration Reference](#17-configuration-reference)
18. [Feature Flag — Natural Interviewer](#18-feature-flag--natural-interviewer)
19. [Scientific Research Basis](#19-scientific-research-basis)
20. [Architecture Decisions](#20-architecture-decisions)
21. [Known Limitations](#21-known-limitations)
22. [Glossary](#22-glossary)

---

Legend for feature flag markers:
- 🔵 Old system only (`use-new-brain: false`)
- 🟢 New system only (`use-new-brain: true`)
- ⚪ Both systems

---

## 1. Project Overview

AI-powered mock interview platform. Candidates take realistic technical interviews (Coding, DSA, Behavioral, System Design) with an adaptive AI interviewer that builds a mental model of each candidate, tracks hypotheses about their understanding, and probes systematically.

Two interview systems coexist behind a feature flag (`interview.use-new-brain`). The old system uses stage-based rules. The new system uses a cognitive brain architecture grounded in 10 academic research domains.

---

## 2. System Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  FRONTEND (React + TypeScript, port 3000)                    │
│  Pages: Dashboard, InterviewSetup, Interview, Report         │
│  Hooks: useInterviewSocket (WS), useInterviews (REST)        │
│  Editor: Monaco | Charts: Recharts | Auth: Clerk             │
└───────────────┬──────────────────────────┬───────────────────┘
                │ REST (Axios)             │ WebSocket
                ▼                          ▼
┌──────────────────────────────────────────────────────────────┐
│  BACKEND (Kotlin + Spring WebFlux, port 8080)                │
│                                                              │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │ conversation/                                           │ │
│  │                                                         │ │
│  │ ⚪ ConversationEngine ──┬── 🔵 InterviewerAgent         │ │
│  │    (routes by flag)     │   🔵 PromptBuilder            │ │
│  │                         │   🔵 AgentOrchestrator        │ │
│  │                         │   🔵 SmartOrchestrator        │ │
│  │                         │   🔵 ReasoningAnalyzer        │ │
│  │                         │   🔵 FollowUpGenerator        │ │
│  │                         │   🔵 HintGenerator            │ │
│  │                         │   🔵 StageReflectionAgent     │ │
│  │                         │   🔵 CandidateModelUpdater    │ │
│  │                         │                               │ │
│  │                         └── 🟢 brain/                   │ │
│  │                             🟢 TheConductor             │ │
│  │                             🟢 NaturalPromptBuilder     │ │
│  │                             🟢 TheAnalyst               │ │
│  │                             🟢 TheStrategist            │ │
│  │                             🟢 BrainService             │ │
│  │                             🟢 BrainFlowGuard           │ │
│  │                             🟢 BrainObjectivesRegistry  │ │
│  │                             🟢 OpenQuestionTransformer  │ │
│  │                                                         │ │
│  │ objectives/              knowledge/                     │ │
│  │ ⚪ ObjectiveTracker      🟢 KnowledgeAdjacencyMap       │ │
│  │ ⚪ FlowGuard                                            │ │
│  │ ⚪ InterviewObjectives                                  │ │
│  └─────────────────────────────────────────────────────────┘ │
│                                                              │
│  interview/     report/      user/       shared/ai/          │
│  ⚪ Services    ⚪ EvalAgent  ⚪ Auth     ⚪ LlmProvider      │
│  ⚪ WS Handler  ⚪ ReportSvc  ⚪ Usage    ⚪ LlmRegistry      │
│  ⚪ Redis Mem   ⚪ Report DTO ⚪ Bootstrap ⚪ ModelConfig      │
│                                          ⚪ OpenAiProvider    │
└──────────────────┬────────────────┬──────────────────────────┘
                   │                │
         ┌─────────┘        ┌──────┘
         ▼                  ▼
┌────────────────┐  ┌───────────────┐  ┌───────────────┐
│  PostgreSQL    │  │  Redis        │  │  Judge0 CE    │
│  (R2DBC async) │  │  Memory+Brain │  │  (Docker)     │
│  14 migrations │  │  2 keyspaces  │  │  Code sandbox │
└────────────────┘  └───────────────┘  └───────────────┘
         │                  │
         │    ┌─────────────┘
         ▼    ▼
┌────────────────────────┐
│  OpenAI API            │
│  gpt-4o (interviewer)  │
│  gpt-4o-mini (bg)      │
│  Fallback: Groq/Gemini │
└────────────────────────┘
```

---

## 3. Tech Stack

### Backend

| Technology | Version | Purpose |
|---|---|---|
| Kotlin | 1.9.25 | Primary language |
| Spring Boot | 3.5.9 | Framework |
| Spring WebFlux | 3.5.9 | Reactive HTTP + WebSocket |
| R2DBC PostgreSQL | managed | Async database driver |
| Flyway | managed | DB migrations (JDBC) |
| Redis (reactive) | managed | Session memory + brain state |
| kotlinx-coroutines | managed | Async programming |
| Jackson Kotlin | managed | JSON serialization |
| OpenAI Java SDK | 4.26.0 | LLM integration |
| nimbus-jose-jwt | 10.8 | JWT validation |
| mockk | 1.13.12 | Test mocking |
| Java | 21 | Runtime |

### Frontend

| Technology | Version | Purpose |
|---|---|---|
| React | 18.3.1 | UI framework |
| TypeScript | 5.6.2 | Type safety |
| Vite | 5.4.10 | Build tool |
| TanStack Query | 5.90.21 | Data fetching + caching |
| Clerk React | 5.61.3 | Authentication |
| Monaco Editor | 4.7.0 | Code editor |
| Recharts | 3.8.0 | Score charts |
| Tailwind CSS | 4.2.1 | Styling |
| Axios | 1.13.6 | HTTP client |
| Radix UI | various | UI primitives (shadcn) |
| Playwright | 1.58.2 | E2E testing |

### External Services

| Service | Purpose | Config Key |
|---|---|---|
| OpenAI | GPT-4o interviewer, GPT-4o-mini background | `llm.openai.api-key` |
| Clerk.dev | JWT auth | `clerk.jwks-url` |
| Judge0 CE | Code execution sandbox | `judge0.base-url` |
| PostgreSQL | Persistent storage | `spring.r2dbc.url` |
| Redis | Session memory + brain cache | `spring.data.redis.url` |

---

## 4. Backend Package Structure

### com.aiinterview.conversation ⚪

**ConversationEngine** ⚪ @Service
Purpose: Central orchestrator. Routes to old or new system based on feature flag.
Methods:
- `handleCandidateMessage(sessionId, content)` — main entry. If `useNewBrain` and brain exists: calls `handleWithBrain()`. Else: old pipeline.
- `handleWithBrain(sessionId, content, brain)` — 🟢 new path. Calls TheConductor, launches TheAnalyst + TheStrategist + FlowGuard in background.
- `startInterview(sessionId)` — sends greeting, initializes brain if flag on.
- `forceEndInterview(sessionId)` — triggers evaluation, cleans up brain.
- `transition(sessionId, state)` — updates Redis + sends STATE_CHANGE WS frame.
- `transitionToNextQuestion(sessionId)` — multi-question progression.
- `cancelSessionScope(sessionId)` — cancels per-session background coroutines.
Depends on: RedisMemoryService, InterviewerAgent, WsSessionRegistry, AgentOrchestrator, SmartOrchestrator, StateContextBuilder, ObjectiveTracker, FlowGuard, CandidateModelUpdater, BrainService, TheConductor, TheAnalyst, TheStrategist, BrainFlowGuard, ReportService.

**InterviewerAgent** 🔵 @Component
Purpose: Streams AI responses using old PromptBuilder. Active when `use-new-brain: false`.
Methods:
- `streamResponse(sessionId, memory, userMessage, objectiveState?)` — builds prompt, streams via LLM, returns response text.
- `hasMeaningfulCode(memory)` — code length > 50, not template.
- CODING GATE: skips LLM for CODING/DSA when no meaningful code.
Depends on: LlmProviderRegistry, ModelConfig, PromptBuilder, WsSessionRegistry, RedisMemoryService, ConversationMessageRepository, StateContextBuilder, ToolContextService.

**PromptBuilder** 🔵 @Component
Purpose: Assembles system prompt from stage rules + memory. Old system.
Methods:
- `buildSystemPrompt(memory, messageType?, stateCtx?, codeDetails?, testResultSummary?, objectiveState?)` — 13-part prompt assembly: BASE_PERSONA, personality, objectives block, situation context, stage rules (fallback), candidate model block, category framework, company style, candidate context, state block, code, tests, question, message type, history, state.
Depends on: None (pure function).

**AgentOrchestrator** 🔵 @Service
Purpose: Handles CodingChallenge/Evaluating transitions + multi-question flow.
Methods: `analyzeAndTransition(sessionId, content)`, `handleQuestionComplete()`.
Depends on: ReasoningAnalyzer, FollowUpGenerator, InterviewerAgent, RedisMemoryService, WsSessionRegistry, ConversationEngine, ReportService.

**SmartOrchestrator** 🔵 @Service
Purpose: LLM-driven background analysis (old system).
Depends on: LlmProviderRegistry, ModelConfig, RedisMemoryService, InterviewSessionRepository, ObjectMapper, StageReflectionAgent.

**ReasoningAnalyzer** 🔵 @Service — Analyzes candidate reasoning via LLM.
**FollowUpGenerator** 🔵 @Service — Generates follow-up questions.
**HintGenerator** ⚪ @Service — Generates progressive hints (levels 1-3).
**StageReflectionAgent** 🔵 @Service — Reflects on stage transitions.
**CandidateModelUpdater** 🔵 @Component — Updates CandidateModel every 2 turns.
**StateContextBuilder** ⚪ @Component — Fetches fresh state from Redis+DB before LLM calls.
**ToolContextService** 🔵 @Component — Stage-specific context (code, tests) for REVIEW stage.
**PersonalityPrompts** 🔵 object — Static personality prompt text.

### com.aiinterview.conversation.brain 🟢

**TheConductor** 🟢 @Component
Purpose: Replaces InterviewerAgent. Reads InterviewerBrain, applies SilenceIntelligence, builds prompt via NaturalPromptBuilder, streams response.
Methods:
- `respond(sessionId, candidateMessage, brain, state)` — silence check → CODING GATE → build prompt → stream → OpenQuestionTransformer → acknowledge tracking → contradiction surfacing.
- `shouldRespond(brain, message, state)` — returns RESPOND / SILENT / WAIT_THEN_RESPOND. BEHAVIORAL always RESPOND.
- `getReassurance(brain)` — type-specific reassurance messages (4 variants per type).
- `detectAndTrackAcknowledgment(sessionId, response)` — prevents phrase repetition.
Fields: `useNewBrain` (from `@Value("${interview.use-new-brain:false}")`).
Depends on: BrainService, NaturalPromptBuilder, LlmProviderRegistry, ModelConfig, WsSessionRegistry, ConversationMessageRepository, InterviewSessionRepository.

**TheAnalyst** 🟢 @Component
Purpose: Single background agent replacing 7 old agents. One gpt-4o-mini call per exchange.
Methods:
- `analyze(sessionId, candidateMessage, aiResponse, brain)` — builds prompt, calls LLM, applies 20 update types to brain.
Updates: CandidateProfile (18 fields), hypotheses (new + updates), claims + contradictions, goals, thought thread, actions, exchange scores, Bloom's levels, topic signal, topic history, challenge rate, ZDP, question types, ownership probes, dismissal probes, cognitive load actions, safety actions, anxiety averages.
Depends on: BrainService, LlmProviderRegistry, ModelConfig, ObjectMapper.

**TheStrategist** 🟢 @Component
Purpose: Meta-cognitive reviewer. Runs every 5 turns.
Methods:
- `review(sessionId, brain)` — reviews full brain, updates InterviewStrategy (approach, tone, time, avoidance, tokens, selfCritique). Abandons stale hypotheses. Queues FORMATIVE_FEEDBACK when struggling.
Depends on: BrainService, LlmProviderRegistry, ModelConfig, ObjectMapper.

**BrainService** 🟢 @Service
Purpose: Redis persistence for InterviewerBrain. Per-session Mutex for atomic updates.
Key: `brain:{sessionId}`, TTL: 3 hours.
Methods: `initBrain()`, `getBrain()`, `getBrainOrNull()`, `updateBrain()` (with Mutex), `deleteBrain()`, plus 20+ convenience update methods (appendThought, addHypothesis, updateHypothesis, addClaim, addContradiction, markGoalComplete, addAction, completeTopAction, updateStrategy, updateCandidateProfile, addExchangeScore, incrementTurnCount, appendTopicToHistory, updateChallengeSuccessRate, updateZdpLevel, recordQuestionType, appendUsedAcknowledgment, updateBloomsLevel, appendTranscriptTurn, incrementFormativeFeedback).
Depends on: ReactiveStringRedisTemplate, ObjectMapper.

**BrainFlowGuard** 🟢 @Component
Purpose: 4-rule safety net. Returns IntendedAction or null.
Rules: (1) problem by turn 4, (2) overtime wrap-up, (3) 8-turn stall nudge, (4) behind-schedule pace warning.

**BrainObjectivesRegistry** 🟢 object
Purpose: Fixed objectives per interview type. CODING 9+3, BEHAVIORAL 8+2, SYSTEM_DESIGN 8+2.
Methods: `forCategory(category)`, `computeBrainInterviewState(brain, remainingMinutes)`.

**NaturalPromptBuilder** 🟢 @Component
Purpose: Builds 13-section prompt from InterviewerBrain state.
Sections: IDENTITY (static) → SITUATION → CANDIDATE (after turn 2) → THOUGHT_THREAD → GOALS + Bloom's → HYPOTHESES (top 2) → CONTRADICTIONS (after turn 5) → STRATEGY → ACTION → CODE (REVIEW) → TESTS → HISTORY (6 turns) → HARD_RULES (static + acknowledgments).

**OpenQuestionTransformer** 🟢 object
Purpose: Prevents anchoring bias. Transforms "Is it X or Y?" → open questions.

**InterviewerBrain** 🟢 data class (30+ fields)
See Section 6.2 for complete field documentation.

### com.aiinterview.conversation.objectives ⚪

**ObjectiveTracker** ⚪ @Component — Marks objectives complete via LLM (old system path).
**FlowGuard** ⚪ @Component — 4-rule safety net for old system (returns String probe).
**InterviewObjectives** ⚪ data classes — Objective, InterviewObjectives, ObjectivesRegistry.
**ObjectiveState** ⚪ — computeObjectiveState(), ObjectiveState data class.

### com.aiinterview.conversation.knowledge 🟢

**KnowledgeAdjacencyMap** 🟢 object
Purpose: Maps demonstrated topics to adjacent unknowns for predictive probing.
12 topic groups, 3+ adjacent topics each. Methods: `getAdjacentTopics()`, `getNextProbe()`, `getNextBestTopic()`, `getAdjacentTopicsForSuccessRate()`, `toHypothesis()`.

### com.aiinterview.interview ⚪

**InterviewController** @RestController `/api/v1/interviews` — startSession, endSession, getSession, listSessions.
**QuestionController** @RestController `/api/v1/questions` — getQuestion, listQuestions, generateQuestions.
**IntegrityController** @RestController `/api/v1/integrity` — reportSignals (tab switch, paste detection).
**InterviewSessionService** @Service — session lifecycle, question selection, usage checks.
**QuestionService** @Service — selectQuestionsForSession, getQuestionById.
**QuestionGeneratorService** @Service — AI question generation via LLM.
**RedisMemoryService** @Service — old system memory (key: `interview:session:{sessionId}:memory`, TTL: 2h). Per-session Mutex. Methods: initMemory, getMemory, updateMemory, appendTranscriptTurn, appendAgentNote, setComplexityDiscussed, setEdgeCasesCovered, updateStage, incrementQuestionIndex, markObjectivesComplete, incrementTurnCount, deleteMemory.
**TranscriptCompressor** @Service — compresses rolling transcript via LLM.
**InterviewWebSocketHandler** @Component — WS message routing. CANDIDATE_MESSAGE, CODE_RUN, CODE_SUBMIT, REQUEST_HINT, END_INTERVIEW, PING.
**WsSessionRegistry** @Service — tracks active WS sessions, sendMessage().

### com.aiinterview.report ⚪

**EvaluationAgent** @Component — LLM evaluation with 60s timeout, optional brain enrichment.
**ReportService** @Service — generateAndSaveReport (idempotent), getReport, listReports, getUserStats.
**ReportController** @RestController `/api/v1` — getReport, listReports, getUserStats.

### com.aiinterview.user ⚪

**AuthController** @RestController `/api/v1/auth` — getUser.
**UserBootstrapService** @Service — getOrCreateUser with Redis cache (5min TTL).
**UsageLimitService** @Service — checkUsageAllowed, incrementUsage, getUsageThisMonth.

### com.aiinterview.shared.ai ⚪

**LlmProvider** interface — complete(LlmRequest): LlmResponse, stream(LlmRequest): Flow<String>.
**LlmProviderRegistry** @Component — primary + fallback provider routing.
**ModelConfig** @ConfigurationProperties — interviewerModel (gpt-4o), backgroundModel (gpt-4o-mini), generatorModel, evaluatorModel.
**OpenAiProvider** @Component — OpenAI SDK integration.
**GroqProvider** @Component — Groq fallback.
**GeminiProvider** @Component — Gemini fallback.

---

## 5. Conversation Layer — Deep Dive

### 5.1 Two Systems (Feature Flag)

When `interview.use-new-brain: false` (default):
1. ConversationEngine.handleCandidateMessage()
2. InterviewerAgent.streamResponse() — builds prompt via PromptBuilder
3. Background: SmartOrchestrator + AgentOrchestrator + ObjectiveTracker + FlowGuard + CandidateModelUpdater
4. Stage machine drives behavior (8 stages: SMALL_TALK → WRAP_UP)

When `interview.use-new-brain: true`:
1. ConversationEngine.handleWithBrain()
2. TheConductor.respond() — builds prompt via NaturalPromptBuilder
3. Background: TheAnalyst (1 call replaces 7 agents) + TheStrategist (every 5 turns)
4. Objectives drive behavior (9-12 goals per interview type)

### 5.2 Message Flow (New System)

```
Candidate sends message
  │
  ▼
1. ConversationEngine.handleCandidateMessage()
2. Check: useNewBrain && brain exists? → handleWithBrain()
3. Persist message to DB + brain transcript
4. Transition → CANDIDATE_RESPONDING
5. Compute InterviewState from objectives + remaining time
6. BrainFlowGuard.check() → queue action if needed
7. BrainService.incrementTurnCount()
8. TheConductor.respond():
   a. SilenceIntelligence: RESPOND / SILENT / WAIT
   b. CODING GATE (CODING/DSA only, no code → canned response)
   c. NaturalPromptBuilder.build() → 13-section prompt
   d. completeTopAction() from queue
   e. LlmProviderRegistry.stream() → AI_CHUNK frames to WS
   f. OpenQuestionTransformer.transform() on response
   g. detectAndTrackAcknowledgment()
   h. markContradictionSurfaced() if applicable
   i. Persist response to DB + brain transcript
9. Transition → AI_ANALYZING
10. Background (fire-and-forget on per-session scope):
    a. TheAnalyst.analyze() — updates full brain
    b. TheStrategist.review() — every 5 turns
    c. AgentOrchestrator.analyzeAndTransition() — legacy compatibility
```

### 5.3 InterviewerAgent (Old System) 🔵

8-stage progression: SMALL_TALK → PROBLEM_PRESENTED → CLARIFYING → APPROACH → CODING → REVIEW → FOLLOWUP → WRAP_UP.

maxTokensFor() per stage: SMALL_TALK=120, PROBLEM_PRESENTED=50, CLARIFYING=60-100, APPROACH=120, CODING=60, REVIEW=150, FOLLOWUP=150, WRAP_UP=100.

CODING GATE: When stage=CODING, category in (CODING, DSA), and !hasMeaningfulCode → skip LLM, return canned response.

Streaming: 10s timeout → fallback to complete() with backgroundModel.

### 5.4 TheConductor (New System) 🟢

SilenceIntelligence decisions:
- RESPOND: questions (?), help/hint/stuck, done/finished, IDK signals, FlowGuard actions, BEHAVIORAL type
- SILENT: coding phase + message < 10 chars
- WAIT_THEN_RESPOND: approach phase + message > 200 chars + no urgent actions

Token budget: `brain.currentStrategy.recommendedTokens` (60-180), calibrated by TheStrategist.

### 5.5 TheAnalyst — Single Background Agent 🟢

One gpt-4o-mini call per exchange. Outputs JSON with 12 top-level fields:
`candidateProfileUpdate`, `newHypothesis`, `hypothesisUpdates`, `newClaims`, `contradictionFound`, `goalsCompleted`, `thoughtThreadAppend`, `nextAction`, `exchangeScore`, `bloomsLevelUpdate`, `topicSignalUpdate`, `adjacentTopicsToProbe`, `ownershipProbeNeeded`, `zdpUpdate`, `questionType`.

Auto-queued actions on detection: REDUCE_LOAD (cognitive overload), RESTORE_SAFETY (safety < 0.4), PRODUCTIVE_UNKNOWN (IDK signals), MENTAL_SIMULATION (code written), TEST_HYPOTHESIS (new hypothesis), PROBE_DEPTH (dismissal language, ownership gap), WRAP_UP_TOPIC (signal > 0.8).

### 5.6 NaturalPromptBuilder — 13 Sections 🟢

| # | Section | Content | When |
|---|---------|---------|------|
| 1 | INTERVIEWER_IDENTITY | 5-line static identity | Always |
| 2 | SITUATION | Turn, time, phase, goals, challenge calibration | Always |
| 3 | CANDIDATE | Signal, thinking, state, pressure, trajectory, anxiety, flow, safety, reasoning, avoidance | After turn 2 |
| 4 | THOUGHT_THREAD | Compressed history + active thread (500 chars) | When non-empty |
| 5 | GOALS | Completed, remaining (top 3), Bloom's depth | When goals remain |
| 6 | HYPOTHESES | Top 2 open with test strategy | When hypotheses exist |
| 7 | CONTRADICTIONS | Top 1 unsurfaced with surfacing guidance | After turn 5 |
| 8 | STRATEGY | Approach, tone, avoidance from TheStrategist | When strategy set |
| 9 | ACTION | Top priority action from queue | When action queued |
| 10 | CODE | Candidate code (max 2000 chars) | REVIEW phase, CODING/DSA |
| 11 | TESTS | Test results + "don't reveal" rule | When available |
| 12 | HISTORY | Last 6 turns with `<candidate_input>` tags | Always |
| 13 | HARD_RULES | Non-negotiable rules + acknowledgment tracking | Always |

---

## 6. Interview Memory & Brain Architecture

### 6.1 Old System: InterviewMemory 🔵

Redis key: `interview:session:{sessionId}:memory`, TTL: 2 hours.
Per-session kotlinx Mutex for atomic updates.

| Field | Type | Default | Purpose |
|---|---|---|---|
| sessionId | UUID | — | Session identity |
| userId | UUID | — | User identity |
| state | String | — | WS-level state (InterviewState sealed class) |
| category | String | — | CODING/DSA/BEHAVIORAL/SYSTEM_DESIGN |
| personality | String | — | Interviewer personality |
| currentQuestion | InternalQuestionDto? | null | Current question |
| candidateAnalysis | CandidateAnalysis? | null | ReasoningAnalyzer output |
| hintsGiven | Int | 0 | Hints used (max 3) |
| followUpsAsked | List<String> | [] | Follow-up questions asked |
| currentCode | String? | null | Code editor content |
| programmingLanguage | String? | null | Selected language |
| rollingTranscript | List<TranscriptTurn> | [] | Last 6 turns |
| earlierContext | String | "" | Compressed older transcript |
| evalScores | EvalScores | zeros | Running scores |
| interviewStage | String | "SMALL_TALK" | 8-stage progression |
| currentQuestionIndex | Int | 0 | Multi-question position |
| totalQuestions | Int | 1 | Total questions |
| targetCompany | String? | null | Target company |
| targetRole | String? | null | Target role |
| experienceLevel | String? | null | Experience level |
| background | String? | null | Background text |
| complexityDiscussed | Boolean | false | Checklist: complexity |
| edgeCasesCovered | Int | 0 | Checklist: edge cases |
| agentNotes | String | "" | SmartOrchestrator notes |
| lastTestResult | TestResultCache? | null | Last test execution |
| completedObjectives | List<String> | [] | Objective tracking |
| turnCount | Int | 0 | Exchange counter |
| pendingProbe | String? | null | Next probe to embed |
| candidateModel | CandidateModel | defaults | Mental model (old) |

### 6.2 New System: InterviewerBrain 🟢

Redis key: `brain:{sessionId}`, TTL: 3 hours.
Per-session kotlinx Mutex in BrainService.

| Field | Type | Default | Purpose |
|---|---|---|---|
| sessionId, userId | UUID | — | Identity |
| candidateProfile | CandidateProfile | defaults | 18-field behavioral model |
| hypothesisRegistry | HypothesisRegistry | empty | Beliefs being tested |
| claimRegistry | ClaimRegistry | empty | Claims + contradictions |
| interviewGoals | InterviewGoals | from registry | Objectives (replaces stages) |
| thoughtThread | ThoughtThread | empty | Running inner monologue |
| currentStrategy | InterviewStrategy | empty | Approach + selfCritique |
| actionQueue | ActionQueue | empty | Prioritized intended actions |
| interviewType | String | — | CODING/BEHAVIORAL/SYSTEM_DESIGN |
| questionDetails | InterviewQuestion | — | Question + ScoringRubric |
| turnCount | Int | 0 | Exchange counter |
| usedAcknowledgments | List<String> | [] | Prevents phrase repetition |
| topicSignalBudget | Map<String, Float> | {} | Information yield per topic |
| bloomsTracker | Map<String, Int> | {} | Bloom's level per topic (1-6) |
| exchangeScores | List<ExchangeScore> | [] | Per-turn independent scores |
| hintOutcomes | List<HintOutcome> | [] | Hint generalization tracking |
| topicHistory | List<String> | [] | Topic order for interleaving |
| challengeSuccessRate | Float | 0.7 | 70% target calibration |
| zdpEdge | Map<String, ZdpLevel> | {} | Zone of Proximal Development |
| questionTypeHistory | List<String> | [] | Socratic type distribution |
| formativeFeedbackGiven | Int | 0 | Formative feedback count |
| currentCode | String? | null | Code editor content |
| programmingLanguage | String? | null | Selected language |
| personality | String | "friendly" | Interviewer personality |
| targetCompany | String? | null | Target company |
| experienceLevel | String? | null | Experience level |
| rollingTranscript | List<BrainTranscriptTurn> | [] | Last 8 turns |
| earlierContext | String | "" | Compressed history |
| hintsGiven | Int | 0 | Hints used |

### 6.3 CandidateProfile (18 fields) 🟢

| Field | Type | Values | Purpose |
|---|---|---|---|
| thinkingStyle | ThinkingStyle | BOTTOM_UP, TOP_DOWN, INTUITIVE, METHODICAL, UNKNOWN | How they approach problems |
| reasoningPattern | ReasoningPattern | SCHEMA_DRIVEN, SEARCH_DRIVEN, UNKNOWN | Expert vs novice signal |
| knowledgeMap | Map<String, Float> | topic → 0.0-1.0 | Topic confidence |
| communicationStyle | CommunicationStyle | VERBOSE, TERSE, CLEAR, CONFUSED, UNKNOWN | How they communicate |
| pressureResponse | PressureResponse | RISES, FREEZES, STEADY, DEFENSIVE, UNKNOWN | Under pressure behavior |
| avoidancePatterns | List<String> | free text | Observed avoidance |
| overallSignal | CandidateSignal | STRONG, SOLID, AVERAGE, STRUGGLING, UNKNOWN | Overall calibration |
| currentState | EmotionalState | CONFIDENT, NERVOUS, STUCK, FLOWING, FRUSTRATED, NEUTRAL | Current emotion |
| anxietyLevel | Float | 0.0-1.0 | Per-turn anxiety |
| avgAnxietyLevel | Float | 0.0-1.0 | Running average |
| flowState | Boolean | true/false | In flow — don't interrupt |
| trajectory | PerformanceTrajectory | IMPROVING, DECLINING, STABLE | Performance direction |
| psychologicalSafety | Float | 0.0-1.0 (default 0.7) | Safety level |
| linguisticPattern | LinguisticPattern | JUSTIFIED_REASONER, ASSERTIVE_GUESSER, HEDGED_UNDERSTANDER, PATTERN_MATCHER, UNKNOWN | Speech pattern |
| abstractionLevel | Int | 1-5 | Code narration depth |
| selfRepairCount | Int | 0+ | Self-corrections observed |
| cognitiveLoadSignal | CognitiveLoad | NOMINAL, ELEVATED, OVERLOADED | Cognitive state |
| unknownHandlingPattern | UnknownHandling | REASONS_FROM_PRINCIPLES, ADMITS_AND_STOPS, PANICS, GUESSES_BLINDLY, UNKNOWN | IDK behavior |

### 6.4 ActionQueue (15 types) 🟢

TEST_HYPOTHESIS, SURFACE_CONTRADICTION, ADVANCE_GOAL, PROBE_DEPTH, REDIRECT, WRAP_UP_TOPIC, END_INTERVIEW, EMOTIONAL_ADJUST, REDUCE_LOAD, MAINTAIN_FLOW, RESTORE_SAFETY, PRODUCTIVE_UNKNOWN, REDUCE_PRESSURE, MENTAL_SIMULATION, FORMATIVE_FEEDBACK.

Each action: id, type, description, priority (1=urgent, 5=low), expiresAfterTurn, source (FLOW_GUARD/HYPOTHESIS/CONTRADICTION/GOAL/META_STRATEGY/COGNITIVE_LOAD/SAFETY/ANALYST), bloomsLevel, isInterleavedVariant, originalTopic.

---

## 7. LLM Provider Layer

### 7.1 LlmProvider Interface

```kotlin
interface LlmProvider {
    suspend fun complete(request: LlmRequest): LlmResponse
    fun stream(request: LlmRequest): Flow<String>
    fun providerName(): String
    suspend fun healthCheck(): Boolean
}
```

### 7.2 LlmRequest

```kotlin
data class LlmRequest(
    val messages: List<LlmMessage>,
    val model: String,
    val maxTokens: Int = 1000,
    val temperature: Double = 0.7,
    val responseFormat: ResponseFormat = TEXT | JSON,
)
```

### 7.3 Model Configuration

| Config Key | Default | Used For |
|---|---|---|
| `llm.resolved.interviewer-model` | gpt-4o | TheConductor/InterviewerAgent streaming |
| `llm.resolved.background-model` | gpt-4o-mini | TheAnalyst, TheStrategist, SmartOrchestrator, ReasoningAnalyzer |
| `llm.resolved.generator-model` | gpt-4o | Question generation |
| `llm.resolved.evaluator-model` | gpt-4o | EvaluationAgent |

### 7.4 Provider Fallback

Primary provider rate limit or unavailable → fallback provider. Configured via `llm.provider` and `llm.fallback-provider`.

---

## 8. WebSocket Protocol

### 8.1 Inbound (Client → Server)

| Type | Fields | Handler |
|---|---|---|
| CANDIDATE_MESSAGE | text, codeSnapshot? | ConversationEngine.handleCandidateMessage() |
| CODE_RUN | code, language, stdin? | CodeExecutionService.runCode() |
| CODE_SUBMIT | code, language, sessionQuestionId? | CodeExecutionService.submitCode() |
| REQUEST_HINT | hintLevel | HintGenerator.generateHint() |
| END_INTERVIEW | reason | ConversationEngine.forceEndInterview() |
| PING | — | Returns PONG |

### 8.2 Outbound (Server → Client)

| Type | Fields | Sent When |
|---|---|---|
| INTERVIEW_STARTED | sessionId, state | WS connected |
| AI_CHUNK | delta, done | Each streaming token + final done=true |
| STATE_CHANGE | state | State transition |
| CODE_RUN_RESULT | stdout, stderr, exitCode | Code run complete |
| CODE_RESULT | status, stdout, stderr, runtimeMs, testResults | Code submit complete |
| HINT_DELIVERED | hint, level, hintsRemaining, refused | Hint generated |
| QUESTION_TRANSITION | questionIndex, questionTitle, questionDescription, codeTemplates? | Next question |
| SESSION_END | reportId | Report generated |
| STATE_SYNC | full state snapshot | Reconnect |
| ERROR | code, message | Error occurred |
| PONG | — | Heartbeat response |

---

## 9. Database Schema

### 9.1 Tables

**interview_sessions**: id, userId, status (PENDING/ACTIVE/COMPLETED/ABANDONED/EXPIRED), type, config (JSONB), startedAt, endedAt, durationSecs, lastHeartbeat, currentStage, integritySignals.

**questions**: id, title, description, type, difficulty, topicTags, examples, constraintsText, testCases, solutionHints, optimalApproach, followUpPrompts, source, category, codeTemplates, functionSignature, timeComplexity, spaceComplexity, evaluationCriteria, slug, generationParams, createdAt, deletedAt.

**session_questions**: id, sessionId, questionId, orderIndex.

**conversation_messages**: id, sessionId, role (AI/CANDIDATE), content, createdAt.

**evaluation_reports**: id, sessionId, userId, overallScore, problemSolvingScore, algorithmScore, codeQualityScore, communicationScore, efficiencyScore, testingScore, strengths (JSONB), weaknesses (JSONB), suggestions (JSONB), narrativeSummary, dimensionFeedback (JSONB), hintsUsed, nextSteps (JSONB), anxietyLevel, anxietyAdjustmentApplied, initiativeScore, learningAgilityScore, researchNotes, completedAt, createdAt.

**code_submissions**: id, sessionQuestionId, userId, code, language, status, judge0Token, testResults (JSONB), runtimeMs, memoryKb, submittedAt.

**users**: id, orgId, clerkUserId, email, fullName, role, subscriptionTier, createdAt.
**organizations**: id, name, type, createdAt.
**org_invitations**: id, orgId, email, role, status, createdAt.
**interview_templates**: id, name, description, config, createdAt.

### 9.2 Migrations (V1-V14)

| Version | File | What Changed |
|---|---|---|
| V1 | create_organizations | organizations table |
| V2 | create_users | users table |
| V3 | create_interview_tables | sessions, questions, session_questions, messages |
| V4 | create_code_and_reports | code_submissions, evaluation_reports |
| V5 | create_misc | interview_templates, org_invitations |
| V6 | extend_questions_table | generation params, complexity, slug, category, templates |
| V7 | extend_evaluation_reports | hints_used, completed_at, dimension_feedback |
| V8 | convert_enums_to_varchar | enum → VARCHAR migration |
| V9 | convert_remaining_enums | complete enum conversion |
| V10 | convert_jsonb_to_text | JSONB → TEXT for R2DBC compatibility |
| V11 | add_session_heartbeat | last_heartbeat column |
| V12 | add_stage_and_templates | current_stage, interview_templates |
| V13 | add_next_steps_and_integrity | next_steps, integrity_signals |
| V14 | add_brain_evaluation_columns | anxiety_level, anxiety_adjustment_applied, initiative_score, learning_agility_score, research_notes |

---

## 10. Authentication & Security

**ClerkJwtAuthFilter** (order -200): Validates JWT from Authorization header via JwksValidator. Skips: /health, /actuator, /ws, /api/v1/code/languages. On success: sets User as principal. On failure: 401.

**RateLimitFilter** (order -150): Redis-based per-user rate limit. Key: `ratelimit:{userId}:{epochMinute}`. Default: 60 req/min.

**WsAuthHandshakeInterceptor**: Validates JWT from WS query param `token`. Stores userId in session attributes.

**UserBootstrapService**: `getOrCreateUser(clerkUserId, email, fullName)`. Redis cache: `user:clerk:{clerkUserId}`, TTL 5 min. Creates org on first user.

---

## 11. Code Execution (Judge0)

**Judge0Client**: REST client. submit() → pollResult() loop (500ms interval, 30s timeout). Base64 encoding. Java class name normalization.

**CodeExecutionService**: `runCode()` — direct execution, result via WS. `submitCode()` — with test cases, results persisted to code_submissions.

Supported languages: Python, Java, JavaScript, C++, Go, Ruby, Rust, TypeScript (via LanguageMap).

---

## 12. Report Generation & Evaluation

### 12.1 Scoring (8 Dimensions) 🟢

| Dimension | Weight | Measures |
|---|---|---|
| problem_solving | 20% | Approach process, breakdown, constraints |
| algorithm_depth | 15% | Understanding WHY (not just correct choice) |
| code_quality | 15% | Readability, naming, structure, abstraction |
| communication | 15% | Thinking process narration |
| efficiency | 10% | Time + space complexity |
| testing | 10% | Verification, edge cases |
| initiative | 10% | Proactivity beyond minimum |
| learning_agility | 5% | In-interview learning rate |

Old system (6 dimensions): problem_solving 25%, algorithm 20%, code_quality 20%, communication 15%, efficiency 10%, testing 10%.

### 12.2 Score Adjustments 🟢

| Adjustment | Condition | Amount |
|---|---|---|
| High anxiety | avgAnxietyLevel > 0.7 | +0.75 all dimensions |
| Moderate anxiety | avgAnxietyLevel > 0.5 | +0.50 all dimensions |
| Productive struggle | selfRepair + correct | +0.50 per exchange |
| Schema-driven | ReasoningPattern.SCHEMA_DRIVEN | +1.0 algorithm |
| High abstraction | abstractionLevel >= 4 | +1.0 code_quality |

### 12.3 Anti-Halo Architecture 🟢

Exchange scores computed independently per turn by TheAnalyst. Dimension scores = recency-weighted average of per-exchange scores. Holistic transcript for narrative only. Exchange scores trusted over impression on conflict.

### 12.4 Brain Enrichment (15 signals) 🟢

Injected into EvaluationAgent prompt when brain is available: confirmed hypotheses, incorrect claims, exchange score summary, anxiety adjustment, productive struggle count, reasoning pattern, linguistic pattern, psychological safety, hint outcomes, Bloom's levels, ZDP edge topics, challenge calibration, interleaving, STAR ownership, scoring rubric.

### 12.5 Report Generation Flow

1. ConversationEngine.forceEndInterview() → Evaluating state
2. ReportService.generateAndSaveReport() (idempotent)
3. Load memory + brain (if exists)
4. EvaluationAgent.evaluate(memory, brain?) → EvaluationResult
5. Compute weighted overallScore
6. Persist EvaluationReport
7. Update session status → COMPLETED
8. UsageLimitService.incrementUsage()
9. Send SESSION_END via WS
10. Delete Redis memory + brain

---

## 13. Interview Objectives System 🟢

### CODING/DSA (9 required + 3 optional)

problem_shared → approach_understood → approach_justified → solution_implemented → complexity_owned → edge_cases_explored → reasoning_depth_assessed → mental_simulation_tested → interview_closed.
Optional: optimization_explored, follow_up_variant, reach_evaluate_level.

### BEHAVIORAL (8 required + 2 optional)

psychological_safety → star_q1_complete → star_q1_ownership → star_q2_complete → star_q2_ownership → star_q3_complete → learning_demonstrated → interview_closed.
Optional: star_q4_complete, situational_judgment.

### SYSTEM_DESIGN (8 required + 2 optional)

problem_shared → requirements_gathered → high_level_design → component_deep_dive → tradeoffs_acknowledged → failure_modes_explored → scalability_addressed → interview_closed.
Optional: alternative_considered, data_model_defined.

---

## 14. Knowledge Adjacency Map 🟢

12 topic groups: hash_map_usage, bfs_algorithm, recursion_correct, dp_pattern_recognized, binary_tree_traversal, time_complexity_stated, sorting_algorithm, two_pointer_pattern, high_level_design_done, requirements_gathered, gave_action, star_situation_given.

Each has 3+ adjacent topics with: probeQuestion (open, non-leading), bloomsLevel (1-6), diagnosticValue (0-1), isOrthogonal.

Flow: Candidate demonstrates topic X → getAdjacentTopics(X) → filter by knowledgeMap → prefer orthogonal when signal > 0.6 → toHypothesis() → TEST_HYPOTHESIS action.

---

## 15. Interview Flow — End to End

1. User selects category, difficulty, personality on InterviewSetupPage
2. POST `/api/v1/interviews/sessions` → InterviewSessionService.startSession()
3. Session created in DB (status=PENDING), questions selected, RedisMemoryService.initMemory()
4. Frontend connects WebSocket to `/ws/interview/{sessionId}`
5. WsAuthHandshakeInterceptor validates JWT
6. InterviewWebSocketHandler.handle() → register session
7. ConversationEngine.startInterview() → greeting message + brain init (if flag on)
8. Candidate sends CANDIDATE_MESSAGE
9. ConversationEngine.handleCandidateMessage() or handleWithBrain()
10. AI response streamed via AI_CHUNK frames
11. Background agents update state (TheAnalyst or SmartOrchestrator)
12. Repeat 8-11 until END_INTERVIEW or timer expires
13. ConversationEngine.forceEndInterview() → Evaluating state
14. ReportService.generateAndSaveReport()
15. SESSION_END sent via WS with reportId
16. Frontend navigates to ReportPage

---

## 16. API Reference

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/api/v1/interviews/sessions` | Required | Start interview |
| GET | `/api/v1/interviews/sessions` | Required | List sessions (paginated) |
| GET | `/api/v1/interviews/sessions/{id}` | Required | Get session detail |
| POST | `/api/v1/interviews/sessions/{id}/end` | Required | End interview |
| GET | `/api/v1/reports/{sessionId}` | Required | Get report |
| GET | `/api/v1/reports` | Required | List reports |
| GET | `/api/v1/users/me/stats` | Required | User stats |
| GET | `/api/v1/users/me` | Required | Get user |
| GET | `/api/v1/code/languages` | Public | Supported languages |
| POST | `/api/v1/integrity` | Required | Report integrity signals |
| GET | `/health` | Public | Health check |

---

## 17. Configuration Reference

| Variable | Default | Purpose |
|---|---|---|
| `DATABASE_URL` | r2dbc:postgresql://localhost:5432/aiinterview | R2DBC connection |
| `FLYWAY_URL` | jdbc:postgresql://localhost:5432/aiinterview | Migration connection |
| `REDIS_URL` | redis://localhost:6379 | Redis connection |
| `OPENAI_API_KEY` | — | OpenAI API key |
| `CLERK_JWKS_URL` | — | Clerk JWKS endpoint |
| `CLERK_PUBLISHABLE_KEY` | — | Clerk frontend key |
| `JUDGE0_BASE_URL` | http://localhost:2358 | Judge0 endpoint |
| `JUDGE0_AUTH_TOKEN` | — | Judge0 auth |
| `interview.use-new-brain` | false | Feature flag |
| `interview.free-tier-limit` | 3 | Free interviews/month |
| `interview.redis-ttl-hours` | 2 | Memory TTL |
| `rate-limit.requests-per-minute` | 60 | HTTP rate limit |

---

## 18. Feature Flag — Natural Interviewer

```yaml
interview:
  use-new-brain: false  # default
```

| Flag | Response Path | Background Agents | Prompt System |
|---|---|---|---|
| false | InterviewerAgent | SmartOrchestrator + 6 others | PromptBuilder (stage-based) |
| true | TheConductor | TheAnalyst + TheStrategist | NaturalPromptBuilder (brain-based) |

Migration: false (current) → internal testing → beta 10% → all users → remove old agents (30 days).
Rollback: set false. Immediate. No data migration. Brain Redis keys expire in 3 hours.

---

## 19. Scientific Research Basis 🟢

| Domain | Research | Implementation |
|---|---|---|
| Cognitive Science | Sweller 1988, Chi 1981 | Cognitive load detection, schema/search reasoning |
| Psycholinguistics | Hyland 1996, Schiffrin 1987 | Anxiety detection, dismissal probing, specificity 1-4 |
| Educational Psychology | Anderson 2001, Vygotsky 1978, Feuerstein 1980, Bjork 1994 | Bloom's tracker, ZDP targeting, hint generalization, 70% calibration |
| Behavioral Science | Thorndike 1920, Kahneman 1974, Behroozi 2019 | Anti-halo scoring, anchoring prevention, observer effect |
| Affective Computing | Picard 1997, Lupien 2007 | Anxiety adjustment (+0.5/+0.75) |
| Org Psychology | Schmidt/Hunter 1998, Campion 1994 | Initiative + learning agility, structured objectives |
| Information Theory | Shannon 1948, Cronbach 1951 | Signal depletion, dimension independence |
| Neuroscience | Bjork 1994, Kornell/Bjork 2008 | Productive struggle bonus, topic interleaving |
| Social Psychology | Edmondson 1999, Bernieri 1996 | Psychological safety, rapport building |
| CS Research | Brooks 1983, Wiedenbeck 1991 | Abstraction levels 1-5, mental simulation |

---

## 20. Architecture Decisions

| Decision | Why | Trade-off |
|---|---|---|
| Feature flag (not rewrite) | Zero-risk rollout, A/B testing | Both systems maintained temporarily |
| Per-session Mutex (kotlinx) | GET→modify→SET not atomic | Serializes writes per session (not across) |
| Per-session CoroutineScope | Singleton scope leaks on hung LLM calls | ConcurrentHashMap overhead per session |
| 1 analyst replaces 7 agents | Single LLM call, coherent brain update | Larger prompt, single point of failure |
| Exchange scores primary | Prevents halo effect from early impression | More LLM calls (1 per exchange for scoring) |
| Pending eval (not 3.0 default) | Default 3.0 was product-destroying | Frontend must handle pending state |
| XML tags for candidate input | Prompt injection mitigation | ~10 extra tokens per prompt |
| Open question transformer | Prevents anchoring bias | May occasionally transform valid binary questions |

---

## 21. Known Limitations

| Issue | Location | Impact | Fix Path |
|---|---|---|---|
| Judge0 privileged Docker | docker-compose | Container escape risk | Separate VM or gVisor |
| JWT in WS query param | WsAuthHandshakeInterceptor | Token in logs/history | Short-lived ticket endpoint |
| No circuit breakers | LlmProviderRegistry | Slow fail on outage | Resilience4j |
| Default 3.0 on eval timeout | EvaluationAgent | Misleading scores | Pending state + retry |
| No structured LLM logging | All agents | Silent failures | LlmCallLogger + Micrometer |
| Brain size unbounded | BrainService | Redis memory growth | Max field sizes, compression |
| No automated testing of brain | conversation/brain/ | Regression risk | Unit + integration tests |

---

## 22. Glossary

| Term | Definition |
|---|---|
| InterviewerBrain | 🟢 Unified cognitive state. 30+ fields replacing InterviewMemory. Redis key: `brain:{sessionId}`. |
| InterviewMemory | 🔵 Session state for old system. Redis key: `interview:session:{sessionId}:memory`. |
| TheConductor | 🟢 Real-time response generator. Replaces InterviewerAgent. |
| TheAnalyst | 🟢 Background agent (1 call/exchange). Replaces 7 old agents. |
| TheStrategist | 🟢 Meta-cognitive reviewer (every 5 turns). |
| CandidateProfile | 🟢 18-field behavioral model within InterviewerBrain. |
| HypothesisRegistry | 🟢 Beliefs being tested about candidate knowledge. Max 5 open. |
| ClaimRegistry | 🟢 Specific technical claims + detected contradictions. |
| ActionQueue | 🟢 Prioritized intended actions (15 types). Consumed by TheConductor. |
| ThoughtThread | 🟢 Running inner monologue. Extractive compression at 600 chars. |
| InterviewStrategy | 🟢 Current approach, tone, avoidance, selfCritique. Updated every 5 turns. |
| ObjectiveState | Computed state from goals + time. Phase labels are informational only. |
| FlowGuard | 4-rule safety net. Old: returns String. New: returns IntendedAction. |
| SilenceIntelligence | 🟢 3-way decision in TheConductor: RESPOND / SILENT / WAIT_THEN_RESPOND. |
| OpenQuestionTransformer | 🟢 Converts leading/binary questions to open ones. Prevents anchoring. |
| KnowledgeAdjacencyMap | 🟢 12 topic groups for predictive probing. Maps known → adjacent unknown. |
| ExchangeScore | 🟢 Per-turn score (dimension, score, evidence, bloomsLevel). Anti-halo. |
| ZdpLevel | 🟢 Zone of Proximal Development. canDoAlone / canDoWithPrompt / cannotDo. |
| ScoringRubric | 🟢 Per-question scoring checklist generated from optimalApproach. |
| use-new-brain | Feature flag. false = old system, true = brain system. |
| backgroundModel | gpt-4o-mini. Used by TheAnalyst, TheStrategist, all background agents. |
| interviewerModel | gpt-4o. Used by TheConductor and InterviewerAgent for streaming. |
| fire-and-forget | Background coroutine launched on per-session scope. Never blocks response. |
