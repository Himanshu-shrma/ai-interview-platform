# AI Interview Platform — Local Development Guide

## Prerequisites

- **Java 17+** (JDK, not JRE)
- **Maven 3.9+**
- **Node.js 18+** and npm
- **Docker Desktop** (for PostgreSQL, Redis, Judge0)
- **Clerk.dev account** (free tier)
- **OpenAI API key** (GPT-4o access required)

## 1. Clone and Configure

```bash
git clone <repo-url>
cd ai-interview-platform
cp .env.example .env
```

Edit `.env` and fill in your actual keys (see "Getting Your Keys" below).

## 2. Start Infrastructure

```bash
docker-compose up -d
```

This starts:
- PostgreSQL 15 on port 5432 (app database)
- Redis 7 on port 6379 (session memory, rate limiting)
- Judge0 CE on port 2358 (code execution sandbox)

Verify all containers are healthy:
```bash
docker-compose ps
```

## 3. Start Backend

```bash
cd backend
mvn spring-boot:run
```

Backend starts on `http://localhost:8080`. Flyway runs migrations automatically on first boot.

Verify:
```bash
curl http://localhost:8080/health
```

## 4. Start Frontend

```bash
cd frontend
cp ../.env.example .env.local  # Then edit with frontend-specific vars
npm install
npm run dev
```

Frontend starts on `http://localhost:3000`.

### Frontend `.env.local`

```
VITE_CLERK_PUBLISHABLE_KEY=pk_test_...
VITE_API_BASE_URL=http://localhost:8080
VITE_WS_URL=ws://localhost:8080
```

## 5. Run the Demo

1. Open `http://localhost:3000`
2. Sign in with Clerk (Google, GitHub, or email)
3. Click "Start Interview" on the dashboard
4. Configure: CODING / MEDIUM / Python / Friendly Mentor
5. Complete the interview:
   - Send 3+ messages discussing your approach
   - Write code in the Monaco editor
   - Click "Run" to test, then "Submit"
   - Click "End Interview" when ready
6. Wait for the evaluation report (10-15 seconds)
7. View your detailed report with radar chart and scores

## Getting Your Keys

### Clerk.dev

1. Go to [dashboard.clerk.com](https://dashboard.clerk.com)
2. Create a new application (free tier is fine)
3. Enable sign-in methods: Email, Google, GitHub
4. Copy from API Keys page:
   - **Publishable key** → `CLERK_PUBLISHABLE_KEY` and `VITE_CLERK_PUBLISHABLE_KEY`
   - **Secret key** → `CLERK_SECRET_KEY`
5. Copy JWKS URL from API Keys → Advanced:
   - Format: `https://<your-instance>.clerk.accounts.dev/.well-known/jwks.json`
   - → `CLERK_JWKS_URL`

### OpenAI

1. Go to [platform.openai.com/api-keys](https://platform.openai.com/api-keys)
2. Create a new API key
3. Ensure your account has GPT-4o access
4. → `OPENAI_API_KEY`

## Running Tests

```bash
# Backend unit tests (no Docker needed)
cd backend && mvn test -Dtest='!HealthControllerTest,!ClerkJwtAuthFilterTest,!RedisMemoryServiceTest'

# All backend tests (requires Docker running)
cd backend && mvn test

# Frontend build check
cd frontend && npm run build
```

## Environment Variables Reference

| Variable | Where | Description |
|---|---|---|
| `OPENAI_API_KEY` | Backend | OpenAI API key for GPT-4o/4o-mini |
| `CLERK_SECRET_KEY` | Backend | Clerk backend secret key |
| `CLERK_PUBLISHABLE_KEY` | Backend | Clerk publishable key |
| `CLERK_JWKS_URL` | Backend | Clerk JWKS endpoint URL |
| `DATABASE_URL` | Backend | R2DBC PostgreSQL connection string |
| `REDIS_URL` | Backend | Redis connection string |
| `JUDGE0_BASE_URL` | Backend | Judge0 API URL (default: http://localhost:2358) |
| `JUDGE0_AUTH_TOKEN` | Backend | Judge0 authentication token |
| `VITE_CLERK_PUBLISHABLE_KEY` | Frontend | Clerk publishable key for React |
| `VITE_API_BASE_URL` | Frontend | Backend API URL (default: http://localhost:8080) |
| `VITE_WS_URL` | Frontend | WebSocket URL (default: ws://localhost:8080) |

## Known Limitations (MVP)

- **Text-only interviews** — no voice input/output yet
- **No payment integration** — free tier enforced via Redis counter (3 interviews/month), no Stripe
- **No B2B features** — no org management, recruiter views, or team dashboards
- **Single question per session** — one interview question per session for MVP
- **No question caching** — first session in a new category triggers GPT-4o question generation (~5s delay)
- **Judge0 requires Docker privileged mode** — needed for seccomp sandbox
- **BEHAVIORAL and SYSTEM_DESIGN categories** — functional but use stub AI prompts (less polished than CODING/DSA)
- **CASE_STUDY category** — hidden from UI for MVP
- **Bundle size** — Monaco Editor + recharts produce a large JS bundle (~860KB); code splitting deferred

## Deferred to Phase 2

- **Voice mode** — OpenAI Whisper (STT) + OpenAI TTS for spoken interviews
- **Stripe integration** — Pro tier ($9/mo) with unlimited interviews
- **B2B organization features** — company accounts, recruiter dashboards, candidate pipelines
- **Interview templates** — reusable configs shared within an organization
- **Multi-question sessions** — sessions with 2-3 questions of increasing difficulty
- **Code splitting** — lazy-load Monaco Editor and recharts for faster initial load
- **GPT-4o-mini transcript compression** — currently uses naive string concatenation
- **Analytics dashboard** — trend charts, category-wise performance over time
- **Mobile responsive design** — current layout optimized for desktop
