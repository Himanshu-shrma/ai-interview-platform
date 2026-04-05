---
name: frontend-interview-ui
description: >
  USE THIS SKILL when working on InterviewPage, ReportPage, WS integration,
  Monaco editor, type-based layout (CODING vs BEHAVIORAL vs SYSTEM_DESIGN),
  score display, conversation components, or any React frontend work.
  Files: frontend/src/pages/*.tsx, frontend/src/hooks/*.ts,
  frontend/src/components/interview/*.tsx, frontend/src/types/index.ts
---

# Frontend Interview UI

## Interview Type Detection — CRITICAL

Every layout decision gates on interview type:

```typescript
// InterviewPage.tsx
const isBehavioral = session?.category === 'BEHAVIORAL'
const isCoding = session?.category === 'CODING' || session?.category === 'DSA'

// Layout: BEHAVIORAL = full-width, no editor
// CODING = split panel (conversation + code editor)
// SYSTEM_DESIGN = split panel but no run/submit

className={isBehavioral ? 'w-full max-w-3xl mx-auto'
  : showCodeEditor ? 'w-1/2 lg:w-2/5 border-r'
  : 'w-full max-w-3xl mx-auto'}

// UI element visibility:
{showCodeEditor && !isBehavioral && <CodeEditor />}    // No editor for behavioral
showHints={session?.category === 'CODING' || session?.category === 'DSA'}  // Hints only coding
showRunSubmit={isCoding}  // Run/Submit only coding
```

## WebSocket Hook (useInterviewSocket.ts)

### Connection
- URL: `${WS_BASE_URL}/ws/interview/${sessionId}?token=${clerkJWT}`
- Max 3 retries with exponential backoff (2s, 4s, 8s)
- Heartbeat PING every 25 seconds
- Message queue for pre-connection sends

### Key Design Patterns
- All callbacks stored as refs (prevents effect re-runs)
- Single `useEffect` manages entire WS lifecycle per sessionId
- Local `cancelled` flag per effect invocation (React StrictMode safe)
- `wsRef` shared across connect/send/disconnect

```typescript
const { status, send, disconnect } = useInterviewSocket({
    sessionId: sessionId ?? '',
    onMessage: handleMessage,
})
```

## Conversation Hook (useConversation.ts)

```typescript
const { messages, addCandidateMessage, appendAiToken, finalizeAiMessage, addAiMessage, replaceAll } = useConversation()
```

- `appendAiToken(delta)` — accumulates streaming tokens into current AI message
- `finalizeAiMessage()` — marks current AI message complete (on AI_CHUNK done=true)
- `addAiMessage(text)` — adds complete AI message (for AI_MESSAGE)
- `replaceAll(msgs)` — replaces all messages (for STATE_SYNC reconnect)

## WS Message Handling (InterviewPage.tsx)

```typescript
case 'AI_CHUNK':
    if (chunk.done) { finalizeAiMessage() }
    else { appendAiToken(chunk.delta); setIsAiThinking(false) }

case 'STATE_CHANGE':
    setCurrentState(sc.state)
    if (sc.state === 'CODING_CHALLENGE') { setShowCodeEditor(true) }

case 'CODE_RESULT':
    setIsCodeRunning(false); setCodeResult(msg)

case 'HINT_DELIVERED':
    if (!hint.refused) { setHintState({...}); setHintsGiven(3 - hint.hintsRemaining) }

case 'SESSION_END':
    setReportId(end.reportId)
    setTimeout(() => navigate(`/report/${sessionId}`), 2000)

case 'STATE_SYNC':  // Reconnect recovery
    setCurrentState(sync.state)
    setCurrentCode(sync.currentCode ?? '')
    replaceAll(sync.messages.map(...))

case 'QUESTION_TRANSITION':
    setCurrentQuestionIndex(qt.questionIndex)
    setCurrentCode('')  // Reset for new question
    setCodeResult(null)
```

## CodeSnapshot — Sent with Every Message

Every CANDIDATE_MESSAGE includes editor state:
```typescript
const codeSnapshot: CodeSnapshot = {
    content: code || null,
    language: currentLanguageRef.current || null,
    hasMeaningfulCode: hasMeaningfulCode(code),  // checks for real code
    lineCount: code ? code.split('\n').length : 0,
    hasRunResults: codeResultRef.current !== null,
    lastRunPassed: codeResultRef.current?.status === 'ACCEPTED',
}
send({ type: 'CANDIDATE_MESSAGE', text: content, codeSnapshot })
```

## Monaco Editor (CodeEditor.tsx)

- Lazy loaded: `const CodeEditor = lazy(() => import(...))`
- Options: wordWrap 'on', no minimap, fontSize 13, automaticLayout true
- CODE_UPDATE sent on every change for brain awareness
- Code templates loaded per question + language

## Report Page (ReportPage.tsx)

### Radar Chart
```typescript
const radarData = (Object.keys(dimensionLabels) as ...).map((key) => ({
    dimension: dimensionLabels[key],  // "Problem Solving", "Algorithm", etc.
    score: scores[key],
    fullMark: 10,
}))

<RadarChart data={radarData} cx="50%" cy="50%" outerRadius="75%">
    <PolarGrid />
    <PolarAngleAxis dataKey="dimension" tick={{ fontSize: 11 }} />
    <PolarRadiusAxis angle={30} domain={[0, 10]} />
    <Radar dataKey="score" stroke="hsl(221, 83%, 53%)" fill="hsl(221, 83%, 53%)" fillOpacity={0.2} />
</RadarChart>
```

### Score Label Thresholds
```typescript
function scoreLabel(score: number): string {
    if (score >= 9) return 'Excellent'
    if (score >= 7) return 'Good'
    if (score >= 5) return 'Average'
    return 'Needs Work'
}
```

### Score Colors
```typescript
score >= 9 → blue | score >= 7 → green | score >= 5 → yellow | < 5 → red
```

## TanStack Query Patterns

```typescript
// Data fetching
const { data, isLoading } = useQuery({
    queryKey: ['interview', sessionId],
    queryFn: () => api.get<SessionDetailDto>(`/api/v1/interviews/sessions/${sessionId}`),
    enabled: !!sessionId,
})

// Mutations
const mutation = useMutation({
    mutationFn: (config: InterviewConfig) =>
        api.post<StartSessionResponse>('/api/v1/interviews/sessions', config),
    onSuccess: (data) => navigate(`/interview/${data.sessionId}`),
})
```

## Rules
- **No `<form>` tags** — use controlled state + onClick handlers
- **AI messages**: render with markdown (dangerouslySetInnerHTML via renderMarkdown)
- **Candidate messages**: plain text only, no HTML
- **Import alias**: `@/` maps to `frontend/src/` (vite.config.ts)
- **UI components**: shadcn/ui from `@/components/ui/`

## Integrity Signals

InterviewPage tracks TAB_SWITCH and PASTE_DETECTED events:
- `document.visibilitychange` → records TAB_SWITCH
- `document.paste` → records PASTE_DETECTED (if > 20 chars)
- Flushed to `/api/v1/integrity` every 30 seconds (best effort)
