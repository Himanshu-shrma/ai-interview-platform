# AI Interview Platform — Product Specification

**Version:** 2.0
**Last Updated:** 2026-03-22
**Status:** Active development — natural interviewer system live

---

## 1. What This Platform Does

An AI-powered mock interview platform where candidates take realistic technical interviews with an adaptive AI interviewer. The AI builds a mental model of each candidate across the interview — tracking hypotheses about their understanding, detecting contradictions between statements, calibrating question difficulty, and adapting its tone based on the candidate's emotional state.

Three interview types are supported: Coding/DSA (algorithm problems with a live code editor and test execution), Behavioral (STAR-method story extraction with ownership verification), and System Design (architectural thinking with trade-off assessment).

After each interview, candidates receive a detailed evaluation report scored across 8 dimensions, with evidence-based next steps grounded in what the AI actually tested during the interview.

## 2. Who Uses It

**Candidates** — software engineers preparing for technical interviews. They configure an interview (type, difficulty, personality, target company), have a real-time conversation with the AI interviewer, write and test code, then receive a detailed report with actionable feedback. The platform is available 24/7 for self-paced practice.

**Recruiters (B2B — planned)** — hiring managers who want structured, consistent evaluation across all candidates. Same scoring rubric per question. Research-grounded bias reduction. Detailed candidate comparison.

## 3. Core Features

**Live AI Interview** — Real-time conversation via WebSocket with streaming AI responses. The AI adapts in real-time based on what the candidate says and does. Supports coding, behavioral, and system design formats.

**Code Editor** — Monaco Editor (VS Code's editor engine) with syntax highlighting for Python, Java, JavaScript, TypeScript, C++, Go, Rust, Ruby, and more. Custom stdin input. Code runs in a sandboxed Judge0 CE environment.

**Test Execution** — Code is tested against hidden test cases. Results show pass/fail per case with input, expected output, actual output, and runtime. The AI does not reveal which tests fail — it asks the candidate to trace through failing inputs.

**Adaptive Difficulty** — The AI targets a 70% success rate (optimal for assessment). If the candidate is getting everything right, it pushes harder. If struggling, it eases off and finds what they do know.

**8-Dimension Evaluation** — Problem Solving (20%), Algorithm Depth (15%), Code Quality (15%), Communication (15%), Efficiency (10%), Testing (10%), Initiative (10%), Learning Agility (5%).

**Study Plan** — Each report includes specific next steps derived from tested hypotheses about the candidate's knowledge gaps. Not generic advice — evidence-based from what was actually probed during the interview.

**Progress Tracking** — Dashboard with score trend chart, interview history, difficulty recommendation, and free tier usage.

**Anxiety Fairness** — Research shows interview anxiety suppresses performance 20-30%. The AI detects anxiety signals (excessive hedging, apologies, self-doubt) and adjusts scores upward to be fair.

**Multiple Personalities** — Choose from FAANG Senior (direct, challenging), Friendly Mentor (encouraging, supportive), Startup Engineer (pragmatic, ship-focused), or Adaptive (matches your energy).

## 4. Interview Types

### Coding / DSA
Tests: algorithm choice, code correctness, complexity analysis, edge case handling. AI pursues 9 goals in order: share problem, understand approach, justify approach, see code implemented, verify complexity, explore edge cases, assess reasoning depth, test mental simulation, close professionally. Optional: optimization discussion, harder follow-up variant. Duration: 30-60 minutes.

### Behavioral
Tests: STAR storytelling (Situation, Task, Action, Result), personal ownership, self-awareness. AI pursues 8 goals: establish psychological safety, collect 3 STAR stories (each with ownership verification), demonstrate learning/growth, close. When candidate says "we did X", the AI probes: "What was YOUR specific contribution?" Duration: 30-45 minutes.

### System Design
Tests: requirements gathering, architecture thinking, trade-offs, failure modes, scalability. AI pursues 8 goals: share design problem, gather requirements, get high-level architecture, deep-dive one component, acknowledge trade-offs, explore failure modes, address scalability, close. Duration: 45-60 minutes.

## 5. The AI Interviewer — How It Works

The AI interviewer uses a brain architecture with three agents working together:

**The Conductor** — the real-time conversational agent. It reads the brain state, decides whether to respond or stay silent (during coding phases, it stays quiet), builds a prompt from 13 sections of context, and streams the response. It applies an open-question transformer to prevent anchoring bias ("Is it O(n) or O(n^2)?" becomes "What's the complexity?").

**The Analyst** — runs in the background after every exchange. A single AI call that updates the entire brain: candidate profile (thinking style, anxiety, flow state), hypotheses about their knowledge, claims they've made, goals completed, running inner monologue, and the next action to take. It detects cognitive overload, dismissal language, self-correction, and contradiction between statements.

**The Strategist** — reviews the full brain state every 5 turns. Adjusts the interview strategy: tone, pace, difficulty, what to avoid. Provides honest self-critique of its own performance. Abandons stale hypotheses that haven't been testable.

### How It Builds a Mental Model
After turn 2, the AI has a preliminary model: overall signal (strong/solid/average/struggling), thinking style (top-down/bottom-up/intuitive/methodical), emotional state, anxiety level. By turn 5, it's tracking specific knowledge claims, forming hypotheses, and choosing which topics to probe next based on a knowledge adjacency map.

### How It Calibrates Difficulty
The AI tracks a "challenge success rate" — how often the candidate answers its probing questions correctly. Target: 70%. Above 85%: questions get harder. Below 50%: questions get easier. It also tracks Zone of Proximal Development per topic — what the candidate can do alone vs. with prompting.

### Why It Sometimes Stays Silent
During coding phases, the AI stays silent unless the candidate asks a question, says they're done, or asks for help. Research shows that being watched while coding degrades performance by up to 50%. After 90 seconds of coding silence, it sends a brief reassurance ("Take your time.").

## 6. The Evaluation Report

### 8 Dimensions
| Dimension | Weight | What It Measures |
|-----------|--------|-----------------|
| Problem Solving | 20% | How they break down and approach problems |
| Algorithm Depth | 15% | Understanding WHY their approach works (not just that it does) |
| Code Quality | 15% | Readability, naming, structure, abstraction level |
| Communication | 15% | Explaining thinking process clearly throughout |
| Efficiency | 10% | Time and space complexity awareness |
| Testing | 10% | Edge case identification, verification behavior |
| Initiative | 10% | Going beyond the minimum (proactive edge cases, voluntary optimizations) |
| Learning Agility | 5% | How effectively they learned during the interview (hint usage, self-correction) |

### Score Adjustments
- **Anxiety fairness**: +0.75 for high anxiety, +0.5 for moderate
- **Productive struggle**: +0.5 when candidate struggled but arrived at correct answer
- **Schema-driven reasoning**: +1.0 to algorithm score for immediately recognizing the pattern
- **High abstraction**: +1.0 to code quality for algorithm-level narration (vs. syntax-level)

### Next Steps
Each recommendation includes: area, specific gap (from a tested hypothesis), evidence from the interview, a concrete 1-2 hour practice task, and a free resource (LeetCode problem number, NeetCode video, specific book chapter).

## 7. User Journey

1. Sign up via Clerk (email/Google/GitHub)
2. Land on Dashboard — see interview history, stats, score trend
3. Click "Start New Interview" → InterviewSetupPage
4. Configure: category, difficulty, personality, language, target company, experience level
5. Click "Start Interview" → session created, redirected to InterviewPage
6. WebSocket connects. AI sends greeting.
7. Conversation begins. Candidate types responses. AI streams back.
8. For coding: code editor appears. Write code, run it, submit for testing.
9. AI probes edge cases, complexity, trade-offs.
10. Timer expires or candidate clicks "End Interview"
11. "Generating your report..." overlay (10-15 seconds)
12. Redirect to ReportPage — full evaluation with scores, radar chart, strengths, weaknesses, study plan

## 8. Platform Capabilities

- **Code Editor**: Monaco (VS Code engine), 12+ languages, syntax highlighting, auto-complete
- **Code Execution**: Judge0 CE sandbox, test cases, stdout/stderr, runtime measurement
- **Real-time Streaming**: WebSocket with AI_CHUNK frames, 10-second timeout with fallback
- **Score Charts**: Recharts radar chart for dimension scores, line chart for score trends
- **Interview History**: Paginated list with category/difficulty badges, score display, resume/view actions
- **Integrity Monitoring**: Tab switch detection, paste detection (for proctoring)

## 9. What Makes This Different

| Feature | This Platform | HackerRank | interviewing.io |
|---------|--------------|------------|-----------------|
| AI builds mental model | Yes — tracks hypotheses, claims, contradictions | No | No (human interviewer) |
| Anxiety fairness adjustment | Yes — research-grounded score correction | No | No |
| Anti-halo scoring | Yes — per-exchange independent scores | No | No |
| Learning agility measured | Yes — how well they learn during interview | No | No |
| Contradiction detection | Yes — surfaces inconsistencies between statements | No | No |
| 8 evaluation dimensions | Yes | Typically 2-3 | Subjective rating |
| Available 24/7 | Yes | Yes | Limited by human availability |
| Research-grounded | 10 academic domains (25+ papers) | No | No |

## 10. Subscription and Usage Limits

**Free Tier**: 3 interviews per calendar month. All features included. Reports fully detailed.

**Pro Tier** (planned): Unlimited interviews. Priority AI response time. Company-specific preparation packs.

When the free limit is reached: POST to start session returns 429. Frontend shows upgrade message.

## 11. Technical Requirements

- Modern web browser (Chrome, Firefox, Safari, Edge — latest 2 versions)
- Stable internet connection (WebSocket requires persistent connection)
- Screen width 768px+ recommended (code editor requires horizontal space)

## 12. Roadmap Items

- Voice interviews (OpenAI Whisper STT + TTS)
- B2B recruiter dashboard with candidate comparison
- Question bank management UI
- Circuit breakers for OpenAI API reliability
- Mobile-responsive interview experience
- Interview recording/playback
