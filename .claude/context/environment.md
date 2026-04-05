# Development Environment

## Local Services
| Service | Port | Purpose |
|---------|------|---------|
| Backend | 8080 | Spring Boot API + WS |
| Frontend | 3000 | Vite dev server |
| PostgreSQL | 5432 | Main database (postgres:15-alpine) |
| Redis | 6379 | Session cache + brain state (redis:7-alpine, AOF enabled) |
| Judge0 Server | 2358 | Code execution API (judge0:1.13.1) |
| Judge0 Worker | — | Resque workers (same image) |
| Judge0 DB | — | Separate postgres:16-alpine |
| Judge0 Redis | — | Separate redis with password |

## Start Everything
```bash
# 1. Infrastructure
docker-compose up -d

# 2. Backend
cd backend && mvn spring-boot:run

# 3. Frontend
cd frontend && npm run dev
```

## Environment Variables (from .env / .env.local)
### Backend
```
OPENAI_API_KEY=sk-...           # Required
CLERK_JWKS_URL=https://...      # Required
CLERK_PUBLISHABLE_KEY=pk_...    # Required
JUDGE0_BASE_URL=http://localhost:2358
JUDGE0_AUTH_TOKEN=...
POSTGRES_USER=aiinterview
POSTGRES_PASSWORD=changeme
LLM_PROVIDER=openai             # openai | groq | gemini
LLM_INTERVIEWER_MODEL=gpt-4o
LLM_BACKGROUND_MODEL=gpt-4o-mini
```

### Frontend (frontend/.env.local)
```
VITE_CLERK_PUBLISHABLE_KEY=pk_test_...
VITE_API_BASE_URL=http://localhost:8080
VITE_WS_BASE_URL=ws://localhost:8080
```

## Common Commands
```bash
# Backend
cd backend && mvn compile -q              # Quick compile check
cd backend && mvn test                    # Run all tests
cd backend && mvn test -Dtest=ClassName   # Run specific test
cd backend && mvn spring-boot:run         # Start server

# Frontend
cd frontend && npm install                # Install deps
cd frontend && npm run dev                # Dev server (port 3000)
cd frontend && npm run build              # Production build check
cd frontend && npx tsc --noEmit           # Type check only

# Docker
docker-compose up -d                      # Start all infra
docker-compose down                       # Stop all
docker-compose logs -f redis              # Tail logs
```

## Health Check
```bash
curl http://localhost:8080/health
# Expected: {"status":"UP","db":"UP","redis":"UP"}
```
