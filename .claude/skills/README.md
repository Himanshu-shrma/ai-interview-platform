# AI Interview Platform — Agent Skills

8 specialized agent skills for Claude Code to work on this codebase
as a domain expert.

## Skills Overview

| Skill | Trigger When | Most Critical Rules |
|-------|-------------|---------------------|
| brain-architecture | TheConductor/Analyst/Strategist/BrainService work | NEVER mutate brain directly, NEVER silent catch |
| kotlin-webflux-patterns | Any Kotlin backend work | NEVER .block(), ALWAYS awaitSingle() |
| llm-prompt-engineering | Any LLM prompt or schema change | INTERNAL marker required, XML tag injection protection |
| interview-flow-design | Phase/silence/goal/type behavior | BEHAVIORAL has no editor/hints/run/submit |
| database-migration | Schema changes, new tables/columns | IF NOT EXISTS, JSONB as TEXT, no SERIAL |
| frontend-interview-ui | InterviewPage/ReportPage/WS work | Type-based layout, AI_CHUNK accumulation |
| code-execution-judge0 | Code submission/Judge0/test cases | outputMatches() handles set vs list |
| evaluation-scoring | EvaluationAgent/ReportService/scores | Formula weights must sum exactly to 1.0 |

## How Claude Code Uses These

- "Fix the brain phase tracking" → brain-architecture + interview-flow-design
- "Add a new DB column" → database-migration
- "Change what the AI says during coding" → interview-flow-design + llm-prompt-engineering
- "Fix the radar chart" → frontend-interview-ui + evaluation-scoring
- "Add a new language to code execution" → code-execution-judge0
- "Why is the overall score wrong?" → evaluation-scoring
- "AI is asking to explain code during CODING phase" → interview-flow-design
