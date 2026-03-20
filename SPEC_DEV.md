# AI Interview Platform — Developer Specification

## 1. Project Overview

A full-stack AI-powered mock interview platform where candidates take real-time technical interviews (Coding, DSA, Behavioral, System Design) with an adaptive AI interviewer. The system uses a multi-agent AI architecture — GPT-4o streams conversational responses while GPT-4o-mini agents run background analysis, generate hints, and produce follow-up questions. Interviews run over WebSocket with live code execution via Judge0 CE. After each interview, an evaluation agent scores the candidate across six dimensions and generates a detailed report.

## 2. System Architecture

### High-Level Diagram (ASCII)

```
┌──────────────────────────────────────────────────────────────────────┐
│                         FRONTEND (React + Vite)                      │
│                         http://localhost:3000                         │
│  ┌──────────┐  ┌──────────────┐  ┌───────────┐  ┌──────────────┐   │
│  │Dashboard  │  │InterviewSetup│  │ Interview │  │   Report     │   │
│  │  Page     │  │    Page      │  │   Page    │  │    Page      │   │
│  └────┬──────┘  └──────┬───────┘  └─────┬─────┘  └──────┬───────┘   │
│       │ REST           │ REST       WS + REST        │ REST         │
└───────┼────────────────┼────────────────┼────────────┼───────────────┘
        │                │                │            │
   ┌────▼────────────────▼────────────────▼────────────▼───────────────┐
   │                    Clerk.dev (JWT Auth)                            │
   │              ClerkJwtAuthFilter (@Order -200)                      │
   │              RateLimitFilter    (@Order -150)                      │
   └────────────────────────┬──────────────────────────────────────────┘
                            │
   ┌────────────────────────▼──────────────────────────────────────────┐
   │                  BACKEND (Spring WebFlux)                          │
   │                  http://localhost:8080                              │
   │                                                                    │
   │  ┌─────────────┐  ┌──────────────────────────────────────────┐    │
   │  │ Controllers  │  │        WebSocket Handler                  │    │
   │  │ (REST API)   │  │    /ws/interview/{sessionId}              │    │
   │  └──────┬───────┘  └──────────────┬───────────────────────────┘    │
   │         │                         │                                │
   │  ┌──────▼─────────────────────────▼───────────────────────────┐    │
   │  │              Conversation Engine                            │    │
   │  │  ┌──────────────┐ ┌───────────────┐ ┌──────────────────┐  │    │
   │  │  │ Interviewer   │ │  Reasoning    │ │  Agent           │  │    │
   │  │  │ Agent (GPT-4o)│ │  Analyzer     │ │  Orchestrator    │  │    │
   │  │  └───────┬───────┘ │  (GPT-4o-mini)│ └────────┬─────────┘  │    │
   │  │          │         └───────────────┘          │             │    │
   │  │  ┌───────▼──────┐ ┌───────────────┐ ┌────────▼─────────┐  │    │
   │  │  │ PromptBuilder│ │ FollowUp Gen  │ │ Hint Generator   │  │    │
   │  │  └──────────────┘ │ (GPT-4o-mini) │ │ (GPT-4o-mini)    │  │    │
   │  │                   └───────────────┘ └──────────────────┘  │    │
   │  └────────────────────────────────────────────────────────────┘    │
   │         │              │                    │                      │
   │  ┌──────▼──────┐ ┌────▼─────┐  ┌───────────▼─────────────────┐   │
   │  │ PostgreSQL  │ │  Redis   │  │  LLM Provider Registry       │   │
   │  │ (R2DBC)     │ │ (Memory) │  │  OpenAI / Groq / Gemini      │   │
   │  │ :5432       │ │ :6379    │  └──────────────────────────────┘   │
   │  └─────────────┘ └──────────┘                                     │
   │         │                                                          │
   │  ┌──────▼────────────────────────────────────────────────────┐    │
   │  │              Judge0 CE (Code Execution)                    │    │
   │  │              http://localhost:2358                          │    │
   │  │  ┌──────────┐  ┌────────────┐  ┌──────────┐  ┌────────┐  │    │
   │  │  │  Server   │  │  Workers   │  │ Judge0 DB│  │Judge0  │  │    │
   │  │  │  :2358    │  │  (Resque)  │  │(Postgres)│  │ Redis  │  │    │
   │  │  └──────────┘  └────────────┘  └──────────┘  └────────┘  │    │
   │  └────────────────────────────────────────────────────────────┘    │
   └────────────────────────────────────────────────────────────────────┘
```

### Request Flow

**REST API Flow (dashboard, setup, reports):**
1. Frontend sends HTTP request with `Authorization: Bearer {clerkJWT}` via axios interceptor
2. `ClerkJwtAuthFilter` validates JWT against Clerk JWKS endpoint (cached 60min)
3. `RateLimitFilter` checks Redis counter (`ratelimit:{userId}:{epochMinute}`) — 60 req/min
4. Controller method executes, calling service layer (suspend coroutines)
5. Service reads/writes PostgreSQL via R2DBC repositories
6. JSON response returned to frontend

**WebSocket Interview Flow (real-time conversation):**
1. Frontend opens `ws://localhost:8080/ws/interview/{sessionId}?token={jwt}`
2. `WsAuthHandshakeInterceptor` validates JWT, stores userId in session attributes
3. `InterviewWebSocketHandler.onConnect()` checks Redis memory + DB state
4. If first connect: sends `INTERVIEW_STARTED`, calls `ConversationEngine.startInterview()`
5. If reconnect: loads messages from DB, sends `STATE_SYNC` with full state restore
6. On `CANDIDATE_MESSAGE`: `ConversationEngine.handleCandidateMessage()` →
   - Persists message to DB + Redis transcript
   - `InterviewerAgent.streamResponse()` builds prompt via `PromptBuilder`, calls LLM
   - Streams tokens as `AI_CHUNK { delta, done }` via WebSocket
   - Fire-and-forget: `AgentOrchestrator.analyzeAndTransition()` runs background analysis
7. On `CODE_RUN`/`CODE_SUBMIT`: `CodeExecutionService` submits to Judge0 REST API, polls result, sends `CODE_RESULT`
8. On `END_INTERVIEW`: `ConversationEngine.forceEndInterview()` transitions to EVALUATING → `ReportService.generateAndSaveReport()` → sends `SESSION_END { reportId }`

## 3. Tech Stack

### Backend (from pom.xml)

| Technology | Version | Purpose |
|---|---|---|
| Spring Boot (parent) | 3.5.9 | Application framework |
| Java | 21 | Runtime |
| Kotlin | 1.9.25 | Primary language |
| spring-boot-starter-webflux | 3.5.9 | Reactive HTTP + WebSocket server |
| spring-boot-starter-data-r2dbc | 3.5.9 | Async PostgreSQL access |
| spring-boot-starter-security | 3.5.9 | Security filter chain |
| spring-boot-starter-data-redis-reactive | 3.5.9 | Reactive Redis client |
| spring-boot-starter-actuator | 3.5.9 | Health endpoints |
| kotlinx-coroutines-core | (BOM) | Coroutine support |
| kotlinx-coroutines-reactor | (BOM) | Reactor-coroutine bridge |
| reactor-kotlin-extensions | (BOM) | Kotlin extensions for Reactor |
| jackson-module-kotlin | (BOM) | Kotlin data class serialization |
| r2dbc-postgresql | (BOM) | Async PostgreSQL R2DBC driver |
| postgresql (JDBC) | (BOM) | Flyway migration driver |
| flyway-core + flyway-database-postgresql | (BOM) | Database migrations |
| nimbus-jose-jwt | 10.8 | Clerk JWT/JWKS validation |
| openai-java | 4.26.0 | OpenAI API SDK |
| mockk-jvm | 1.13.12 | Test mocking |
| testcontainers | (BOM) | Redis containers for integration tests |

### Frontend (from package.json)

| Technology | Version | Purpose |
|---|---|---|
| React | 18.3.1 | UI framework |
| React Router | 7.13.1 | Client-side routing |
| TypeScript | 5.6.2 | Type safety |
| Vite | 5.4.10 | Build tool + dev server (port 3000) |
| Tailwind CSS | 4.2.1 | Utility-first styling |
| @tailwindcss/vite | 4.2.1 | Tailwind Vite plugin |
| @tanstack/react-query | 5.90.21 | Server state management + caching |
| @clerk/clerk-react | 5.61.3 | Authentication UI + JWT provider |
| @monaco-editor/react | 4.7.0 | Code editor (VS Code engine) |
| Recharts | 3.8.0 | Radar chart for evaluation reports |
| Axios | 1.13.6 | HTTP client with JWT interceptor |
| Lucide React | 0.577.0 | Icons |
| @radix-ui/* | various | Headless UI primitives (avatar, dialog, progress, select, tabs, tooltip) |
| date-fns | 4.1.0 | Date formatting |
| @playwright/test | 1.58.2 | E2E testing |

### External Services

| Service | Purpose | Configuration |
|---|---|---|
| Clerk.dev | JWT authentication (no custom auth) | `CLERK_JWKS_URL`, `CLERK_PUBLISHABLE_KEY` |
| OpenAI API | GPT-4o/4o-mini for all AI agents | `OPENAI_API_KEY`, `OPENAI_BASE_URL` |
| Groq API | Optional LLM fallback provider | `GROQ_API_KEY`, `GROQ_BASE_URL` |
| Google Gemini | Optional LLM fallback provider | `GEMINI_API_KEY`, `GEMINI_BASE_URL` |
| Judge0 CE | Sandboxed code execution (Docker) | `JUDGE0_BASE_URL`, `JUDGE0_AUTH_TOKEN` |

## 4. Backend Architecture

### Package Structure

```
com.aiinterview
├── auth/            → ClerkJwtAuthFilter (JWT validation @Order -200),
│                      RateLimitFilter (Redis counter @Order -150),
│                      JwksValidator, JwksCache, WsAuthHandshakeInterceptor
├── conversation/    → ConversationEngine (message flow orchestrator),
│                      InterviewerAgent (GPT-4o streaming responses),
│                      PromptBuilder (system prompt construction with stage rules),
│                      ReasoningAnalyzer (GPT-4o-mini candidate analysis),
│                      FollowUpGenerator (GPT-4o-mini follow-up questions),
│                      HintGenerator (GPT-4o-mini progressive hints),
│                      AgentOrchestrator (coordinates all agents post-response)
├── interview/
│   ├── dto/         → CandidateQuestionDto, InternalQuestionDto, SessionDto,
│   │                  InterviewConfig, ApiError, QuestionGenerationParams
│   ├── model/       → InterviewSession, Question, SessionQuestion,
│   │                  ConversationMessage, CodeSubmission, EvaluationReport,
│   │                  User, Organization, OrgInvitation, InterviewTemplate
│   ├── repository/  → R2DBC reactive repositories for all entities
│   ├── service/     → InterviewSessionService, QuestionService,
│   │                  QuestionGeneratorService, RedisMemoryService,
│   │                  CodeExecutionService, UsageLimitService,
│   │                  TranscriptCompressor, InterviewMemory (data class)
│   └── ws/          → InterviewWebSocketHandler, WsSessionRegistry,
│                      WsMessageTypes (all inbound/outbound message data classes)
├── code/            → Judge0Client (WebClient-based async HTTP),
│                      CodeExecutionService (run + submit with test cases)
├── report/          → EvaluationAgent (GPT-4o final scoring),
│                      ReportService (report generation + persistence)
├── user/            → UserBootstrapService (getOrCreateUser, Redis-cached 5min),
│                      UserController (GET /api/v1/users/me)
└── shared/
    ├── domain/      → Enums.kt (InterviewCategory, InterviewType, Difficulty)
    ├── ai/          → LlmProviderRegistry, LlmProvider (interface),
    │                  OpenAiProvider, LlmRequest, LlmResponse, ModelConfig
    └── config/      → SecurityConfig, CorsConfig, WebSocketConfig
```

### Domain Models

**InterviewSession** (`interview_sessions`)
- `id: UUID` (PK), `userId: UUID` (FK→users), `status: String` (PENDING|ACTIVE|COMPLETED|ABANDONED|EXPIRED), `type: String` (DSA|CODING|SYSTEM_DESIGN|BEHAVIORAL), `config: String` (JSONB), `startedAt`, `endedAt`, `durationSecs: Int?`, `lastHeartbeat`, `currentStage: String?` (SMALL_TALK|PROBLEM_PRESENTED|...|WRAP_UP)

**Question** (`questions`)
- `id: UUID` (PK), `title`, `description`, `type`, `difficulty`, `category`, `topicTags: Array<String>?`, `examples: String?` (JSONB), `constraintsText`, `testCases: String?` (JSONB), `solutionHints: String?` (JSONB), `optimalApproach`, `followUpPrompts: String?` (JSONB), `evaluationCriteria: String?` (JSONB), `codeTemplates: String?` (JSONB), `functionSignature: String?` (JSONB), `slug`, `source`, `deletedAt`

**SessionQuestion** (`session_questions`)
- `id: UUID` (PK), `sessionId: UUID` (FK), `questionId: UUID` (FK), `orderIndex: Int`, `finalCode`, `languageUsed`, `submittedAt`

**ConversationMessage** (`conversation_messages`)
- `id: UUID` (PK), `sessionId: UUID` (FK), `role: String` (AI|CANDIDATE|SYSTEM), `content: String`, `metadata: String?` (JSONB)

**EvaluationReport** (`evaluation_reports`)
- `id: UUID` (PK), `sessionId: UUID` (unique FK), `userId: UUID` (FK), 6 dimension scores (`BigDecimal(5,2)`), `overallScore`, `strengths/weaknesses/suggestions: String?` (JSONB arrays), `narrativeSummary`, `dimensionFeedback: String?` (JSONB map), `hintsUsed: Int`, `completedAt`

**CodeSubmission** (`code_submissions`)
- `id: UUID` (PK), `sessionQuestionId: UUID` (FK), `userId: UUID` (FK), `code`, `language`, `status` (PENDING|RUNNING|ACCEPTED|WRONG_ANSWER|TIME_LIMIT|RUNTIME_ERROR|COMPILE_ERROR), `judge0Token`, `testResults: String?` (JSONB), `runtimeMs`, `memoryKb`

**User** (`users`)
- `id: UUID` (PK), `orgId: UUID` (FK), `clerkUserId: String` (unique), `email`, `fullName`, `role` (CANDIDATE|RECRUITER|ORG_ADMIN), `subscriptionTier` (FREE|PRO)

**Organization** (`organizations`) — `id`, `name`, `type` (PERSONAL|COMPANY|UNIVERSITY), `plan`, `seatsLimit`

### API Endpoints

| Method | Path | Description | Auth |
|---|---|---|---|
| POST | `/api/v1/interviews/sessions` | Create session, select questions, init Redis memory → `StartSessionResponse` | Y |
| POST | `/api/v1/interviews/sessions/{id}/end` | Mark session COMPLETED with duration | Y |
| GET | `/api/v1/interviews/sessions/{id}` | Session detail with questions + report | Y |
| GET | `/api/v1/interviews/sessions` | Paginated session list → `PagedResponse<SessionSummaryDto>` | Y |
| GET | `/api/v1/reports/{sessionId}` | Full evaluation report or 404 | Y |
| GET | `/api/v1/reports` | Report list (newest first) | Y |
| GET | `/api/v1/users/me` | Current user profile | Y |
| GET | `/api/v1/users/me/stats` | Aggregated stats → `UserStatsDto` | Y |
| GET | `/api/v1/questions` | All non-deleted questions (candidate view) | N |
| GET | `/api/v1/questions/{id}` | Single question | N |
| POST | `/api/v1/admin/questions/generate` | AI question generation → `InternalQuestionDto` | Y |
| DELETE | `/api/v1/admin/questions/{id}` | Soft delete question | Y |
| GET | `/api/v1/code/languages` | Supported languages → `{ languages: string[] }` | N |
| GET | `/health` | DB + Redis health check | N |

### WebSocket Protocol

**Connection:** `ws://localhost:8080/ws/interview/{sessionId}?token={clerkJWT}`

Auth: `WsAuthHandshakeInterceptor` extracts JWT from `token` query param, validates via JWKS, stores `sessionId` + `userId` in WebSocket session attributes.

**Inbound Messages (Client → Server):**

| Type | Fields | Handler |
|---|---|---|
| `PING` | — | Responds with `PONG`, updates `lastHeartbeat` |
| `CANDIDATE_MESSAGE` | `text`, `codeSnapshot?` | → `ConversationEngine.handleCandidateMessage()` → AI streaming + background analysis |
| `CODE_RUN` | `code`, `language`, `stdin?` | → `CodeExecutionService.runCode()` → `CODE_RESULT` |
| `CODE_SUBMIT` | `code`, `language`, `sessionQuestionId?`, `stdin?` | → `CodeExecutionService.submitCode()` → `CODE_RESULT` with test results |
| `CODE_UPDATE` | `code`, `language` | Syncs editor state to Redis memory (no execution) |
| `REQUEST_HINT` | `hintLevel?` | → `HintGenerator.generateHint()` → `HINT_DELIVERED` |
| `END_INTERVIEW` | `reason` | → `ConversationEngine.forceEndInterview()` → EVALUATING → report |

**Outbound Messages (Server → Client):**

| Type | Fields | When Sent |
|---|---|---|
| `INTERVIEW_STARTED` | `sessionId`, `state` | First WebSocket connect |
| `AI_CHUNK` | `delta`, `done` | Each streamed token from GPT-4o; `done=true` on last |
| `AI_MESSAGE` | `text`, `state` | Non-streaming full AI response (fallback) |
| `STATE_CHANGE` | `state` | State machine transition (e.g., CANDIDATE_RESPONDING → AI_ANALYZING) |
| `CODE_RESULT` | `status`, `stdout`, `stderr`, `runtimeMs`, `testResults[]` | After Judge0 execution completes |
| `CODE_RUN_RESULT` | `stdout`, `stderr`, `exitCode` | After ad-hoc code run |
| `HINT_DELIVERED` | `hint`, `level`, `hintsRemaining`, `refused` | After hint generation |
| `QUESTION_TRANSITION` | `questionIndex`, `questionTitle`, `questionDescription`, `codeTemplates?` | Moving to next question in multi-Q interview |
| `SESSION_END` | `reportId` | Evaluation complete; frontend navigates to report page |
| `STATE_SYNC` | `state`, `currentQuestionIndex`, `totalQuestions`, `currentQuestion`, `currentCode`, `programmingLanguage`, `hintsGiven`, `messages[]`, `showCodeEditor` | Full state restore on WebSocket reconnection |
| `ERROR` | `code`, `message` | Any error during processing |
| `PONG` | — | Heartbeat response |

### Interview Agent Architecture

**Message flow for a candidate message:**

1. `InterviewWebSocketHandler` receives `CANDIDATE_MESSAGE` JSON frame
2. Extracts `text` and optional `codeSnapshot`; syncs code snapshot to Redis if meaningful
3. Calls `ConversationEngine.handleCandidateMessage(sessionId, content)`
4. ConversationEngine persists message to DB (`ConversationMessageRepository`) and Redis transcript (`RedisMemoryService.appendTranscriptTurn`)
5. Transitions state to `CANDIDATE_RESPONDING`, then `AI_ANALYZING`
6. Calls `InterviewerAgent.streamResponse(sessionId, memory, userMessage)`
7. InterviewerAgent classifies message type (CONSTRAINT_QUESTION, CLARIFYING_QUESTION, CANDIDATE_STATEMENT)
8. `PromptBuilder.buildSystemPrompt(memory, messageType)` constructs prompt:
   - Static BASE_PERSONA rules → stage-specific rules → category framework → question context → conversation history → current state
9. InterviewerAgent calls `LlmProviderRegistry.stream()` with interviewer model (GPT-4o, 10s timeout)
10. Tokens stream to client as `AI_CHUNK { delta, done }` frames
11. If streaming fails/times out: falls back to `llm.complete()` with background model
12. After response: `InterviewerAgent.updateInterviewStage()` detects stage transitions based on content
13. Response persisted to DB + Redis transcript
14. **Fire-and-forget:** `AgentOrchestrator.analyzeAndTransition(sessionId, candidateMessage)` launches on background coroutine scope
15. AgentOrchestrator calls `ReasoningAnalyzer.analyze()` (GPT-4o-mini) → returns `AnalysisResult` with gaps, confidence, suggested transition
16. Based on analysis: may trigger `FollowUpGenerator`, state transitions, or `handleQuestionComplete()`

**Stage tracking:** `InterviewMemory.interviewStage` — strict forward-only 8-stage progression:
```
SMALL_TALK → PROBLEM_PRESENTED → CLARIFYING → APPROACH → CODING → REVIEW → FOLLOWUP → WRAP_UP
```
Updated by `InterviewerAgent.updateInterviewStage()` based on content analysis (e.g., "go ahead and code" triggers APPROACH→CODING).

**State machine:** `InterviewMemory.state` — WS-level states:
```
INTERVIEW_STARTING → QUESTION_PRESENTED → CANDIDATE_RESPONDING → AI_ANALYZING → FOLLOW_UP → CODING_CHALLENGE → QUESTION_TRANSITION → EVALUATING → INTERVIEW_END
```

### LLM Integration

**Provider Registry:** `LlmProviderRegistry` supports OpenAI (primary), Groq, and Gemini as pluggable providers. Falls back to fallback provider on rate limit or unavailability.

**Model Configuration** (`ModelConfig` via `llm.resolved.*`):
- `interviewerModel`: gpt-4o (streaming interviewer responses)
- `backgroundModel`: gpt-4o-mini (reasoning, follow-ups, hints, compression)
- `generatorModel`: gpt-4o (question generation)
- `evaluatorModel`: gpt-4o (final evaluation reports)

**Prompt Building** (`PromptBuilder`):
1. BASE_PERSONA — static rules (senior interviewer, short responses, no code in chat)
2. Stage rules — per-stage behavior (SMALL_TALK: greet; CODING: stay silent; REVIEW: reference code lines)
3. Category framework — DSA/CODING/BEHAVIORAL/SYSTEM_DESIGN expectations
4. Question context — title, description, optimal approach (hidden from candidate)
5. Conversation history — rolling 6-turn transcript + compressed earlier context
6. Current state — stage, question index, candidate analysis, code snapshot

**Streaming:** `OpenAiProvider.stream()` calls `client.chat().completions().createStreaming(params)` and yields tokens via Kotlin `Flow<String>`. 10-second timeout per streaming attempt.

**Token limits** (dynamic by stage via `InterviewerAgent.maxTokensFor()`):
- SMALL_TALK: 120, PROBLEM_PRESENTED: 50, CLARIFYING: 60-100, APPROACH: 120, CODING: 60, REVIEW: 150, FOLLOWUP: 150, WRAP_UP: 100

## 5. Frontend Architecture

### Page Structure

| Route | Component | Description |
|---|---|---|
| `/` | `LandingPage` | Marketing page; redirects to /dashboard if signed in |
| `/sign-in/*` | Clerk `SignIn` | Clerk-hosted sign-in |
| `/sign-up/*` | Clerk `SignUp` | Clerk-hosted sign-up |
| `/dashboard` | `DashboardPage` | Interview history (paginated) + stats sidebar (total, avg, best, free quota) |
| `/interview/setup` | `InterviewSetupPage` | Configure category, difficulty, language, personality, role, duration → start session |
| `/interview/:sessionId` | `InterviewPage` | Live interview: conversation panel + Monaco code editor + test results + timer |
| `/report/:sessionId` | `ReportPage` | Evaluation report: animated score, radar chart, dimension bars, narrative, strengths/weaknesses |

All routes except `/`, `/sign-in`, `/sign-up` are wrapped in `ProtectedRoute` (Clerk `useAuth()` check).

### Key Components

**Interview Components** (`src/components/interview/`):
- `ConversationPanel` — Message list with auto-scroll, text input (Enter to send, Shift+Enter for newline), "Request Hint" button with hint dot indicators, ThinkingIndicator
- `CodeEditor` — Monaco Editor wrapper (vs-dark theme, fontSize 14), language selector, Run/Submit toolbar, optional stdin textarea. Lazy-loaded via React Suspense
- `TestResults` — Displays stdout/stderr for ad-hoc runs; test case rows (expandable, auto-expand failures) for submissions
- `HintPanel` — Yellow card with hint text, level indicator, remaining count
- `TimerDisplay` — MM:SS countdown; color-coded (gray → yellow at 5min → red pulse at 2min); fires `onTimeExpired`
- `ThinkingIndicator` — 3 bouncing dots with staggered delays

**Shared Components** (`src/components/shared/`):
- `PageHeader` — Logo + Clerk UserButton
- `CategoryBadge` — Color-coded category label (CODING=blue, DSA=purple, BEHAVIORAL=green, SYSTEM_DESIGN=orange)
- `DifficultyBadge` — Color-coded difficulty label (EASY=green, MEDIUM=yellow, HARD=red)
- `ScoreDisplay` — Large score with color coding (blue ≥9, green ≥7, yellow ≥5, red <5)

**UI Primitives** (`src/components/ui/`): 14 shadcn/ui components (avatar, badge, button, card, dialog, input, label, progress, select, separator, skeleton, tabs, tooltip)

### State Management

- **Server state:** TanStack Query for all API data (interviews, reports, stats, languages). StaleTime: 30s default, 60s for stats, 5min for languages, Infinity for reports.
- **WebSocket state:** `useInterviewSocket` hook manages connection lifecycle, retry, heartbeat. Returns `{ status, send, disconnect }`.
- **Conversation state:** `useConversation` hook manages local message array. Handles streaming (`appendAiToken`) and batch (`addAiMessage`) AI responses.
- **Component state:** `InterviewPage` manages ~15 useState variables: `currentState`, `currentCode`, `currentLanguage`, `codeResult`, `isCodeRunning`, `hintState`, `hintsGiven`, `isAiThinking`, `showCodeEditor`, `reportId`, `currentQuestionIndex`, `editorHighlighted`, `codeTemplates`, plus refs for stale-closure avoidance.

### WebSocket Client

**Hook:** `useInterviewSocket({ sessionId, onMessage, onStatusChange })`

**Connection:** `${VITE_WS_BASE_URL}/ws/interview/${sessionId}?token=${clerkJWT}`

**Reconnection:** Max 3 retries with exponential backoff (2s, 4s, 8s). On reconnect, backend sends `STATE_SYNC` with full interview state.

**Heartbeat:** Sends `PING` every 25 seconds.

**Message queue:** Messages sent before connection is established are queued and flushed on `onopen`.

**Streaming rendering:** `AI_CHUNK` messages are handled by `appendAiToken(delta)` which updates the active AI message's content character-by-character. `done: true` triggers `finalizeAiMessage()`.

## 6. Database Schema

### Tables

**organizations**
| Column | Type | Constraints | Purpose |
|---|---|---|---|
| id | UUID | PK, DEFAULT gen_random_uuid() | Primary key |
| name | VARCHAR(255) | NOT NULL | Organization name |
| type | VARCHAR(50) | NOT NULL | PERSONAL, COMPANY, UNIVERSITY |
| plan | VARCHAR(50) | NOT NULL, DEFAULT 'FREE' | Subscription plan |
| seats_limit | INT | DEFAULT 1 | Max users in org |
| created_at | TIMESTAMPTZ | DEFAULT NOW() | Creation timestamp |

**users**
| Column | Type | Constraints | Purpose |
|---|---|---|---|
| id | UUID | PK, DEFAULT gen_random_uuid() | Primary key |
| org_id | UUID | FK→organizations, NOT NULL | Organization membership |
| clerk_user_id | VARCHAR(255) | UNIQUE, NOT NULL | Clerk external ID |
| email | VARCHAR(255) | NOT NULL | User email |
| full_name | VARCHAR(255) | | Display name |
| role | VARCHAR(50) | NOT NULL, DEFAULT 'CANDIDATE' | CANDIDATE, RECRUITER, ORG_ADMIN |
| subscription_tier | VARCHAR(50) | DEFAULT 'FREE' | FREE, PRO |
| created_at | TIMESTAMPTZ | DEFAULT NOW() | |

**interview_sessions**
| Column | Type | Constraints | Purpose |
|---|---|---|---|
| id | UUID | PK, DEFAULT gen_random_uuid() | Primary key |
| user_id | UUID | FK→users, NOT NULL | Session owner |
| status | VARCHAR(50) | NOT NULL, DEFAULT 'PENDING' | PENDING, ACTIVE, COMPLETED, ABANDONED, EXPIRED |
| type | VARCHAR(50) | NOT NULL | DSA, CODING, SYSTEM_DESIGN, BEHAVIORAL |
| config | JSONB | NOT NULL, DEFAULT '{}' | Serialized InterviewConfig |
| started_at | TIMESTAMPTZ | | When interview began |
| ended_at | TIMESTAMPTZ | | When interview ended |
| duration_secs | INT | | Actual interview duration |
| created_at | TIMESTAMPTZ | DEFAULT NOW() | |
| last_heartbeat | TIMESTAMPTZ | | Last WS ping time (V11) |
| current_stage | VARCHAR(30) | DEFAULT 'SMALL_TALK' | 8-stage progression (V12) |

**questions**
| Column | Type | Constraints | Purpose |
|---|---|---|---|
| id | UUID | PK, DEFAULT gen_random_uuid() | Primary key |
| title | VARCHAR(500) | NOT NULL | Question title |
| description | TEXT | NOT NULL | Full problem description |
| type | VARCHAR(50) | NOT NULL | DSA, CODING, SYSTEM_DESIGN, BEHAVIORAL |
| difficulty | VARCHAR(50) | NOT NULL | EASY, MEDIUM, HARD |
| interview_category | VARCHAR(30) | DEFAULT 'CODING' | CODING, DSA, BEHAVIORAL, SYSTEM_DESIGN, CASE_STUDY (V6) |
| topic_tags | TEXT[] | | Topic labels |
| examples | TEXT | | JSON examples |
| constraints | TEXT | | Problem constraints |
| test_cases | TEXT | | JSON test cases |
| solution_hints | TEXT | | JSON progressive hints |
| optimal_approach | TEXT | | Hidden optimal solution |
| follow_up_prompts | TEXT | | JSON follow-up questions |
| evaluation_criteria | TEXT | | JSON scoring criteria (V6) |
| code_templates | TEXT | DEFAULT '{}' | JSON per-language starter code (V6/V12) |
| function_signature | TEXT | DEFAULT '{}' | JSON function signatures (V6/V12) |
| slug | VARCHAR(255) | UNIQUE (nullable) | URL-safe identifier (V6) |
| source | VARCHAR(20) | DEFAULT 'AI_GENERATED' | AI_GENERATED or MANUAL (V6) |
| time_complexity | VARCHAR(20) | | Expected time complexity (V6) |
| space_complexity | VARCHAR(20) | | Expected space complexity (V6) |
| generation_params | TEXT | | JSON LLM generation config (V6) |
| deleted_at | TIMESTAMPTZ | | Soft delete (V6) |
| created_at | TIMESTAMPTZ | DEFAULT NOW() | |

**session_questions**
| Column | Type | Constraints | Purpose |
|---|---|---|---|
| id | UUID | PK | Primary key |
| session_id | UUID | FK→interview_sessions, NOT NULL | Parent session |
| question_id | UUID | FK→questions, NOT NULL | Referenced question |
| order_index | INT | NOT NULL, DEFAULT 0 | Question order (0-based) |
| final_code | TEXT | | Last submitted code |
| language_used | VARCHAR(50) | | Programming language |
| submitted_at | TIMESTAMPTZ | | When code was submitted |
| created_at | TIMESTAMPTZ | DEFAULT NOW() | |

**conversation_messages**
| Column | Type | Constraints | Purpose |
|---|---|---|---|
| id | UUID | PK | Primary key |
| session_id | UUID | FK→interview_sessions, NOT NULL | Parent session |
| role | VARCHAR(50) | NOT NULL | AI, CANDIDATE, SYSTEM |
| content | TEXT | NOT NULL | Message text |
| metadata | TEXT | | JSON metadata |
| created_at | TIMESTAMPTZ | DEFAULT NOW() | |

**code_submissions**
| Column | Type | Constraints | Purpose |
|---|---|---|---|
| id | UUID | PK | Primary key |
| session_question_id | UUID | FK→session_questions, NOT NULL | Parent question |
| user_id | UUID | FK→users, NOT NULL | Submitter |
| code | TEXT | NOT NULL | Source code |
| language | VARCHAR(50) | NOT NULL | Programming language |
| status | VARCHAR(50) | NOT NULL, DEFAULT 'PENDING' | Execution result status |
| judge0_token | VARCHAR(255) | | Judge0 submission token |
| test_results | TEXT | | JSON test case results |
| runtime_ms | INT | | Execution time |
| memory_kb | INT | | Memory usage |
| submitted_at | TIMESTAMPTZ | DEFAULT NOW() | |

**evaluation_reports**
| Column | Type | Constraints | Purpose |
|---|---|---|---|
| id | UUID | PK | Primary key |
| session_id | UUID | UNIQUE FK→interview_sessions | One report per session |
| user_id | UUID | FK→users, NOT NULL | Report owner |
| overall_score | DECIMAL(5,2) | | Weighted final score |
| problem_solving_score | DECIMAL(5,2) | | 0-10 |
| algorithm_score | DECIMAL(5,2) | | 0-10 |
| code_quality_score | DECIMAL(5,2) | | 0-10 |
| communication_score | DECIMAL(5,2) | | 0-10 |
| efficiency_score | DECIMAL(5,2) | | 0-10 |
| testing_score | DECIMAL(5,2) | | 0-10 |
| strengths | TEXT | | JSON array of strings |
| weaknesses | TEXT | | JSON array of strings |
| suggestions | TEXT | | JSON array of strings |
| narrative_summary | TEXT | | Free-form assessment |
| dimension_feedback | TEXT | | JSON map: dimension → feedback (V7) |
| hints_used | INT | DEFAULT 0 | Total hints requested (V7) |
| completed_at | TIMESTAMPTZ | | Report generation time (V7) |
| created_at | TIMESTAMPTZ | DEFAULT NOW() | |

**interview_templates** — `id`, `name`, `type`, `difficulty`, `config` (JSONB)

**org_invitations** — `id`, `org_id` (FK), `email`, `role`, `token` (unique), `expires_at`, `accepted_at`

### Indexes

| Index | Table | Columns | Notes |
|---|---|---|---|
| idx_users_clerk_user_id | users | clerk_user_id | Unique |
| idx_users_org_id | users | org_id | |
| idx_questions_type_difficulty | questions | type, difficulty | |
| idx_questions_slug | questions | slug | Unique, nullable (V6) |
| idx_questions_category_difficulty | questions | interview_category, difficulty | WHERE deleted_at IS NULL (V6) |
| idx_sessions_user_status | interview_sessions | user_id, status | |
| idx_session_questions_session | session_questions | session_id | |
| idx_messages_session_created | conversation_messages | session_id, created_at | |
| idx_submissions_session_question | code_submissions | session_question_id | |
| idx_submissions_user | code_submissions | user_id | |
| idx_reports_user | evaluation_reports | user_id | |
| idx_org_invitations_org | org_invitations | org_id | |
| idx_org_invitations_token | org_invitations | token | Unique |

### Migrations

| Version | File | Changes |
|---|---|---|
| V1 | `V1__create_organizations.sql` | Creates `organizations` table |
| V2 | `V2__create_users.sql` | Creates `users` table with FK to organizations |
| V3 | `V3__create_interview_tables.sql` | Creates `questions`, `interview_sessions`, `session_questions`, `conversation_messages` |
| V4 | `V4__create_code_and_reports.sql` | Creates `code_submissions`, `evaluation_reports` |
| V5 | `V5__create_misc.sql` | Creates `interview_templates`, `org_invitations` |
| V6 | `V6__extend_questions_table.sql` | Adds `source`, `deleted_at`, `generation_params`, `space_complexity`, `time_complexity`, `evaluation_criteria`, `slug`, `interview_category`, `code_templates`, `function_signature` to questions |
| V7 | `V7__extend_evaluation_reports.sql` | Adds `dimension_feedback`, `hints_used`, `completed_at` to evaluation_reports |
| V8 | `V8__convert_enums_to_varchar.sql` | Converts role, interview_type, session_status, difficulty from PostgreSQL enums to VARCHAR |
| V9 | `V9__convert_remaining_enums_to_varchar.sql` | Converts remaining enum columns to VARCHAR |
| V10 | `V10__convert_jsonb_to_text.sql` | Converts JSONB columns to TEXT (R2DBC compatibility) |
| V11 | `V11__add_session_heartbeat.sql` | Adds `last_heartbeat` to interview_sessions |
| V12 | `V12__add_stage_and_templates.sql` | Adds `current_stage` to interview_sessions; ensures interview_templates exists |

## 7. Authentication & Security

**Frontend → Backend auth flow:**

1. User signs in via Clerk React components (`/sign-in`)
2. Clerk manages session; `useAuth().getToken()` returns a signed JWT
3. `AuthTokenBridge` (in Providers) calls `setAuthTokenProvider()` to inject Clerk's `getToken` into axios
4. Axios request interceptor adds `Authorization: Bearer {token}` to every request
5. For WebSocket: token passed as `?token={jwt}` query parameter

**Backend JWT validation:**

1. `ClerkJwtAuthFilter` (`@Order(-200)`) intercepts every request
2. Skips `/health`, `/actuator/**`, `/ws/**` (WS has its own auth)
3. Extracts token from `Authorization: Bearer {token}` header
4. `JwksValidator.validate(token)` fetches Clerk JWKS endpoint (cached via `JwksCache` for 60 minutes)
5. Validates JWT signature, expiry, claims → extracts `ClerkClaims(userId, email, fullName)`
6. `UserBootstrapService.getOrCreateUser(clerkUserId, email, fullName)` — creates user + personal org on first login; cached in Redis for 5 minutes
7. Sets Spring Security context with `User` entity as principal
8. Returns 401 JSON `{ error: "UNAUTHORIZED", message: "..." }` on failure

**WebSocket auth:**

1. `WsAuthHandshakeInterceptor` intercepts WebSocket handshake
2. Extracts JWT from `token` query parameter
3. Validates via same `JwksValidator`
4. Stores `sessionId` + `userId` in WebSocket session attributes

**Rate limiting:** `RateLimitFilter` (`@Order(-150)`) — Redis key `ratelimit:{userId}:{epochMinute}`, limit: 60 req/min. Returns 429 on exceed.

**Public endpoints:** `/health`, `/actuator/**`, `/api/v1/code/languages`, `/api/v1/questions`, `/api/v1/questions/{id}`

## 8. Interview Flow (Technical)

**1. User clicks "Start Interview":**
- Frontend POSTs `InterviewConfig` to `/api/v1/interviews/sessions`
- `InterviewSessionService.startSession()`:
  - Creates `InterviewSession` (status=ACTIVE) in PostgreSQL
  - Selects questions via `QuestionService.selectQuestionsForSession(config, count)` — count depends on category (CODING/DSA: 2, BEHAVIORAL: 3, SYSTEM_DESIGN: 1)
  - Creates `SessionQuestion` rows in DB
  - Initializes `InterviewMemory` in Redis (state=INTERVIEW_STARTING, stage=SMALL_TALK, 2hr TTL)
  - Returns `{ sessionId, wsUrl: "ws://localhost:8080/ws/interview/{id}" }`

**2. WebSocket connects:**
- Frontend opens WebSocket with Clerk JWT in query param
- `WsAuthHandshakeInterceptor` validates JWT
- `InterviewWebSocketHandler.onConnect()` checks: memory exists in Redis?
  - First connect: sends `INTERVIEW_STARTED`, calls `ConversationEngine.startInterview()`
  - Reconnect: calls `handleReconnect()` → loads messages from DB, sends `STATE_SYNC`

**3. First AI message (SMALL_TALK):**
- `ConversationEngine.startInterview()` sends hardcoded greeting: "Hey! How's it going? I'll be your interviewer today."
- Persists to DB, appends to Redis transcript
- Transitions state to QUESTION_PRESENTED
- Frontend renders AI message in ConversationPanel

**4. User sends a message:**
- Frontend sends `{ type: "CANDIDATE_MESSAGE", text: "...", codeSnapshot: {...} }`
- WebSocketHandler syncs codeSnapshot to Redis memory if meaningful
- `ConversationEngine.handleCandidateMessage()`:
  - Persists to DB + Redis transcript
  - Transitions: CANDIDATE_RESPONDING → AI_ANALYZING
  - `InterviewerAgent.streamResponse()`:
    - Classifies message type
    - `PromptBuilder.buildSystemPrompt()` constructs system prompt with stage rules
    - `LlmProviderRegistry.stream()` → GPT-4o with 10s timeout
    - Tokens sent as `AI_CHUNK { delta, done }` frames
    - `updateInterviewStage()` detects stage transitions
  - Fire-and-forget: `AgentOrchestrator.analyzeAndTransition()`
    - `ReasoningAnalyzer.analyze()` → candidate analysis + suggested transition
    - May trigger FollowUp generation, CODING_CHALLENGE transition, or question completion

**5. Code submission:**
- Frontend sends `{ type: "CODE_SUBMIT", code: "...", language: "python", sessionQuestionId: "..." }`
- `CodeExecutionService.submitCode()`:
  - `Judge0Client.submit()` — POSTs base64-encoded code to Judge0 (normalizes Java class→Main)
  - Runs each test case concurrently via `coroutineScope { testCases.map { async { ... } } }`
  - `Judge0Client.pollResult()` — polls every 500ms, 30s timeout, uses `Base64.getMimeDecoder()` for results
  - Persists `CodeSubmission` to DB
  - Updates `SessionQuestion.finalCode`
  - Sends `CODE_RESULT` with test results via WebSocket

**6. Interview ends:**
- Triggered by: `END_INTERVIEW` message, timer expiry, or AgentOrchestrator detecting last question complete
- `ConversationEngine.forceEndInterview()` transitions to EVALUATING
- Fire-and-forget: `ReportService.generateAndSaveReport()`:
  - Idempotency check (return existing if found)
  - `EvaluationAgent.evaluate(memory)` — GPT-4o analysis of full interview
  - Computes weighted overall score (25% problemSolving, 20% algorithm, 20% codeQuality, 15% communication, 10% efficiency, 10% testing)
  - Persists `EvaluationReport` to DB
  - `UsageLimitService.incrementUsage()` — increments monthly counter (only after report saved)
  - Sends `SESSION_END { reportId }` via WebSocket
  - Deletes Redis memory

**7. Report viewing:**
- Frontend navigates to `/report/{sessionId}`
- `useReport` hook fetches `GET /api/v1/reports/{sessionId}`
- If 404: retries up to 10 times with 3s delay (report still generating)
- Renders: animated score count-up, radar chart, dimension progress bars, narrative, strengths/weaknesses/suggestions

## 9. Key Configuration

### Backend (application.yml / environment)

| Variable | Purpose | Required |
|---|---|---|
| `DATABASE_URL` | R2DBC PostgreSQL connection URL | Yes |
| `POSTGRES_USER` | Database username (default: aiinterview) | Yes |
| `POSTGRES_PASSWORD` | Database password (default: changeme) | Yes |
| `FLYWAY_URL` | JDBC URL for Flyway migrations | Yes |
| `REDIS_URL` | Redis connection URL (default: redis://localhost:6379) | Yes |
| `LLM_PROVIDER` | Active LLM provider: openai, groq, gemini (default: openai) | Yes |
| `OPENAI_API_KEY` | OpenAI API key | Yes (if provider=openai) |
| `GROQ_API_KEY` | Groq API key | If provider=groq |
| `GEMINI_API_KEY` | Gemini API key | If provider=gemini |
| `LLM_FALLBACK_PROVIDER` | Fallback provider name | No |
| `LLM_INTERVIEWER_MODEL` | Interviewer model (default: gpt-4o) | No |
| `LLM_BACKGROUND_MODEL` | Background model (default: gpt-4o-mini) | No |
| `LLM_GENERATOR_MODEL` | Question generator model (default: gpt-4o) | No |
| `LLM_EVALUATOR_MODEL` | Evaluator model (default: gpt-4o) | No |
| `CLERK_JWKS_URL` | Clerk JWKS endpoint for JWT validation | Yes |
| `CLERK_PUBLISHABLE_KEY` | Clerk publishable key | Yes |
| `JUDGE0_BASE_URL` | Judge0 API URL (default: http://localhost:2358) | Yes |
| `JUDGE0_AUTH_TOKEN` | Judge0 authentication token | Yes |
| `JUDGE0_AUTH_HEADER` | Judge0 auth header name (default: X-Auth-Token) | No |
| `CORS_ALLOWED_ORIGINS` | Comma-separated allowed origins (default: http://localhost:3000) | No |
| `WS_BASE_URL` | WebSocket base URL for session responses (default: ws://localhost:8080) | No |

### Frontend (.env.local)

| Variable | Purpose | Required |
|---|---|---|
| `VITE_CLERK_PUBLISHABLE_KEY` | Clerk publishable key | Yes |
| `VITE_API_BASE_URL` | Backend API URL (default: http://localhost:8080) | Yes |
| `VITE_WS_BASE_URL` | WebSocket URL (default: ws://localhost:8080) | Yes |

## 10. Running Locally

### Prerequisites

- Java 21+
- Maven 3.9+
- Node.js 18+
- Docker Desktop (for PostgreSQL, Redis, Judge0)
- Clerk.dev account (free tier) — publishable key, secret key, JWKS URL
- OpenAI API key (GPT-4o access required)

### Setup

```bash
# 1. Clone and configure
git clone <repo>
cd ai-interview-platform
cp .env.example .env
# Edit .env with your Clerk + OpenAI keys

# 2. Start infrastructure (PostgreSQL, Redis, Judge0)
docker-compose up -d
# Verify: docker-compose ps (all 6 containers healthy)

# 3. Start backend (port 8080)
cd backend && mvn spring-boot:run
# Flyway runs migrations automatically on startup
# Verify: curl http://localhost:8080/health

# 4. Start frontend (port 3000)
cd frontend
cp ../.env .env.local  # or create with VITE_* vars
npm install
npm run dev
# Open: http://localhost:3000

# 5. Verify Judge0
curl http://localhost:2358/system_info \
  -H "X-Auth-Token: judge0_dev_token_replace_in_production"
```

### Key Commands

```bash
# Backend
cd backend && mvn spring-boot:run        # Start server
cd backend && mvn test                   # Run all tests (needs Docker for Redis)
cd backend && mvn compile -q             # Compile only

# Frontend
cd frontend && npm run dev               # Dev server
cd frontend && npm run build             # Production build (tsc + vite)

# Infrastructure
docker-compose up -d                     # Start all services
docker-compose down                      # Stop all services
cd judge0 && docker-compose up -d        # Judge0 only (standalone)
```

## 11. Known Architecture Decisions

- **Spring WebFlux over Spring MVC** — entire backend is reactive/non-blocking. All service methods are `suspend fun` using Kotlin coroutines. R2DBC for async database access. This supports concurrent WebSocket connections without thread-per-connection overhead.
- **Redis for interview memory** — hot session state in Redis (2hr TTL) with structured snapshot + rolling 6-turn transcript. Older turns compressed via GPT-4o-mini and stored as `earlierContext`. Avoids bloating LLM context while preserving interview history.
- **Pluggable LLM providers** — `LlmProviderRegistry` supports OpenAI, Groq, and Gemini with automatic fallback. Provider selected via config (`LLM_PROVIDER`), not hardcoded. Allows switching between providers without code changes.
- **Modular monolith** — packages act as modules (`conversation/`, `interview/`, `code/`, `report/`, `auth/`), not separate services. Simplifies deployment while maintaining clear boundaries.
- **WebSocket as primary transport** — interview interactions flow through WebSocket (not REST polling). Enables real-time streaming of AI responses token-by-token.
- **Dual state tracking** — coarse `state` (WS-level: CANDIDATE_RESPONDING, AI_ANALYZING) drives UI transitions; fine-grained `interviewStage` (SMALL_TALK→WRAP_UP) drives AI behavior rules. Both stored in Redis memory.
- **Fire-and-forget background analysis** — `AgentOrchestrator.analyzeAndTransition()` runs on `SupervisorJob + Dispatchers.IO` scope, decoupled from the main request flow. AI response streams immediately while analysis happens in parallel.
- **JSONB stored as String** — JSONB columns stored as `String` in Kotlin entities (not `JsonNode`) due to R2DBC driver limitations. Parsed at service layer via ObjectMapper.
- **Enum columns converted to VARCHAR** — migrations V8/V9 converted PostgreSQL native enums to VARCHAR for flexibility (adding new values without migration).
- **Judge0 Java class normalization** — `Judge0Client` replaces `public class X` with `public class Main` for Java submissions because Judge0 saves all files as `Main.java`.

## 12. Current Limitations & Known Issues

- **Text-only interviews** — voice pipeline (Whisper STT + OpenAI TTS) is designed in the spec but not implemented. All interaction is text-based.
- **No B2B features** — organizations, invitations, recruiter views, and templates exist in the schema but have no functional endpoints beyond basic CRUD.
- **BEHAVIORAL and SYSTEM_DESIGN categories** — marked as "beta" in the frontend setup page. Prompt engineering for these categories is less refined than CODING/DSA.
- **CASE_STUDY category** — hidden from users. Maps to CODING database type via `dbType()` helper.
- **Single LLM provider at runtime** — while fallback exists, the system doesn't load-balance across providers. Primary must be available for streaming to work.
- **Large frontend bundle** — Monaco Editor contributes ~7MB to the build (chunked separately). Recharts and Clerk add ~500KB combined.
- **No question caching** — questions are generated fresh via LLM each time `/admin/questions/generate` is called. No deduplication or similarity check.
- **Free tier enforcement via Redis counter** — `usage:{userId}:interviews:{YYYY-MM}` with 35-day TTL. Not backed by PostgreSQL; a Redis flush resets counters.
- **Judge0 requires privileged Docker mode** — `privileged: true` needed for seccomp sandboxing. May not work in all container environments.
- **Cognitive complexity warning** — `InterviewPage.tsx` `handleMessage` callback has cognitive complexity 19 (threshold 15). Non-blocking lint warning.
- **10-second streaming timeout** — `InterviewerAgent` falls back to non-streaming (background model) if GPT-4o doesn't start streaming within 10s. Under high load, candidates may get shorter responses from the fallback model.
- **Hardcoded interview greeting** — `ConversationEngine.startInterview()` sends a fixed opening message regardless of personality setting.
- **No HTTPS/WSS in local dev** — all local connections are unencrypted HTTP/WS.
