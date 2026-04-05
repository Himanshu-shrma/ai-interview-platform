# WebSocket Protocol Reference

## Connection
```
ws://localhost:8080/ws/interview/{sessionId}?token={clerkJWT}
```

## Inbound Messages (Client → Server)

### CANDIDATE_MESSAGE
```typescript
{ type: 'CANDIDATE_MESSAGE', text: string, codeSnapshot?: CodeSnapshot }
```
CodeSnapshot: `{ content, language, hasMeaningfulCode, lineCount, hasRunResults, lastRunPassed }`

### CODE_RUN
```typescript
{ type: 'CODE_RUN', code: string, language: string, stdin?: string }
```

### CODE_SUBMIT
```typescript
{ type: 'CODE_SUBMIT', code: string, language: string, sessionQuestionId: string }
```

### CODE_UPDATE
```typescript
{ type: 'CODE_UPDATE', code: string, language: string }
```

### REQUEST_HINT
```typescript
{ type: 'REQUEST_HINT', hintLevel: number }
```

### END_INTERVIEW
```typescript
{ type: 'END_INTERVIEW', reason: 'CANDIDATE_ENDED' | 'TIME_EXPIRED' }
```

### PING
```typescript
{ type: 'PING' }
```

## Outbound Messages (Server → Client)

### AI_CHUNK (streaming)
```typescript
{ type: 'AI_CHUNK', delta: string, done: boolean }
```
- `done: false` → accumulate token
- `done: true` → finalize message (delta is "")

### STATE_CHANGE
```typescript
{ type: 'STATE_CHANGE', state: string }
```
States: INTERVIEW_STARTING, QUESTION_PRESENTED, CODING_CHALLENGE, CANDIDATE_RESPONDING, AI_ANALYZING, EVALUATING, INTERVIEW_END

### CODE_RESULT (from CODE_SUBMIT)
```typescript
{ type: 'CODE_RESULT', status: 'ACCEPTED'|'FAILED', stdout?, stderr?, runtimeMs?, testResults?: TestResult[] }
```

### CODE_RUN_RESULT (from CODE_RUN)
```typescript
{ type: 'CODE_RUN_RESULT', stdout?, stderr?, exitCode? }
```

### HINT_DELIVERED
```typescript
{ type: 'HINT_DELIVERED', hint: string, level: number, hintsRemaining: number, refused: boolean }
```

### SESSION_END
```typescript
{ type: 'SESSION_END', reportId: string }
```

### STATE_SYNC (reconnect)
```typescript
{
    type: 'STATE_SYNC',
    state: string,
    currentQuestionIndex: number,
    totalQuestions: number,
    currentQuestion: { title, description, codeTemplates? } | null,
    currentCode: string | null,
    programmingLanguage: string | null,
    hintsGiven: number,
    messages: { role: string, content: string }[],
    showCodeEditor: boolean
}
```

### ERROR
```typescript
{ type: 'ERROR', code: string, message: string }
```
Codes: SESSION_ERROR, SESSION_COMPLETED, SESSION_EXPIRED, SESSION_NOT_FOUND, PARSE_ERROR, RATE_LIMITED, INVALID_MESSAGE, UNSUPPORTED_LANGUAGE, EXECUTION_ERROR, AI_ERROR, HINT_ERROR, UNKNOWN_TYPE

## Message Flow Diagrams

### Normal Interview Turn
```
Client: CANDIDATE_MESSAGE → Server: STATE_CHANGE(CANDIDATE_RESPONDING)
Server: AI_CHUNK(delta, done=false) × N → Server: AI_CHUNK("", done=true)
Server: STATE_CHANGE(AI_ANALYZING) [background agents fire]
```

### Code Submission
```
Client: CODE_SUBMIT → Server: [runs against test cases]
Server: CODE_RESULT(status, testResults) → [brain action queued]
If all pass: Server: STATE_CHANGE(FOLLOW_UP)
```

### Interview End
```
Client: END_INTERVIEW → Server: STATE_CHANGE(EVALUATING)
Server: [EvaluationAgent runs] → Server: SESSION_END(reportId)
Client: navigate(`/report/${sessionId}`)
```

### Reconnect
```
Client: WS connect → Server detects existing memory
Server: STATE_SYNC(full state + message history)
Client: restores all UI state from STATE_SYNC
```
