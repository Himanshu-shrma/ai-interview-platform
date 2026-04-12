# AI Interview Platform — Claude Context

## What This Project Is
AI-powered mock interview platform. Candidates practice CODING / BEHAVIORAL / SYSTEM_DESIGN interviews with an adaptive AI interviewer. Real-time WebSocket communication. Judge0 CE for code execution. Clerk.dev for auth.

## Stack
| Layer | Technology |
|-------|-----------|
| Backend | Kotlin 1.9.25, Spring Boot 3.5.9 + WebFlux, Java 21 |
| Async | Kotlin Coroutines + Reactor (R2DBC, Redis Reactive) |
| DB | PostgreSQL 15 via R2DBC, Flyway migrations (via JDBC) |
| Cache | Redis 7 (brain state, session memory, rate limiting) |
| Auth | Clerk.dev JWT (ClerkJwtAuthFilter + JwksValidator) |
| LLM | OpenAI GPT-4o (primary), GPT-4o-mini (background), configurable Groq/Gemini |
| Code exec | Judge0 CE 1.13.1 (Docker, REST on port 2358) |
| Frontend | React 18 + TypeScript + Vite 5 |
| UI | Tailwind CSS 4 + shadcn/ui (Radix primitives) |
| Editor | Monaco Editor (@monaco-editor/react) |
| Charts | Recharts 3 |
| Auth (FE) | @clerk/clerk-react |
| HTTP | TanStack Query 5 + Axios |
| Routing | react-router-dom 7 |

## Branch Rules
- Primary feature branch: `feature/natural-interviewer`
- NEVER commit to master directly
- Verify: `git branch --show-current` before work
- Commit format: `feat(TASK-XXX): description` / `fix(TASK-XXX): description`

## Architecture: The Brain System
The AI interviewer uses 3 agents:

1. **TheConductor** (`conversation/brain/TheConductor.kt`) — Real-time streaming response via `modelConfig.interviewerModel` (GPT-4o). Includes silence intelligence (RESPOND/SILENT/WAIT_THEN_RESPOND).
2. **TheAnalyst** (`conversation/brain/TheAnalyst.kt`) — Background per-turn analysis via `modelConfig.backgroundModel` (GPT-4o-mini). ONE LLM call per exchange. Updates brain state: candidate profile, hypotheses, claims, goals, actions.
3. **TheStrategist** (`conversation/brain/TheStrategist.kt`) — Meta-cognitive review every 5 turns via background model.

**State**: `InterviewerBrain` in Redis key `brain:{sessionId}` TTL 3h
**Mutex**: Per-session `kotlinx.coroutines.sync.Mutex` in BrainService
**Goals**: `BrainObjectivesRegistry.forCategory(type)` — CODING(10 required), BEHAVIORAL(8), SYSTEM_DESIGN(8)
**Safety**: `BrainFlowGuard` (4 rules max)
**Prompt**: `NaturalPromptBuilder` — 13-section system prompt built from brain state

## ConversationEngine — Central Orchestrator
`conversation/ConversationEngine.kt` — The god class (385 lines, 12 deps).
- `startInterview()`: Sends greeting ONLY (problem presented by TheConductor on turn 1)
- `handleCandidateMessage()`: Load brain -> persist message -> compute state -> FlowGuard -> TheConductor -> fire-and-forget TheAnalyst + TheStrategist
- `forceEndInterview()`: Transition to Evaluating -> ReportService.generateAndSaveReport()
- Per-session `CoroutineScope(SupervisorJob() + Dispatchers.IO)`

## WebSocket Protocol
All interview events over WS: `ws://localhost:8080/ws/interview/{sessionId}?token={clerkJWT}`

**Inbound** (WsMessageTypes.kt sealed class): CANDIDATE_MESSAGE, CODE_RUN, CODE_SUBMIT, CODE_UPDATE, REQUEST_HINT, END_INTERVIEW, PING
**Outbound** (WsMessageTypes.kt sealed class): INTERVIEW_STARTED, AI_CHUNK, AI_MESSAGE, STATE_CHANGE, CODE_RUN_RESULT, CODE_RESULT, HINT_DELIVERED, HINT_RESPONSE, QUESTION_TRANSITION, SESSION_END, STATE_SYNC, ERROR, PONG

## Database
PostgreSQL 15. R2DBC driver. JSONB stored as TEXT (R2DBC limitation). Enums stored as VARCHAR.
19 migrations (V1-V19). Next: V20__...sql

**Tables**: organizations, users, questions, interview_sessions, session_questions, conversation_messages, code_submissions, evaluation_reports, interview_templates, org_invitations, candidate_memory_profiles

## Key Patterns — READ BEFORE CODING

### 1. R2DBC Coroutine Bridge
```kotlin
// CORRECT
val result = repo.findById(id).awaitSingleOrNull()
// NEVER — blocks Netty event loop
val result = repo.findById(id).block()
```

### 2. Redis Brain — Always Use BrainService
```kotlin
// Atomic read-modify-write with per-session mutex
brainService.updateBrain(sessionId) { brain -> brain.copy(turnCount = brain.turnCount + 1) }
// NEVER read + write without mutex
```

### 3. LLM Calls — Always Use LlmProviderRegistry
```kotlin
val response = llmProviderRegistry.complete(LlmRequest.build(systemPrompt, userMessage, model, maxTokens))
// Streaming for TheConductor:
llm.stream(request).collect { token -> ... }
```

### 4. Model Selection (ModelConfig.kt)
| Task | Field | Default |
|------|-------|---------|
| TheConductor | interviewerModel | gpt-4o |
| TheAnalyst/TheStrategist | backgroundModel | gpt-4o-mini |
| QuestionGenerator | generatorModel | gpt-4o |
| EvaluationAgent | evaluatorModel | gpt-4o |

### 5. Error Handling
```kotlin
// WRONG: catch (_: Exception) {}
// CORRECT: catch (e: Exception) { log.warn("Failed [what]: ${e.message}") }
```

### 6. React Query Pattern (Frontend)
```typescript
const { data, isLoading } = useQuery({ queryKey: ['key', id], queryFn: () => api.get<T>(`/endpoint/${id}`) })
```

### 7. WS Message Send
```kotlin
registry.sendMessage(sessionId, OutboundMessage.AiChunk(delta = token, done = false))
```

## Progress Dashboard (TASK-P2-01)
- `ProgressService` — `GET /api/v1/users/me/progress` → `ProgressResponse`
- Dimension trends: last 10 sessions, 8 dimensions (problemSolving, algorithmChoice, codeQuality, communication, efficiency, testing, initiative, learningAgility)
- Rolling average: last 5 sessions per dimension
- Insight cards: `mostImproved` (max positive delta), `needsAttention` (lowest avg among stagnant/declining), `platformPercentile` (only when 10+ sessions)
- Delta = `avg(second half) - avg(first half)` of score list; requires ≥2 data points
- Frontend: `DashboardPage` rebuilt with toggleable Recharts `LineChart`, insight cards, rolling average table
- Percentile SQL: `EvaluationReportRepository.countUsersWithAverageBelow()` + `countDistinctUsers()`

## Cross-Session Memory (TASK-P1-02)
- `CandidateMemoryProfile` (Redis key: none — stored in Postgres `candidate_memory_profiles` table)
- `CandidateMemoryService.upsertFromReport()` — called from ReportService after every session
- `CandidateHistory` in `InterviewerBrain` — populated by `BrainService.initBrain(candidateMemory=...)`
- `NaturalPromptBuilder` section 13 — CANDIDATE_HISTORY injected when `brain.candidateHistory != null AND turnCount > 0`
- Memory opt-out: `users.memory_enabled = false` suppresses injection + upsert
- Transparency: `GET /api/v1/users/me/memory` returns `DerivedInsights`; `DELETE` resets to zero
- Toggle: `PATCH /api/v1/users/me/memory-enabled` + `/settings` page in frontend

## File Map — Critical Files
| File | Purpose |
|------|---------|
| ConversationEngine.kt | Central orchestrator |
| BrainService.kt | Redis brain CRUD + Mutex |
| TheConductor.kt | AI response streaming + silence intelligence |
| TheAnalyst.kt | Background brain updates (JSON parsing with fallback) |
| TheStrategist.kt | Meta-review every 5 turns |
| NaturalPromptBuilder.kt | 13-section LLM prompt construction |
| BrainObjectivesRegistry.kt | Goal definitions + computeBrainInterviewState() + inferPhaseLabel() |
| InterviewerBrain.kt | All brain data classes (47 fields, 15+ enums) |
| BrainFlowGuard.kt | 4 safety rules |
| EvaluationAgent.kt | Post-session 8-dimension scoring (reads InterviewerBrain only) |
| CandidateMemoryService.kt | Cross-session memory aggregation + derived insights |
| MemoryController.kt | GET/DELETE /api/v1/users/me/memory + PATCH memory-enabled |
| ProgressService.kt | Dimension trends, rolling avg, delta insight cards, platform percentile |
| ProgressController.kt | GET /api/v1/users/me/progress |
| ReportService.kt | Score formula + report generation + persistence |
| CodeExecutionService.kt | Judge0 integration + test result brain actions |
| InterviewWebSocketHandler.kt | WS message routing |
| WsMessageTypes.kt | All WS sealed classes (InboundMessage + OutboundMessage) |
| SecurityConfig.kt | WebFlux security (permitAll: /health, /actuator/**, /ws/**, /api/v1/code/languages) |
| InterviewPage.tsx | Main interview UI (split panel, WS, code editor) |
| ReportPage.tsx | Score display + radar chart |
| useInterviewSocket.ts | WS hook with reconnect (3 retries, exponential backoff) |
| useConversation.ts | Message state + AI token accumulation |

## Known Issues
- HintGenerator reads InterviewMemory NOT InterviewerBrain (BROKEN)
- Dual state: InterviewMemory AND InterviewerBrain in Redis (EvaluationAgent reads Memory, TheConductor reads Brain)
- Dead brain fields: `topicSignalBudget`, `zdpEdge` (marginal value)
- ConversationEngine is a god class (385+ lines, 12 deps)
- No Sentry error tracking
- No GDPR data deletion endpoint

## Evaluation Score Formula (ReportService.kt)
```
overall = problemSolving*0.20 + algorithm*0.15 + codeQuality*0.15 + communication*0.15
        + efficiency*0.10 + testing*0.10 + initiative*0.10 + learningAgility*0.05
```

## Frontend Routes (App.tsx)
| Path | Page | Auth |
|------|------|------|
| / | LandingPage | No |
| /sign-in/* | Clerk SignIn | No |
| /sign-up/* | Clerk SignUp | No |
| /onboarding | OnboardingPage | Yes — shown to new users (onboardingCompleted=false) |
| /dashboard | DashboardPage | Yes |
| /interview/setup | InterviewSetupPage | Yes |
| /interview/:sessionId | InterviewPage | Yes |
| /report/:sessionId | ReportPage | Yes |
| /settings | AccountSettingsPage | Yes — AI Memory toggle |

## Onboarding Flow
- `ProtectedRoute` fetches `getMe()` and redirects to `/onboarding` if `!user.onboardingCompleted`
- `OnboardingPage`: 3-step wizard (questions → recommendation → launch)
- `POST /api/v1/users/me/onboarding` sets `onboarding_completed=true`, returns `OnboardingRecommendation`
- Recommendation matrix: `OnboardingService.recommend()` — exploring→EASY, staff→SYSTEM_DESIGN/HARD, switching→BEHAVIORAL, senior_swe+active→HARD, else→MEDIUM
- Redis cache evicted via `UserBootstrapService.evictCache()` after onboarding save

## Agent Skills — Read Before Work
| Skill | When To Read | Location |
|-------|-------------|----------|
| brain-architecture | TheConductor/Analyst/Strategist/BrainService | `.claude/skills/brain-architecture/SKILL.md` |
| kotlin-webflux-patterns | Any backend Kotlin work | `.claude/skills/kotlin-webflux-patterns/SKILL.md` |
| llm-prompt-engineering | LLM prompts, JSON schemas, eval criteria | `.claude/skills/llm-prompt-engineering/SKILL.md` |
| interview-flow-design | Phases, silence logic, goals, type behavior | `.claude/skills/interview-flow-design/SKILL.md` |
| database-migration | Schema changes, migrations, Redis | `.claude/skills/database-migration/SKILL.md` |
| frontend-interview-ui | InterviewPage, ReportPage, WS, Monaco | `.claude/skills/frontend-interview-ui/SKILL.md` |
| code-execution-judge0 | Judge0, test cases, code submission | `.claude/skills/code-execution-judge0/SKILL.md` |
| evaluation-scoring | Score formula, EvaluationAgent, reports | `.claude/skills/evaluation-scoring/SKILL.md` |

Each skill has a `references/` folder with detailed reference docs.
