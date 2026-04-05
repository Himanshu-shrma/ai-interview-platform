# Package Organization

```
com.aiinterview/
  auth/          — ClerkJwtAuthFilter, SecurityConfig, RateLimitFilter
  code/          — Judge0Client, CodeExecutionService, LanguageMap
  conversation/  — ConversationEngine, HintGenerator, InterviewState
    brain/       — TheConductor, TheAnalyst, TheStrategist, BrainService, etc.
    knowledge/   — KnowledgeAdjacencyMap
  interview/
    controller/  — InterviewController, QuestionController
    dto/         — SessionDto, QuestionDto, ApiError
    model/       — InterviewSession, Question, ConversationMessage, SessionQuestion
    repository/  — All R2DBC repositories
    service/     — InterviewSessionService, QuestionService, RedisMemoryService
    ws/          — InterviewWebSocketHandler, WsSessionRegistry, WsMessageTypes
  report/
    controller/  — ReportController
    service/     — ReportService, EvaluationAgent
    model/       — EvaluationReport
  shared/
    ai/          — LlmProviderRegistry, LlmRequest, ModelConfig, providers/
    domain/      — Enums
  user/          — UserBootstrapService, UsageLimitService
```
