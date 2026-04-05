# Skill: React + TypeScript Frontend Patterns

## When This Applies
Any frontend work: pages, components, hooks, WS integration, report display.

## Project Structure
```
frontend/src/
  pages/
    DashboardPage.tsx       — interview list + stats
    InterviewSetupPage.tsx  — category/difficulty/company form
    InterviewPage.tsx       — MAIN: split panel, WS, code editor
    ReportPage.tsx          — scores + radar + transcript
  hooks/
    useInterviewSocket.ts   — WS connection + reconnect + message routing
    useConversation.ts      — message state + AI token accumulation
    useInterviews.ts        — TanStack Query hooks for REST
    useReport.ts            — Report data fetching
  components/
    interview/              — CodeEditor, ConversationPanel, TestResults, TimerDisplay, HintPanel, ThinkingIndicator
    shared/                 — CategoryBadge, DifficultyBadge, PageHeader, ScoreDisplay
    ui/                     — shadcn/ui components (button, card, dialog, etc.)
  lib/
    api.ts                  — Axios instance
    utils.ts                — cn() utility for classnames
  types/
    index.ts                — ALL TypeScript interfaces
  providers/
    index.tsx               — ClerkProvider + QueryClient + Router
```

## Interview Type Layout — CRITICAL
```typescript
const isBehavioral = session?.category === 'BEHAVIORAL'
const isCoding = session?.category === 'CODING' || session?.category === 'DSA'

// BEHAVIORAL: full-width conversation, no code editor, no hints
// CODING/DSA: split panel (conversation + code editor), run/submit/hints
// SYSTEM_DESIGN: split panel (conversation + notes editor), no run/submit, no hints
```

Layout class logic in InterviewPage.tsx:
```typescript
className={isBehavioral ? 'w-full max-w-3xl mx-auto' : showCodeEditor ? 'w-1/2 lg:w-2/5 border-r' : 'w-full max-w-3xl mx-auto'}
```

## WebSocket Hook (useInterviewSocket.ts)
- Connects to `${WS_BASE_URL}/ws/interview/${sessionId}?token=${clerkJWT}`
- Max 3 retries with exponential backoff (2s, 4s, 8s)
- Heartbeat PING every 25s
- Queues messages if WS not yet open
- Handles STATE_SYNC on reconnect (server sends full state)
- Uses refs for callbacks to prevent effect re-runs

## Conversation Hook (useConversation.ts)
- `messages` array with `role: 'AI' | 'CANDIDATE'`
- `appendAiToken(delta)` — accumulates streaming tokens
- `finalizeAiMessage()` — marks current AI message complete
- `addAiMessage(text)` — adds complete AI message
- `addCandidateMessage(text)` — adds candidate message
- `replaceAll(msgs)` — for STATE_SYNC restore

## WS Message Handling (InterviewPage.tsx)
```typescript
case 'AI_CHUNK': // Accumulate streaming tokens, finalize on done=true
case 'STATE_CHANGE': // Update state, show code editor on CODING_CHALLENGE
case 'CODE_RESULT': // Show test results panel
case 'HINT_DELIVERED': // Show hint panel
case 'SESSION_END': // Navigate to /report/:sessionId after 2s delay
case 'STATE_SYNC': // Full state restore on reconnect
case 'QUESTION_TRANSITION': // Reset per-question state, load new code templates
```

## CodeSnapshot — Sent with Every Message
Every CANDIDATE_MESSAGE includes a CodeSnapshot:
```typescript
interface CodeSnapshot {
  content: string | null
  language: string | null
  hasMeaningfulCode: boolean  // checks for real code vs template
  lineCount: number
  hasRunResults: boolean
  lastRunPassed: boolean | null
}
```

## TanStack Query Patterns
```typescript
const { data, isLoading } = useQuery({
  queryKey: ['interview', sessionId],
  queryFn: () => api.get<SessionDetailDto>(`/api/v1/interviews/sessions/${sessionId}`),
  enabled: !!sessionId,
})

const mutation = useMutation({
  mutationFn: (config: InterviewConfig) =>
    api.post<StartSessionResponse>('/api/v1/interviews/sessions', config),
  onSuccess: (data) => navigate(`/interview/${data.sessionId}`),
})
```

## Monaco Editor (CodeEditor.tsx)
- Language selector syncs with currentLanguage state
- CODE_UPDATE sent on every change (for brain awareness)
- Code templates loaded per question + language
- `hasMeaningfulCode()` check before sending snapshots

## Integrity Signals (InterviewPage.tsx)
Tracks TAB_SWITCH and PASTE_DETECTED events, flushes to `/api/v1/integrity` every 30s.

## Rules
- No `<form>` tags — use controlled state + onClick handlers
- AI messages: always render with markdown (dangerouslySetInnerHTML)
- Candidate messages: plain text only
- Use shadcn/ui components from `@/components/ui/`
- Import paths use `@/` alias (configured in vite.config.ts)
