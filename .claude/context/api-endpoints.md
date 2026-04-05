# API Endpoints Reference

## REST Endpoints
| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | /api/v1/interviews/sessions | YES | Start interview session |
| GET | /api/v1/interviews/sessions | YES | List sessions (page, size) |
| GET | /api/v1/interviews/sessions/{id} | YES | Session detail + questions |
| POST | /api/v1/interviews/sessions/{id}/end | YES | End interview |
| GET | /api/v1/reports/{sessionId} | YES | Get evaluation report |
| GET | /api/v1/reports | YES | List reports (page, size) |
| GET | /api/v1/users/me | YES | Get current user profile |
| GET | /api/v1/users/me/stats | YES | User stats (scores, counts) |
| GET | /api/v1/code/languages | NO | Supported Judge0 languages |
| POST | /api/v1/integrity | YES | Report integrity signals (tab switch, paste) |
| GET | /health | NO | Health check |
| GET | /actuator/health | NO | Spring actuator with details |

## WebSocket
`ws://localhost:8080/ws/interview/{sessionId}?token={clerkJWT}`

### Inbound Messages (WsMessageTypes.kt — InboundMessage sealed class)
```json
{ "type": "CANDIDATE_MESSAGE", "text": "string", "codeSnapshot": { ... } }
{ "type": "CODE_RUN", "code": "string", "language": "string", "stdin": "string?" }
{ "type": "CODE_SUBMIT", "code": "string", "language": "string", "sessionQuestionId": "uuid" }
{ "type": "CODE_UPDATE", "code": "string", "language": "string" }
{ "type": "REQUEST_HINT", "hintLevel": 1 }
{ "type": "END_INTERVIEW", "reason": "CANDIDATE_ENDED" }
{ "type": "PING" }
```

### Outbound Messages (WsMessageTypes.kt — OutboundMessage sealed class)
```json
{ "type": "INTERVIEW_STARTED", "sessionId": "string", "state": "string" }
{ "type": "AI_CHUNK", "delta": "string", "done": false }
{ "type": "AI_MESSAGE", "text": "string", "state": "string" }
{ "type": "STATE_CHANGE", "state": "string" }
{ "type": "CODE_RUN_RESULT", "stdout": "?", "stderr": "?", "exitCode": 0 }
{ "type": "CODE_RESULT", "status": "ACCEPTED|FAILED", "testResults": [...], "runtimeMs": 123 }
{ "type": "HINT_DELIVERED", "hint": "string", "level": 1, "hintsRemaining": 2, "refused": false }
{ "type": "QUESTION_TRANSITION", "questionIndex": 1, "questionTitle": "...", "codeTemplates": {...} }
{ "type": "SESSION_END", "reportId": "uuid" }
{ "type": "STATE_SYNC", "state": "...", "currentQuestionIndex": 0, "messages": [...], ... }
{ "type": "ERROR", "code": "string", "message": "string" }
{ "type": "PONG" }
```

### Error Codes
- SESSION_ERROR: Session not found in Redis
- SESSION_COMPLETED: Interview already ended
- SESSION_EXPIRED: Session timed out
- SESSION_NOT_FOUND: No such session
- UNSUPPORTED_LANGUAGE: Language not in Judge0 LanguageMap
- EXECUTION_ERROR: Judge0 execution failed
- AI_ERROR: LLM unavailable
