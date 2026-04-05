---
name: interview-flow-design
description: >
  USE THIS SKILL when changing interview phases, AI behavior per phase,
  silence logic, goal tracking, type-specific behavior (CODING vs BEHAVIORAL
  vs SYSTEM_DESIGN), FlowGuard rules, or candidate intent detection.
  Files: BrainObjectivesRegistry.kt, NaturalPromptBuilder.kt (buildPhaseRules),
  TheConductor.kt (shouldRespond), BrainFlowGuard.kt, ConversationEngine.kt
---

# Interview Flow Design

## The 8 Interview Phases

### CODING/DSA Flow
```
INTRO → CLARIFICATION → APPROACH → CODING → REVIEW → FOLLOWUP → WRAP_UP
```

| Phase | AI Behavior | Exit Signal |
|-------|------------|-------------|
| INTRO | Warm greeting, 1-2 exchanges. No problem yet. | Candidate responds to greeting |
| CLARIFICATION | Answer questions honestly. Prompt once if no questions. Never hint at solution. | Candidate stops asking / moves to approach |
| APPROACH | Listen. Let them finish. Ask ONE of: complexity? trade-offs? alternatives? When solid: "Go ahead and code it." | approach_justified goal complete |
| CODING | **COMPLETE SILENCE.** Only "Mm." or "Got it." if thinking aloud. Never ask about code. | Done signal: "done"/"finished"/"submitted" |
| REVIEW | "Walk me through your solution." Then ONE specific question about THEIR code. | Code reviewed, complexity discussed |
| FOLLOWUP | "Is there a more optimal approach?" "Can we reduce space complexity?" | Optional depth complete |
| WRAP_UP | Brief positive close. ALWAYS: "Do you have any questions for me?" | interview_closed goal |

### BEHAVIORAL Flow
```
OPENING → STORY_1 → STORY_2 → STORY_3 → FINAL_STORY → WRAP_UP
```

**BEHAVIORAL IS COMPLETELY DIFFERENT:**
- No code editor, no hints, no run/submit buttons
- Full-width conversation layout
- NEVER say "take a moment to read through it"
- Questions asked conversationally, not as problem statements
- STAR method: probe for Situation/Task/Action/Result
- Probe ownership: if "we" > 3x without "I" → "What was YOUR contribution?"

| Phase | AI Behavior |
|-------|------------|
| OPENING | More warm-up (2-3 exchanges). Build psychological safety. |
| STORY_1-3 | Collect STAR stories. If vague: "What specifically did YOU do?" If no result: "What was the outcome?" |
| FINAL_STORY | Third+ story if time allows |
| WRAP_UP | Same as CODING |

### SYSTEM_DESIGN Flow
```
INTRO → REQUIREMENTS → ARCHITECTURE → DESIGN → DEEP_DIVE → WRAP_UP
```

- Code editor visible (for notes/diagrams) but no run/submit
- Challenge + requirements prompt on turn 1
- Probe: "What happens when that fails?", "How does that scale to 10x?"

## Silence Intelligence (TheConductor.kt)

`shouldRespond()` returns one of three decisions:

```kotlin
enum class SilenceDecision { RESPOND, SILENT, WAIT_THEN_RESPOND }
```

### Decision Logic
```
BEHAVIORAL → always RESPOND (pure conversation)

Message ends with "?" → RESPOND (question)
Contains "help"/"hint"/"stuck" → RESPOND
Contains "done"/"finished" → RESPOND (done signal)
IDK signals ("don't know", "no idea") → RESPOND
FlowGuard action pending → RESPOND

CODING/APPROACH phase:
  isThinkingAloud (>50 chars, no ?, has "I'm using"/"I think") → WAIT_THEN_RESPOND
  Short message (<=5 words) during CODING → RESPOND
  Very short (<10 chars) during CODING → SILENT
  Long (>100 chars) without "?" → WAIT_THEN_RESPOND

Default → RESPOND
```

### WAIT_THEN_RESPOND Behavior
2-second delay, then send a short acknowledgment:
- CODING: random from ["Mm.", "Got it.", "Go on.", "Okay.", "Sure."]
- Other: random reassurance ("Take your time.", "No rush.", etc.)

## Candidate Intent Detection (TheAnalyst)

TheAnalyst classifies each candidate message:
```
READING_PROBLEM — short acknowledgment after problem presented
CLARIFYING — asking about constraints (contains "?", "what", "can I assume")
THINKING_ALOUD — explaining without asking (no "?", uses "I would", "I'm thinking")
PROPOSING_APPROACH — specific algorithm/DS mentioned ("using BFS/HashMap")
CODING_NARRATION — explaining while typing ("I'm adding", "now I")
DONE_CODING — signals completion ("done", "finished", "submitted")
EXPLAINING_CODE — walking through solution ("so here I", "this function")
STUCK — asking for help ("I'm stuck", "not sure how", "hint")
REQUESTING_HINT — explicit hint request
```

### Intent → Automatic Actions
- `DONE_CODING` → mark `solution_implemented` complete + queue "Walk me through your solution"
- `CLARIFYING` → mark `clarifying_questions_handled` complete
- `PROPOSING_APPROACH` → mark `approach_understood` complete
- `THINKING_ALOUD` / `CODING_NARRATION` → no action (don't interrupt)

## FlowGuard — 4 Safety Rules

**File**: `BrainFlowGuard.kt`

| Rule | Condition | Action |
|------|-----------|--------|
| 1 | problem_shared not complete by turn 4 | ADVANCE_GOAL: "Present problem NOW" (priority 1) |
| 2 | remainingMinutes <= 0 | END_INTERVIEW: "TIME IS UP" (priority 1) |
| 3 | 8+ failed attempts on same goal | ADVANCE_GOAL: "STALLED — ask direct question" (priority 2) |
| 4 | isBehindSchedule (< 4 min/goal) | ADVANCE_GOAL: "BEHIND SCHEDULE" (priority 2) |

## startInterview() Behavior

**File**: `ConversationEngine.kt`

1. Loads InterviewMemory from Redis
2. Sends greeting ONLY (no problem yet):
   - BEHAVIORAL: "Hey! Great to meet you. Tell me about what you've been working on."
   - Other: "Hey! Great to meet you. Whenever you're ready, we can jump in."
3. Sends CODING_CHALLENGE state change (shows editor for CODING/DSA)
4. Initializes brain with goals from BrainObjectivesRegistry
5. `problem_shared` marked by TheAnalyst AFTER TheConductor presents it on turn 1

## Code Gate (TheConductor.kt)

During CODING phase for CODING/DSA, if no meaningful code exists:
```kotlin
private fun isMeaningfulCode(code: String?): Boolean {
    // >= 3 non-comment lines, has executable patterns (=, if, for, return, etc.)
    // >70% comments = pseudo code → false
}
```
If false, returns canned response without calling LLM:
- "Go ahead — I'll wait while you code."
- "I don't see code yet — go ahead and implement when ready."

## startInterview() — First Message

ConversationEngine.startInterview() sends GREETING ONLY.
TheConductor presents the problem on turn 1 by detecting
that 'problem_shared' is not in brain.interviewGoals.completed.

CODING turn 1: TheConductor presents problem statement.
BEHAVIORAL turn 1: TheConductor asks behavioral question conversationally.
SYSTEM_DESIGN turn 1: TheConductor presents design challenge.

Do NOT add problem presentation to startInterview().
It belongs in NaturalPromptBuilder OPENING_INSTRUCTION section.

## Type-Specific Frontend Layout

```typescript
// InterviewPage.tsx
const isBehavioral = session?.category === 'BEHAVIORAL'
const isCoding = session?.category === 'CODING' || session?.category === 'DSA'

// BEHAVIORAL: w-full max-w-3xl mx-auto (full width, no editor)
// CODING with editor: w-1/2 lg:w-2/5 border-r (split panel)
// showHints: only for CODING/DSA
// showRunSubmit: only for isCoding
```
