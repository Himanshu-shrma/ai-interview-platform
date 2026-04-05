# InterviewerBrain Field Reference

## Core Fields
| Field | Type | Updated By | Critical? |
|-------|------|-----------|-----------|
| sessionId | UUID | initBrain | Yes — identity |
| userId | UUID | initBrain | Yes — ownership |
| interviewType | String | initBrain | Yes — CODING/BEHAVIORAL/SYSTEM_DESIGN |
| questionDetails | InterviewQuestion | initBrain, updateBrain (next Q) | Yes — drives prompt section 6 |
| turnCount | Int | incrementTurnCount (every turn) | Yes — gates sections, FlowGuard |
| startedAt | Instant | initBrain | Yes — time calculations |
| lastActivityAt | Instant | incrementTurnCount | Moderate — timeout detection |

## Candidate Profile (updated by TheAnalyst)
| Field | Type | Drive Behavior? | Notes |
|-------|------|-----------------|-------|
| thinkingStyle | ThinkingStyle enum | Yes — prompt calibration | BOTTOM_UP/TOP_DOWN/INTUITIVE/METHODICAL |
| reasoningPattern | ReasoningPattern | Yes — +1.0 algo score if SCHEMA_DRIVEN | SCHEMA_DRIVEN (expert) / SEARCH_DRIVEN |
| overallSignal | CandidateSignal | Yes — challenge calibration | STRONG/SOLID/AVERAGE/STRUGGLING |
| currentState | EmotionalState | Yes — tone adaptation | CONFIDENT/NERVOUS/STUCK/FLOWING/FRUSTRATED |
| anxietyLevel | Float (0-1) | Yes — anxiety adjustment in eval | >0.7 triggers REDUCE_PRESSURE |
| avgAnxietyLevel | Float | Yes — evaluation score adjustment | Running average, used by EvaluationAgent |
| flowState | Boolean | Yes — "don't interrupt" signal | When true, NaturalPromptBuilder adds flow rule |
| trajectory | PerformanceTrajectory | Yes — calibration | IMPROVING/DECLINING/STABLE |
| psychologicalSafety | Float (0-1) | Yes — <0.4 triggers RESTORE_SAFETY | Critical for BEHAVIORAL interviews |
| linguisticPattern | LinguisticPattern | Moderate — eval adjustment | HEDGED_UNDERSTANDER gets upward adjustment |
| abstractionLevel | Int (1-5) | Moderate — depth assessment | 1=syntax, 5=evaluation |
| selfRepairCount | Int | Yes — productive struggle bonus | +0.5 per exchange in evaluation |
| cognitiveLoadSignal | CognitiveLoad | Yes — OVERLOADED triggers REDUCE_LOAD | NOMINAL/ELEVATED/OVERLOADED |
| avoidancePatterns | List<String> | Moderate — probe targets | "avoids complexity discussion" |
| unknownHandlingPattern | UnknownHandling | Moderate — eval signal | REASONS_FROM_PRINCIPLES is positive |
| pressureResponse | PressureResponse | Yes — pressure calibration | RISES/FREEZES/STEADY/DEFENSIVE |
| communicationStyle | CommunicationStyle | Moderate | VERBOSE/TERSE/CLEAR/CONFUSED |
| knowledgeMap | Map<String, Float> | Low — unused in prompt | Topic → confidence mapping |
| mentalSimulationAbility | Float | Low | Set but rarely read |
| dataPoints | Int | Internal | Gates candidate section (>= 2) |

## Hypothesis & Claim Registries
| Field | Updated By | Purpose |
|-------|-----------|---------|
| hypothesisRegistry.hypotheses | TheAnalyst | Open hypotheses to test |
| claimRegistry.claims | TheAnalyst | Candidate's technical claims |
| claimRegistry.pendingContradictions | TheAnalyst | Contradictions to surface |

## Goal Tracking
| Field | Updated By | Purpose |
|-------|-----------|---------|
| interviewGoals.required | initBrain | Fixed goals from BrainObjectivesRegistry |
| interviewGoals.completed | TheAnalyst (markGoalsComplete) | Phase inference |
| interviewGoals.failedAttempts | — | FlowGuard stall detection (currently unused) |

## Action & Strategy
| Field | Updated By | Purpose |
|-------|-----------|---------|
| actionQueue.pending | TheAnalyst, FlowGuard, CodeExec | Next actions for TheConductor |
| actionQueue.lastCompleted | TheConductor | Tracking |
| currentStrategy | TheStrategist (every 5 turns) | Approach, tone, time guidance |
| thoughtThread | TheAnalyst (appendThought) | Running commentary with compression |

## Code State
| Field | Updated By | Purpose |
|-------|-----------|---------|
| currentCode | InterviewWebSocketHandler (CODE_UPDATE) | AI reads code for review |
| programmingLanguage | InterviewWebSocketHandler | Language context |

## Scoring
| Field | Updated By | Purpose |
|-------|-----------|---------|
| exchangeScores | TheAnalyst | Per-turn dimension scores |
| bloomsTracker | TheAnalyst | Topic → Bloom's level (1-6) |
| hintOutcomes | — | Hint effectiveness tracking |
| challengeSuccessRate | TheAnalyst | Difficulty calibration |

## Acknowledgment Tracking
| Field | Updated By | Purpose |
|-------|-----------|---------|
| usedAcknowledgments | TheConductor | Don't repeat "Right", "Okay", etc. |

## Transcript
| Field | Updated By | Purpose |
|-------|-----------|---------|
| rollingTranscript | ConversationEngine, BrainService | Last 8 turns, included in prompt |
| earlierContext | — | Compressed old transcript (unused) |

## Behavioral-Specific
| Field | Updated By | Purpose |
|-------|-----------|---------|
| personality | initBrain | Interviewer style: friendly/faang_senior/etc. |
| targetCompany | initBrain | Company context for questions |
| experienceLevel | initBrain | Calibration hint |

## DEAD FIELDS — Never Populated (remove before launch)
| Field | Reason |
|-------|--------|
| topicSignalBudget | TheAnalyst sets it but signal depletion detection has marginal value |
| zdpEdge | Zone of Proximal Development — set but never read in prompt |
| topicHistory | Set but interleaving detection rarely fires |
| questionTypeHistory | Set but distribution analysis never runs |
| formativeFeedbackGiven | Counter incremented but never used in decisions |

## THEORETICAL — Low Impact
| Field | Why Low Impact |
|-------|---------------|
| bloomsTracker | 6-level classification unreliable from LLM |
| knowledgeMap | Never displayed or used in prompt |
| mentalSimulationAbility | Set once, rarely read |
