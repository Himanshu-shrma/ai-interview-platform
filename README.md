# AI Interview Platform

A full-stack AI-powered mock interview platform. Candidates take real-feeling technical interviews (DSA + Coding) with an adaptive AI interviewer that behaves like a senior human engineer.

## Stack

| Layer | Technology |
|---|---|
| Backend | Kotlin, Spring Boot 3.x, Spring WebFlux, Coroutines, R2DBC |
| Frontend | React 18, TypeScript, Vite, Tailwind CSS, shadcn/ui, TanStack Query |
| Database | PostgreSQL 15 (via R2DBC) |
| Cache / Memory | Redis 7 |
| Auth | Clerk.dev (JWT validation — no custom auth) |
| Code Execution | Judge0 CE (Docker, REST API only) |
| LLM | OpenAI GPT-4o / GPT-4o-mini |
| Editor | Monaco Editor |

## Getting Started

### Prerequisites

- Docker Desktop (running)
- Java 21
- Node 20
- OpenAI API key
- Clerk.dev account (free tier)

### 1. Configure Environment

```bash
cp .env.example .env
# Fill in OPENAI_API_KEY, CLERK_SECRET_KEY, CLERK_PUBLISHABLE_KEY, CLERK_JWKS_URL
```

Also create `frontend/.env.local`:
```bash
VITE_CLERK_PUBLISHABLE_KEY=pk_test_...
VITE_API_BASE_URL=http://localhost:8080
VITE_WS_URL=ws://localhost:8080
```

### 2. Start Infrastructure

```bash
# Start PostgreSQL, Redis, and Judge0 (from repo root)
docker-compose up -d

# Verify all services are healthy
docker-compose ps
```

> **Note on Judge0 + Windows Docker Desktop**: Judge0 requires `privileged: true` for its
> seccomp sandbox. On Docker Desktop for Windows, ensure that "Use the WSL 2 based engine"
> is enabled in Docker Desktop settings for best compatibility.

### 3. Start Backend

```bash
cd backend
./gradlew bootRun
# Starts on http://localhost:8080
# Health check: http://localhost:8080/health
```

### 4. Start Frontend

```bash
cd frontend
npm install
npm run dev
# Starts on http://localhost:3000
```

## Key Commands

```bash
# Backend
cd backend && ./gradlew bootRun          # Start dev server
cd backend && ./gradlew test             # Run tests
cd backend && ./gradlew build            # Build JAR

# Frontend
cd frontend && npm run dev               # Start Vite dev server
cd frontend && npm run build             # Production build
cd frontend && npm run test              # Vitest tests

# Infrastructure
docker-compose up -d                     # Start all services
docker-compose down                      # Stop all services
docker-compose ps                        # Check service health
docker-compose logs judge0-server        # Judge0 logs

# Judge0 standalone (optional)
cd judge0 && docker-compose up -d        # Start Judge0 only
```

## Architecture

```
ai-interview-platform/
├── backend/          # Kotlin + Spring WebFlux (port 8080)
├── frontend/         # React + TypeScript + Vite (port 3000)
├── judge0/           # Judge0 CE standalone compose + config
├── docs/             # Architecture decisions
├── docker-compose.yml
├── .env.example
└── README.md
```

### Conversation State Machine

```
INTERVIEW_START → QUESTION_PRESENTED → CANDIDATE_RESPONSE
                                            ├── FOLLOW_UP → CANDIDATE_RESPONSE
                                            ├── CODING → HINT → CODING
                                            │         └── FOLLOW_UP
                                            └── EVALUATION → NEXT_QUESTION
                                                          └── INTERVIEW_END
```

### WebSocket Transport

Primary interaction channel: `ws://localhost:8080/ws/interview/{sessionId}`

All interview events (AI tokens, state changes, code results, hints) travel over this connection. REST is a fallback only.

## Judge0 Language IDs

| Language | Judge0 ID |
|---|---|
| Python 3 | 71 |
| Java | 62 |
| JavaScript (Node.js) | 63 |

## MVP Scope

- B2C only (personal org auto-created on signup)
- Text interaction (voice is Phase 2)
- No Stripe billing (free tier enforced via Redis counter: 3 interviews/month)
- DSA + Coding interview types
