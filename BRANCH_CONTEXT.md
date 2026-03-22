# Branch Context

## Current Feature Branch
**Branch:** `feature/natural-interviewer`
**Base Branch:** `master`
**Purpose:** Transform AI interviewer from rule-based flow engine to natural cognitive system.
**Created:** 2026-03-22

## IMPORTANT FOR ALL DEVELOPERS AND AI TOOLS

ALL work for the natural interviewer transformation MUST happen on: `feature/natural-interviewer`

NEVER commit natural interviewer changes to master.
NEVER merge this branch until ALL Phase 1 tasks are complete and tested.

## Branch Rules

DO:
- `git checkout feature/natural-interviewer` before any work
- Work on all NATURAL_INTERVIEWER_TASKS.md tasks here
- Commit frequently with clear messages (feat(TASK-NNN): description)
- Push to remote regularly
- Keep NATURAL_INTERVIEWER_TASKS.md updated

DO NOT:
- Commit natural interviewer code to master
- Delete this branch until feature is complete
- Merge to master until Phase 1+2 fully tested

## Merge Strategy

- PHASE 1 complete -> test on branch -> keep on branch
- PHASE 2 complete -> test on branch -> keep on branch
- PHASE 3 complete -> test on branch -> CANDIDATE FOR MERGE

Pre-merge checklist:
- [ ] All Phase 1 tasks: COMPLETED
- [ ] All Phase 2 tasks: COMPLETED
- [ ] Backend compiles clean
- [ ] Frontend builds clean
- [ ] Feature flag: interview.use-new-brain tested both true/false
- [ ] At least 3 full interviews tested on branch
- [ ] Evaluation reports generating correctly
- [ ] No regression in existing functionality
- [ ] SPEC_DEV.md updated (TASK-048)

## Useful Git Commands

```bash
# Check you're on the right branch
git branch --show-current
# Should output: feature/natural-interviewer

# Push your work
git push origin feature/natural-interviewer

# See diff from master
git diff master..feature/natural-interviewer --stat

# Update branch with latest master
git fetch origin
git rebase origin/master
```
