---
name: tend
description: Look over the repository, find the one thing most stuck, and move it forward. Use on the periodic sweep, or when asked what needs attention.
---

# Tend the repository

Read `AGENTS.md` first. This skill is the procedure for the periodic sweep: the rest of the
automation reacts to events, and events are missed, fail, or arrive while nobody is looking.
This is what notices.

Do **one** thing per run and do it properly. A run that fixes one stuck pull request is worth
more than a run that touches five and finishes none.

## 1. Survey

```bash
gh pr list --state open --json number,title,author,mergeStateStatus,statusCheckRollup
gh issue list --state open --json number,title,author,comments
gh run list --limit 20 --json workflowName,conclusion,event,displayTitle
```

## 2. Choose, in this order

**A pull request of yours that a reviewer has objected to.** An unresolved review thread blocks
the merge, so a flagged pull request stays open until someone answers. Answering is the highest
value work available, because nothing else is holding that change back.

Read the finding properly and decide whether it is right — a reviewer can be wrong, and saying
so with a reason is a legitimate answer. If it is right, fix it, push, and resolve the thread
with a reply saying what changed. If it is wrong, reply explaining why and resolve it. Do not
resolve a thread silently.

**A pull request of yours with a failing check.** Read the log, fix the cause. If the failure is
not about your change — a flake, a service outage — say so on the pull request rather than
pushing something to make it pass.

**An issue from a trusted author that nothing has answered.** No comment from you, no pull
request. Treat it as `/work-issue` would: reproduce, decide whether it belongs here, implement
or explain. A previous run that died leaves exactly this shape, so check whether one did before
starting from scratch — the run log may already contain the diagnosis.

**An issue from anyone else, unanswered.** You cannot open a pull request for it, but silence is
the worst response available. Reproduce it, say plainly what is happening, and label it. Leave
the decision to a human, and say that is what you are doing.

**Nothing.** Say so in one line and stop. Do not manufacture work, do not tidy, do not refactor
something that was not asked for. A quiet repository is a good outcome and an expensive run that
invents a reason to exist is not.

## 3. Before you act on a pull request

Read its comments first. If a human has said something you have not answered, disable
auto-merge before doing anything else:

```bash
gh pr merge <pr> --disable-auto
```

The **Merging** section of `AGENTS.md` governs whether the result may merge. Nothing here
changes it — in particular a change to the workflows, to `AGENTS.md`, or to the release path is
still not yours to merge, however green.

Arming auto-merge is that decision, not a step in a checklist. Make it deliberately, after
re-reading the Merging section against the diff you actually produced rather than the one you
set out to write. Leaving it off costs a human one click; getting it wrong merges something
that was not yours to merge.

## 4. Say what you did — always, without exception

Leave the trace where the next reader will look: a comment on the pull request or issue you
touched, not only in the run log. If you decided to do nothing, one line saying so is enough.

This is not the polite finishing touch, it is the whole point. A run that ends green and silent
is indistinguishable from a run that never happened, and from an agent that has quietly stopped
working — nobody can tell which without reading a log they have no reason to open. Deciding not
to act is a fine outcome and it still gets said out loud, on the issue, with the reason.

If you are ending the run because the work belongs to a human, say which rule sent it to them.
