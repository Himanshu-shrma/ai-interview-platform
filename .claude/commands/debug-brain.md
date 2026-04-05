# Command: Debugging Brain System Issues

## Common Issues and Diagnoses

### Phase stuck / goals never advance
**Cause**: TheAnalyst JSON parse failure
**Check**: grep logs for `FAILURE RATE HIGH` or `Failed to parse`
**Fix**: Verify DTOs have `@JsonIgnoreProperties(ignoreUnknown = true)` + defaults

### AI giving generic questions (doesn't know the problem)
**Cause**: `brain.questionDetails` is blank
**Check**: grep logs for `CRITICAL: Question`
**Fix**: Verify question loaded before `initBrain()`, description not blank

### AI asking to explain code during CODING phase
**Cause**: CODING phase rules not strict enough in NaturalPromptBuilder
**Fix**: Phase rules in `buildPhaseRules()` — check CODING section

### Test results not triggering AI response
**Cause**: Brain action not queued after CODE_RESULT
**Check**: `CodeExecutionService` — look for `brainService.addAction` after result
**Fix**: Add PROBE_DEPTH action when tests fail

### Overall score shows wrong value
**Cause**: ReportService formula bug
**Check**: `ReportService.kt` companion object weights — must sum to 1.0
**Fix**: Verify: 0.20 + 0.15 + 0.15 + 0.15 + 0.10 + 0.10 + 0.10 + 0.05 = 1.0

### Silence intelligence not working
**Check**: grep logs for `SilenceDecision`
**Verify**: `shouldRespond()` in TheConductor.kt — BEHAVIORAL always returns RESPOND

## Key Log Patterns
```
"Brain initialized"                    — should show question title
"problem_shared marked complete"       — phase transition working
"TheAnalyst: no goals completed"       — if consistent: parse failing
"FAILURE RATE HIGH"                    — TheAnalyst JSON errors at scale
"SilenceDecision"                      — TheConductor decision each turn
"action queued"                        — brain actions being added
"POSSIBLE ANALYST FAILURE"             — 5+ turns with 0 goals
```
