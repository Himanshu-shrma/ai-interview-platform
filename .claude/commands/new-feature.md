# Command: Starting a New Feature

## Before Writing Code
1. Read `.claude/CLAUDE.md` — understand current state
2. Read the relevant skill file for the work type
3. Verify branch: `git branch --show-current` (must be `feature/natural-interviewer`)
4. Check migrations: `ls backend/src/main/resources/db/migration/` — note highest V number

## Backend Code
- New service: `@Service`, inject via constructor
- New DB table: create `V{N}__description.sql` first
- New LLM call: use `LlmProviderRegistry`, never call provider directly
- New Redis op: go through `BrainService` for brain state
- New endpoint: add to `SecurityConfig` if no auth needed
- New entity field for JSON: use `String?` type, parse with ObjectMapper

## Frontend Code
- New page: add route to `App.tsx`, wrap in `ProtectedRoute` if auth required
- New data fetch: create hook in `hooks/` using TanStack Query
- Interview type specific: check `isCoding`/`isBehavioral`/`isSystemDesign`
- Use shadcn/ui components from `@/components/ui/`
- Import paths: use `@/` alias

## After Writing Code
```bash
cd backend && mvn compile -q    # Must pass
cd frontend && npm run build    # Must pass
git add -A && git commit -m "feat(TASK-XXX): description"
```
