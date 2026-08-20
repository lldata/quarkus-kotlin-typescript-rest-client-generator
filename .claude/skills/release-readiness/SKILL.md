---
name: release-readiness
description: Assess whether a release is due, reporting to the run summary and opening an issue only when one actually is. Use on the weekly schedule or when asked whether to cut a release.
---

# Release readiness

Read `AGENTS.md` for the version policy before you form an opinion. The short version: a human
tags, you advise. 1.0.0 has not shipped, so until it does nothing here proposes anything but the
next alpha — and nothing at all while the JitPack dependency below stands. Unreleased work is not
stranded meanwhile: any commit is consumable through JitPack, which is often the better answer to
"when is this released?" anyway.

## Gather

- `git describe --tags --abbrev=0` for the latest tag, then `git log <tag>..main --oneline` for
  what has landed since.
- The `## [Unreleased]` section of `CHANGELOG.md`.
- The `version=` line in `gradle.properties`, which the publish workflow checks against the tag.
- `gh issue list --state open` and `gh pr list --state open`.

## Judge

Answer four questions, in this order:

**Can anything be released at all?** `:generator` depends on
`com.github.lldata:typescriptpoet:<commit>`, a JitPack coordinate, and Central will not accept one
as a dependency. While that line stands, no release of any kind can be published and the answer is
hold whatever else has landed — say so first rather than burying it under a changelog audit. Check
it in `generator/build.gradle.kts` rather than assuming it is still true; it goes back to
`dk.lldata:typescriptpoet` when that fork's 2.0.0 lands.

**Is the changelog honest?** Every user-visible commit since the tag should have an entry. List
any that do not, by commit. This is the most common reason a release is not ready, and it is the
one thing here that is a fact rather than a judgment.

**Is there anything half-finished?** A generator change the example does not exercise, a lint
rule with no test, a README section describing behaviour that no longer matches the emitted
output, a `TODO` left in a shipped path. Name it.

**What would the release be?** Per the version policy: while 1.0.0 is in alpha, what has landed
earns the next alpha in sequence. 1.0.0 itself is a human's decision and never yours to propose.
If what has landed feels larger than an alpha bump, say that in as many words and leave the
decision alone.

## Report

**When the answer is hold, do not open an issue.** Write the report to the workflow run summary
and stop:

```bash
cat >> "$GITHUB_STEP_SUMMARY" <<'REPORT'
… the report …
REPORT
```

A standing issue that says "hold" every week is noise, and noise is what makes a real signal
easy to miss. Hold is the answer most weeks, so most weeks leave nothing behind but a run
summary. That is the intended outcome, not a run that failed to find something to say.

**When a release is genuinely due**, open an issue titled **Release readiness** — that is the
signal, and it should be rare enough to be worth a notification.

Before opening one, check whether it already exists, matching the title exactly. A text search
also matches issues that merely contain the words, so do not trust search relevance to rank the
real one first:

```bash
gh issue list --search "Release readiness in:title" --state open --json number,title \
  --jq '.[] | select(.title == "Release readiness") | .number'
```

If one is open, edit its body rather than opening a second, and comment only when the
recommendation itself changes.

The body holds: the latest tag and commit count since, what has landed, what is missing, and the
exact steps a human would take. Releasing here is two steps, not one — the publish workflow
refuses a tag that does not match the committed version, so the bump lands on `main` first. Use
the actual next tag in sequence rather than copying the example; check
`git tag --list 'v1.0.0-alpha*' | sort -V | tail -1` for what comes after it:

```bash
# 1. bump `version=` in gradle.properties on main, and commit it
# 2. then tag that commit
git tag v1.0.0-alphaN && git push origin v1.0.0-alphaN
```

Say plainly what the tag does and does not do: it uploads a signed bundle to the Central Portal
and drafts a GitHub release. It does not release to Maven Central — a human presses Publish at
https://central.sonatype.com/publishing, because a released version is permanent.

If nothing has landed since the last tag, say so in one line in the run summary and stop. Do not
manufacture work.
