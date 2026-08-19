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
# sweep that decides to do nothing should still say why.

set -euo pipefail

: "${GITHUB_OUTPUT:?must be run from a workflow step}"

# A pull request that is open and not merely waiting for its checks. CLEAN and UNSTABLE are both
# fine — those are green, or still running. BLOCKED means something is holding it: an unresolved
# review thread, or a required check that failed. DIRTY means it conflicts.
stuck_prs=$(gh pr list --state open --json number,mergeStateStatus \
  --jq '[.[] | select(.mergeStateStatus == "BLOCKED" or .mergeStateStatus == "DIRTY")] | length')

# An open issue nobody has answered. `claude` is the login the agent comments as.
silent_issues=$(gh issue list --state open --json number,comments \
  --jq '[.[] | select([.comments[].author.login] | index("claude") | not)] | length')

echo "::notice::$stuck_prs stuck pull requests, $silent_issues unanswered issues."

if [ "$stuck_prs" -gt 0 ] || [ "$silent_issues" -gt 0 ]; then
  echo "work=true" >> "$GITHUB_OUTPUT"
else
  echo "work=false" >> "$GITHUB_OUTPUT"
  echo "::notice::Nothing to tend."
fi
