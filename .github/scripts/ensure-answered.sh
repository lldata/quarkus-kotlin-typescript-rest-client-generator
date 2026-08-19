#!/usr/bin/env bash
#
# Makes sure a run said something. Called after the agent step, whatever the outcome.
#
# A run that ends green having produced nothing looks exactly like a run that never happened,
# and exactly like an agent that has quietly stopped working. Nobody reading the issue can tell
# those apart, and an agent nobody can tell is working is not one anybody will leave alone. This
# is the net under that.
#
# Lives here rather than in the workflow so the agent can improve it; see survey.sh for why that
# distinction exists at all.
#
# Usage: ensure-answered.sh <issue-number> <run-url> <outcome>
#   outcome: the agent step's result — `success` or anything else.

set -euo pipefail

issue="${1:?issue number required}"
run_url="${2:?run url required}"
outcome="${3:?outcome required}"

: "${GITHUB_REPOSITORY:?}"
: "${GITHUB_RUN_ID:?}"

# Only what this run produced counts. An older comment from a previous attempt is not this run
# answering the issue, and treating it as one is how a silent regression stays hidden.
started=$(gh run view "$GITHUB_RUN_ID" --repo "$GITHUB_REPOSITORY" --json createdAt --jq .createdAt)

said=$(gh issue view "$issue" --repo "$GITHUB_REPOSITORY" --json comments \
  --jq "[.comments[] | select(.author.login == \"claude\" and .createdAt > \"$started\")] | length")

built=$(gh api "/repos/$GITHUB_REPOSITORY/branches" --paginate \
  --jq "[.[] | select(.name | startswith(\"agent/issue-$issue-\"))] | length")

if [ "$said" -gt 0 ] || [ "$built" -gt 0 ]; then
  echo "::notice::Run left $said comment(s) and $built branch(es)."
  exit 0
fi

if [ "$outcome" = "success" ]; then
  echo "::warning::The run finished without leaving a comment or a branch."
  body="The agent's run on this issue finished without reaching a conclusion — no comment, no branch, no pull request. See [the run log]($run_url).

This note is from the workflow rather than from the agent, so it cannot say why. It exists because a silent success and a broken agent look identical from the outside, and they should not.

The issue is still open and unanswered. Retry with: gh workflow run agent-issue.yml -f issue=$issue"
else
  echo "::warning::The run failed without leaving anything behind."
  body="The agent's run on this issue failed and left nothing behind — no branch, no pull request. See [the run log]($run_url).

This note is from the workflow rather than from the agent, so it says only that the attempt did not finish. It is not a judgment about the issue itself, which is still open and unanswered.

Retry with: gh workflow run agent-issue.yml -f issue=$issue"
fi

gh issue comment "$issue" --repo "$GITHUB_REPOSITORY" --body "$body"
