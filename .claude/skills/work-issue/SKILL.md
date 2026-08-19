---
name: work-issue
description: Implement a trusted author's issue as a pull request, against the project's full build gate. Use when an issue needs to become a change.
---

# Work an issue into a pull request

You are the maintainer of this generator. Read `AGENTS.md` first — it holds the scope rules,
conventions, and the bearing to answer in. This skill is only the procedure.

The issue number is in the prompt. Everything below assumes the author is trusted; the workflow
already checked that, and it is not your job to re-litigate it.

## 0. Which kind of issue is this

Two kinds arrive, and they are worked differently. Decide which before anything else, because
following the wrong procedure is how a run ends up producing nothing at all.

**A library issue** — the emitted client is wrong, a resource shape is read wrongly or not at
all, or the contract lint misjudges something. Steps 1 to 5 are written for these.

**An infrastructure issue** — the workflows, the skills in `.claude/skills/`, `AGENTS.md`, the
build configuration, the documentation. The automation that maintains this repository is part of
the repository, and improving it is ordinary work, not a special case.

A trusted author asking for a change to the workflows or to `AGENTS.md` **is** authorisation to
implement it and open a pull request. The restriction in the Merging section is on *merging*
such a change, not on making it: you open it, you say plainly that it is not yours to merge and
why, and you leave auto-merge off. Refusing to do the work is not what that rule asks for, and
doing it silently is worse than either.

For an infrastructure issue, skip the failing test in step 1 — there is often nothing to write
one against — and instead say in the pull request exactly how you verified the change: the
workflow parses, the shell was replayed against real data, the skill was read back against the
case that motivated it. Steps 2, 5, 6 and 7 apply unchanged.

## 1. Understand before touching anything

Read the issue in full with `gh issue view <n> --comments`. Then find the code it is about and
read that too. State the problem back in one or two sentences before you start — if you cannot,
you do not understand it yet.

For a library issue, reproduce it as a failing test in the existing style, in the test file
where it belongs. A bug that cannot be expressed as a test is a bug you have not located.

The generator reads compiled classes, so a reproduction usually means a fixture resource in
`generator/src/test` shaped like the reporter's, and an assertion on the TypeScript that comes
out of it.

## 2. Decide whether it should be done at all

A trusted author asking for something is authorisation to work on it, not proof it fits. If the
request would take the library out of scope, break the compatibility floors, or duplicate an
existing path, say so on the issue and stop. Explain what you would build instead. That comment
is a complete and successful outcome for this run — do not open a PR to prove a point.

If it needs a breaking change, say so on the issue and stop. Breaking changes need a human to
agree before the work, not after.

## 3. Implement

- Make the failing test pass, and nothing more. Resist adjacent tidying; it makes the PR harder
  to review and hides the actual change.
- A new construct the generator can read goes into the example's resource layer as well as the
  generator's own tests, and then the committed client is regenerated. The example is how a
  reader sees the feature; a feature only visible in a unit test is half-delivered.
- Add the `CHANGELOG.md` entry under `## [Unreleased]`, in prose, linking the issue. Match the
  entries already there; they explain what changed and why a user would care.

## 4. Prove it

```bash
./gradlew build ktlintCheck
```

Then the other half of the gate, which Gradle does not run:

```bash
cd example/web && npm ci && npm run typecheck
```

Both green, on the runner. The Gradle half includes the drift test, which fails when the
committed client no longer matches what the generator would emit — if it fails, regenerate
rather than editing the checked-in client by hand. The npm half is the only thing that proves
the emitted TypeScript actually compiles.

Never open the PR on a red or skipped build. If you cannot get it green, push nothing and
comment on the issue with what you found and where you got stuck. A truthful dead end is worth
more than a PR that shifts the debugging onto the reviewer.

## 5. Open the pull request

Branch as `agent/issue-<n>-<short-slug>`. Commit with a message that explains why, in the style
of the existing log — `git log` is the reference, and those subjects are sentences, not
conventional-commit prefixes.

The PR description covers, briefly:

- What the issue was, and what was actually wrong. If your diagnosis differs from the report,
  say so.
- What changed, and anything you deliberately did not change.
- Any change to the emitted output, shown as the diff to the example's committed client. That
  diff is the statement of what you did to consumers.
- What you verified: name both halves of the gate, and say plainly if either skipped.

Then `Closes #<n>`, and say in a short issue comment that the PR is up.

## 6. Hand it to the checks, not to yourself

Read the **Merging** section of `AGENTS.md` and decide honestly whether this change qualifies.
If it does, enable auto-merge so the merge waits on the build rather than on you:

```bash
gh pr merge <pr> --squash --auto
```

The checks take minutes and this run ends before they finish, so auto-merge is the mechanism —
never poll for green and merge by hand, and never merge without the build having passed.

Arming it early has one consequence worth knowing: a plain comment left by a human after this
point does not disarm it, and the merge still fires when the checks pass. An unresolved review
thread does block it, since `main` requires conversation resolution. So whenever you come back
to a pull request for any reason, read its comments first and disarm it if someone has spoken
and you have not answered:

```bash
gh pr merge <pr> --disable-auto
```

If it does not qualify — a breaking change, a rules or release-path file, something you fixed
without understanding why it worked — leave auto-merge off and say plainly in the PR which
condition it failed. That sentence is the most useful thing in the pull request.

Do not tag, do not publish, do not approve your own pull request.

## 7. Never end silently

Whatever happened, the issue gets a comment before this run ends: the pull request is up, or the
request does not fit and here is why, or you got stuck and here is where. One or two sentences is
plenty.

A run that ends green having said nothing looks exactly like an agent that has stopped working,
and the person reading the issue has no way to tell the difference. Deciding not to act is a
legitimate outcome; ending without saying so is not.
