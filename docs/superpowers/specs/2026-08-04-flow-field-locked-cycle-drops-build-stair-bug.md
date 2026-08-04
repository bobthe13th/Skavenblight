# Flow-Field Cycle-Breaker Drops a Locked, Needed BUILD_STAIR — Bug Report

**Status:** root cause identified with high confidence for the primary bug (live-reproduced, not
just hypothesized); fix not yet designed. Written up for a fresh session to pick up — this is
deliberately a bug report, not a design doc: the actual fix needs its own investigation into
*why* two locked positions end up in a cycle together, which this report does not resolve.

**Context:** found while manually testing
`docs/superpowers/specs/2026-08-03-project-scoped-clanrat-construction-design.md`'s clanrat
siege-construction overhaul in a real (non-GameTest) world. That overhaul is complete, merged, and
not the cause of this bug — see "Why this isn't the construction overhaul's bug" below. This is a
pre-existing defect in the region/flow-field pathing system that the overhaul's own automated
`StaircaseSiegeGroupGameTests` had already been failing against (all 4 cases, unresolved across
several prior sessions — see that design doc's "Post-implementation follow-ups" section for the
paper trail).

## Symptom

A player-run world with a real nexus and territory never gets a staircase built across a real
gap. Clanrats never receive `BUILD_STAIR` work at all (confirmed: 0 stairs ever placed in the
automated `StaircaseSiegeGroupGameTests`, which is why those 4 tests fail — "has not yet arrived
within 3.0 blocks of the nexus" is a downstream symptom of rats never getting *any* construction
instruction, not a timing/scaling problem).

## Live evidence (real playtest, not a GameTest)

Full log excerpt from a single `TerritoryRegionMap` rebuild + two dirty-region recalculations,
seconds apart, same nexus:

```
[08:23:58] [Worker-Main-15/DEBUG] [SiegeProjectManager]: Successful Macro Line built from 66, -56, 61 to 66, -57, 61 (Cost: 4500)
[08:23:58] [Worker-Main-15/DEBUG] [SiegeProjectManager]: Successful Macro Line built from 66, -56, 61 to 72, -50, 61 (Cost: 15750)
[08:23:58] [Worker-Main-15/WARN ] [FlowFieldCalculator]: Broke flow-field cycle of 2 position(s): dropped 67, -55, 61 (BUILD_STAIR, locked=true)
                                    which pointed at 66, -56, 61, kept the other 1 position(s) - reason: dropped the higher-cost position
                                    (or, on an absent/tied cost, the one encountered later while walking the cycle)

[08:24:02] [Worker-Main-11/DEBUG] [SiegeProjectManager]: Successful Macro Line built from 67, -55, 61 to 67, -56, 61 (Cost: 4500)
[08:24:02] [Worker-Main-11/DEBUG] [SiegeProjectManager]: Successful Macro Line built from 67, -55, 61 to 73, -49, 61 (Cost: 15750)
[08:24:02] [Worker-Main-11/WARN ] [FlowFieldCalculator]: Broke flow-field cycle of 2 position(s): dropped 68, -54, 61 (BUILD_STAIR, locked=true)
                                    which pointed at 67, -55, 61, kept the other 1 position(s) - reason: dropped the higher-cost position
```

The same failure recurs, shifted by exactly one diagonal step, across two consecutive
region-topology-triggered rebuilds of the same nexus — not a one-off fluke.

**Contrast** — the cycle-breaker also fired once on a 6-position cycle in the same log and handled
it correctly:

```
[08:24:02] [Worker-Main-11/WARN ] [FlowFieldCalculator]: Broke flow-field cycle of 6 position(s): dropped 74, -60, 59 (WALK, locked=false)
                                    which pointed at 75, -60, 59, kept the other 5 position(s) - reason: kept every locked position in this
                                    cycle (2 of 6), dropped one of the non-locked ones
```

This is the mechanism working as intended: when a cycle has a mix of locked and unlocked
positions, it correctly sacrifices an unlocked one. **The bug only manifests when every position
in a small cycle is locked** — there's no unlocked alternative to sacrifice, so the logic falls
through to a cost-based tie-break, and in this case the tie-break happens to pick the position a
real, in-progress staircase project actually needs.

## Root cause — the code path (identified with high confidence)

`src/main/java/org/ratden/skavenblight/ai/pathing/FlowFieldCalculator.java`:

- `pickCyclePositionToDrop` (`:343-369`, called from `breakMutualCycles` at `:286`): if
  *at least one but not all* of a cycle's positions are locked, it correctly restricts the
  drop-candidates to the unlocked ones (`:347-350`). **If every position in the cycle is locked**,
  there is no unlocked alternative, so it falls straight to the cost/tie-break loop (`:352-366`)
  over the *entire* cycle — dropping whichever position has the higher `nextCostMap` entry (or, on
  an absent/tied cost — routine for macro-project interior positions, which usually have no
  `nextCostMap` entry at all — whichever was encountered later walking the cycle). This tie-break
  has no way to know that the position it's about to drop is the one live construction actually
  needs — cost and encounter-order are the only signals available to it, and this is a case where
  neither one is a signal that means "safe to drop."
- `detectMutualCyclePositions` (`:398+`) and `breakMutualCycles` (`:258-309`) build the cycle and
  call the above; the log line itself is emitted at `:293-301`.

**Why both positions in this specific cycle are locked (worth investigating first):** the two
"Successful Macro Line" log lines immediately preceding each cycle-break both originate from the
*same* anchor (`66,-56,61` in the first pass, `67,-55,61` in the second) — this is
`SiegeProjectManager.evaluateMacroProjects`'s normal 14-direction fan-out from one obstacle anchor
(`SiegeProjectManager.java`, `evaluateMacroProjects`), which is *expected* to sometimes produce more
than one successful candidate line from the same anchor in different directions. One line here is
short (`(66,-56,61)→(66,-57,61)`, cost 4500 — a single vertical/near-vertical hop) and the other is
a long diagonal staircase climb (`(66,-56,61)→(72,-50,61)`, cost 15750, six diagonal steps,
`dx=+6,dy=+6,dz=0`). **Hypothesis, not yet confirmed:** these two independently-traced candidate
lines' own *downstream* instruction positions overlap or point at each other, producing the 2-cell
cycle — `SiegeProjectManager.isNearExistingProject`/`PROXIMITY_RADIUS_SQR` (`SiegeProjectManager.java:23-27`)
only dedups anchors that are near each other in the *same* calculation pass; it does not check
whether two lines fanned from one shared anchor, in different directions, later collide with each
other's *traced* cells. This is the most promising lead for a fresh investigation, but it has not
been confirmed by stepping through an actual repro — treat it as a strong hypothesis, not a
finding.

## Why this isn't the construction-overhaul's bug

The clanrat-construction overhaul (project-scoped worker registration, auto-widening — see the
sibling design doc) only changed *how a already-scheduled* `BUILD_STAIR` instruction gets executed
once a clanrat is standing near it. This bug happens *before* any of that: the instruction is
deleted from `nextInstructionMap` during flow-field calculation, before any goal or `SiegeProject`
ever sees it. Zero stairs get placed because no rat is ever handed the instruction in the first
place — confirmed by the log showing the drop happening during `TerritoryRegionMap`'s own
region-rebuild pass, entirely before any clanrat AI runs.

This is also not new: `StaircaseSiegeGroupGameTests.restrictTerritoryToMinimalArea`'s own
pre-existing javadoc (written well before the construction overhaul, see
`docs/superpowers/plans/2026-07-31-staircase-siege-group-gametest-plan.md`) already predicted a
version of this exact failure mode — though it originally framed it as "real BUILD_STAIR vs. a
GameTest-encasement-geometry artifact." **This live repro is a materially different case: no
GameTest encasement is involved at all** (this was a real playtest world), and the conflict is
between two *both-real, both-locked* macro-project candidates, not a real instruction vs. a test
artifact. Whatever theory the fix works from should account for this non-test-environment
reproduction, not just the GameTest-specific framing in the older plan doc.

## Other known follow-up items (secondary, already documented, not blocking)

These are unrelated to the bug above and don't need to be solved together with it — listed here
only so a fresh session has the full picture in one place. Full detail in
`docs/superpowers/specs/2026-08-03-project-scoped-clanrat-construction-design.md`'s
"Post-implementation follow-ups" section:

1. **Auto-widening's offset never scales in magnitude** — `SiegeProject.tryWiden` alternates sides
   but always shifts exactly one perpendicular unit from the same anchor, so widen #3 duplicates
   widen #1's lane instead of reaching a new one. Only ~3 of the intended 4 lanes are ever reachable.
2. **A narrow, untested corner case survives in `canUse()`/registration preconditions**: a
   widen-eligible project (`BUILD_STAIR`/`BUILD_BRIDGE`, at capacity, below `maxProjectWidth`)
   whose perpendicular retrace then fails still lets one rat hold `MOVE`/`LOOK` while re-attempting
   that trace every tick. Recommended fix direction: cooldown-based retry memoization, not
   width-keyed (which would permanently blacklist the project).
3. **`StaircaseSiegeGroupGameTests`' tightened timeouts** (small-group 8000→5000, large-group
   12000→3000) were calibrated against a since-fixed quadratic work-accumulation bug and can't be
   meaningfully re-validated until *this* report's primary bug is fixed — today 0 stairs are ever
   placed regardless of timeout budget, so the timeout values are simply untested.

## Suggested starting points for a fresh session

- Reproduce deterministically first: the automated `StaircaseSiegeGroupGameTests` (all 4 cases)
  already reproduce this reliably and are much faster to iterate on than a manual playtest — treat
  those as the test harness, not just this report's log excerpt.
- Confirm or refute the "two lines fanned from one anchor collide downstream" hypothesis above by
  adding temporary diagnostic logging (this codebase's established convention — see
  `docs/superpowers/plans/2026-07-31-clanrat-gap-crossing-pathing-fix-plan.md`'s Task 1 for the
  pattern: a `// TEMPORARY DIAGNOSTIC` marker, removed before commit) to `evaluateSingleLine`/
  `evaluateMacroProjects` capturing every candidate line's full traced position set, then check
  for overlap between lines fanned from the same anchor.
- If confirmed, the fix likely belongs in `SiegeProjectManager` (don't let two candidate lines from
  the same anchor claim overlapping downstream cells) rather than in `FlowFieldCalculator`'s
  tie-break (patching the tie-break to "prefer BUILD_STAIR over other actions when all else is
  equal" would mask this specific case but wouldn't address the underlying two-projects-collide
  issue, and could create a different bad tie-break elsewhere).
- `FlowFieldCalculatorTest.java` already has direct unit tests for `detectMutualCyclePositions`/
  `pickCyclePositionToDrop` (pure, static, no Minecraft world needed) — extend that file with a
  fixture reproducing "every position in the cycle is locked" once the real producer is understood,
  so the eventual fix has a fast, deterministic regression test independent of the slower
  GameTest suite.
