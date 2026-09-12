---
name: next-issue
description: Rank this repo's open GitHub issues and recommend the next one to work on, with the reasoning written out. Use when the user asks what to work on next, which issue has the most value, or to triage or re-rank the backlog.
---

# Next issue

Pick the open issue with the most value **now**, and say why it beats the runners-up. The
deliverable is a ranked recommendation, not started work: do not branch, edit, or comment on an
issue unless the user says to.

Issue-tracker mechanics are `docs/agents/issue-tracker.md`. Phases and exit criteria are `PRD.md`
Part 4. Neither is quoted here — read the slice you need (CLAUDE.md, *Bounded reading*).

## Step 1 — gather, bounded

```bash
gh issue list --state open --limit 100 --json number,title,body,labels \
  --jq '.[] | "===== #\(.number) [\([.labels[].name]|join(","))] \(.title)\n\(.body)"' > "$TMPDIR/open-issues.txt"
gh issue list --state closed --limit 25 --json number,title \
  --template '{{range .}}#{{.number}} {{.title}}{{"\n"}}{{end}}'
```

The closed list matters as much as the open one: it says which phase actually shipped, and which
"depends on #N" lines are already satisfied.

Then drop from contention anything that is claimed or gated:

```bash
for n in <candidates>; do
  gh api repos/{owner}/{repo}/issues/$n \
    --jq '"#\(.number) blocked_by=\(.issue_dependencies_summary.blocked_by // 0) assignees=\([.assignees[].login]|join(","))"'
done
```

`blocked_by > 0` or an assignee removes an issue from the ranking; say so rather than silently
omitting it. A body line like "Depends on #66 landing" is a blocker too even with no dependency edge
recorded — check whether the named issue is closed.

## Step 2 — rank

Read the candidates' bodies from the file, not by re-fetching. Score each on:

1. **Does it unblock or protect other work?** A broken or untrusted `check`, a red `main`, a harness
   three issues are waiting on, a seam later phases plug into. This dominates everything below it:
   this project's stated order is measurement before tuning, so a defect in the measuring apparatus
   outranks a feature that would be measured by it.
2. **Is it the current phase's exit criterion?** `PRD.md` Part 4 names one per phase. Work that is
   not on the path to it needs a reason.
3. **Is it decided?** An issue whose body still asks the user to choose between two directions
   (#86's shape) is not ready to be handed to an agent — surface the decision as the recommendation
   instead of the implementation.
4. **Cost against the above.** A `ready-for-agent` spec-sized issue and a one-file fix are not
   comparable on value alone; state the size.

Ignore label prestige. `ready-for-agent` means someone wrote the spec, not that it is next.

## Step 3 — report

Give the user, in the terminal:

- **The recommendation**: one issue, one paragraph on why it wins now, and its size.
- **Runners-up**: two or three, each one line, with what would make it the pick instead.
- **Excluded**: anything dropped for a blocker, an assignee, or an undecided body — with which.

Then stop and ask whether to start it. If they say yes, the work goes on its own branch off the
default branch, named `issue-<number>-<short-slug>` — the rule in the user's global CLAUDE.md,
which overrides any skill that says to commit to the current branch.

## Do not

- Spawn a subagent for this. It is one `gh` call and some reading; a cold agent re-derives the
  project context and returns a worse answer. (Exploratory *code* search is still `Explore`'s.)
- Read `PLAN.md`. CLAUDE.md bars it, and the backlog is GitHub's.
- Re-rank from memory on a later turn. Issues change; re-run step 1.
