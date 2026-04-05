# Known Issues and Technical Debt

## CRITICAL (Fix Before Launch)
1. **HintGenerator reads InterviewMemory NOT InterviewerBrain** — File: `conversation/HintGenerator.kt`. Fix: rewrite to use `BrainService.getBrainOrNull()`

2. **Dual state: InterviewMemory AND InterviewerBrain** — Both live in Redis. EvaluationAgent reads Memory, TheConductor reads Brain. Must migrate EvaluationAgent to read from Brain exclusively.

3. **No GDPR data deletion endpoint** — Fix: `DELETE /api/v1/users/me` with cascade delete

4. **No Sentry error tracking** — Fix: add `io.sentry:sentry-spring-boot-starter-jakarta` to pom.xml

## HIGH
5. **ConversationEngine is a god class** — 385+ lines, 12 deps. Plan: split into InterviewLifecycle + MessageHandler

6. **No LLM cost tracking per session** — Fix: add llmCallCount + estimatedTokensUsed to InterviewerBrain

7. **EvaluationAgent doesn't pass brain to evaluate()** — In `ReportService.generateAndSaveReport()`, brain is already deleted before evaluation. Brain enrichment data may be lost.

## MEDIUM
8. **Dead brain fields** — `topicSignalBudget`, `zdpEdge` have marginal value, cost prompt tokens. Consider removing.

9. **bloomsTracker at 6 levels** — LLM classification unreliable. Consider collapsing to 3 levels.

10. **Question validation** — LLM-generated test cases can have wrong outputs. Need QuestionValidationService that runs optimal solution vs test cases.

## LOW
11. **15 ActionTypes with overlap** — REDUCE_LOAD + REDUCE_PRESSURE could consolidate

12. **outputMatches() too lenient** — Normalized sort comparison can false-positive on unordered outputs where order matters
