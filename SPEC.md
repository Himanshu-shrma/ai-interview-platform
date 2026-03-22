# AI Interview Platform — Product Specification

## What It Does

An AI-powered technical interview platform that conducts realistic coding, behavioral, and system design interviews. The AI interviewer adapts in real-time to each candidate, building a mental model of how they think, what they know, and where their gaps are.

## Interview Types

- **Coding / DSA**: Algorithm problems with live code editor and test execution via Judge0
- **Behavioral**: STAR-method story extraction with ownership verification
- **System Design**: Architectural thinking with trade-off and scalability assessment

## What Makes It Different

### Natural Conversation (not a chatbot)

The AI interviewer builds a mental model of each candidate across the interview. It tracks hypotheses about their understanding, detects contradictions between earlier and later statements, and probes systematically based on what it has learned.

It decides when to push harder and when to ease off. It knows when to stay silent during coding and when to intervene. It adapts its tone to the candidate's emotional state.

Feels like: talking to an experienced senior engineer.
Not like: filling out a form or talking to a script.

### Scientifically Valid Assessment

Grounded in 10 academic research domains:

- **Anti-halo scoring**: Each exchange scored independently to prevent early impressions from biasing later scores
- **Anxiety fairness adjustment**: Research shows anxiety suppresses performance 20-30%. Detected and adjusted
- **Productive struggle recognition**: Correct answer after struggle is a stronger signal than easy correctness
- **Learning agility measurement**: How well did they learn DURING the interview, not just what they knew before
- **Psychological safety monitoring**: Low safety suppresses performance. Detected and addressed

### 8-Dimension Evaluation

| Dimension | What It Measures |
|-----------|-----------------|
| Problem Solving | How they approach and break down problems |
| Algorithm Depth | Understanding WHY their approach works |
| Code Quality | Readability, naming, structure |
| Communication | Explaining thinking process throughout |
| Efficiency | Time and space complexity awareness |
| Testing | Edge case identification and verification |
| Initiative | Going beyond the minimum asked |
| Learning Agility | How effectively they learn during the interview |

### Research-Grounded Reports

Every report includes:
- Evidence-based next steps (from tested hypotheses, not generic advice)
- Anxiety adjustment note (if applicable)
- Cognitive depth reached (Bloom's taxonomy levels per topic)
- Learning agility score (hint generalization, self-correction)
- Reasoning pattern (schema-driven expert vs exploratory search)

## For Candidates

- Real-time AI interviewer that adapts to your level
- Fair assessment regardless of interview anxiety
- Actionable feedback report after each interview
- Live code editor with test execution
- Practice at your own pace, any time

## Technical Highlights

- Kotlin + Spring WebFlux backend (reactive, coroutines)
- React + TypeScript frontend with Monaco editor
- Judge0 CE for sandboxed code execution
- PostgreSQL + Redis for persistence
- Clerk.dev authentication
- OpenAI GPT-4o (interviewer) + GPT-4o-mini (background analysis)
- WebSocket for real-time streaming
- Feature flag system for gradual AI capability rollout
