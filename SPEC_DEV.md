# AI Interview Platform — Complete Developer Specification

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [System Architecture](#2-system-architecture)
3. [Tech Stack](#3-tech-stack)
4. [Backend Package Structure](#4-backend-package-structure)
5. [Conversation Layer — Deep Dive](#5-conversation-layer--deep-dive)
6. [Interview Memory Architecture](#6-interview-memory-architecture)
7. [LLM Provider Layer](#7-llm-provider-layer)
8. [WebSocket Protocol](#8-websocket-protocol)
9. [Database Schema](#9-database-schema)
10. [Authentication & Security](#10-authentication--security)
11. [Code Execution (Judge0)](#11-code-execution-judge0)
12. [Report Generation](#12-report-generation)
13. [Interview Flow — Complete Technical Walkthrough](#13-interview-flow--complete-technical-walkthrough)
14. [API Reference](#14-api-reference)
15. [Configuration Reference](#15-configuration-reference)
16. [Local Development Setup](#16-local-development-setup)
17. [Architecture Decisions Log](#17-architecture-decisions-log)
18. [Known Limitations and Technical Debt](#18-known-limitations-and-technical-debt)
19. [Glossary](#19-glossary)

---

## 1. Project Overview

### What It Does

A full-stack AI-powered mock interview platform where candidates take real-time technical interviews with an adaptive AI interviewer. The system uses a multi-agent AI architecture — GPT-4o streams conversational responses over WebSocket while GPT-4o-mini agents run background analysis, generate hints, produce follow-up questions, and make stage transition decisions. After each interview, an evaluation agent scores the candidate across six dimensions and generates a detailed report with actionable next steps.

### Who Uses It

Software engineering candidates preparing for technical interviews. They configure an interview (category, difficulty, personality, target company), take a live session with AI-streamed conversation and a code editor, then receive a scored evaluation report. The free tier allows 3 interviews per month.

### Core Value Proposition

Unlike static problem banks, this platform creates a realistic interview experience with an AI that adapts its behavior per stage (silent during coding, probing during review), tracks what has been discussed (complexity, edge cases), and generates structured evaluation reports with per-dimension scoring. The multi-agent architecture separates the interviewer's conversational persona from background analysis and decision-making.

---

## 2. System Architecture

### High-Level ASCII Diagram

```
┌──────────────────────────────────────────────────────────────────────┐
│                      FRONTEND (React 18 + Vite)                      │
│                      http://localhost:3000                            │
│  ┌──────────┐ ┌──────────────┐ ┌───────────┐ ┌──────────────┐      │
│  │Dashboard  │ │InterviewSetup│ │ Interview │ │   Report     │      │
│  │  Page     │ │    Page      │ │   Page    │ │    Page      │      │
│  └────┬──────┘ └──────┬───────┘ └─────┬─────┘ └──────┬───────┘      │
│       │ REST           │ REST     WS + REST       │ REST            │
└───────┼────────────────┼──────────────┼────────────┼─────────────────┘
        │                │              │            │
  ┌─────▼────────────────▼──────────────▼────────────▼─────────────────┐
  │                   Clerk.dev (JWT Auth)                              │
  │             ClerkJwtAuthFilter (@Order -200)                        │
  │             RateLimitFilter    (@Order -150)                        │
  └───────────────────────┬────────────────────────────────────────────┘
                          │
  ┌───────────────────────▼────────────────────────────────────────────┐
  │                 BACKEND (Spring WebFlux + Kotlin Coroutines)        │
  │                 http://localhost:8080                                │
  │                                                                     │
  │  ┌─────────────┐  ┌──────────────────────────────────────────┐     │
  │  │ Controllers  │  │      WebSocket Handler                    │     │
  │  │ (REST API)   │  │  /ws/interview/{sessionId}?token=JWT     │     │
  │  └──────┬───────┘  └──────────────┬───────────────────────────┘     │
  │         │                         │                                  │
  │  ┌──────▼─────────────────────────▼──────────────────────────────┐  │
  │  │                 Conversation Engine                             │  │
  │  │                                                                 │  │
  │  │  ┌──────────────┐  ┌────────────────┐  ┌──────────────────┐   │  │
  │  │  │ Interviewer   │  │ Smart          │  │ Agent            │   │  │
  │  │  │ Agent (GPT-4o)│  │ Orchestrator   │  │ Orchestrator     │   │  │
  │  │  └───────┬───────┘  │ (GPT-4o-mini)  │  │ (GPT-4o-mini)   │   │  │
  │  │          │          └────────────────┘  └────────┬─────────┘   │  │
  │  │  ┌───────▼────────┐ ┌───────────────┐  ┌────────▼──────────┐  │  │
  │  │  │ PromptBuilder  │ │ FollowUp Gen  │  │ Hint Generator    │  │  │
  │  │  └────────────────┘ │ (GPT-4o-mini) │  │ (GPT-4o-mini)     │  │  │
  │  │                     └───────────────┘  └───────────────────┘  │  │
  │  │  ┌──────────────────┐  ┌──────────────────┐                   │  │
  │  │  │ StateContext     │  │ ToolContext       │                   │  │
  │  │  │ Builder          │  │ Service           │                   │  │
  │  │  └──────────────────┘  └──────────────────┘                   │  │
  │  │  ┌──────────────────┐  ┌──────────────────┐                   │  │
  │  │  │ Reasoning        │  │ Stage Reflection  │                   │  │
  │  │  │ Analyzer         │  │ Agent             │                   │  │
  │  │  └──────────────────┘  └──────────────────┘                   │  │
  │  └────────────────────────────────────────────────────────────────┘  │
  │         │              │                    │                        │
  │  ┌──────▼──────┐ ┌────▼─────┐  ┌───────────▼──────────────────┐    │
  │  │  PostgreSQL  │ │  Redis   │  │     Judge0 CE (Docker)       │    │
  │  │  (R2DBC)     │ │ (Upstash)│  │     Code Execution           │    │
  │  │  :5432       │ │  :6379   │  │     :2358                    │    │
  │  └─────────────┘ └──────────┘  └──────────────────────────────┘    │
  └────────────────────────────────────────────────────────────────────┘
```

### Two Core Flows

#### Flow 1: REST API Request (e.g., GET /api/v1/interviews/sessions)

1. HTTP request hits Spring WebFlux (Netty).
2. `CorsWebFilter` adds CORS headers (order HIGHEST_PRECEDENCE).
3. `CommitSafeResponseFilter` wraps response for safe error handling.
4. `ClerkJwtAuthFilter` (order -200) extracts `Authorization: Bearer <JWT>`, validates via `JwksValidator` → `JwksCache`, calls `UserBootstrapService.getOrCreateUser()` to resolve/create the `User` entity, sets `UsernamePasswordAuthenticationToken` with `User` as principal.
5. `RateLimitFilter` (order -150) increments Redis counter `ratelimit:{userId}:{epochMinute}`, returns 429 if over limit.
6. Spring Security `SecurityWebFilterChain` checks authorization (all `/api/**` require authentication).
7. `InterviewController.listSessions()` extracts `authentication.principal as User`, calls `InterviewSessionService.listSessions()`.
8. Service queries PostgreSQL via R2DBC reactive repositories, returns DTO.
9. Response serialized to JSON via Jackson.

#### Flow 2: WebSocket Interview Session

1. Client opens `ws://localhost:8080/ws/interview/{sessionId}?token=JWT`.
2. `WsAuthHandshakeInterceptor` validates JWT from `?token=` query param, verifies session ownership, stores `userId` and `sessionId` in exchange attributes.
3. `InterviewWebSocketHandler.handle()` extracts attributes, calls `onConnect()`.
4. `onConnect()` checks session status. If PENDING: marks ACTIVE, calls `ConversationEngine.startInterview()`. If ACTIVE with existing memory: calls `handleReconnect()` which sends `STATE_SYNC`.
5. `WsSessionRegistry.register()` stores the WebSocket session and creates a Reactor `Sinks.Many<String>` for outbound messages.
6. Inbound messages are deserialized and routed by type: `CANDIDATE_MESSAGE` → `ConversationEngine.handleCandidateMessage()`, `CODE_RUN` → `CodeExecutionService.runCode()`, etc.
7. On disconnect: `WsSessionRegistry.deregister()` removes the session.

---

## 3. Tech Stack

### Backend

| Technology | Version | Purpose |
|---|---|---|
| Kotlin | 1.9.25 | Primary language |
| Spring Boot | 3.5.9 | Application framework |
| Spring WebFlux | 3.5.9 | Reactive web framework (Netty) |
| Spring Data R2DBC | 3.5.9 | Async PostgreSQL access |
| Spring Security | 3.5.9 | Auth filters (JWT validation) |
| Spring Data Redis Reactive | 3.5.9 | Interview memory storage |
| Kotlinx Coroutines | BOM-managed | Async programming model |
| Jackson Kotlin Module | BOM-managed | JSON serialization |
| Flyway | BOM-managed | Database migrations |
| PostgreSQL R2DBC Driver | BOM-managed | Reactive DB driver |
| Nimbus JOSE JWT | 10.8 | Clerk JWT validation |
| OpenAI Java SDK | 4.26.0 | LLM API calls (chat completions + streaming) |
| Mockk | 1.13.12 | Test mocking |

### Frontend

| Technology | Version | Purpose |
|---|---|---|
| React | 18.3.1 | UI framework |
| TypeScript | 5.6.2 | Type-safe JavaScript |
| Vite | 5.4.10 | Build tool + dev server |
| Tailwind CSS | 4.2.1 | Utility-first CSS |
| TanStack React Query | 5.90.21 | Server state management + caching |
| React Router DOM | 7.13.1 | Client-side routing |
| Axios | 1.13.6 | HTTP client |
| @clerk/clerk-react | 5.61.3 | Authentication UI + JWT provider |
| @monaco-editor/react | 4.7.0 | Code editor (VS Code engine) |
| Recharts | 3.8.0 | Charts (radar, line) |
| Radix UI | Various | Accessible UI primitives (dialog, select, tabs, tooltip) |
| Lucide React | 0.577.0 | Icons |
| date-fns | 4.1.0 | Date formatting |
| Playwright | 1.58.2 | E2E testing |

### External Services

| Service | Purpose | Configuration | Used In |
|---|---|---|---|
| Clerk.dev | JWT authentication | `CLERK_JWKS_URL`, `CLERK_SECRET_KEY` | `ClerkJwtAuthFilter`, `WsAuthHandshakeInterceptor` |
| OpenAI API | LLM calls (GPT-4o, GPT-4o-mini) | `OPENAI_API_KEY` | `OpenAiProvider` |
| Judge0 CE | Sandboxed code execution | `JUDGE0_BASE_URL`, `JUDGE0_AUTH_TOKEN` | `Judge0Client` |
| PostgreSQL | Persistent data storage | `DATABASE_URL` | R2DBC repositories |
| Redis | Session memory, rate limiting, caching | `REDIS_URL` | `RedisMemoryService`, `RateLimitFilter`, `UsageLimitService`, `UserBootstrapService` |

---

## 4. Backend Package Structure

### com.aiinterview

**Application** (`@SpringBootApplication`)
- Purpose: Entry point. Logs active LLM provider on startup.

### com.aiinterview.auth

**ClerkJwtAuthFilter** (`@Component`, `@Order(-200)`)
- Purpose: Validates JWT from Authorization header, bootstraps User entity.
- Methods: `filter(exchange, chain)` — extracts Bearer token, validates via JwksValidator, creates User via UserBootstrapService, sets Authentication.
- Depends on: `JwksValidator`, `UserBootstrapService`

**JwksCache** (`@Component`)
- Purpose: Caches Clerk JWKS key set with configurable TTL (default 60min).
- Methods: `getJwkSet()` — double-checked locking, fetches from Clerk JWKS URL on cache miss.

**JwksValidator** (`@Component`)
- Purpose: Validates JWT signature and expiry using JWKS keys.
- Methods: `validate(token): JwtClaims` — returns userId, email, fullName.
- Depends on: `JwksCache`

**RateLimitFilter** (`@Component`, `@Order(-150)`)
- Purpose: Per-user rate limiting via Redis counters.
- Methods: `filter(exchange, chain)` — increments `ratelimit:{userId}:{epochMinute}`, returns 429 with Retry-After header if exceeded.
- Depends on: `ReactiveStringRedisTemplate`

**SecurityConfig** (`@Configuration`)
- Purpose: Disables CSRF, permits `/health`, `/actuator/**`, `/ws/**`, `/api/v1/code/languages`; requires auth for `/api/**`.

### com.aiinterview.code

**Judge0Client** (`@Component`)
- Purpose: Submits code to Judge0 CE, polls for results.
- Methods: `submit(code, languageId, stdin?)` — Base64 encodes, normalizes Java class to `Main`, returns token. `pollResult(token)` — polls every 500ms, 30s timeout, Base64 decodes output.
- Depends on: WebClient

**LanguageMap** (object)
- Purpose: Maps language names to Judge0 IDs.
- Languages: JavaScript (63), Java (62), Python (71), C++ (54), Go (60), Ruby (72), C# (51), TypeScript (74), Rust (73), Swift (83), Kotlin (78), PHP (68)

**CodeExecutionService** (`@Service`)
- Purpose: Orchestrates code execution and test running.
- Methods: `runCode(sessionId, code, language, stdin?)` — fire-and-forget execution, sends `CODE_RESULT` via WS. `submitCode(sessionId, sessionQuestionId, code, language)` — runs all test cases concurrently, persists `CodeSubmission`, transitions to FollowUp on all-pass.
- Depends on: `Judge0Client`, `WsSessionRegistry`, `RedisMemoryService`, repositories

**CodeController** (`@RestController /api/v1/code`)
- Endpoints: `POST /run` (202), `POST /submit` (202), `GET /languages` (no auth)

### com.aiinterview.conversation

See [Section 5](#5-conversation-layer--deep-dive) for deep dive.

### com.aiinterview.interview

**InterviewSessionService** (`@Service`)
- Purpose: Session lifecycle — create, end, list, get.
- Methods: `startSession(user, config)` — checks usage limit, creates session + session_questions, inits Redis memory, returns sessionId + wsUrl. `endSession(sessionId, userId)`. `getSession(sessionId, userId)`. `listSessions(userId, page, size)`.
- Question counts: CODING/DSA=2, BEHAVIORAL=3, SYSTEM_DESIGN/CASE_STUDY=1.
- Depends on: repositories, `QuestionService`, `UsageLimitService`, `RedisMemoryService`

**InterviewController** (`@RestController /api/v1/interviews`)
- Endpoints: `POST /sessions` (201), `POST /sessions/{id}/end` (204), `GET /sessions/{id}`, `GET /sessions`

**QuestionController** (`@RestController /api/v1`)
- Endpoints: `GET /questions`, `GET /questions/{id}`, `POST /admin/questions/generate` (201), `DELETE /admin/questions/{id}` (204)

**IntegrityController** (`@RestController /api/v1/integrity`)
- Purpose: Receives tab-switch and paste-detection signals from frontend.
- Endpoints: `POST /` — merges integrity signals into session's `integrity_signals` column.

**GlobalExceptionHandler** (`@RestControllerAdvice`)
- Maps: `NoSuchElementException` → 404, `SessionAccessDeniedException` → 403, `UsageLimitExceededException` → 429, `Exception` → 500.

### com.aiinterview.report

**EvaluationAgent** (`@Component`)
- Purpose: Calls GPT-4o to score interviews across 6 dimensions.
- Methods: `evaluate(memory): EvaluationResult` — retry once on failure, falls back to default scores.
- Uses category-specific criteria (CODING/BEHAVIORAL/SYSTEM_DESIGN).

**ReportService** (`@Service`)
- Purpose: Orchestrates report generation pipeline.
- Methods: `generateAndSaveReport(sessionId)` — idempotent, calls EvaluationAgent, computes weighted overall score, persists, updates session, increments usage, sends SESSION_END, deletes Redis memory. `getReport(sessionId)`. `listReports(userId)`. `getUserStats(userId)`.

**ReportController** (`@RestController /api/v1`)
- Endpoints: `GET /reports/{sessionId}`, `GET /reports`, `GET /users/me/stats`

### com.aiinterview.shared

**HealthController** (`@RestController /health`)
- Purpose: Health check pinging PostgreSQL and Redis.

**CorsConfig** (`@Configuration`)
- Purpose: CORS filter allowing configured origins with credentials.

**LlmProviderRegistry** (`@Component`)
- Purpose: Provider selection with automatic fallback.

**ModelConfig** (`@ConfigurationProperties`)
- Fields: `interviewerModel` (gpt-4o), `backgroundModel` (gpt-4o-mini), `generatorModel` (gpt-4o), `evaluatorModel` (gpt-4o).

### com.aiinterview.user

**UserBootstrapService** (`@Service`)
- Purpose: Get-or-create user from Clerk JWT claims. Redis cache with 5min TTL.
- Methods: `getOrCreateUser(clerkUserId, email, fullName)` — cache → DB → create (with personal org) in transaction.

**UsageLimitService** (`@Service`)
- Purpose: Monthly interview usage tracking.
- Redis key: `usage:{userId}:interviews:{YYYY-MM}` with 35-day TTL.
- Methods: `checkUsageAllowed(userId, plan)`, `incrementUsage(userId)`, `getUsageThisMonth(userId)`.

**AuthController** (`@RestController /api/v1/users`)
- Endpoints: `GET /me` — returns UserDto.

---

## 5. Conversation Layer — Deep Dive

### 5.1 The Multi-Agent Architecture

#### Agent Overview Table

| Agent | Model | Role | Runs When | Output |
|---|---|---|---|---|
| InterviewerAgent | GPT-4o (streaming) | Generate AI interviewer responses | Every candidate message | Streamed AI_CHUNK frames |
| ReasoningAnalyzer | GPT-4o-mini | Analyze candidate's understanding | Background after every AI response | CandidateAnalysis + transition suggestion |
| FollowUpGenerator | GPT-4o-mini | Generate targeted follow-up questions | When gaps detected in analysis | Follow-up question string |
| HintGenerator | GPT-4o-mini | Generate progressive hints (3 levels) | On REQUEST_HINT from candidate | Hint text + deduction |
| SmartOrchestrator | GPT-4o-mini | LLM-driven checklist + stage decisions | Fire-and-forget after AI response | OrchestratorDecision (checklist, stage, notes) |
| StageReflectionAgent | GPT-4o-mini | Analyze completed stage, plan next | Only on stage transitions (3-4x) | Agent note saved to Redis |
| EvaluationAgent | GPT-4o | Score interview across 6 dimensions | Once, when interview ends | EvaluationResult with scores + narrative |

#### How Agents Coordinate

For a single candidate message, the sequence is:

1. `ConversationEngine.handleCandidateMessage()` persists message to Redis transcript + DB.
2. Transitions WS state to `CANDIDATE_RESPONDING`.
3. Calls `InterviewerAgent.streamResponse()` — this is **synchronous** (blocks until streaming completes).
   - InterviewerAgent fetches fresh `StateContext` via `StateContextBuilder.build()`.
   - Fetches stage-specific data via `ToolContextService.fetchForStage()`.
   - Checks CODING GATE (skip LLM if no code in CODING stage).
   - Builds system prompt via `PromptBuilder.buildSystemPrompt()`.
   - Streams response via `LlmProviderRegistry.stream()`.
   - Persists response to Redis + DB.
   - Runs keyword-based `updateInterviewStage()`.
   - Returns full response text.
4. Transitions WS state to `AI_ANALYZING`.
5. Launches **fire-and-forget** background coroutine on `backgroundScope`:
   - `SmartOrchestrator.orchestrate()` — LLM-driven analysis.
   - `AgentOrchestrator.analyzeAndTransition()` — calls `ReasoningAnalyzer`, handles state transitions.

### 5.2 ConversationEngine

**State Management**: Updates `InterviewMemory.state` in Redis and sends `STATE_CHANGE` WS frames via `transition()`.

**handleCandidateMessage() sequence**:
1. Load memory from Redis (throws if missing).
2. Append candidate message to Redis rolling transcript.
3. Persist candidate message to `conversation_messages` table.
4. Transition → `CANDIDATE_RESPONDING`.
5. Call `interviewerAgent.streamResponse()` (synchronous — waits for streaming to complete).
6. Transition → `AI_ANALYZING`.
7. Fetch fresh `StateContext`.
8. Launch background: `SmartOrchestrator.orchestrate()` + `AgentOrchestrator.analyzeAndTransition()`.

**startInterview()**: Transitions to `QUESTION_PRESENTED`, streams personality-specific opening message (e.g., "Hey. Ready to get started?" for FAANG_SENIOR), persists to transcript.

**transitionToNextQuestion()**: Loads next SessionQuestion by orderIndex, updates Redis memory (new question, reset hints/code/analysis), sends `QUESTION_TRANSITION` WS event, streams transition message.

**Coroutine scope**: `backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` — failures in one background task don't cancel others.

### 5.3 InterviewerAgent

**Message classification** (`classifyMessage()`):
- `CONSTRAINT_QUESTION`: Question containing constraint keywords (size, limit, null, edge case, etc.)
- `CLARIFYING_QUESTION`: Any question not about constraints
- `CANDIDATE_STATEMENT`: Not a question — approach explanation, info

**maxTokensFor() mapping**:

| Stage | CONSTRAINT_Q | CLARIFYING_Q | CANDIDATE_STATEMENT |
|---|---|---|---|
| SMALL_TALK | 120 | 120 | 120 |
| PROBLEM_PRESENTED | 50 | 50 | 50 |
| CLARIFYING | 60 | 80 | 100 |
| APPROACH | 120 | 120 | 120 |
| CODING | 60 | 60 | 60 |
| REVIEW | 150 | 150 | 150 |
| FOLLOWUP | 150 | 150 | 150 |
| WRAP_UP | 100 | 100 | 100 |
| (other) | 80 | 100 | 150 |

**CODING GATE**: When `stage == "CODING"` and `hasMeaningfulCode == false`:
- Skips LLM call entirely (zero tokens).
- Returns canned response based on message content:
  - Long message (>150 chars): "I think I follow your approach — go ahead and implement it in the editor."
  - Contains "done"/"finish": "I don't see code in the editor yet — go ahead and implement when ready."
  - Contains "start"/"begin"/"implement"/"code"/"write": "Sure, take your time."
  - Default: "Go ahead — I'll wait while you code."

**Streaming**: 10s timeout via `withTimeout(STREAM_TIMEOUT_MS)`. On timeout, falls back to `LlmProviderRegistry.complete()` with `backgroundModel` and 200 max tokens.

**updateInterviewStage()** — keyword-based transitions (forward-only):
- `SMALL_TALK → PROBLEM_PRESENTED`: Always after first exchange.
- `PROBLEM_PRESENTED → CLARIFYING`: If message ends with `?` or contains constraint keywords.
- `PROBLEM_PRESENTED → APPROACH`: If message contains approach/algorithm keywords.
- `CLARIFYING → APPROACH`: If candidate stops asking questions and discusses solution.
- `APPROACH → CODING`: If AI says "go ahead"/"code it"/"implement" OR meaningful code detected.
- `CODING → REVIEW`: If meaningful code exists AND candidate says "done"/"finished"/"walk you through".
- `REVIEW → FOLLOWUP`: If complexity mentioned AND edge cases covered, or AI says "good job"/"let's move".
- `FOLLOWUP → WRAP_UP`: If AI says "covers it"/"that's it"/"questions for me".

### 5.4 PromptBuilder — Complete Documentation

**Method signature**:
```kotlin
fun buildSystemPrompt(
    memory: InterviewMemory,
    messageType: MessageType? = null,
    stateCtx: StateContext? = null,
    codeDetails: String? = null,
    testResultSummary: String? = null,
): String
```

**Prompt assembly order** (each section is a separate `appendLine` block):

1. **BASE_PERSONA** — Always first. Rules:
   - ONE thing per message. Never string multiple questions.
   - SHORT responses: 1-3 sentences max.
   - NEVER reveal solution or write code.
   - NEVER coach. REACT to what they said.
   - Professional but human tone.

2. **Personality rules** (`personalityRules(memory.personality)`):
   - `FAANG_SENIOR`: Direct, efficient, push back on suboptimal.
   - `FRIENDLY_MENTOR`/`FRIENDLY`: Warm, encouraging, gentle nudges.
   - `STARTUP_ENGINEER`/`STARTUP`: Pragmatic, ship-focused, casual.
   - `ADAPTIVE`: Match candidate's energy.

3. **Stage-specific rules** (`stageRules(memory)`):
   - **SMALL_TALK**: Be warm, present the problem after brief exchange, then go silent.
   - **PROBLEM_PRESENTED**: Wait for candidate to speak. Do NOT prompt them.
   - **CLARIFYING**: Answer constraints in 5 words max. Never hint at solution.
   - **APPROACH**: React to their specific approach. When approach is good, say "go ahead and code it." If code editor is EMPTY: do NOT ask about complexity/edge cases.
   - **CODING**: Stay SILENT. Respond in ONE sentence max if they speak. Never write code for them.
   - **REVIEW**: Ask to trace through with specific example. Pick 2-3 edge cases ONE AT A TIME. Reference specific code lines.
   - **FOLLOWUP**: Introduce ONE harder variant. React to their answer.
   - **WRAP_UP**: Ask "any questions for me?" then close professionally.

4. **Category framework** (`categoryFramework(memory.category)`):
   - `CODING`/`DSA`: Problem understanding, algorithm choice, code correctness, complexity, edge cases.
   - `BEHAVIORAL`: Full STAR method with 5 behavioral domains (Leadership, Conflict, Technical Challenges, Growth, Collaboration). Rules for probing vague answers.
   - `SYSTEM_DESIGN`: Requirements, architecture, data modeling, scalability, trade-offs.

5. **Company-specific style** (`companyStyle(company)`) — if `targetCompany` set:
   - Google: Algorithmic thinking, scalability, "billions of records".
   - Meta: Move fast, practical solutions, product impact.
   - Amazon: Leadership principles, customer impact, failure modes.
   - Microsoft: Extensibility, clean design, collaborative problem-solving.
   - Apple: Attention to detail, simplicity, elegance.

6. **Candidate context** (`candidateContext(memory)`) — if experienceLevel/background/targetRole set:
   - Injects level, background, role. "Adjust difficulty and expectations silently."

7. **LIVE STATE BLOCK** (`buildStateBlock(stateCtx)`) — from StateContextBuilder:
   - TIME: remaining minutes, overtime/wrap-up warnings.
   - CODE EDITOR: EMPTY (with rules blocking complexity/edge case questions) or HAS CODE (line count, test results).
   - CHECKLIST: complexity discussed, edge cases covered, hints given.
   - AGENT NOTES: Observations from previous turns (SmartOrchestrator/StageReflection).
   - TARGET COMPANY calibration.

8. **Code details** — from ToolContextService. Actual code during REVIEW, "EDITOR IS EMPTY" during APPROACH.

9. **Test result summary** — from ToolContextService. Pass/fail counts during REVIEW.

10. **Question context** — title, description, optimal approach (reference only — do NOT reveal).

11. **Message type hint** — how to respond to constraint questions, clarifying questions, or candidate statements.

12. **Conversation history** — `earlierContext` (compressed summary) + rolling transcript (last N turns).

13. **Current state** — question index, interview stage, candidate analysis, hints given, current code.

**Truncation**: If prompt exceeds 16,000 chars (~4K tokens), truncates `earlierContext` to 500 chars first, then drops oldest transcript turns.

### 5.5 AgentOrchestrator

**analyzeAndTransition()** step by step:
1. Load fresh memory from Redis.
2. Call `ReasoningAnalyzer.analyze(memory, candidateMessage)`.
3. Based on `result.suggestedTransition`:
   - `CodingChallenge`: Transition WS state → `CODING_CHALLENGE`. Frontend shows code editor.
   - `Evaluating`: Call `handleQuestionComplete()` — if more questions remain, calls `ConversationEngine.transitionToNextQuestion()`. Otherwise, transitions to `EVALUATING` and launches report generation.
   - `FollowUp`: Transition WS state → `FOLLOW_UP`. Gaps stored in Redis for next prompt.
   - `null`: No action.

**Coroutine scope**: `reportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`.

### 5.6 ReasoningAnalyzer

**Prompt**: System prompt requests JSON with `approach`, `confidence` (high/medium/low), `correctness` (correct/partial/incorrect), `gaps` (list of knowledge gaps), `codingSignalDetected` (ready to code?), `readyForEvaluation` (review complete?).

**Analysis result**: `AnalysisResult(analysis: CandidateAnalysis, suggestedTransition: InterviewState?)`.

**Transition logic**:
- `codingSignalDetected && stage in [CLARIFYING, APPROACH, PROBLEM_PRESENTED]` → `CodingChallenge`
- `readyForEvaluation && stage in [REVIEW, FOLLOWUP, WRAP_UP]` → `Evaluating`
- `gaps.isNotEmpty() && stage not in [CODING, SMALL_TALK]` → `FollowUp`

**Score updates**: On `correct` → +1.5 problemSolving. On `partial` → +0.75. Each gap → -0.25 communication.

### 5.7 FollowUpGenerator

- Triggered when gaps detected in ReasoningAnalyzer output.
- Max 3 follow-ups per question.
- Prompt: "Generate a targeted follow-up question addressing one of the gaps." Includes gaps, previously asked follow-ups (to avoid repeats), question context.
- The follow-up question is appended to `memory.followUpsAsked`.

### 5.8 HintGenerator

**Hint levels**: Level 1 = abstract (point toward a concept, -0.5 from problemSolving). Level 2 = names a data structure (-1.0). Level 3 = describes approach without code (-1.5).

**Refusal**: If `hintsGiven >= 3`, sends `HINT_DELIVERED` with `refused: true` and "You've used all available hints."

**Prompt**: Includes question title, optimal approach (context only), candidate's current approach, known gaps. "Provide a Level N hint."

### 5.9 EvaluationAgent

**Prompt structure**: `BASE_SYSTEM_PROMPT` (scoring rules: 0-10 scale, hints deduct -0.5 each) + category-specific criteria + JSON schema with `nextSteps`.

**Category-specific criteria**:
- CODING/DSA: problemSolving, algorithmChoice, codeQuality, communication, efficiency, testing (standard definitions).
- BEHAVIORAL (STAR): Maps dimensions to STAR components — Situation, Task, Action, Result, Depth, Growth.
- SYSTEM_DESIGN: Requirements, architecture, data modeling, trade-offs, scalability, reliability.

**Scoring formula** (in ReportService):
```
overallScore = problemSolving × 0.25 + algorithmChoice × 0.20 + codeQuality × 0.20
             + communication × 0.15 + efficiency × 0.10 + testing × 0.10
```

**Input**: Category, difficulty, question title/description, transcript summary, final code, candidate analysis, hints given, follow-ups asked.

**Output**: `EvaluationResult` with strengths, weaknesses, suggestions, nextSteps (area, specificGap, evidenceFromInterview, actionItem, resource, priority), narrativeSummary, dimensionFeedback, scores.

---

## 6. Interview Memory Architecture

### 6.1 InterviewMemory Data Class

| Field | Type | Default | Purpose | When Updated |
|---|---|---|---|---|
| sessionId | UUID | — | Session identifier | Init |
| userId | UUID | — | Owner identifier | Init |
| state | String | "INTERVIEW_STARTING" | WS-level state machine | Every state transition |
| category | String | — | Interview category (CODING/DSA/BEHAVIORAL/SYSTEM_DESIGN) | Init |
| personality | String | — | AI personality (faang_senior/friendly/startup/adaptive) | Init |
| currentQuestion | InternalQuestionDto? | null | Active question with full details | Init, question transition |
| candidateAnalysis | CandidateAnalysis? | null | Latest AI analysis of candidate | After ReasoningAnalyzer |
| hintsGiven | Int | 0 | Hints used this question | After each hint |
| followUpsAsked | List\<String\> | [] | Follow-up questions asked | After each follow-up |
| timeElapsedSec | Long | 0 | Elapsed time | Not actively updated |
| currentCode | String? | null | Code in editor | Every CODE_UPDATE / CANDIDATE_MESSAGE |
| programmingLanguage | String? | null | Selected language | Init, language change |
| rollingTranscript | List\<TranscriptTurn\> | [] | Last N conversation turns | Every message |
| earlierContext | String | "" | Compressed older transcript | When transcript exceeds 6 turns |
| evalScores | EvalScores | all 0.0 | Running score estimates | After ReasoningAnalyzer |
| interviewStage | String | "SMALL_TALK" | Fine-grained 8-stage progression | After each AI response |
| currentQuestionIndex | Int | 0 | 0-based question index | Question transition |
| totalQuestions | Int | 1 | Total questions in session | Init |
| targetCompany | String? | null | Target company for calibration | Init |
| targetRole | String? | null | Target role | Init |
| experienceLevel | String? | null | junior/mid/senior/staff | Init |
| background | String? | null | Free-text background | Init |
| complexityDiscussed | Boolean | false | Whether complexity was discussed | SmartOrchestrator |
| edgeCasesCovered | Int | 0 | Count of edge cases covered | SmartOrchestrator |
| agentNotes | String | "" | AI observations (capped 500 chars) | SmartOrchestrator, StageReflectionAgent |
| lastTestResult | TestResultCache? | null | Cached test run results | After code submission |
| createdAt | Instant | now() | Memory creation time | Init |
| lastActivityAt | Instant | now() | Last activity timestamp | Every transcript append |

### 6.2 Redis Memory Layout

**Key format**: `interview:session:{sessionId}:memory`
**Serialization**: JSON via Jackson ObjectMapper.
**TTL**: 2 hours (configurable via `interview.redis-ttl-hours`). Refreshed on every WebSocket activity via `refreshTTL()`.

**RedisMemoryService methods**:
- `initMemory(sessionId, userId, config, firstQuestion, totalQuestions)` — creates initial memory.
- `getMemory(sessionId)` — throws `SessionNotFoundException` if missing.
- `updateMemory(sessionId, updater)` — atomic read-modify-write.
- `appendTranscriptTurn(sessionId, role, content)` — appends turn, triggers compression when > 6 turns.
- `refreshTTL(sessionId)` — resets TTL without deserializing.
- `deleteMemory(sessionId)` — cleanup on interview end.
- `memoryExists(sessionId)` — reconnect check.
- `appendAgentNote(sessionId, note)` — appends to agentNotes, caps at 500 chars.
- `setComplexityDiscussed(sessionId, value)`, `setEdgeCasesCovered(sessionId, count)`, `updateStage(sessionId, stage)`, `incrementQuestionIndex(sessionId)`.

### 6.3 Dual State Tracking

**State Machine 1 — WS-level state** (`InterviewMemory.state`):

```
INTERVIEW_STARTING → QUESTION_PRESENTED → CANDIDATE_RESPONDING → AI_ANALYZING
  → FOLLOW_UP / CODING_CHALLENGE / QUESTION_TRANSITION
  → ... (loops through CANDIDATE_RESPONDING → AI_ANALYZING)
  → EVALUATING → INTERVIEW_END
  Any state → EXPIRED (on timeout)
```

These are sent to the frontend as `STATE_CHANGE` messages and control UI behavior (show editor, show loading, show evaluating overlay).

**State Machine 2 — Interview stage** (`InterviewMemory.interviewStage`):

```
SMALL_TALK → PROBLEM_PRESENTED → CLARIFYING → APPROACH → CODING → REVIEW → FOLLOWUP → WRAP_UP
```

Forward-only. Controls AI behavior (what the AI is allowed to say/ask at each stage). Updated by `InterviewerAgent.updateInterviewStage()` (keyword-based) and `SmartOrchestrator` (LLM-driven, confidence > 0.85).

### 6.4 Transcript Compression

- `TranscriptCompressor` compresses the 2 oldest turns when rolling transcript exceeds 6 turns (configurable via `interview.transcript-max-turns`).
- Uses GPT-4o-mini to summarize turns into a brief sentence.
- Falls back to manual "role: content (truncated)" format if LLM fails.
- Compressed text is prepended to `earlierContext`.
- The 2 compressed turns are dropped from `rollingTranscript`.
- This keeps the prompt size bounded while preserving interview context.

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

### 7.2 LlmProviderRegistry

- **Provider selection**: Primary provider from `llm.provider` config (default: "openai").
- **Fallback**: Optional `llm.fallback-provider`. Used on `RateLimitException` or `ProviderUnavailableException`.
- **Stream fallback**: If streaming fails and fallback exists, falls back to `complete()` and emits entire response as single token.

### 7.3 Model Configuration

| Config Key | Default Model | Used For | Max Tokens Range |
|---|---|---|---|
| `llm.resolved.interviewer-model` | gpt-4o | InterviewerAgent streaming responses | 50-150 (stage-dependent) |
| `llm.resolved.background-model` | gpt-4o-mini | ReasoningAnalyzer, FollowUpGenerator, HintGenerator, SmartOrchestrator, StageReflectionAgent, TranscriptCompressor, InterviewerAgent fallback | 150-300 |
| `llm.resolved.generator-model` | gpt-4o | QuestionGeneratorService | 2000 |
| `llm.resolved.evaluator-model` | gpt-4o | EvaluationAgent | 2000 |

### 7.4 Streaming Implementation

1. `InterviewerAgent` creates `LlmRequest` with `messages: [SYSTEM prompt, USER message]`.
2. `LlmProviderRegistry.stream(request)` returns `Flow<String>`.
3. `OpenAiProvider.stream()` uses the OpenAI Java SDK 4.26.0 streaming API.
4. Each token is emitted as a `Flow` element.
5. `InterviewerAgent` collects tokens, appends to `StringBuilder`, and sends each as `OutboundMessage.AiChunk(delta=token, done=false)` via `WsSessionRegistry`.
6. After collection completes, sends `AiChunk(delta="", done=true)`.
7. **Timeout**: `withTimeout(10_000L)`. If no token arrives within 10 seconds, falls back to `complete()` with `backgroundModel`.

### 7.5 Provider Comparison

| Provider | Base URL | Notes |
|---|---|---|
| OpenAI | https://api.openai.com/v1 | Primary. GPT-4o + GPT-4o-mini. Streaming supported. |
| Groq | https://api.groq.com/openai/v1 | Fallback. Uses OpenAI-compatible API. Fast inference. |
| Gemini | https://generativelanguage.googleapis.com/v1beta/openai | Fallback. OpenAI-compatible wrapper. |

---

## 8. WebSocket Protocol

### 8.1 Connection and Auth

**URL**: `ws://localhost:8080/ws/interview/{sessionId}?token={JWT}`

`WsAuthHandshakeInterceptor` (order -100):
1. Extracts JWT from `?token=` query parameter.
2. Validates via `JwksValidator.validate()`.
3. Looks up user in DB by `clerkUserId`.
4. Verifies session exists and belongs to user.
5. Stores `userId` and `sessionId` in exchange attributes.
6. If JWT invalid or session mismatch: returns 401/403.

### 8.2 Complete Message Reference

#### Inbound Messages (Client → Server)

**CANDIDATE_MESSAGE**
- Fields: `text` (string), `codeSnapshot?` (CodeSnapshot)
- Handler: `ConversationEngine.handleCandidateMessage()`
- What happens: Persists message → streams AI response → background analysis

**CODE_RUN**
- Fields: `code` (string), `language` (string), `stdin?` (string)
- Handler: `CodeExecutionService.runCode()` (fire-and-forget)
- What happens: Submits to Judge0, sends `CODE_RUN_RESULT` when complete

**CODE_SUBMIT**
- Fields: `code` (string), `language` (string), `sessionQuestionId` (UUID), `stdin?` (string)
- Handler: `CodeExecutionService.submitCode()` (fire-and-forget)
- What happens: Runs all test cases concurrently, sends `CODE_RESULT`

**CODE_UPDATE**
- Fields: `code` (string), `language` (string)
- Handler: Updates `memory.currentCode` in Redis
- What happens: Syncs editor content to memory for AI awareness

**REQUEST_HINT**
- Fields: `hintLevel?` (int)
- Handler: `HintGenerator.generateHint()`
- What happens: Generates hint at next level, sends `HINT_DELIVERED`

**END_INTERVIEW**
- Fields: `reason?` (string: "CANDIDATE_ENDED" | "TIME_EXPIRED")
- Handler: `ConversationEngine.forceEndInterview()`
- What happens: Transitions to EVALUATING, launches report generation

**PING**
- Fields: none
- Handler: Responds with PONG
- What happens: Heartbeat keepalive

#### Outbound Messages (Server → Client)

**INTERVIEW_STARTED** — `sessionId`, `state`. Sent on first connect.

**AI_CHUNK** — `delta` (string), `done` (boolean). Streaming AI response tokens. `done=true` signals end of response.

**AI_MESSAGE** — `text`, `state`. Non-streaming complete AI message.

**STATE_CHANGE** — `state` (string). WS-level state machine transition. Frontend uses this to show/hide editor, overlays, etc.

**CODE_RUN_RESULT** — `stdout?`, `stderr?`, `exitCode?`. Simple code run output.

**CODE_RESULT** — `status`, `stdout?`, `stderr?`, `runtimeMs?`, `testResults?` (array of TestResult). Test case results for code submission.

**HINT_DELIVERED** — `hint`, `level`, `hintsRemaining`, `refused?`. Hint response.

**QUESTION_TRANSITION** — `questionIndex`, `questionTitle`, `questionDescription`, `codeTemplates?`. Multi-question interview transition.

**SESSION_END** — `reportId`. Report is ready. Frontend navigates to report page.

**STATE_SYNC** — `state`, `currentQuestionIndex`, `totalQuestions`, `currentQuestion?`, `currentCode?`, `programmingLanguage?`, `hintsGiven`, `messages[]`, `showCodeEditor`. Full state recovery on reconnect.

**ERROR** — `code`, `message`. Error notification. Codes: `SESSION_COMPLETED`, `SESSION_EXPIRED`, `SESSION_NOT_FOUND`, `AI_ERROR`, `SESSION_ERROR`.

**PONG** — Heartbeat response.

### 8.3 Message Flow Diagrams

**Scenario 1: Candidate sends a message (happy path)**
```
Client                  Server
  |--- CANDIDATE_MESSAGE --->|
  |                          |  persist to Redis + DB
  |<--- STATE_CHANGE --------|  state: CANDIDATE_RESPONDING
  |<--- AI_CHUNK ------------|  delta: "Interesting, "
  |<--- AI_CHUNK ------------|  delta: "so you're thinking..."
  |<--- AI_CHUNK ------------|  delta: "", done: true
  |<--- STATE_CHANGE --------|  state: AI_ANALYZING
  |                          |  (background: SmartOrchestrator + AgentOrchestrator)
```

**Scenario 2: Candidate runs code**
```
Client                  Server
  |--- CODE_RUN ------------>|
  |                          |  submit to Judge0, poll
  |<--- CODE_RUN_RESULT -----|  stdout, stderr, exitCode
```

**Scenario 3: Candidate submits code (test cases)**
```
Client                  Server
  |--- CODE_SUBMIT --------->|
  |                          |  run N test cases concurrently
  |<--- CODE_RESULT ---------|  status, testResults[]
```

**Scenario 4: Interview ends**
```
Client                  Server
  |--- END_INTERVIEW ------->|
  |<--- STATE_CHANGE --------|  state: EVALUATING
  |                          |  (background: EvaluationAgent ~10-15s)
  |<--- SESSION_END ---------|  reportId
  |  (navigates to /report/{sessionId})
```

**Scenario 5: WebSocket reconnect**
```
Client                  Server
  |--- WS connect ---------->|
  |                          |  detect ACTIVE session with existing memory
  |<--- STATE_SYNC ----------|  full state: messages[], code, stage, hints
  |  (restores UI state)
```

### 8.4 WsSessionRegistry

- `ConcurrentHashMap<UUID, WebSocketSession>` for sessions.
- `ConcurrentHashMap<UUID, Sinks.Many<String>>` for outbound message sinks.
- Heartbeat job every 30s: prunes disconnected sessions, sends PING.
- `register(sessionId, wsSession)`: Creates sink, returns `Flux<String>` for `session.send()`.
- `sendMessage(sessionId, message)`: Serializes to JSON, pushes to sink via `tryEmitNext`.

---

## 9. Database Schema

### 9.1 Entity Relationship Overview

`organizations` 1:N `users` 1:N `interview_sessions` 1:N `session_questions` N:1 `questions`
`interview_sessions` 1:N `conversation_messages`
`interview_sessions` 1:1 `evaluation_reports`
`session_questions` 1:N `code_submissions`

### 9.2 Complete Table Documentation

#### organizations
Purpose: Tenant container (currently 1:1 with user — personal org).

| Column | Type | Nullable | Default | Purpose |
|---|---|---|---|---|
| id | UUID | NO | gen_random_uuid() | PK |
| name | VARCHAR(255) | NO | — | Org name |
| type | VARCHAR(50) | NO | 'PERSONAL' | PERSONAL/COMPANY/UNIVERSITY |
| plan | VARCHAR(50) | NO | 'FREE' | FREE/PRO |
| seats_limit | INT | NO | 1 | Max users |
| created_at | TIMESTAMPTZ | YES | NOW() | — |

#### users
Purpose: Platform users (candidates).

| Column | Type | Nullable | Default | Purpose |
|---|---|---|---|---|
| id | UUID | NO | gen_random_uuid() | PK |
| org_id | UUID | NO | — | FK → organizations |
| clerk_user_id | VARCHAR(255) | NO | — | Clerk external ID (unique) |
| email | VARCHAR(255) | NO | — | User email |
| full_name | VARCHAR(255) | YES | — | Display name |
| role | VARCHAR(50) | NO | 'CANDIDATE' | User role |
| subscription_tier | VARCHAR(50) | NO | 'FREE' | FREE/PRO |
| created_at | TIMESTAMPTZ | YES | NOW() | — |

Indexes: `idx_users_clerk_user_id` (clerk_user_id), `idx_users_org_id` (org_id)

#### questions
Purpose: Interview question bank (AI-generated or manual).

| Column | Type | Nullable | Default | Purpose |
|---|---|---|---|---|
| id | UUID | NO | gen_random_uuid() | PK |
| title | VARCHAR(500) | NO | — | Question title |
| description | TEXT | NO | — | Full problem description |
| type | VARCHAR(50) | NO | — | DSA/CODING/SYSTEM_DESIGN/BEHAVIORAL |
| difficulty | VARCHAR(50) | NO | — | EASY/MEDIUM/HARD |
| topic_tags | TEXT[] | YES | — | Array of topic tags |
| examples | TEXT | YES | — | JSON examples |
| constraints_text | TEXT | YES | — | Problem constraints |
| test_cases | TEXT | YES | — | JSON test cases for Judge0 |
| solution_hints | TEXT | YES | — | JSON progressive hints |
| optimal_approach | TEXT | YES | — | Reference solution approach |
| follow_up_prompts | TEXT | YES | — | JSON follow-up questions |
| created_at | TIMESTAMPTZ | YES | NOW() | — |
| source | VARCHAR(50) | YES | 'AI_GENERATED' | AI_GENERATED/MANUAL |
| deleted_at | TIMESTAMPTZ | YES | — | Soft delete |
| generation_params | TEXT | YES | — | JSON generation parameters |
| space_complexity | VARCHAR(50) | YES | — | e.g., "O(n)" |
| time_complexity | VARCHAR(50) | YES | — | e.g., "O(n log n)" |
| evaluation_criteria | TEXT | YES | — | JSON evaluation criteria |
| slug | VARCHAR(500) | YES | — | URL-friendly slug |
| interview_category | VARCHAR(50) | YES | — | CODING/DSA/BEHAVIORAL/SYSTEM_DESIGN |
| code_templates | TEXT | YES | '{}' | JSON {language: template} |
| function_signature | TEXT | YES | '{}' | JSON {language: signature} |

#### interview_sessions
Purpose: Interview session records.

| Column | Type | Nullable | Default | Purpose |
|---|---|---|---|---|
| id | UUID | NO | gen_random_uuid() | PK |
| user_id | UUID | NO | — | FK → users |
| status | VARCHAR(50) | NO | 'PENDING' | PENDING/ACTIVE/COMPLETED/ABANDONED/EXPIRED |
| type | VARCHAR(50) | NO | — | Interview type |
| config | TEXT | NO | '{}' | JSON InterviewConfig |
| started_at | TIMESTAMPTZ | YES | — | When WS connected |
| ended_at | TIMESTAMPTZ | YES | — | When interview ended |
| duration_secs | INT | YES | — | Total duration |
| created_at | TIMESTAMPTZ | YES | NOW() | — |
| last_heartbeat | TIMESTAMPTZ | YES | — | Last WS activity |
| current_stage | VARCHAR(30) | YES | 'SMALL_TALK' | Current interview stage |
| integrity_signals | TEXT | YES | — | JSON proctoring signals |

Indexes: `idx_sessions_user_id` (user_id), `idx_sessions_heartbeat` (last_heartbeat)

#### session_questions
Purpose: Links sessions to questions with ordering.

| Column | Type | Nullable | Default | Purpose |
|---|---|---|---|---|
| id | UUID | NO | gen_random_uuid() | PK |
| session_id | UUID | NO | — | FK → interview_sessions |
| question_id | UUID | NO | — | FK → questions |
| order_index | INT | NO | — | Question order (0-based) |
| final_code | TEXT | YES | — | Last submitted code |
| language_used | VARCHAR(50) | YES | — | Programming language |
| submitted_at | TIMESTAMPTZ | YES | — | Code submission time |
| created_at | TIMESTAMPTZ | YES | NOW() | — |

#### conversation_messages
Purpose: Persistent transcript (separate from Redis rolling transcript).

| Column | Type | Nullable | Default | Purpose |
|---|---|---|---|---|
| id | UUID | NO | gen_random_uuid() | PK |
| session_id | UUID | NO | — | FK → interview_sessions |
| role | VARCHAR(50) | NO | — | AI/CANDIDATE/SYSTEM |
| content | TEXT | NO | — | Message content |
| metadata | TEXT | YES | — | JSON metadata |
| created_at | TIMESTAMPTZ | YES | NOW() | — |

#### code_submissions
Purpose: Code execution results.

| Column | Type | Nullable | Default | Purpose |
|---|---|---|---|---|
| id | UUID | NO | gen_random_uuid() | PK |
| session_question_id | UUID | NO | — | FK → session_questions |
| user_id | UUID | NO | — | FK → users |
| code | TEXT | NO | — | Submitted code |
| language | VARCHAR(50) | NO | — | Language |
| status | VARCHAR(50) | NO | 'PENDING' | ACCEPTED/WRONG_ANSWER/etc. |
| judge0_token | VARCHAR(255) | YES | — | Judge0 submission token |
| test_results | TEXT | YES | — | JSON test results |
| runtime_ms | INT | YES | — | Execution time |
| memory_kb | INT | YES | — | Memory used |
| submitted_at | TIMESTAMPTZ | YES | NOW() | — |

#### evaluation_reports
Purpose: Post-interview evaluation results.

| Column | Type | Nullable | Default | Purpose |
|---|---|---|---|---|
| id | UUID | NO | gen_random_uuid() | PK |
| session_id | UUID | NO | — | FK → interview_sessions |
| user_id | UUID | NO | — | FK → users |
| overall_score | DECIMAL(4,2) | YES | — | Weighted overall 0-10 |
| problem_solving_score | DECIMAL(4,2) | YES | — | 0-10 |
| algorithm_score | DECIMAL(4,2) | YES | — | 0-10 |
| code_quality_score | DECIMAL(4,2) | YES | — | 0-10 |
| communication_score | DECIMAL(4,2) | YES | — | 0-10 |
| efficiency_score | DECIMAL(4,2) | YES | — | 0-10 |
| testing_score | DECIMAL(4,2) | YES | — | 0-10 |
| strengths | TEXT | YES | — | JSON string[] |
| weaknesses | TEXT | YES | — | JSON string[] |
| suggestions | TEXT | YES | — | JSON string[] |
| narrative_summary | TEXT | YES | — | Free-text summary |
| dimension_feedback | TEXT | YES | — | JSON {dimension: feedback} |
| hints_used | INT | NO | 0 | Hints consumed |
| next_steps | TEXT | YES | — | JSON NextStep[] |
| completed_at | TIMESTAMPTZ | YES | — | Report completion |
| created_at | TIMESTAMPTZ | YES | NOW() | — |

#### interview_templates, org_invitations
Exist but are not actively used in the current implementation.

### 9.3 Migration History

| Version | File | What Changed |
|---|---|---|
| V1 | create_organizations | organizations table with type enum |
| V2 | create_users | users table with FK to organizations |
| V3 | create_interview_tables | questions, interview_sessions, session_questions, conversation_messages |
| V4 | create_code_and_reports | code_submissions, evaluation_reports |
| V5 | create_misc | interview_templates, org_invitations |
| V6 | extend_questions_table | Added source, deleted_at, generation_params, complexities, slug, interview_category, code_templates, function_signature |
| V7 | extend_evaluation_reports | Added dimension_feedback, hints_used, completed_at |
| V8 | convert_enums_to_varchar | Converted org_type, session_status, interview_type from PG enums to VARCHAR |
| V9 | convert_remaining_enums | Converted user_role, difficulty, message_role, submission_status to VARCHAR |
| V10 | convert_jsonb_to_text | Converted all JSONB columns to TEXT for R2DBC compatibility |
| V11 | add_session_heartbeat | Added last_heartbeat to interview_sessions |
| V12 | add_stage_and_templates | Added current_stage to sessions, code_templates/function_signature defaults |
| V13 | add_next_steps_and_integrity | Added next_steps to reports, integrity_signals to sessions |

---

## 10. Authentication & Security

### 10.1 JWT Flow End to End

1. Frontend loads Clerk `<SignIn>` component.
2. User authenticates via Clerk (email/password, Google, GitHub).
3. Clerk issues JWT with `sub` (userId), `email`, `name` claims.
4. Frontend stores JWT via `useAuth().getToken()`.
5. `AuthTokenBridge` in `providers/index.tsx` calls `setAuthTokenProvider()` with Clerk's `getToken`.
6. Axios request interceptor attaches `Authorization: Bearer {token}` to every request.
7. Backend: `ClerkJwtAuthFilter` extracts token, validates via `JwksValidator`.
8. `JwksValidator` fetches JWKS from Clerk (cached 60min in `JwksCache`), verifies signature + expiry.
9. `UserBootstrapService.getOrCreateUser()` finds or creates User entity.
10. Filter sets `UsernamePasswordAuthenticationToken` with `User` as principal.

### 10.2 ClerkJwtAuthFilter

- **Order**: -200 (runs before Spring Security filter chain).
- **Skips**: Paths not starting with `/api/`.
- **Checks**: Bearer token present, JWT valid, not expired.
- **Creates**: `UsernamePasswordAuthenticationToken(user, null, emptyList())`.
- **On failure**: Returns 401 with JSON `{ error, message, timestamp }`.

### 10.3 WsAuthHandshakeInterceptor

- Different from REST auth because WebSocket connections can't set Authorization headers.
- JWT is passed as `?token=` query parameter.
- Validates JWT, looks up user in DB, verifies session ownership.
- Caches `sessionId → userId` mapping in `authCache` (ConcurrentHashMap) for reconnect scenarios.

### 10.4 UserBootstrapService

- `getOrCreateUser(clerkUserId, email, fullName)`:
  1. Check Redis cache: `user:clerk:{clerkUserId}` (5min TTL).
  2. If miss: Query DB by clerkUserId.
  3. If not in DB: Create new Organization (PERSONAL, FREE) + User in a transaction.
  4. Cache result in Redis.
- Race condition handling: Catches unique constraint violation on clerk_user_id, retries DB lookup.

### 10.5 Rate Limiting

- Redis key: `ratelimit:{userId}:{epochMinute}`.
- Default limit: 60 requests per minute (configurable via `rate-limit.requests-per-minute`).
- TTL: 2 minutes per key.
- Returns 429 with `Retry-After: 60` header.

### 10.6 Public Endpoints

| Endpoint | Why Public |
|---|---|
| `GET /health` | Infrastructure health check |
| `GET /actuator/**` | Spring Boot monitoring |
| `/ws/**` | WebSocket — auth via handshake interceptor, not filter |
| `GET /api/v1/code/languages` | Language list for UI — no user context needed |

---

## 11. Code Execution (Judge0)

### 11.1 Judge0Client

- **Submission**: POST to Judge0 `/submissions?base64_encoded=true&wait=false`. Source code and stdin are Base64 encoded.
- **Java normalization**: If code contains `class` but not `class Main`, replaces the class name with `Main` (Judge0 requires main class).
- **Polling**: GET `/submissions/{token}?base64_encoded=true` every 500ms (configurable). Timeout after 30 seconds (configurable). Throws `Judge0TimeoutException` on timeout.
- **Result decoding**: stdout, stderr, compileOutput are Base64-decoded. Status ID >= 3 means finished.

### 11.2 CodeExecutionService

- **runCode()**: Fire-and-forget. Submits code to Judge0, polls result, sends `CODE_RUN_RESULT` (stdout/stderr/exitCode). No persistence.
- **submitCode()**: Loads test cases from Question entity. Runs each test case concurrently (`coroutineScope { testCases.map { async { ... } } }`). Compares stdout to expected output. Persists `CodeSubmission`. Sends `CODE_RESULT` with per-test-case results. If all tests pass, transitions to FollowUp state.

### 11.3 Supported Languages

| Language | Judge0 ID |
|---|---|
| JavaScript | 63 |
| Java | 62 |
| Python / Python3 | 71 |
| C++ / CPP | 54 |
| Go / Golang | 60 |
| Ruby | 72 |
| C# / CSharp | 51 |
| TypeScript | 74 |
| Rust | 73 |
| Swift | 83 |
| Kotlin | 78 |
| PHP | 68 |

---

## 12. Report Generation

### 12.1 ReportService

**Idempotency**: Checks if report already exists for sessionId before generating.

**Pipeline** (`generateAndSaveReport()`):
1. Load InterviewMemory from Redis.
2. Load InterviewSession from DB.
3. Call `EvaluationAgent.evaluate(memory)` — returns `EvaluationResult`.
4. Compute weighted overall score:
   ```
   overall = problemSolving × 0.25 + algorithmChoice × 0.20 + codeQuality × 0.20
            + communication × 0.15 + efficiency × 0.10 + testing × 0.10
   ```
   Clamped to [0.0, 10.0].
5. Serialize strengths, weaknesses, suggestions, nextSteps, dimensionFeedback as JSON.
6. Save `EvaluationReport` to DB.
7. Update session: `status = COMPLETED`, set `endedAt` and `durationSecs`.
8. Increment usage via `UsageLimitService.incrementUsage()`.
9. Send `SESSION_END` WS message with reportId.
10. Delete Redis memory (cleanup).

### 12.2 EvaluationAgent

Category-specific system prompts:
- CODING/DSA criteria: problem understanding, algorithm selection, code quality, communication, efficiency, testing.
- BEHAVIORAL criteria (STAR): Situation, Task, Action, Result, Depth, Growth — mapped to same 6 score fields.
- SYSTEM_DESIGN criteria: requirements, architecture, data modeling, trade-offs, scalability, reliability.

JSON output schema includes `nextSteps` array with `area`, `specificGap`, `evidenceFromInterview`, `actionItem`, `resource`, `priority` (HIGH/MEDIUM/LOW).

Retry: 2 attempts, then falls back to default scores (all 3.0) with generic feedback.

### 12.3 Frontend Report Polling

`useReport` hook:
- Queries `GET /api/v1/reports/{sessionId}`.
- `staleTime: Infinity` (report never changes after creation).
- Retries on 404 (report still generating) up to 10 times with 3-second delay.
- Does not retry on other errors.
- Shows "Generating your report..." with spinner while retrying.

---

## 13. Interview Flow — Complete Technical Walkthrough

### Step 1: User Configures Interview

1. Frontend: `InterviewSetupPage` renders form (category, difficulty, personality, language, company, experience, background, duration).
2. User clicks "Start Interview".
3. `useStartInterview()` calls `POST /api/v1/interviews/sessions` with `InterviewConfig` body.

### Step 2: Session Creation

4. `InterviewController.startSession()` extracts User from auth.
5. `InterviewSessionService.startSession()`:
   a. `UsageLimitService.checkUsageAllowed()` — checks Redis monthly counter.
   b. Determines question count: CODING/DSA=2, BEHAVIORAL=3, SYSTEM_DESIGN=1.
   c. `QuestionService.selectQuestionsForSession()` — finds or generates questions via DB random query or GPT-4o generation.
   d. Saves `InterviewSession` (status=PENDING) to DB.
   e. Saves `SessionQuestion` entries (one per question, with orderIndex).
   f. `RedisMemoryService.initMemory()` — creates InterviewMemory in Redis (TTL 2h).
   g. Returns `StartSessionResponse(sessionId, wsUrl)`.
6. Frontend navigates to `/interview/{sessionId}`.

### Step 3: WebSocket Connection

7. `InterviewPage` mounts, `useInterviewSocket` opens WS to `/ws/interview/{sessionId}?token={JWT}`.
8. `WsAuthHandshakeInterceptor` validates JWT, checks session ownership.
9. `InterviewWebSocketHandler.onConnect()`:
   a. Session status = PENDING → marks ACTIVE, sets `startedAt`.
   b. Calls `ConversationEngine.startInterview(sessionId)`.

### Step 4: Interview Starts

10. `ConversationEngine.startInterview()`:
    a. Transitions WS state → QUESTION_PRESENTED.
    b. Selects personality-specific opening message.
    c. Streams opening as AI_CHUNK frames.
    d. Persists to Redis transcript + DB.
11. Frontend: `AI_CHUNK` messages populate conversation panel via `useConversation.appendAiToken()`.

### Step 5: Candidate Interaction Loop

12. Candidate types a message. Frontend sends `CANDIDATE_MESSAGE` with code snapshot.
13. `InterviewWebSocketHandler` routes to `ConversationEngine.handleCandidateMessage()`.
14. Memory updated with message. WS state → CANDIDATE_RESPONDING.
15. `InterviewerAgent.streamResponse()`:
    a. `StateContextBuilder.build()` fetches fresh state from Redis + DB.
    b. `ToolContextService.fetchForStage()` gets stage-specific context.
    c. CODING GATE: If CODING stage and no code → canned response.
    d. `PromptBuilder.buildSystemPrompt()` assembles full prompt.
    e. `LlmProviderRegistry.stream()` → AI_CHUNK frames to client.
    f. `updateInterviewStage()` checks for stage transitions.
16. WS state → AI_ANALYZING.
17. Background: `SmartOrchestrator.orchestrate()` + `AgentOrchestrator.analyzeAndTransition()`.
18. Repeat from step 12.

### Step 6: Interview Ends

19. Candidate clicks "End Interview" → sends `END_INTERVIEW`.
20. `ConversationEngine.forceEndInterview()` → WS state → EVALUATING.
21. Frontend shows "Generating your report..." overlay.
22. Background: `ReportService.generateAndSaveReport()` (see Section 12).
23. `SESSION_END` sent with reportId.
24. Frontend navigates to `/report/{sessionId}` after 2-second delay.

### Step 7: Report Viewing

25. `ReportPage` mounts. `useReport` queries `GET /api/v1/reports/{sessionId}`.
26. If 404 (report still generating): retries up to 10 times, 3s apart.
27. Report renders: hero score (animated), radar chart, dimension bars with tooltips, narrative, strengths/weaknesses/suggestions, study plan (next steps), session details.

---

## 14. API Reference

### Sessions

**POST /api/v1/interviews/sessions** — Create new interview session
- Auth: Required
- Body: `{ category, difficulty, personality, programmingLanguage?, targetRole?, targetCompany?, durationMinutes, experienceLevel?, background? }`
- Response (201): `{ sessionId, wsUrl }`
- Errors: 429 (usage limit), 400 (missing body)

**GET /api/v1/interviews/sessions** — List user's sessions
- Auth: Required
- Params: `page` (default 0), `size` (default 20)
- Response: `{ content: SessionSummaryDto[], page, size, total }`

**GET /api/v1/interviews/sessions/{sessionId}** — Get session detail
- Auth: Required (must own session)
- Response: SessionDetailDto with questions
- Errors: 404, 403

**POST /api/v1/interviews/sessions/{sessionId}/end** — End session
- Auth: Required (must own session)
- Response: 204

### Reports

**GET /api/v1/reports/{sessionId}** — Get interview report
- Auth: Required
- Response: ReportDto with scores, feedback, nextSteps
- Errors: 404 (not found or still generating)

**GET /api/v1/reports** — List user's reports
- Auth: Required
- Params: `page` (default 0), `size` (default 10)
- Response: ReportSummaryDto[]

### Stats

**GET /api/v1/users/me/stats** — Get user statistics
- Auth: Required
- Response: `{ totalInterviews, completedInterviews, averageScore, bestScore, interviewsThisMonth, freeInterviewsRemaining, scoreByCategory, scoreByDifficulty }`

### User

**GET /api/v1/users/me** — Get current user
- Auth: Required
- Response: UserDto

### Code

**POST /api/v1/code/run** — Run code (no test cases)
- Auth: Required
- Body: `{ sessionId, code, language, stdin? }`
- Response: 202 (result sent via WS)

**POST /api/v1/code/submit** — Submit code against test cases
- Auth: Required
- Body: `{ sessionId, sessionQuestionId, code, language }`
- Response: 202 (result sent via WS)

**GET /api/v1/code/languages** — List supported languages
- Auth: Not required
- Response: `{ languages: string[] }`

### Questions

**GET /api/v1/questions** — List questions (candidate view)
- Auth: Required
- Response: CandidateQuestionDto[]

**GET /api/v1/questions/{id}** — Get single question
- Auth: Required
- Response: CandidateQuestionDto

**POST /api/v1/admin/questions/generate** — Generate question via AI
- Auth: Required (admin)
- Body: QuestionGenerationParams
- Response (201): InternalQuestionDto

### Integrity

**POST /api/v1/integrity** — Report proctoring signals
- Auth: Required
- Body: `{ sessionId, signals: [{ type, timestamp, metadata? }] }`
- Response: 204

### Health

**GET /health** — Health check
- Auth: Not required
- Response: `{ status, database, redis }`

---

## 15. Configuration Reference

### 15.1 Backend Environment Variables

| Variable | Default | Required | Purpose |
|---|---|---|---|
| `DATABASE_URL` | — | Yes | PostgreSQL JDBC URL for Flyway |
| `FLYWAY_URL` | — | Yes | PostgreSQL JDBC URL for Flyway |
| `SPRING_R2DBC_URL` | — | Yes | R2DBC connection URL |
| `SPRING_R2DBC_USERNAME` | — | Yes | DB username |
| `SPRING_R2DBC_PASSWORD` | — | Yes | DB password |
| `REDIS_URL` | localhost:6379 | No | Redis connection |
| `OPENAI_API_KEY` | — | Yes | OpenAI API key |
| `CLERK_JWKS_URL` | — | Yes | Clerk JWKS endpoint |
| `CLERK_SECRET_KEY` | — | No | Clerk secret (unused currently) |
| `JUDGE0_BASE_URL` | http://localhost:2358 | No | Judge0 API |
| `JUDGE0_AUTH_TOKEN` | — | No | Judge0 auth |
| `CORS_ALLOWED_ORIGINS` | http://localhost:3000 | No | CORS origins |

### 15.2 Frontend Environment Variables

| Variable | Required | Purpose |
|---|---|---|
| `VITE_CLERK_PUBLISHABLE_KEY` | Yes | Clerk frontend key |
| `VITE_API_BASE_URL` | No (default: http://localhost:8080) | Backend REST API URL |
| `VITE_WS_BASE_URL` | No (default: ws://localhost:8080) | WebSocket URL |

### 15.3 application.yml Key Blocks

- `spring.r2dbc`: Connection pool (initial 5, max 20).
- `spring.flyway`: JDBC-based migrations (R2DBC doesn't support Flyway natively).
- `clerk.jwks-cache-ttl-minutes`: 60 (JWKS cache TTL).
- `llm.provider`: "openai" (primary LLM provider name).
- `llm.resolved.*`: Model names for each agent role.
- `interview.free-tier-limit`: 3 (monthly interview cap for free users).
- `interview.redis-ttl-hours`: 2 (session memory TTL).
- `interview.transcript-max-turns`: 6 (before compression kicks in).
- `rate-limit.requests-per-minute`: 60.
- `judge0.poll-interval-ms`: 500, `poll-timeout-secs`: 30.

---

## 16. Local Development Setup

### Prerequisites

- Java 17+ (for Spring Boot 3.5.x)
- Maven 3.8+
- Node.js 18+ with npm
- Docker and Docker Compose (for PostgreSQL, Redis, Judge0)

### Step-by-Step Setup

```bash
# 1. Start infrastructure
docker-compose up -d

# 2. Wait for services to be healthy
docker-compose ps  # All should show "healthy"

# 3. Set environment variables (or create .env)
export OPENAI_API_KEY=sk-...
export CLERK_JWKS_URL=https://your-clerk-instance.clerk.accounts.dev/.well-known/jwks.json
export VITE_CLERK_PUBLISHABLE_KEY=pk_test_...

# 4. Start backend
cd backend && mvn spring-boot:run

# 5. Start frontend (new terminal)
cd frontend && npm install && npm run dev

# 6. Open http://localhost:3000
```

### Infrastructure Services

| Service | Port | Health Check |
|---|---|---|
| PostgreSQL | 5432 | `docker exec -it ai-interview-platform-postgres-1 pg_isready` |
| Redis | 6379 | `docker exec -it ai-interview-platform-redis-1 redis-cli ping` |
| Judge0 Server | 2358 | `curl http://localhost:2358/system_info` |

### Useful Commands

```bash
# Backend
cd backend && mvn spring-boot:run        # Start
cd backend && mvn test                   # All tests
cd backend && mvn clean test             # Force recompile + test
cd backend && mvn compile                # Compile only

# Frontend
cd frontend && npm run dev               # Dev server (:3000)
cd frontend && npm run build             # Production build
cd frontend && npx playwright test       # E2E tests

# Infrastructure
docker-compose up -d                     # Start all
docker-compose down                      # Stop all
docker-compose logs -f judge0-server     # View Judge0 logs
```

---

## 17. Architecture Decisions Log

| Decision | Why | Trade-off |
|---|---|---|
| R2DBC instead of JDBC | Fully reactive stack with Spring WebFlux and coroutines | Flyway requires separate JDBC URL for migrations; no JPA/Hibernate |
| TEXT columns instead of JSONB | R2DBC PostgreSQL driver doesn't map JSONB natively (V10 migration) | Lost PostgreSQL JSON operators; all JSON parsed in application layer |
| VARCHAR instead of PG enums | R2DBC doesn't map PostgreSQL enums well (V8-V9 migrations) | Lost DB-level enum validation; validated in application layer |
| Redis for interview memory | Sub-ms latency for per-turn state. Memory is ephemeral (2h TTL). | Data loss if Redis restarts mid-interview; accepted for MVP |
| Fire-and-forget background tasks | Background agents (analysis, hints, orchestration) must not block streaming | Errors are logged but not surfaced to user. CoroutineExceptionHandler prevents silent swallowing. |
| Dual state machines | WS-level state controls frontend UI, interview stage controls AI behavior. They serve different purposes. | More complex than single state machine; but necessary separation of concerns |
| GPT-4o for interviewer, GPT-4o-mini for background | Interviewer needs highest quality for realistic conversation; background tasks are analytical and don't need 4o quality | Higher cost for interviewer responses |
| Jackson for Redis serialization | Consistent with Spring's JSON handling; human-readable in Redis | Larger than binary formats; acceptable for session-sized payloads |
| Clerk for auth | No custom auth logic needed; JWT validation only | Vendor lock-in; but auth is not core differentiator |
| Judge0 CE Docker | Self-hosted code sandbox; no external dependency for code execution | Requires Docker; Judge0 worker can be resource-heavy |
| Transcript compression at 6 turns | Keeps prompt under token limit while preserving context | Lossy — compressed turns lose detail. 6-turn window chosen empirically. |

---

## 18. Known Limitations and Technical Debt

| Issue | Location | Impact | Fix Path |
|---|---|---|---|
| No WebSocket authentication refresh | `WsAuthHandshakeInterceptor` | JWT expires during long interviews; WS stays open but no re-auth | Add periodic token refresh via PING/PONG payload |
| Keyword-based stage transitions | `InterviewerAgent.updateInterviewStage()` | False positives on common words; SmartOrchestrator (LLM-based) partially mitigates | Fully migrate to SmartOrchestrator-driven transitions |
| No session cleanup for abandoned sessions | `InterviewSession` | Sessions stuck in ACTIVE status if user closes browser without ending | Add scheduled task to expire sessions with stale `last_heartbeat` |
| TranscriptCompressor uses LLM | `TranscriptCompressor.compress()` | Extra LLM call every ~3 turns; fallback is lossy | Consider local summarization or larger transcript window |
| Single-threaded Judge0 polling | `Judge0Client.pollResult()` | Blocks coroutine during polling loop | Use Judge0 webhook callback instead of polling |
| No test isolation for Redis | Tests | Tests that hit Redis need a running instance | Add Testcontainers for Redis in test suite |
| `updateInterviewStage` runs on main path | `InterviewerAgent` | Adds latency to response (though minimal — no LLM call) | Move to background if SmartOrchestrator fully replaces it |
| No pagination for conversation_messages | `ConversationMessageRepository` | `findRecentBySessionId` uses LIMIT but no cursor | Add cursor-based pagination if transcripts grow very long |
| `backgroundScope` never cancelled | `ConversationEngine` | Orphaned coroutines on application shutdown | Register `@PreDestroy` to cancel scope |
| InterviewMemory grows unbounded fields | `agentNotes` field | Capped at 500 chars by `appendAgentNote` but not enforced on read | Consider structured list instead of string concatenation |
| SPEC_DEV.md can drift from code | This document | Becomes stale as code evolves | Add CI check or generation script |
| No rate limiting on WebSocket messages | `InterviewWebSocketHandler` | Malicious client could flood messages | Add per-session WS rate limiter |

---

## 19. Glossary

| Term | Definition |
|---|---|
| **InterviewMemory** | The in-memory state of an active interview, stored in Redis. Contains everything the AI needs to generate contextual responses. |
| **interviewStage** | Fine-grained 8-stage progression (SMALL_TALK → WRAP_UP) that controls AI behavior. Stored as `InterviewMemory.interviewStage`. |
| **WS-level state** | Coarse state machine (INTERVIEW_STARTING, CANDIDATE_RESPONDING, AI_ANALYZING, etc.) sent to frontend via STATE_CHANGE. Stored as `InterviewMemory.state`. |
| **fire-and-forget** | Background coroutine launched with `backgroundScope.launch()` that does not block the caller. Used for AgentOrchestrator, SmartOrchestrator, report generation. |
| **earlierContext** | Compressed summary of old transcript turns. Generated by TranscriptCompressor when rolling transcript exceeds 6 turns. |
| **rollingTranscript** | The last N (default 6) conversation turns stored in InterviewMemory. Most recent context for prompt building. |
| **codeSnapshot** | A summary of the code editor state (content, language, hasMeaningfulCode, lineCount) sent with every CANDIDATE_MESSAGE from the frontend. |
| **backgroundModel** | GPT-4o-mini. Used for all background/analytical tasks: ReasoningAnalyzer, SmartOrchestrator, HintGenerator, FollowUpGenerator, StageReflectionAgent, TranscriptCompressor. |
| **interviewerModel** | GPT-4o. Used only for InterviewerAgent streaming responses — the conversational AI persona. |
| **evaluatorModel** | GPT-4o. Used for EvaluationAgent (post-interview scoring). |
| **generatorModel** | GPT-4o. Used for QuestionGeneratorService (AI question creation). |
| **StateContext** | Fresh snapshot of interview state fetched from Redis + DB by StateContextBuilder before every LLM call. Includes time remaining, code status, checklist progress, agent notes. |
| **ToolContext** | Stage-specific pre-fetched data from ToolContextService. REVIEW stage gets actual code + test results. APPROACH gets code existence status. CODING gets nothing. |
| **CODING GATE** | Optimization in InterviewerAgent that skips the LLM call entirely when stage is CODING and no meaningful code exists. Returns canned responses. Zero token cost. |
| **agentNotes** | Free-text field in InterviewMemory where SmartOrchestrator and StageReflectionAgent save observations about the candidate. Capped at 500 chars. Injected into prompts. |
| **complexityDiscussed** | Boolean tracked by SmartOrchestrator indicating whether the candidate has discussed time/space complexity. Used in prompts and evaluation readiness checks. |
| **SessionQuestion** | Join entity linking an InterviewSession to a Question with an `orderIndex`. Multiple questions per session (CODING=2, BEHAVIORAL=3, etc.). |
| **InternalQuestionDto** | Full question DTO with test cases, hints, optimal approach. Used by backend agents. Never exposed to candidates. |
| **CandidateQuestionDto** | Redacted question DTO without solutions or test cases. Returned by public API. |
