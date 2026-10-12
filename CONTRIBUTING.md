# Contributing to Questa

Thanks for looking. This is a small, solo-maintained, **vibe-coded** project —
see the note at the top of the [README](README.md) before you invest time in it.
The guidance below is shaped by that: the bottleneck is human review capacity,
not ideas.

## The one rule that matters most

**Do not open a pull request that you have not personally read line by line.**

This codebase was written mostly by AI agents. That is fine and it is not
hidden — but it means the repository has no reliable record of which parts were
actually understood and verified by a human. A pull request is currently the
only place where a human is attesting that a piece of code is correct. An
AI-generated PR with a human's name on it destroys that signal entirely.

Concretely: if you used an AI agent to help, that is fine. Say so. But you are
still responsible for every line you submit, and you should be able to explain
any of them on request.

## What is most useful

Ranked roughly by value per unit of your time:

1. **Bug reports with reproduction steps.** Genuinely the most useful thing
   here. Include your app version (bottom of Settings), Android version, and
   what you expected to happen.
2. **Fixes that come with a test.**
3. **Small, well-scoped refactors** that make existing code easier to follow.
4. **Documentation of how something works.** If you reverse-engineered a
   non-obvious part of the sync engine, writing that down helps more than a
   refactor would.

What tends not to be worth it: large feature PRs, dependency bumps with no
stated motivation, and formatting-only churn.

## Before you start

```bash
git clone https://github.com/palo007/questa.git
cd questa
./gradlew testDebugUnitTest      # make sure it is green before you change anything
./gradlew assembleDebug
```

If the tests are already failing on a clean checkout, say so in your issue
rather than trying to fix it as part of your change — it makes triage much
faster.

## Code style

Match the file you are in. There is no enforced formatter, and adding one is a
welcome contribution in its own right.

Roughly: Kotlin official style, 2-space indent, `val` over `var` unless
mutation is the point, and no clever abstractions without a comment explaining
why they exist.

## The parity rule — please read this one

Questa shares data with an earlier progressive web app. Import, export, sync
and merge must stay interchangeable with it. Some things are therefore **not**
yours to change unilaterally:

- the export format
- the HLC (hybrid logical clock) behaviour
- the three-way merge and its conflict resolution
- tombstone handling
- the Room schema

A change in those areas needs to be agreed as a change request and backed by a
golden fixture **before** it is merged. A Kotlin-only *feature* is fine, but it
has to be recorded as an accepted divergence so it does not silently get
"corrected" back later.

Anything touching the parity zone carries a one-line commit trailer:

```
Parity: none - <reason>
Parity: CR-<id>
Parity: DIV-<id>
```

If you are unsure which applies: `Parity: none` plus a clear reason is always
better than a missing trailer.

## Tests

Every behaviour change wants a test. The fixture-backed tests in
`app/src/test/java/com/example/data/sync/` are the most valuable ones — they
round-trip real-shaped payloads and assert import and re-export are identical.
If you change serialisation, merge, or sync logic, those tests must still pass.

Do not "fix" a failing test by relaxing its assertion unless you are confident
the old expectation was wrong, and say so explicitly in the PR description.

## Commits and PRs

- One logical change per commit. Small and reviewable beats large and clever.
- Explain *why* in the commit body, not just *what*.
- In the PR description: what changed, why, how you tested it, and anything a
  reviewer should look at hardest.
- Call out anything you are unsure about. "I could not work out whether this is
  correct for X" is genuinely useful information to a reviewer.

## Reading the code: an index of internal tags

Comments throughout the codebase carry short IDs left over from the way this was
built. None of it requires access to a private repository to decode:

| Tag | Meaning |
|---|---|
| `WP-8.n` | "Work Package 8, item n" — a numbered slice of the Stage-8 sync rewrite. `WP-8.5` is the export stamp, `WP-8.21` the cold-start migrations, and so on. The number says nothing about priority or order. |
| `KT-n`, `PX-n`, `DEC-n`, `Dn` | Individual decisions and fixes within a work package: "Kotlin task", "parity exception", "decision". A `DEC-` comment is the *reason* a choice was made. |
| `CR-n`, `DIV-n` | Change requests and accepted divergences recorded for the [parity rule](#the-parity-rule--please-read-this-one). These are the ones worth reading closely — they mark a place where the two implementations deliberately differ. |
| `guess G-n` | A judgement call that had not been confirmed against the original web app when it was written. If you see one and can check it, that is a good contribution. |
| `review n`, `C1`/`C2` | Items raised in a code review, and the reviewer's comment number. |
| `Opti` | The original progressive web app this was ported from: <https://github.com/Palo007/Questa>. |
| `parity`, `golden fixture` | The byte-exact JSON in `fixtures/` that pins cross-implementation behaviour. |

Names like "Momus" or "the orchestrator" refer to roles in the tooling used to
build this, not to anything in the code. Where such a reference changes how you
read the code, it is a comment worth rewriting in a PR — plain rationale
survives, the role names do not.

## Licence

Contributions are accepted under the [Apache License 2.0](LICENSE).
