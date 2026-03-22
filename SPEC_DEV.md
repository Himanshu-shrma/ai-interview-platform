# AI Interview Platform — Complete Developer Specification

**Version:** 2.0 — Natural Interviewer Architecture
**Last Updated:** 2026-03-22
**Branch:** feature/natural-interviewer
**Status:** Brain architecture only (old agents removed)

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [System Architecture](#2-system-architecture)
3. [Tech Stack](#3-tech-stack)
4. [Backend Packages](#4-backend-packages)
5. [Auth & Security](#5-auth--security)
6. [Shared / LLM Layer](#6-shared--llm-layer)
7. [Interview Package](#7-interview-package)
8. [Conversation Package](#8-conversation-package)
9. [Brain Package](#9-brain-package)
10. [Objectives & Knowledge](#10-objectives--knowledge)
11. [Report Package](#11-report-package)
12. [User Package](#12-user-package)
13. [Code Execution](#13-code-execution)
14. [WebSocket Protocol](#14-websocket-protocol)
15. [Database Schema](#15-database-schema)
16. [Key Data Flows](#16-key-data-flows)
17. [Frontend Architecture](#17-frontend-architecture)
18. [API Reference](#18-api-reference)
19. [Configuration](#19-configuration)
20. [Evaluation System](#20-evaluation-system)
21. [Scientific Research Basis](#21-scientific-research-basis)
22. [Architecture Decisions](#22-architecture-decisions)
23. [Known Limitations](#23-known-limitations)
24. [Glossary](#24-glossary)

---

## 1. Project Overview

AI-powered mock interview platform. Brain architecture with three agents: TheConductor (real-time responses via gpt-4o), TheAnalyst (background analysis via gpt-4o-mini after every exchange), TheStrategist (meta-review every 5 turns via gpt-4o-mini). 48 research-grounded tasks implemented across 5 phases.

Old agent system (InterviewerAgent, PromptBuilder, AgentOrchestrator, SmartOrchestrator, ReasoningAnalyzer, FollowUpGenerator, StageReflectionAgent, CandidateModelUpdater, StateContextBuilder, ToolContextService, TranscriptCompressor) has been deleted. ConversationEngine routes exclusively through the brain.

84 Kotlin files. 38 frontend files. 14 DB migrations. Spring Boot 3.5.9 + Kotlin 1.9.25 + React 18.3.1.

---

## 2. System Architecture

```
Frontend (React 18, port 3000)
  Pages: Dashboard, InterviewSetup, Interview, Report
  Hooks: useInterviewSocket (WS), useInterviews (REST)
  Editor: Monaco | Charts: Recharts | Auth: Clerk
    │ REST (Axios)              │ WebSocket
    ▼                           ▼
Backend (Kotlin + Spring WebFlux, port 8080)
  ┌─ Auth: ClerkJwtAuthFilter (order -200) ──────────────┐
  │  RateLimitFilter (order -150)                         │
  ├──────────────────────────────────────────────────────-┤
  │  ConversationEngine (central orchestrator)            │
  │    ├── TheConductor (real-time, gpt-4o streaming)     │
  │    ├── TheAnalyst (background, gpt-4o-mini)           │
  │    ├── TheStrategist (every 5 turns, gpt-4o-mini)     │
  │    ├── BrainFlowGuard (4 safety rules)                │
  │    └── BrainService → Redis brain:{sessionId} [3h]    │
  │  HintGenerator (WS REQUEST_HINT handler)              │
  │  InterviewState (sealed class, WS state machine)      │
  ├──────────────────────────────────────────────────────-┤
  │  InterviewSessionService | RedisMemoryService         │
  │  EvaluationAgent | ReportService                      │
  │  CodeExecutionService | Judge0Client                  │
  │  UserBootstrapService | UsageLimitService              │
  └──────────────────────────────────────────────────────-┘
    │              │              │              │
    ▼              ▼              ▼              ▼
 PostgreSQL     Redis          Judge0 CE     OpenAI API
 (R2DBC)      2 keyspaces    (Docker)      gpt-4o/4o-mini
 14 migrations  brain: + memory:
```

---

## 3. Tech Stack

### Backend (pom.xml)

| Technology | Version | Purpose |
|---|---|---|
| Spring Boot | 3.5.9 | Framework |
| Kotlin | 1.9.25 | Language |
| Java | 21 | Runtime |
| Spring WebFlux | BOM | Reactive HTTP + WS |
| R2DBC PostgreSQL | BOM | Async DB driver |
| Flyway | BOM | DB migrations (JDBC) |
| Redis Reactive | BOM | Session cache |
| kotlinx-coroutines | BOM | Async |
| Jackson Kotlin | BOM | JSON |
| OpenAI Java SDK | 4.26.0 | LLM integration |
| nimbus-jose-jwt | 10.8 | JWT validation |
| mockk | 1.13.12 | Test mocking |

### Frontend (package.json)

| Technology | Version | Purpose |
|---|---|---|
| React | 18.3.1 | UI framework |
| TypeScript | 5.6.2 | Type safety |
| Vite | 5.4.10 | Build tool |
| TanStack Query | 5.90.21 | Data fetching |
| Clerk React | 5.61.3 | Auth |
| Monaco Editor | 4.7.0 | Code editor |
| Recharts | 3.8.0 | Charts |
| Tailwind CSS | 4.2.1 | Styling |
| Axios | 1.13.6 | HTTP |
| react-router-dom | 7.13.1 | Routing |
| Playwright | 1.58.2 | E2E tests |

---

## 4. Backend Packages

| Package | Files | Purpose |
|---|---|---|
| `com.aiinterview` | 1 | Application.kt entry point |
| `com.aiinterview.auth` | 5 | JWT validation, rate limiting, security config |
| `com.aiinterview.shared` | 3 | CORS, health, response filters |
| `com.aiinterview.shared.ai` | 8 | LLM interface, registry, providers, config |
| `com.aiinterview.shared.ai.providers` | 3 | OpenAI, Groq, Gemini |
| `com.aiinterview.shared.domain` | 1 | Enums (8 enums) |
| `com.aiinterview.interview.controller` | 3 | REST controllers |
| `com.aiinterview.interview.dto` | 3 | Request/response DTOs |
| `com.aiinterview.interview.model` | 5 | DB entities |
| `com.aiinterview.interview.repository` | 5 | R2DBC repositories |
| `com.aiinterview.interview.service` | 5 | Session, memory, questions |
| `com.aiinterview.interview.ws` | 5 | WebSocket handler, registry, messages |
| `com.aiinterview.conversation` | 3 | ConversationEngine, HintGenerator, InterviewState |
| `com.aiinterview.conversation.brain` | 9 | Brain architecture (core) |
| `com.aiinterview.conversation.knowledge` | 1 | KnowledgeAdjacencyMap |
| `com.aiinterview.report.controller` | 1 | Report REST |
| `com.aiinterview.report.dto` | 1 | Report DTOs |
| `com.aiinterview.report.model` | 1 | EvaluationReport entity |
| `com.aiinterview.report.repository` | 1 | Report repository |
| `com.aiinterview.report.service` | 2 | EvaluationAgent, ReportService |
| `com.aiinterview.user.controller` | 1 | Auth REST |
| `com.aiinterview.user.dto` | 1 | User DTO |
| `com.aiinterview.user.model` | 3 | User, Org, Invitation entities |
| `com.aiinterview.user.repository` | 3 | User repositories |
| `com.aiinterview.user.service` | 2 | Bootstrap, usage limits |
| `com.aiinterview.code` | 2 | Judge0 client, language map |
| `com.aiinterview.code.controller` | 1 | Code REST |
| `com.aiinterview.code.model` | 1 | CodeSubmission entity |
| `com.aiinterview.code.repository` | 1 | Code repository |
| `com.aiinterview.code.service` | 1 | Code execution |

---

## 5. Auth & Security

**ClerkJwtAuthFilter** (@Component, order -200) — Validates JWT via JwksValidator. Skips: /health, /actuator, /ws, /api/v1/code/languages. On success: sets User as authentication principal. On failure: 401.

**RateLimitFilter** (@Component, order -150) — Redis key: `ratelimit:{userId}:{epochMinute}`. Default: 60 req/min. On exceed: 429.

**WsAuthHandshakeInterceptor** — Validates JWT from WS query param `token`. Stores userId in session attributes. Thread-safe session tracking via ConcurrentHashMap.

**JwksCache** — Caches JWKS from Clerk. TTL: `clerk.jwks-cache-ttl-minutes` (default 60).

**SecurityConfig** — Permits: /health, /actuator/**, /ws/**, /api/v1/code/languages. All else requires auth.

---

## 6. Shared / LLM Layer

### LlmProvider Interface
```kotlin
interface LlmProvider {
    suspend fun complete(request: LlmRequest): LlmResponse
    fun stream(request: LlmRequest): Flow<String>
    fun providerName(): String
    suspend fun healthCheck(): Boolean
}
```

### LlmProviderRegistry
Primary + fallback routing. On rate limit or unavailable: tries fallback. Stream: `Flow<String>` with catch → fallback to complete().

### ModelConfig (@ConfigurationProperties)
| Key | Default | Used By |
|---|---|---|
| interviewerModel | gpt-4o | TheConductor |
| backgroundModel | gpt-4o-mini | TheAnalyst, TheStrategist, HintGenerator |
| generatorModel | gpt-4o | QuestionGeneratorService |
| evaluatorModel | gpt-4o | EvaluationAgent |

### Providers
**OpenAiProvider** — OpenAI Java SDK 4.26.0. **GroqProvider** — Groq API. **GeminiProvider** — Google Gemini API.

---

## 7. Interview Package

### InterviewSessionService (@Service)
Methods: `startSession(user, config)` — creates session + selects questions + inits memory. `endSession(sessionId, userId)`. `getSession(sessionId, userId)`. `listSessions(userId, page, size)`. Questions per category: CODING/DSA=2, BEHAVIORAL=3, SYSTEM_DESIGN=1.

### RedisMemoryService (@Service)
Key: `interview:session:{sessionId}:memory`. TTL: 2h. Per-session Mutex. Methods: `initMemory()`, `getMemory()`, `updateMemory()` (atomic with Mutex), `appendTranscriptTurn()` (extractive compression when > 6 turns), `deleteMemory()`, `appendAgentNote()`, `setComplexityDiscussed()`, `setEdgeCasesCovered()`, `updateStage()`, `incrementQuestionIndex()`, `markObjectivesComplete()`, `incrementTurnCount()`.

### InterviewMemory (data class)
30+ fields including: sessionId, userId, state, category, personality, currentQuestion, candidateAnalysis, hintsGiven, currentCode, programmingLanguage, rollingTranscript, earlierContext, evalScores, interviewStage, currentQuestionIndex, totalQuestions, targetCompany, experienceLevel, complexityDiscussed, edgeCasesCovered, agentNotes, lastTestResult, completedObjectives, turnCount, pendingProbe, candidateModel.

### InterviewWebSocketHandler (@Component)
Handles: CANDIDATE_MESSAGE (→ ConversationEngine), CODE_RUN (→ CodeExecutionService.runCode), CODE_SUBMIT (→ CodeExecutionService.submitCode), CODE_UPDATE (→ update memory), REQUEST_HINT (→ HintGenerator), END_INTERVIEW (→ ConversationEngine.forceEndInterview), PING (→ PONG). Per-session rate limit: 1 msg/sec via messageCooldowns map.

### WsSessionRegistry (@Service)
Tracks active sessions. `register(sessionId, sink)`, `deregister(sessionId)`, `sendMessage(sessionId, message)` → serializes to JSON → emits to Sinks.Many.

---

## 8. Conversation Package

### ConversationEngine (@Service)
Central orchestrator. Dependencies: RedisMemoryService, WsSessionRegistry, ConversationMessageRepository, SessionQuestionRepository, InterviewSessionRepository, QuestionService, ObjectMapper, ReportService (@Lazy), BrainService, TheConductor, TheAnalyst, TheStrategist, BrainFlowGuard.

**handleCandidateMessage(sessionId, content)**:
1. Load brain via BrainService.getBrainOrNull()
2. If null: send SESSION_ERROR, return
3. Persist message to DB + brain transcript + old memory transcript
4. Transition → CANDIDATE_RESPONDING
5. Calculate remaining minutes from session.startedAt
6. Compute InterviewState from brain objectives
7. BrainFlowGuard.check() → add action if needed
8. BrainService.incrementTurnCount()
9. TheConductor.respond() → streams AI response
10. Transition → AI_ANALYZING
11. Background (fire-and-forget): TheAnalyst.analyze()
12. Background (every 5 turns): TheStrategist.review()

**startInterview(sessionId)**:
1. Load memory from RedisMemoryService
2. Transition → QUESTION_PRESENTED
3. Send personality-based greeting via AI_CHUNK frames
4. Initialize brain via BrainService.initBrain()
5. Persist greeting to transcript

**forceEndInterview(sessionId)**:
1. Transition → EVALUATING
2. Background: ReportService.generateAndSaveReport()
3. Finally: BrainService.deleteBrain() + cancelSessionScope()

Per-session CoroutineScope (SupervisorJob + Dispatchers.IO). @PreDestroy cancels all.

### HintGenerator (@Service)
Generates hints at levels 1-3. Used by InterviewWebSocketHandler for REQUEST_HINT.

### InterviewState (sealed class)
Variants: QuestionPresented, CandidateResponding, AiAnalyzing, FollowUp, CodingChallenge, QuestionTransition, Evaluating, InterviewEnd, Expired. `toString(state)` converts to WS-compatible string.

---

## 9. Brain Package

### InterviewerBrain (data class, 30+ fields)

| Field | Type | Default | Updated By |
|---|---|---|---|
| sessionId | UUID | — | init |
| userId | UUID | — | init |
| candidateProfile | CandidateProfile | defaults | TheAnalyst |
| hypothesisRegistry | HypothesisRegistry | empty | TheAnalyst |
| claimRegistry | ClaimRegistry | empty | TheAnalyst |
| interviewGoals | InterviewGoals | from registry | TheAnalyst |
| thoughtThread | ThoughtThread | empty | TheAnalyst |
| currentStrategy | InterviewStrategy | empty | TheStrategist |
| actionQueue | ActionQueue | empty | TheAnalyst, FlowGuard |
| interviewType | String | — | init |
| questionDetails | InterviewQuestion | — | init |
| turnCount | Int | 0 | BrainService |
| usedAcknowledgments | List<String> | [] | TheConductor |
| topicSignalBudget | Map<String, Float> | {} | TheAnalyst |
| bloomsTracker | Map<String, Int> | {} | TheAnalyst |
| exchangeScores | List<ExchangeScore> | [] | TheAnalyst |
| hintOutcomes | List<HintOutcome> | [] | TheAnalyst |
| topicHistory | List<String> | [] | TheAnalyst |
| challengeSuccessRate | Float | 0.7 | TheAnalyst |
| zdpEdge | Map<String, ZdpLevel> | {} | TheAnalyst |
| questionTypeHistory | List<String> | [] | TheAnalyst |
| formativeFeedbackGiven | Int | 0 | TheStrategist |
| currentCode | String? | null | WS CODE_UPDATE |
| programmingLanguage | String? | null | init |
| personality | String | "friendly" | init |
| rollingTranscript | List<BrainTranscriptTurn> | [] | BrainService |

### CandidateProfile (18 fields)

| Field | Type | Enums |
|---|---|---|
| thinkingStyle | ThinkingStyle | BOTTOM_UP, TOP_DOWN, INTUITIVE, METHODICAL, UNKNOWN |
| reasoningPattern | ReasoningPattern | SCHEMA_DRIVEN, SEARCH_DRIVEN, UNKNOWN |
| knowledgeMap | Map<String, Float> | topic → 0.0-1.0 |
| communicationStyle | CommunicationStyle | VERBOSE, TERSE, CLEAR, CONFUSED, UNKNOWN |
| pressureResponse | PressureResponse | RISES, FREEZES, STEADY, DEFENSIVE, UNKNOWN |
| overallSignal | CandidateSignal | STRONG, SOLID, AVERAGE, STRUGGLING, UNKNOWN |
| currentState | EmotionalState | CONFIDENT, NERVOUS, STUCK, FLOWING, FRUSTRATED, NEUTRAL |
| anxietyLevel | Float (0-1) | Per-turn |
| avgAnxietyLevel | Float (0-1) | Running average |
| flowState | Boolean | — |
| trajectory | PerformanceTrajectory | IMPROVING, DECLINING, STABLE |
| psychologicalSafety | Float (0-1, default 0.7) | — |
| linguisticPattern | LinguisticPattern | JUSTIFIED_REASONER, ASSERTIVE_GUESSER, HEDGED_UNDERSTANDER, PATTERN_MATCHER, UNKNOWN |
| abstractionLevel | Int (1-5) | 1=syntax, 2=operation, 3=purpose, 4=algorithm, 5=evaluation |
| selfRepairCount | Int | Self-corrections observed |
| cognitiveLoadSignal | CognitiveLoad | NOMINAL, ELEVATED, OVERLOADED |
| unknownHandlingPattern | UnknownHandling | REASONS_FROM_PRINCIPLES, ADMITS_AND_STOPS, PANICS, GUESSES_BLINDLY, UNKNOWN |

### ActionType (15 values)
TEST_HYPOTHESIS, SURFACE_CONTRADICTION, ADVANCE_GOAL, PROBE_DEPTH, REDIRECT, WRAP_UP_TOPIC, END_INTERVIEW, EMOTIONAL_ADJUST, REDUCE_LOAD, MAINTAIN_FLOW, RESTORE_SAFETY, PRODUCTIVE_UNKNOWN, REDUCE_PRESSURE, MENTAL_SIMULATION, FORMATIVE_FEEDBACK.

### BrainService (@Service)
Redis key: `brain:{sessionId}`. TTL: 3h. Per-session Mutex. Methods: initBrain, getBrain, getBrainOrNull, updateBrain (atomic), deleteBrain, + 20 convenience methods (appendThought, addHypothesis, updateHypothesis, addClaim, addContradiction, markContradictionSurfaced, markGoalComplete, markGoalsComplete, addAction, completeTopAction, updateStrategy, updateCandidateProfile, addExchangeScore, incrementTurnCount, appendUsedAcknowledgment, updateBloomsLevel, appendTranscriptTurn, appendTopicToHistory, updateChallengeSuccessRate, updateZdpLevel, recordQuestionType, incrementFormativeFeedback). @PreDestroy cleans mutexes.

### TheConductor (@Component)
**respond(sessionId, candidateMessage, brain, state)**:
1. shouldRespond() → RESPOND/SILENT/WAIT_THEN_RESPOND
2. SILENT: return "" (coding phase, short message)
3. WAIT: delay 2s, send reassurance
4. RESPOND: generateResponse()
5. CODING GATE (CODING/DSA only, no code → canned response)
6. NaturalPromptBuilder.build() → 13-section prompt
7. completeTopAction() from queue
8. tryStreaming() → AI_CHUNK frames (10s timeout)
9. Fallback: tryFallback() with backgroundModel
10. OpenQuestionTransformer.transform() on response
11. detectAndTrackAcknowledgment()
12. markContradictionSurfaced() if applicable
13. Persist to DB + brain transcript

### TheAnalyst (@Component)
**analyze(sessionId, candidateMessage, aiResponse, brain)**:
Single gpt-4o-mini call. JSON output → AnalystDecision. 20 update operations:
1. CandidateProfile (18 fields)
2. New hypothesis + TEST_HYPOTHESIS action
3. Hypothesis status updates
4. New claims
5. Contradiction detection
6. Goals completed
7. Thought thread append
8. Next action queued
9. Exchange score
10. Bloom's level update
11. Topic signal budget + WRAP_UP_TOPIC if depleted
12. Adjacent topic hypotheses via KnowledgeAdjacencyMap
13. Dismissal language → PROBE_DEPTH
14. Cognitive overload → REDUCE_LOAD
15. Low safety → RESTORE_SAFETY
16. Anxiety average update
17. Topic interleaving detection
18. Challenge success rate update
19. STAR ownership probe (BEHAVIORAL)
20. ZDP level update + question type tracking

### TheStrategist (@Component)
Fires when turnCount % 5 == 0. Updates InterviewStrategy (approach, tone, time, avoidance, recommendedTokens 60-180, selfCritique). Abandons stale hypotheses. Queues FORMATIVE_FEEDBACK when struggling.

### NaturalPromptBuilder (@Component)
13 sections: IDENTITY (static), SITUATION, CANDIDATE (after turn 2), THOUGHT_THREAD, GOALS + Bloom's, HYPOTHESES (top 2), CONTRADICTIONS (after turn 5), STRATEGY, ACTION, CODE (REVIEW), TESTS, HISTORY (6 turns), HARD_RULES (static + acknowledgments). 20-item acknowledgment pool.

### OpenQuestionTransformer (object)
Transforms "Is it X or Y?" → open question. Applied to all AI responses.

---

## 10. Objectives & Knowledge

### BrainObjectivesRegistry (object)
CODING/DSA: 9 required (problem_shared → interview_closed) + 3 optional. BEHAVIORAL: 8 required (psychological_safety → interview_closed) + 2 optional. SYSTEM_DESIGN: 8 required + 2 optional.

### BrainFlowGuard (@Component)
4 rules: (1) problem by turn 4, (2) overtime wrap-up, (3) 8-turn stall nudge, (4) behind-schedule pace warning. Returns IntendedAction or null.

### KnowledgeAdjacencyMap (object)
12 topic groups (hash_map_usage, bfs_algorithm, recursion_correct, dp_pattern_recognized, binary_tree_traversal, time_complexity_stated, sorting_algorithm, two_pointer_pattern, high_level_design_done, requirements_gathered, gave_action, star_situation_given). Each: 3+ adjacent topics with probeQuestion, bloomsLevel (1-6), diagnosticValue (0-1), isOrthogonal. Methods: getAdjacentTopics, getNextProbe, getNextBestTopic, getAdjacentTopicsForSuccessRate, toHypothesis.

---

## 11. Report Package

### EvaluationAgent (@Component)
`evaluate(memory, brain?)`: 60s timeout. Type-aware prompts (CODING/BEHAVIORAL/SYSTEM_DESIGN criteria). Brain enrichment: 15 signals injected. 8-dimension scoring. JSON schema includes initiative + learningAgility + nextSteps. Retry once on parse failure. Default scores on double failure.

### ReportService (@Service)
`generateAndSaveReport(sessionId)`: Idempotent. Steps: load memory → load brain → EvaluationAgent.evaluate() → compute weighted overall → serialize JSONB → persist EvaluationReport → update session COMPLETED → incrementUsage → SESSION_END WS → delete memory + brain.

Weights: problemSolving=0.25, algorithm=0.20, codeQuality=0.20, communication=0.15, efficiency=0.10, testing=0.10. (Initiative + learningAgility scored but not yet in overall formula — requires frontend update.)

---

## 12. User Package

**UserBootstrapService** — getOrCreateUser(clerkUserId, email, fullName). Redis cache: `user:clerk:{clerkUserId}`, TTL 5min.

**UsageLimitService** — checkUsageAllowed(userId, plan), incrementUsage(userId), getUsageThisMonth(userId). Redis key: `usage:{userId}:interviews:{YYYY-MM}`, TTL 35 days.

---

## 13. Code Execution

**Judge0Client** — Submit code → poll every 500ms → 30s timeout. Base64 encoding. Java class name normalization.

**CodeExecutionService** — `runCode()`: direct execution, result via WS CODE_RUN_RESULT. `submitCode()`: with test cases, result via CODE_RESULT, persisted to DB.

**Supported languages**: Python (71), Java (62), JavaScript (63), TypeScript (74), C++ (54), C (50), Go (60), Rust (73), Ruby (72), Kotlin (78), Swift (83), Scala (81).

---

## 14. WebSocket Protocol

### Inbound (Client → Server)
| Type | Key Fields | Handler |
|---|---|---|
| CANDIDATE_MESSAGE | text, codeSnapshot? | ConversationEngine |
| CODE_RUN | code, language, stdin? | CodeExecutionService |
| CODE_SUBMIT | code, language, sessionQuestionId? | CodeExecutionService |
| CODE_UPDATE | code, language | RedisMemoryService |
| REQUEST_HINT | hintLevel | HintGenerator |
| END_INTERVIEW | reason | ConversationEngine |
| PING | — | → PONG |

### Outbound (Server → Client)
| Type | Key Fields | Trigger |
|---|---|---|
| INTERVIEW_STARTED | sessionId, state | WS connect |
| AI_CHUNK | delta, done | Each token + final |
| AI_MESSAGE | text, state | Complete message |
| STATE_CHANGE | state | State transition |
| CODE_RUN_RESULT | stdout, stderr, exitCode | Run complete |
| CODE_RESULT | status, testResults, runtimeMs | Submit complete |
| HINT_DELIVERED | hint, level, hintsRemaining, refused | Hint generated |
| QUESTION_TRANSITION | questionIndex, title, description, codeTemplates? | Next question |
| SESSION_END | reportId | Report ready |
| STATE_SYNC | full state snapshot | Reconnect |
| ERROR | code, message | Any error |
| PONG | — | Heartbeat |

---

## 15. Database Schema

### Tables (10)

**organizations**: id, name, type, plan, seats_limit, created_at.
**users**: id, org_id (FK), clerk_user_id (unique), email, full_name, role, subscription_tier, created_at.
**questions**: id, title, description, type, difficulty, topic_tags, examples, constraints, test_cases, solution_hints, optimal_approach, follow_up_prompts, source, deleted_at, generation_params, space_complexity, time_complexity, evaluation_criteria, slug, interview_category, code_templates, function_signature, created_at.
**interview_sessions**: id, user_id (FK), status, type, config, started_at, ended_at, duration_secs, created_at, last_heartbeat, current_stage, integrity_signals.
**session_questions**: id, session_id (FK), question_id (FK), order_index, final_code, language_used, submitted_at, created_at.
**conversation_messages**: id, session_id (FK), role, content, metadata, created_at.
**code_submissions**: id, session_question_id (FK), user_id (FK), code, language, status, judge0_token, test_results, runtime_ms, memory_kb, submitted_at.
**evaluation_reports**: id, session_id (FK unique), user_id (FK), overall_score, problem_solving_score, algorithm_score, code_quality_score, communication_score, efficiency_score, testing_score, strengths, weaknesses, suggestions, narrative_summary, dimension_feedback, hints_used, next_steps, anxiety_level, anxiety_adjustment_applied, initiative_score, learning_agility_score, research_notes, completed_at, created_at.
**interview_templates**: id, name, type, difficulty, config, created_at.
**org_invitations**: id, org_id (FK), email, role, token (unique), expires_at, accepted_at, created_at.

### Migrations (V1-V14)
V1: organizations. V2: users. V3: questions, sessions, session_questions, messages. V4: code_submissions, evaluation_reports. V5: templates, invitations. V6: extend questions (source, slug, category, complexity). V7: extend reports (dimension_feedback, hints_used, completed_at). V8-V9: convert enums to VARCHAR. V10: convert JSONB to TEXT. V11: session heartbeat. V12: current_stage, code_templates. V13: next_steps, integrity_signals. V14: anxiety_level, anxiety_adjustment_applied, initiative_score, learning_agility_score, research_notes.

### Redis Keyspaces
| Pattern | TTL | Purpose |
|---|---|---|
| `brain:{sessionId}` | 3h | InterviewerBrain (new system) |
| `interview:session:{sessionId}:memory` | 2h | InterviewMemory |
| `user:clerk:{clerkUserId}` | 5min | User cache |
| `ratelimit:{userId}:{epochMinute}` | 2min | Rate limit counter |
| `usage:{userId}:interviews:{YYYY-MM}` | 35d | Monthly usage |

---

## 16. Key Data Flows

### Flow A: Session Creation
1. POST /api/v1/interviews/sessions → InterviewController.startSession()
2. InterviewSessionService.startSession(user, config)
3. UsageLimitService.checkUsageAllowed()
4. QuestionService.selectQuestionsForSession()
5. Save InterviewSession (status=PENDING) + SessionQuestions to DB
6. RedisMemoryService.initMemory()
7. Return { sessionId, wsUrl }

### Flow B: Candidate Message → AI Response
1. CANDIDATE_MESSAGE WS frame → InterviewWebSocketHandler
2. ConversationEngine.handleCandidateMessage(sessionId, content)
3. BrainService.getBrainOrNull(sessionId)
4. Persist message: DB + brain transcript + memory transcript
5. Transition → CANDIDATE_RESPONDING
6. computeBrainInterviewState(brain, remainingMinutes)
7. BrainFlowGuard.check() → queue action if needed
8. BrainService.incrementTurnCount()
9. TheConductor.respond():
   a. shouldRespond() → RESPOND/SILENT/WAIT
   b. NaturalPromptBuilder.build() (13 sections)
   c. LlmProviderRegistry.stream() → AI_CHUNK frames
   d. OpenQuestionTransformer.transform()
10. Transition → AI_ANALYZING
11. Background: TheAnalyst.analyze() → updates full brain
12. Background (every 5 turns): TheStrategist.review()

### Flow C: Report Generation
1. END_INTERVIEW WS or timer → ConversationEngine.forceEndInterview()
2. Transition → EVALUATING
3. ReportService.generateAndSaveReport()
4. Load memory + brain
5. EvaluationAgent.evaluate(memory, brain) → 8-dimension scores
6. Compute weighted overallScore
7. Save EvaluationReport to DB
8. Update session status=COMPLETED
9. UsageLimitService.incrementUsage()
10. SESSION_END WS frame with reportId
11. BrainService.deleteBrain() + cancelSessionScope()

---

## 17. Frontend Architecture

### Routes (App.tsx)
| Route | Component | Auth |
|---|---|---|
| `/` | LandingPage | No |
| `/sign-in/*` | Clerk SignIn | No |
| `/sign-up/*` | Clerk SignUp | No |
| `/dashboard` | DashboardPage | Yes |
| `/interview/setup` | InterviewSetupPage | Yes |
| `/interview/:sessionId` | InterviewPage | Yes |
| `/report/:sessionId` | ReportPage | Yes |

### Pages
**DashboardPage**: Interview list (paginated), StatsCard (total/avg/best), ProgressChart (line chart from reports), DifficultyRecommendation.
**InterviewSetupPage**: Category (4), Difficulty (3), Personality (4), Language (select), Role/Company (text), Experience (select), Background (textarea), Duration (30/45/60), difficulty preview card.
**InterviewPage**: Split panel — ConversationPanel (left) + CodeEditor (right, when coding). HintPanel, TimerDisplay, integrity tracking (tab switch + paste detection).
**ReportPage**: Hero score (animated), radar chart, 6 dimension bars with tooltips, narrative, strengths/weaknesses/suggestions, study plan (next steps with priority colors), session details.

### Key Hooks
| Hook | Query Key | API Call | Returns |
|---|---|---|---|
| useInterviewList(page) | ['interviews', page] | listSessions | PagedResponse<SessionSummaryDto> |
| useInterviewDetail(id) | ['interview', id] | getSession | SessionDetailDto |
| useStartInterview() | mutation | startSession | navigates to /interview/{id} |
| useUserStats() | ['userStats'] | getStats | UserStatsDto |
| useLanguages() | ['languages'] | getLanguages | string[] |
| useReportList(page, size) | ['reports', page, size] | listReports | ReportSummaryDto[] |
| useReport(sessionId) | ['report', id] | getReport | ReportDto (retry 404 x10, 3s delay) |
| useInterviewSocket | — | WebSocket | { status, send, disconnect } |
| useConversation | — | — | { messages, addCandidate, appendAiToken, finalize } |

### Vite Manual Chunks
monaco (@monaco-editor/react, monaco-editor), recharts, clerk (@clerk/clerk-react).

---

## 18. API Reference

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | /api/v1/interviews/sessions | Yes | Start interview |
| GET | /api/v1/interviews/sessions | Yes | List sessions (page, size) |
| GET | /api/v1/interviews/sessions/{id} | Yes | Session detail |
| POST | /api/v1/interviews/sessions/{id}/end | Yes | End interview |
| GET | /api/v1/reports/{sessionId} | Yes | Get report |
| GET | /api/v1/reports | Yes | List reports (page, size) |
| GET | /api/v1/users/me | Yes | Get user |
| GET | /api/v1/users/me/stats | Yes | User stats |
| GET | /api/v1/code/languages | No | Supported languages |
| POST | /api/v1/integrity | Yes | Report integrity signals |
| GET | /health | No | Health check |

---

## 19. Configuration

| Variable | Default | Required | Purpose |
|---|---|---|---|
| DATABASE_URL | r2dbc:postgresql://localhost:5432/aiinterview | Yes | R2DBC |
| FLYWAY_URL | jdbc:postgresql://localhost:5432/aiinterview | Yes | Migrations |
| REDIS_URL | redis://localhost:6379 | Yes | Redis |
| OPENAI_API_KEY | — | Yes | OpenAI |
| CLERK_JWKS_URL | — | Yes | JWT validation |
| CLERK_PUBLISHABLE_KEY | — | Yes | Frontend auth |
| JUDGE0_BASE_URL | http://localhost:2358 | Yes | Code sandbox |
| JUDGE0_AUTH_TOKEN | — | Yes | Judge0 auth |
| VITE_CLERK_PUBLISHABLE_KEY | — | Yes | Frontend Clerk |
| VITE_API_BASE_URL | http://localhost:8080 | No | API URL |
| VITE_WS_URL | ws://localhost:8080 | No | WS URL |

---

## 20. Evaluation System

### 8 Dimensions
| Dimension | Weight | Measures |
|---|---|---|
| problem_solving | 20% | Approach, breakdown, constraints |
| algorithm_depth | 15% | Understanding WHY (not just correct choice) |
| code_quality | 15% | Readability, naming, abstraction |
| communication | 15% | Thinking process narration |
| efficiency | 10% | Time + space complexity |
| testing | 10% | Verification, edge cases |
| initiative | 10% | Proactivity beyond minimum |
| learning_agility | 5% | In-interview learning |

### Score Adjustments
| Condition | Amount | Research |
|---|---|---|
| avgAnxietyLevel > 0.7 | +0.75 all dims | Lupien 2007 |
| avgAnxietyLevel > 0.5 | +0.50 all dims | Picard 1997 |
| selfRepair + correct | +0.50 per exchange | Bjork 1994 |
| SCHEMA_DRIVEN | +1.0 algorithm | Chi 1981 |
| abstractionLevel >= 4 | +1.0 code_quality | Brooks 1983 |

### Anti-Halo
ExchangeScores computed independently per turn. Dimension scores = recency-weighted average. Holistic transcript for narrative only.

### Brain Enrichment (15 signals)
Confirmed hypotheses, incorrect claims, exchange scores, anxiety, struggle count, reasoning pattern, linguistic pattern, safety, hint outcomes, Bloom's levels, ZDP edge, challenge rate, interleaving, STAR ownership, scoring rubric.

---

## 21. Scientific Research Basis

| Domain | Research | Implementation |
|---|---|---|
| Cognitive Science | Sweller 1988, Chi 1981 | Cognitive load detection, schema/search reasoning |
| Psycholinguistics | Hyland 1996, Schiffrin 1987 | Anxiety detection, dismissal probing |
| Educational Psychology | Anderson 2001, Vygotsky 1978, Feuerstein 1980, Bjork 1994 | Bloom's tracker, ZDP, hint generalization, 70% calibration |
| Behavioral Science | Thorndike 1920, Kahneman 1974, Behroozi 2019 | Anti-halo, anchoring prevention, observer effect |
| Affective Computing | Picard 1997, Lupien 2007 | Anxiety adjustment |
| Org Psychology | Schmidt/Hunter 1998, Campion 1994 | Initiative, learning agility, structured objectives |
| Information Theory | Shannon 1948, Cronbach 1951 | Signal depletion, dimension independence |
| Neuroscience | Bjork 1994, Kornell/Bjork 2008 | Struggle bonus, interleaving |
| Social Psychology | Edmondson 1999, Bernieri 1996 | Safety protocol, rapport |
| CS Research | Brooks 1983, Wiedenbeck 1991 | Abstraction levels, mental simulation |

---

## 22. Architecture Decisions

| Decision | Why | Trade-off |
|---|---|---|
| 3 agents (not 1 or 7) | Conductor=sync, Analyst=async, Strategist=periodic. Clean separation. | 2 extra LLM calls per interview |
| Brain in separate Redis key | Brain TTL 3h vs memory 2h. Independent lifecycle. | Two keyspaces to manage |
| Remove old system entirely | Dead code complexity. Single path simplifies debugging. | No instant rollback |
| Per-session Mutex | GET→modify→SET not atomic. | Serializes writes per session |
| Per-session CoroutineScope | Singleton scope leaked on hung LLM calls. | ConcurrentHashMap overhead |
| Extractive compression | Zero LLM cost for thought thread. | Lower quality than LLM summary |
| FlowGuard 4 rules max | Minimal intervention. AI decides everything else. | May miss edge cases |
| TEXT not JSONB | R2DBC driver doesn't support JSONB natively. | No DB-level JSON queries |
| Exchange scores primary | Prevents halo effect. | More data per interview |

---

## 23. Known Limitations

| Issue | Location | Impact | Fix Path |
|---|---|---|---|
| Judge0 privileged Docker | docker-compose | Container escape risk | Separate VM |
| JWT in WS query param | WsAuthHandshakeInterceptor | Token in logs | Ticket endpoint |
| No circuit breakers | LlmProviderRegistry | Slow fail on outage | Resilience4j |
| Brain size unbounded | BrainService | Redis memory growth | Max field sizes |
| No brain unit tests | conversation/brain/ | Regression risk | Test suite |
| Initiative/agility not in overall formula | ReportService | New dims scored but not weighted | Frontend + formula update |
| HintGenerator still uses old memory | HintGenerator.kt | Not brain-aware | Rewrite to use brain |
| Frontend shows 6 dims not 8 | ReportPage.tsx | Initiative + agility invisible | Add 2 bars |

---

## 24. Glossary

| Term | Definition |
|---|---|
| InterviewerBrain | Unified cognitive state in Redis (brain:{sessionId}). 30+ fields. |
| CandidateProfile | 18-field behavioral model within brain. Updated by TheAnalyst. |
| TheConductor | Real-time response generator. Streams via gpt-4o. |
| TheAnalyst | Background agent (1 call/exchange). Updates full brain. Replaces 7 old agents. |
| TheStrategist | Meta-cognitive reviewer (every 5 turns). Updates strategy + selfCritique. |
| BrainService | Redis persistence for brain. Per-session Mutex. |
| BrainFlowGuard | 4-rule safety net. Returns IntendedAction or null. |
| NaturalPromptBuilder | 13-section brain-driven prompt builder. |
| OpenQuestionTransformer | Converts leading/binary questions to open ones. |
| KnowledgeAdjacencyMap | 12 topic groups for predictive probing. |
| ActionQueue | Prioritized actions (15 types). Consumed by TheConductor. |
| ExchangeScore | Per-turn independent score. Anti-halo. |
| HypothesisRegistry | Beliefs being tested. Max 5 open. |
| ClaimRegistry | Specific claims + contradictions. |
| ThoughtThread | Running inner monologue. Extractive compression at 600 chars. |
| InterviewStrategy | Approach + tone + avoidance + selfCritique. Updated every 5 turns. |
| SilenceIntelligence | 3-way decision: RESPOND / SILENT / WAIT_THEN_RESPOND. |
| ZdpLevel | Zone of Proximal Development. canDoAlone / canDoWithPrompt / cannotDo. |
| challengeSuccessRate | 70% target. Calibrates question difficulty. |
| backgroundModel | gpt-4o-mini. Used by TheAnalyst + TheStrategist. |
| interviewerModel | gpt-4o. Used by TheConductor for streaming. |
| fire-and-forget | Background coroutine on per-session scope. Never blocks response. |
