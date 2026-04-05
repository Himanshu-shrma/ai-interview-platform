# Skill: Interview Flow + Phase Logic

## When This Applies
Any work on interview phase transitions, AI behavior per phase, goal tracking, or interview type differences.

## Phase Labels (inferred from completed goals in BrainObjectivesRegistry.kt)

### CODING/DSA Phases
```
INTRO -> CLARIFICATION -> APPROACH -> CODING -> REVIEW -> FOLLOWUP -> WRAP_UP
```
Triggered by goal completions:
- `problem_shared` -> CLARIFICATION
- `clarifying_questions_handled` -> APPROACH
- `approach_understood` -> CODING
- `solution_implemented` -> REVIEW
- `complexity_owned` -> FOLLOWUP
- `interview_closed` -> WRAP_UP

### BEHAVIORAL Phases
```
OPENING -> STORY_1 -> STORY_2 -> STORY_3 -> FINAL_STORY -> WRAP_UP
```
- `psychological_safety` -> STORY_1
- `star_q1_complete` -> STORY_2
- `star_q2_complete` -> STORY_3
- `star_q3_complete` -> FINAL_STORY
- `interview_closed` -> WRAP_UP

### SYSTEM_DESIGN Phases
```
INTRO -> REQUIREMENTS -> ARCHITECTURE -> DESIGN -> DEEP_DIVE -> WRAP_UP
```
- `problem_shared` -> REQUIREMENTS
- `requirements_gathered` -> ARCHITECTURE
- `high_level_design` -> DESIGN
- `tradeoffs_acknowledged` -> DEEP_DIVE
- `interview_closed` -> WRAP_UP

## Phase-Specific AI Behavior (NaturalPromptBuilder.kt)

### INTRO/OPENING
- Warm greeting, 1-2 exchanges
- BEHAVIORAL: more warm-up (2-3 exchanges), build psychological safety
- Do NOT present problem yet

### CLARIFICATION
- Answer questions honestly and concisely
- If no questions: prompt once ("Any questions about constraints?")
- Do NOT hint at solution

### APPROACH
- Listen while candidate thinks, do NOT prompt if silent
- After explanation: ask ONE of (complexity? trade-offs? alternatives?)
- When solid: "Sounds good. Go ahead and code it." — then NOTHING ELSE

### CODING — CRITICAL
- **COMPLETE SILENCE** unless candidate asks `?`
- If thinking aloud: "Mm." or "Got it." ONLY
- FORBIDDEN: "walk me through your code", "can you explain", "why did you"
- Move to review ONLY on done/finished/submitted signal

### REVIEW
- "Walk me through your solution."
- Then ONE specific question about THEIR actual code
- Do NOT ask generic or multiple questions

### FOLLOWUP/DEEP_DIVE
- "Is there a more optimal approach?"
- "Can we reduce space complexity?"

### WRAP_UP
- Brief positive close
- ALWAYS: "Do you have any questions for me?"

### BEHAVIORAL STAR Phases
- If vague: "What specifically did YOU do?"
- If no result: "What was the actual outcome?"
- If "we" not "I": "What was your personal contribution?"

## Silence Intelligence (TheConductor.kt)
```kotlin
enum class SilenceDecision { RESPOND, SILENT, WAIT_THEN_RESPOND }
```
- BEHAVIORAL: always RESPOND
- Question mark in message: RESPOND
- "help"/"hint"/"stuck": RESPOND
- "done"/"finished": RESPOND
- FlowGuard action pending: RESPOND
- CODING phase + thinking aloud: WAIT_THEN_RESPOND (2s delay, then "Mm.")
- CODING phase + short message (<10 chars): SILENT

## Candidate Intent Detection (TheAnalyst)
```
READING_PROBLEM, CLARIFYING, THINKING_ALOUD, PROPOSING_APPROACH,
CODING_NARRATION, DONE_CODING, EXPLAINING_CODE, STUCK, REQUESTING_HINT
```
Intent triggers automatic goal completions and brain actions.

## FlowGuard Rules (BrainFlowGuard.kt)
4 rules max:
1. problem_shared not complete by turn 4 -> present problem
2. Overtime (< 3 min remaining) -> "Let's wrap up"
3. 8 turns with no goal progress -> nudge forward
4. Behind schedule by > 2 goals -> pace faster

## ConversationEngine.startInterview()
- Sends greeting ONLY (no problem yet)
- TheConductor presents problem on turn 1 (when `problem_shared` not in completedGoals)
- BEHAVIORAL: greeting + "Tell me about what you've been working on"
- CODING: greeting + "Whenever you're ready, we can jump in"
- Initializes brain with goals from BrainObjectivesRegistry

## Code Gate (TheConductor.kt)
During CODING phase for CODING/DSA interviews, if `isMeaningfulCode` is false:
- Returns canned response ("Go ahead — I'll wait while you code.")
- Does NOT call LLM
- Checks: >= 3 real code lines, not just comments, has executable patterns
