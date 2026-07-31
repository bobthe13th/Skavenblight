# Clanrat Gap-Crossing Pathing Fix — Design

## Goal

Root-cause and fix whatever is causing `StaircaseSiegeGroupGameTests`' four tests
(`testSingleRatBuildsStaircaseAcrossSmallGap` and its small-group/large-group/giant-gap siblings) to
fail non-deterministically, then verify the fix holds across repeated runs — not just one green
run, since the bug is known to be flaky rather than a hard, always-reproducing failure.

## Background

`StaircaseSiegeGroupGameTests` (see
`docs/superpowers/plans/2026-07-31-staircase-siege-group-gametest-plan.md`) proves, using the fully
real production stack with no hand-fed instructions, that a group of clanrats can discover a
diagonal gap, build a staircase across it, and reach a nexus. All four tests exist and run to
completion, but none pass reliably yet — by design: clanrat siege construction/pathing is still an
unfinished system, and this suite exists specifically to give that work something concrete to turn
green against.

Across the session that built this suite, five real production bugs were found and fixed along the
way (all committed, all independently verified to cause no regressions in the rest of the GameTest
suite):

1. `TerrainEvaluator.isActionCompleted`'s `MINE` case checked only the foot cell, missing
   head/ceiling obstructions `determineMacroAction` can also select `MINE` for.
2. `RegionFlowField.getNextSiegeNode`'s "already built, walk through" substitution used
   `node.pos().above()` for every non-`MINE` action; only correct for the pure-vertical climbs
   (`PILLAR`/`SPIRAL`/`LADDER`) — `BUILD_STAIR` lands the mob at `node.pos()` itself.
3. `AbstractSiegeConstructionGoal.findEffectiveNode`'s lookahead reused
   `MAX_TARGET_CLAIM_DISTANCE` (2.5, sized for tolerating crowd-shove during an *already-claimed*
   build) as its own peek distance, letting a construction goal claim and build a stair two
   flow-field hops away — before the mob ever walked onto the connector's real entry cell. Fixed
   with a dedicated, tighter `LOOKAHEAD_SNAP_DISTANCE` (1.5, one legitimate adjacent interaction).
4. `FollowFlowFieldGoal` had no way to physically climb the first stair of any gap-spanning
   staircase: that step is approached from a ledge with open air (the gap itself) at the mob's own
   Y in that direction, so vanilla's `WalkNodeEvaluator` never generates an ascend node there —
   `Navigation.moveTo` silently returns a dead, 1-node path with no error and no retry. Fixed by
   driving that one short hop directly instead of through vanilla pathfinding.
5. The escape-hatch (`FollowFlowFieldGoal`'s "hasn't moved in ~10 ticks" fallback) called
   `Navigation.moveTo` directly for its own target, hitting the exact same wall as (4) via a second
   code path. Fixed by routing both call sites through one shared hop helper, and by replacing the
   original `JumpControl`+`MoveControl` hop (generic AI steering for opportunistic hops, not a
   precisely-landing leap) with a direct one-tick velocity impulse — the same technique vanilla
   uses for its own deliberate jumps (Rabbit's hop, Goat's ram).

With all five fixes in place, `testSingleRatBuildsStaircaseAcrossSmallGap` *still* doesn't pass
reliably. Repeated runs show genuinely different symptoms from run to run: some runs show real
partial progress (a stair or two built, a few steps taken) before stalling; others show the rat
frozen from tick 0, never attempting even an ordinary flat step; a few end mid-timeout at a
fractional Y (mid-jump). No pattern tying this to anything changed this session, to the test's
GameTest-assigned world coordinates (ruled out — persists on a freshly wiped world, and the
coordinates are well within `double` precision regardless), or to timing has been found. This is a
genuinely unresolved, non-deterministic bug, not a known fix waiting to be written down.

## Scope

**In scope:** root-causing and fixing the specific non-determinism described above, verified via
`testSingleRatBuildsStaircaseAcrossSmallGap` (and, once that one is reliable, confirmed against its
three siblings too).

**Explicitly out of scope:** any other clanrat building/pathing issue not connected to this
gap-crossing flakiness. The project owner has confirmed clanrat building is broadly unfinished, but
this plan targets only the thing these tests were built to verify — other issues get their own
future plans.

## Architecture

Two tasks, investigate then fix — deliberately not a single "implement the fix" task, because the
fix isn't known yet:

### Task 1: Root-cause the non-determinism

A fresh implementer, briefed with the background above (so it starts from everything already ruled
out rather than re-deriving it), builds a small, reusable diagnostic harness and uses it to find the
actual point of divergence between a passing run and a failing run — replacing this session's
ad-hoc, reactive logging with a systematic, comparative method.

**Harness design:**
- A temporary, toggleable diagnostic block (matching this session's own established convention: add
  directly to the test file / goal classes under a `// TEMPORARY DIAGNOSTIC` marker, revert before
  any commit) that captures, at a fixed short interval (e.g. every 10 ticks, matching
  `FollowFlowFieldGoal`'s own `pathingUpdateTimer` cadence): the rat's exact position and velocity,
  its active goal name, `Navigation.isInProgress()`/`isDone()`/current `Path`, and the flow field's
  raw and resolved instruction at the rat's current position.
- Run `testSingleRatBuildsStaircaseAcrossSmallGap` in isolation (temporarily disabling other
  `@GameTest`-annotated classes the way this session's own earlier investigation already did once,
  if that helps isolate signal) repeatedly — enough runs to collect a meaningful mix of passing and
  failing outcomes (this session's own experience suggests ~10-20 runs is enough to see both).
- Compare the captured evidence between at least one passing run and at least one failing run,
  looking specifically for the first tick at which their behavior diverges (not just where they end
  up) — that divergence point is the actual lead, not any single run's end state.

**Deliverable:** a written root-cause report (where in the codebase, why, and under what
condition), plus a recommended fix approach. The report does not need to include the fix itself if
the investigation runs out of budget before implementing it — but per the fix-loop's own escalation
rules, if the assigned implementer can't converge after its allotted rounds, escalate to a fresh
implementer on a more capable model before concluding the harness approach itself has failed.

**Escalation if the harness doesn't converge:** live-observing the test in a rendered client with
the mod's own `DebugFlowFieldReaderItem` overlay — proven effective this session for a different,
visually-obvious bug (a floating stair block). Non-determinism is harder to catch by eye across
runs than a single consistently-wrong placement, so this is a fallback, not the primary method, but
worth trying if the harness's comparative diffing doesn't surface a clear divergence point.

### Task 2: Implement and verify the fix

Implement whatever Task 1's report points to. Then verify reliability — the actual bar for a bug
known to be non-deterministic, not just "it passed once":

- Run the full 4-test `StaircaseSiegeGroupGameTests` suite **10 consecutive times**.
- All 4 tests must pass in all 10 runs, with no new failures beyond the two pre-existing, unrelated
  ones already documented in the original design spec's "Known risks" section
  (`testparentregiongetsrealinstructionsforsharedconnectorcells` always failing,
  `testRepeatedConnectorCompletionsDontExplodeRebuildCount` sometimes flaky on real-time waits).
- If any run in the 10 fails, that's a genuine finding, not noise to average away — return to Task
  1's investigation with the new failure's evidence rather than patching around it.

## Testing

No unit test framework — GameTest only, exactly as established by the test suite this plan targets.
Task 1's own harness IS the testing method for that task (evidence-gathering, not pass/fail).
Task 2's testing is the 10-consecutive-clean-runs bar above.

## Known risks

- The root cause might not be a single, isolated bug — it could be an interaction between several
  of the systems already touched this session (flow-field recompute timing, goal priority
  arbitration, the new velocity-impulse hop's own timing). Task 1's report should say so explicitly
  if that's what the evidence shows, rather than forcing a single-cause narrative.
- 10 consecutive clean runs is a judgment call, not a formally derived number — chosen because it's
  large enough that a bug with even a modest failure rate (say, 1-in-5) would very likely show up at
  least once, while staying practical to run repeatedly during development. If Task 2's fix passes
  10 straight but the project owner has reason to want more confidence later, raising this bar is a
  cheap, non-destructive follow-up.
