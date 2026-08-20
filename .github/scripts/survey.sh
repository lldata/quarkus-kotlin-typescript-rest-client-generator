#!/usr/bin/env bash
#
# Decides whether the sweep has anything to do, without starting a model.
#
# This lives here rather than inline in the workflow on purpose. GitHub refuses a commit from a
# GitHub App that touches `.github/workflows/` without the `workflows` permission, so anything
# written into the workflow file is something the agent can never change about itself. Behaviour
# belongs in scripts it can edit; structure, permissions and triggers stay in YAML, where a
# change should need a human anyway.
#
# Writes `work=true|false` to $GITHUB_OUTPUT. Emits annotations saying what it found, because a
# sweep that decides to do nothing should still say why — and names what it found, because a
# count alone cannot be checked against the repository by whoever reads the log.

set -euo pipefail

: "${GITHUB_OUTPUT:?must be run from a workflow step}"

# A pull request that is open and not merely waiting for its checks. CLEAN and UNSTABLE are both
# fine — those are green, or still running. BLOCKED means something is holding it: an unresolved
# review thread, or a required check that failed. DIRTY means it conflicts.
stuck_prs=$(gh pr list --state open --json number,mergeStateStatus \
  --jq '[.[] | select(.mergeStateStatus == "BLOCKED" or .mergeStateStatus == "DIRTY") | .number] | join(", ")')

# An open issue nobody has answered. `claude` is the login the agent comments as. Oldest first:
# a burst of issues filed together is worked in the order it was filed, which is what someone
# filing them expects and what the concurrency group only appeared to provide.
silent_issues=$(gh issue list --state open --json number,comments \
  --jq '[.[] | select([.comments[].author.login] | index("claude") | not) | .number] | sort | join(", ")')

# An issue already being worked has no comment on it yet either, so it looks exactly like one
# nobody has picked up. Taking it would mean two runs on one issue: two branches, two pull
# requests, and a second run reproducing a diagnosis the first has already made. While any issue
# run is in flight the sweep leaves issues alone; pull requests are still fair game, since those
# are the half nothing else is watching.
working=$(gh run list --workflow=agent-issue.yml --status in_progress --json databaseId --jq 'length')

if [ "$working" -gt 0 ] && [ -n "$silent_issues" ]; then
  echo "::notice::Issue run in flight; leaving issues $silent_issues alone this sweep."
  silent_issues=""
fi

echo "::notice::Stuck pull requests: ${stuck_prs:-none}. Unanswered issues: ${silent_issues:-none}."

if [ -n "$stuck_prs" ] || [ -n "$silent_issues" ]; then
  echo "work=true" >> "$GITHUB_OUTPUT"
else
  echo "work=false" >> "$GITHUB_OUTPUT"
  echo "::notice::Nothing to tend."
fi
