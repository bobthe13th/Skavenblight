# Investigation plan: `StaircaseSiegeGroupGameTests`' 4 remaining failures (zero stairs ever built)

**Status: plan only, nothing in this document has been implemented.** No production code changes
were made while writing this — it's the output of a deep-scan/research pass, not a fix.

## Symptom recap

`testSingleRatBuildsStaircaseAcrossSmallGap`, `testSmallGroupBuildsStaircaseAcrossSmallGap`,
`testLargeGroupBuildsStaircaseAcrossSmallGap`, and `testLargeGroupBuildsChainedStaircaseAcrossGiantGap`
all fail identically, every single run: **zero `COBBLESTONE_STAIRS` blocks are ever placed** in the
crossing zone, regardless of group size (1, 4, 10 rats) or timeout. This is not flaky/racy — it's
deterministic across every run this session, including with the head-clearance fix
(`TerrainEvaluator.isFitForWalking`) fully reverted, which rules that fix out as the cause.

**Correction (2026-08-05, later session): the claim below was wrong.** The "Cost: 5275" line
belonged to a *different* test's region, not this one — never bounds-checked against the actual
structure the log line's coordinates fall inside. See "Step 1 result (corrected)" below for the
methodology mistake and the real, bounds-verified findings. The paragraph immediately below is left
in place, struck through in spirit, purely so a future reader can see exactly what was wrongly
asserted and not repeat the same shortcut:

~~**Already confirmed this session:** the diagonal `BUILD_STAIR` crossing line IS still being
*discovered* — a `"Successful Macro Line built ... (Cost: 5275)"` log entry (matching the
known-good signature from earlier verification) appears in a completed run's log. So this is not a
"the planner never finds the crossing" bug. The question is what happens to that discovery
afterward.~~

## Prior-session context (found during this deep scan, not previously connected)

A commit predating this whole conversation
(`004f8f5`, `docs/superpowers/specs/2026-08-03-project-scoped-clanrat-construction-design.md`'s
"Post-implementation follow-ups" section) already diagnosed this exact test failure once, via
diagnostic logging, as: **`FlowFieldCalculator`'s cycle-breaker dropping the crossing's own locked,
genuinely-needed `BUILD_STAIR` instruction on a 2-cell mutual cycle.**

**This session's own logs do not currently reproduce that specific mechanism** — across every
saved full-run log from this session, only 2 cycle-breaks ever fired, and both dropped a `WALK`
action, never `BUILD_STAIR`. The codebase has changed substantially since that diagnosis (three
"Critical" construction-goal bugs were fixed after it was written, and this session independently
fixed the self-collision cycle guard, the `isCompleted`/predecessor-position bug, the entryPos
routing fallback, and the empty-build-order candidate guard). **Treat the old diagnosis as a
strong prior on the *class* of bug (something silently drops or fails to promote the crossing's own
instructions before any rat can see them), not as a still-accurate pointer to the exact mechanism.**
Re-derive, don't assume.

## Deep-scan findings: the full pipeline, stage by stage

Traced via direct code reading (`SiegeProjectManager`, `FlowFieldCalculator`, `SiegeProject`) plus
an Explore pass over the construction-goal chain (`ClanratEntity`, `AbstractSiegeProjectGoal`,
`AbstractSiegeConstructionGoal`, `BuildFlowFieldGoal`, `AwaitFormationGoal`,
`SiegeNodeLookahead`).

**Stage A — discovery.** `SiegeProjectManager.evaluateSingleLine` traces the diagonal line,
logs `"Successful Macro Line built"`, and adds a `SiegeProject` to `candidateProjects`. **Confirmed
working** (the log line appears).

**Stage B — promotion.** At the end of the pass, `FlowFieldCalculator.finalizeCalculation` runs, in
this exact order:
```
breakMutualCycles();                                                    // can delete map entries
state.updateInstructions(new HashMap<>(nextInstructionMap));
projectManager.finalizeCandidateProjects(nextCostMap, state.getInstructionMap());  // checks against the POST-cycle-break map
```
`SiegeProject.survivedMapOverwrite` (`SiegeProject.java:83-97`) rejects the candidate if **any
single one** of its `instructions` keys is missing from, or has a different action in, the final
(post-cycle-break) instruction map, or if a cheaper route to `entryPos` now exists. If rejected, the
candidate is silently dropped and **never added to `activeProjects`.**

**Stage C — goal selection & registration.** `BuildFlowFieldGoal`/`AbstractSiegeProjectGoal.canUse()`
requires `SiegeNodeLookahead.findEffectiveNode(...)` to find a construction-action node, AND
`flowField.findProjectFor(node.pos())` → `SiegeProjectManager.findProjectContaining(pos)` to find
that position as a key in some project's `instructions` map **within `activeProjects`**. If Stage B
never promoted the project, this can never succeed — for any rat, any tick, regardless of group
size. `AbstractSiegeProjectGoal` is goal-priority 6; `AwaitFormationGoal` (8) and
`FollowFlowFieldGoal`/`StrandedGoal` (9-10) are strictly lower priority and only run when
`canUse()` fails here.

**Stage D — actual placement.** `SiegeProject.tick()` accumulates work per registered worker and
eventually calls `SiegeInteractionHandler.constructSiegeBlock`. Everything here is downstream of,
and gated by, Stage B/C succeeding first.

**Recently-fixed bugs in this area, assessed:**
- Quadratic worker-count accumulation fix, MINE-step-stall fix, `canUse()`/registration-precondition
  mirroring fix (all pre-dating this conversation) — all three are Stage C/D fixes. **None of them
  can matter if Stage B never promotes the project in the first place.** This is the key reason the
  prior session's own three "Critical" fixes didn't move these four tests at all: they fixed real
  bugs in a part of the pipeline that's currently unreachable for this specific test geometry.
- This session's self-collision guard (`evaluateSingleLine`'s `reenteredProject` check) — scoped
  specifically to *an already-active* project's own entryPos re-colliding with itself. Does not
  apply to a *candidate* (not-yet-active) project's interior, which is the situation at Stage B.
- This session's empty-build-order guard — prevents a specific degenerate candidate from being
  committed at all. Does not protect a *real, non-degenerate* candidate (like this diagonal line)
  from being overwritten/dropped by something else during the same pass.

## Primary hypothesis (high confidence, not yet confirmed)

**The diagonal `BUILD_STAIR` candidate is discovered every pass, but never survives
`finalizeCandidateProjects`/`survivedMapOverwrite` — so it never reaches `activeProjects`, and the
entire construction-goal pipeline (Stages C/D) is correctly, permanently unreachable.** Two known
mechanisms could cause this, both already documented as live, unfixed gaps in
`docs/pathing/instruction-map-invariants.md` and `FlowFieldCalculator`'s own comments:

1. **`breakMutualCycles` drops one of the candidate's own interior cells.** A freshly-discovered
   candidate's positions are NOT in `lockedPositions` yet (that set is populated from
   *already-active* projects only, before the pass starts) — so if any of its interior cells
   collide with an unrelated instruction discovered in the *same* pass (e.g. the ordinary flood, or
   a different macro-line), `pickCyclePositionToDrop`'s "prefer locked, else cost/tie-break" logic
   has no reason to protect it. This exactly matches the *class* of bug the pre-existing
   `docs/superpowers/specs/2026-08-04-flow-field-locked-cycle-drops-build-stair-bug.md` names, and
   is structurally the same shape as the empty-build-order bug this session already found and
   fixed — just triggered by a different producer.
2. **An unguarded `nextInstructionMap.putAll`/ordinary-flood write overwrites one interior cell**
   without ever triggering a logged cycle-break at all (e.g. if the overwriting write doesn't
   itself close a cycle, `breakMutualCycles` never fires and there's no WARN log to find — this
   would explain why grepping for `BUILD_STAIR` in cycle-break logs came up empty even though
   Stage B could still be failing).

Either mechanism produces the exact observed signature: deterministic, present in every run,
independent of group size, with the discovery log firing but nothing downstream of it ever
happening.

## Investigation steps (in order — cheapest/most-discriminating first)

### Step 1 — Confirm or refute Stage B directly (cheap, ~5 minutes, no code changes)

`SiegeProjectManager` already exposes `getActiveProjectCount()`, `getLastPassCandidatesGenerated()`,
and `getLastPassCandidatesSurvived()`, and `PathingDebugFileWriter` already publishes them as
`"Siege Projects: N active | N generated / N survived this pass"` in every debug dump. Run one of
the four failing tests (or trigger a manual dump while it runs) and read that line for the region
containing the crossing.

- **If `survived` < `generated`, or `active` stays 0 across the whole run:** primary hypothesis
  confirmed. Proceed to Step 2.
- **If the candidate DOES survive and `active` > 0:** primary hypothesis refuted. The bug is
  downstream, in Stage C/D — proceed to Step 4 instead (re-open the goal-priority investigation the
  advisor previously deferred, this time with a confirmed-active project as the starting point).

This single check is the fork point for the entire rest of the investigation. Do not skip it or
assume the answer.

### Step 1 result (corrected, 2026-08-05)

**Methodology note first, because it cost real time twice:** `pathing_test_giant` structures get
placed at a *different* world offset every run. A candidate's `entryPos` being numerically "close
to" a test's nexus/rat coordinates from a *different* log excerpt proves nothing — the only valid
check is computing the actual test's structure bounding box (origin from the `LogTestReporter`
failure line, size from the template, here 96×96 in X/Z) and confirming the candidate's coordinates
fall inside it. Two earlier passes through this investigation (both now retracted) attributed a
candidate to the single-rat test without doing this and drew wrong conclusions both times. Every
TEMP-DIAG line below is bounds-checked before being used as evidence.

Added two temporary diagnostics (still in the tree, marked `[TEMP-DIAG]`, not yet removed):
- `SiegeProjectManager.finalizeCandidateProjects`/`injectActiveProjects`: per-pass counters
  (`macroEvaluationCount`, `lineStepsEvaluated`, `maxCandidateProjectLength`, candidate
  survival) and the live `activeProjects` list at the start of each pass.
- `TerritoryRegionMap`'s two `setActiveConnectorProject` call sites (`rebuildRegionsAndGraph` and
  the steady-state `tick()` fast path): the assigned `parentConnector`'s two endpoints, cost, and
  the project's own action set/key count.

**Confirmed, bounds-verified, for `testSingleRatBuildsStaircaseAcrossSmallGap`** (structure bounds
x∈[1925444,1925540], z∈[215208,215304] in the run this was captured from):
- The rat's own ground region (region 1) already has a route-tree-assigned `parentConnector` to
  region 2 (the platform), with `entryInA={1925482,-58,215247}` — the rat's *exact* stuck block —
  and `entryInB={1925482,-44,215233}`, right at the platform's base. Cost 29265,
  `projectActions=[WALK, MINE, BUILD_STAIR]`, 14 keys. **This is a real connector with real
  construction work, not a bogus all-WALK artifact through GameTest's encasement geometry** (the
  failure mode the class javadoc at lines ~129-152 describes fighting once already) — that specific
  hypothesis is refuted for this test.
- Two *other*, unrelated regions in the same run showed `candidatesGenerated=0` with nonzero
  `macroEvaluationCount` (4 and 6) at both `maxCandidateProjectLength=6` and `=32` — evaluateSingleLine's
  fan runs and every line aborts — but bounds-checking placed neither of those regions inside the
  single-rat structure, so this does NOT confirm the primary hypothesis (Stage B rejection) for
  *this* test. It's recorded here only so a future pass doesn't re-discover and re-attribute it.
- `lineStepsEvaluated` is dead: declared, reset every pass, read by the diagnostic, never
  incremented anywhere in the codebase. Every one of ~76+ pass-summary lines across two separate
  full-suite runs read exactly 0, including passes that generated real multi-key candidates. Don't
  trust it as a signal until something actually increments it.

**Net result: the primary hypothesis as originally framed (Stage B silently rejects a discovered
diagonal `BUILD_STAIR` candidate) is not confirmed AND not cleanly refuted — it's the wrong
question for this test's actual topology.** The single-rat test's crossing is not a reactively-
discovered candidate that needs to survive `finalizeCandidateProjects` at all. It's a route-tree
connector, already correctly shaped (real WALK/MINE/BUILD_STAIR actions, entryPos exactly under the
rat), already re-seeded into `activeProjects` every single pass via `setActiveConnectorProject`
(unconditional, not gated on any block-change/dirty-region condition the way reactive candidate
promotion is). The open question going forward is narrower and different from Steps 2-4 below:
**why does a mob standing exactly on a real, already-assigned connector's own entryPos never
receive or act on its first instruction?** That points at how `injectActiveProjects` merges a
connector's `getRemainingInstructions()` into the published `RegionFlowField`, and/or at whatever
goal reads that field for a mob at this exact position (`AbstractSiegeProjectGoal.canUse()` /
`SiegeNodeLookahead.findEffectiveNode` / `SpiralSapperGoal.canUse()` depending on which action the
mob's own position resolves to). Steps 2-4 below were written for a Stage-B-rejection hypothesis
that no longer fits the evidence for this test; treat them as background, not a live prescription,
until re-checked against a connector-shaped bug instead of a candidate-shaped one.

TEMP-DIAG logging is still present in `SiegeProjectManager.java` and `TerritoryRegionMap.java` —
strip it before any commit in this area.

## Root cause found (2026-08-05, same session, continued)

Added per-rat goal/nav-state diagnostics (`ClanratEntity.describeAllGoalCanUseState`/
`describeFlowFieldNavState`, `FollowFlowFieldGoal.describeInternalState`, all TEMP-DIAG, logged
every 100 ticks from `StaircaseSiegeGroupGameTests.awaitArrivalAndStaircase`) and traced
`testSingleRatBuildsStaircaseAcrossSmallGap`'s rat end to end, by entity id (no coordinate
correlation needed — sidesteps the whole bounds-checking problem above).

**Goal priority is not the bug.** At every sample, exactly one goal's `canUse()` was ever true at a
time, in the correct priority order (`FollowFlowFieldGoal` while walking flat ground,
`BuildFlowFieldGoal` when standing on a cell whose lookahead resolves to a construction action).
Nothing starves anything. The mob makes real progress across ordinary flat ground and successfully
triggers `BuildFlowFieldGoal` to place a real `COBBLESTONE_STAIRS` block exactly where the flow
field says to build one.

**The actual failure: `FollowFlowFieldGoal.tryClimb`'s single-block ballistic jump never
succeeds for this connector's diagonal climb step, even once the real stair block exists.**
Confirmed via repeated samples at the exact same frozen position
(`{14509781,-58,-1250048}` in the captured run):
- `nextHopTerrain` shows a real, correctly-placed `cobblestone_stairs` block at the climb target
  (`{14509782,-57,-1250048}`, one step diagonally up) from this point onward.
- `followGoalState`'s `activeClimbTarget` oscillates `null` → `{14509782,-57,...}` → `null` →
  `{...}` → `null` across consecutive samples, `climbCyclesElapsed` always reset to 0, position
  never advancing past `y=-58`. That is `tryClimb`'s own "already climbing, but `onGround()` read
  true" branch firing every attempt — the mob never actually leaves the ground, or if it does,
  never crosses the 1-block gap — so every attempt is scored "landed short" and a fresh jump gets
  relaunched, forever, without ever making net progress.
- This reproduces even though the stair is real and correctly built — ruling out every hypothesis
  from earlier in this investigation (discovery, promotion, goal priority, wiring). This is a
  movement-execution bug in the ballistic-climb mechanism itself, not a pathing/planning bug.

**Not yet determined:** *why* the jump fails to carry the mob across — whether `jump()`+
`setWantedPosition` genuinely never gets the mob airborne at all for this exact
dx=±1,dy=+1,dz=0 case, a timing issue between the 10-tick `pathingUpdateTimer` gate and vanilla's
own single-tick jump pulse, or something specific to this stair's approach direction. That would
need tick-by-tick Y/velocity logging around one live launch, not 100-tick sampling — a good next
step if this is picked back up, but out of scope for what was asked this round (trace + notes,
not a fix).

All TEMP-DIAG code added this round (`ClanratEntity.describeAllGoalCanUseState`/
`hasFlowFieldAssigned`/`describeFlowFieldNavState`, `FollowFlowFieldGoal.describeInternalState`,
the periodic log in `StaircaseSiegeGroupGameTests.awaitArrivalAndStaircase`) has been removed now
that the question it existed to answer is answered. The two earlier TEMP-DIAG blocks in
`SiegeProjectManager.java` and `TerritoryRegionMap.java` (per-pass counters, connector-origin
logging) are also removed — they were useful for ruling out Stage A/B, and that's now done.

## Attempted fix, reverted: "replace the ballistic jump with vanilla pathfinding" (2026-08-05)

Tried, per explicit instruction, replacing `FollowFlowFieldGoal.tryClimb`'s `JumpControl.jump()` +
`MoveControl.setWantedPosition` mechanism with a plain `Navigation.moveTo(to)` call for climbs
(treating a climb the same as any other hop). Rationale at the time: the class's own javadoc
justifying the ballistic jump ("WalkNodeEvaluator never generates an ascend node... across the same
gap") sounded like it described an *approach-side* problem (no floor on the way to the target), and
this session's own trace showed the failure reproducing even with the target stair fully built and
the mob already grounded immediately adjacent to it — a materially simpler case than what that
javadoc described, so it seemed plausible vanilla pathfinding would just work here.

**Empirically wrong, confirmed twice via full-suite runs with per-rat diagnostics re-added:** even
with a real, correctly-built `cobblestone_stairs` block one step diagonally up-and-across from a
grounded mob, `Navigation.moveTo` toward it returns `navIsInProgress=false navIsDone=true` forever
— the exact same signature as before the change, and identical to what the original developer's
(removed, now restored) comment described. This isn't a timing/approach issue, it's a structural
limitation: vanilla's `WalkNodeEvaluator` does not generate ascend nodes for a diagonal (X+Z) move
combined with a Y change in the same step, regardless of whether the destination block exists. This
is exactly the shape `BUILD_STAIR` is designed to require (see the class javadoc: "the gap must be
diagonal ... TerrainEvaluator.determineMacroAction only produces BUILD_STAIR for a diagonal
direction") — so the one action this whole system exists to place is, structurally, the one kind of
move vanilla pathfinding can't make. The ballistic-jump mechanism isn't a workaround for a solved
problem; it's the only viable mechanism for this specific move, full stop.

**Reverted** `FollowFlowFieldGoal.java` back to the original ballistic-jump implementation
(verified byte-identical to the pre-change baseline via `git diff --stat`, and the known 4-test
failure baseline confirmed restored via a fresh full-suite run — one run showed a 5th, unrelated
failure, `testthreeregionsroutethroughcheaperintermediatehop`, that did not reproduce on immediate
re-run: flaky, not caused by this change).

**New isolated regression test added, kept despite not reproducing the bug:**
`FollowFlowFieldGoalClimbGameTests.testRatCrossesAnAlreadyBuiltDiagonalStairStep` — a single
`ClanratEntity`, hand-fed flow field, one already-built diagonal stair, no competing goals. This
passes on the ORIGINAL (unfixed) ballistic-jump code, meaning the bug does not reproduce in this
minimal, single-goal setup. That's a real, useful finding in its own right: **whatever causes the
oscillating "landed short, relaunch, landed short" failure in the full `StaircaseSiegeGroupGameTests`
scenario depends on something this isolated setup doesn't have** — most likely the frequent
goal-preemption dynamic `FollowFlowFieldGoal.start()`'s own comment already documents ("this goal
gets briefly preempted and immediately resumed far more often than its own 10-tick cadence would
suggest"), which resets `pathingUpdateTimer` to 0 on every restart without touching
`activeClimbTarget`/`climbCyclesElapsed` — a plausible mechanism for `tryClimb`'s "already climbing,
but `onGround()` read true" check firing prematurely, on the tick immediately after a restart,
before a jump arc had any real chance to develop. **Not yet verified** — would need either a
tick-by-tick trace of `activeClimbTarget`/`onGround()`/goal running-state during one live failing
climb, or a version of the isolated test that deliberately forces `stop()`/`start()` cycling mid-arc
to see if that alone reproduces the oscillation. This is the next concrete step if this bug is
picked back up: verify the preemption hypothesis before attempting another fix, since "replace with
vanilla pathfinding" is now a closed, empirically-refuted avenue and a second guess without new
evidence risks the same outcome.

### Step 2 — If Stage B is confirmed broken: find which mechanism (WARN log vs. silent overwrite)

- Grep the same run's log for `"Broke flow-field cycle"` entries whose dropped position falls
  within the crossing's own coordinate range (not just filtering by action name — filter by
  proximity to the known crossing anchor/endpoint from the "Successful Macro Line built" line, since
  an overwritten interior cell might get dropped later as `WALK` if something else's action landed
  there, not necessarily logged as `BUILD_STAIR`).
  - **If found:** mechanism 1 (cycle-breaker). Proceed to Step 3a.
  - **If not found:** mechanism 2 (silent overwrite, no cycle formed). Add a temporary log inside
    `SiegeProject.survivedMapOverwrite` printing exactly which key failed the check and what the
    final map held there instead, and re-run. Proceed to Step 3b with that evidence.

### Step 3a — If it's the cycle-breaker: design the fix

The core issue is that a freshly-discovered candidate's interior cells aren't locked/protected
during the *same pass* they're discovered. Two candidate fix shapes (pick after seeing real
evidence of which cells collide with what):
- Extend the self-collision-guard pattern: before finalizing a cycle-break decision, check whether
  either side of the 2-cycle belongs to a *this-pass* candidate in `candidateProjects` (not just
  `lockedPositions`), and prefer keeping that side.
- Or: move `finalizeCandidateProjects`'s promotion check to run *before* `breakMutualCycles`, so a
  candidate's cells get locked (added to `lockedPositions`) before cycle-breaking evaluates them —
  changes the pass ordering, higher risk, needs careful review against every other consumer of
  `lockedPositions`'s current "only already-active projects" invariant.

### Step 3b — If it's a silent overwrite: identify and fix the specific write

Once `survivedMapOverwrite`'s log pinpoints the exact key and what overwrote it, trace that write
back to its producer (likely another `evaluateSingleLine` call from a different anchor in the same
pass's 14-direction fan, or the ordinary flood's own `processOrthogonalNeighbors` write racing a
candidate that hasn't locked its cells yet). Fix shape: extend `isNearExistingProject`/the
self-collision guard's scope to cover *candidate* projects from this same pass, not just already-
active ones — candidates would need to publish their claimed cells into a pass-scoped set as they're
created, checked by subsequent same-pass line traces and by `processOrthogonalNeighbors`'s writes.

### Step 4 — If Stage B is NOT the problem: resume the goal-priority investigation

Only reachable if Step 1 shows the project going active. In that case, re-open the deferred
investigation from earlier this session with a concrete anchor: with a confirmed-active project,
step through `AbstractSiegeProjectGoal.canUse()`/`SiegeNodeLookahead.findEffectiveNode` /
`SiegeProject.canAcceptWorker` for one specific rat and one specific tick, logging each condition's
value, to find which one is unexpectedly false. The Explore pass for this plan already flagged one
secondary, unconfirmed discrepancy worth checking first in this branch: `SiegeNodeLookahead`'s
`findEffectiveNode` may return a node whose `.pos()` is a "next hop" value (per the
`SiegeLineTracer`/`FlowFieldCalculator` convention documented in
`docs/pathing/instruction-map-invariants.md`) that itself isn't a key in `instructions` for the
first unbuilt step of a short chain — worth a dedicated unit test regardless of whether it's the
primary cause here.

## What this plan deliberately does not do

- Does not implement any fix. Every step above ends in "confirm/refute" or "identify," not "ship."
- Does not re-litigate the StrandedGoal/Z-axis observation from the prior investigation (assessed
  and set aside as likely benign in `docs/pathing/instruction-map-invariants.md`) — if Step 1
  confirms the primary hypothesis, that observation becomes fully explained as a side effect (zero
  active construction projects anywhere means every lookahead trace anywhere in the field
  legitimately shows "all WALK/LEAP," not just near the gap), and doesn't need independent
  investigation.
- Does not touch the auto-widening offset-magnitude gap or the widen-retry-starvation residual
  documented in the 2026-08-03 design doc's follow-ups #1/#2 — both are real but downstream of this
  bug and out of scope until stairs are actually being placed at all.

## Second real bug found and fixed (2026-08-05, same session, continued): spurious first tread

After the `tryClimb` oscillation was identified as the mechanism behind "0 stairs built," a first
attempted fix — filling the open air `SiegeInteractionHandler.constructSiegeBlock` leaves under
every `BUILD_STAIR` tread — was implemented, tested (RED→GREEN), and reported done. The user then
observed live: "the first step being built was a stair on top of a cobblestone block, when the
first stair should have been placed on the already existing ground." That single observation
disproved the fix's premise.

**Root cause:** `SiegeLineTracer.trace()` walks a fixed `(dx,dy,dz)` stride from the anchor until
`isWalkableTerrain(nextPos)` first goes true. `determineMacroAction` picks `BUILD_STAIR` for a
diagonal step whenever `pos.below()` merely isn't *blocking motion* — a shallower check than real
walkability, which also requires *that* cell's own floor (one level further down) to be solid. On
flat terrain, the tracer's descent can overshoot by exactly one tread: the last diagonal step before
natural termination gets tagged `BUILD_STAIR` even though real, already-existing ground sits one
level below where the fill would go — a cell the mob could just walk onto directly, one level lower,
with zero construction. The riser-fill fix was filling *that* gap correctly per its own logic, but
the gap itself should never have existed — hence "a stair on top of a cobblestone block" sitting one
level above the real floor.

**Fix:** reverted the riser-fill entirely (`SiegeInteractionHandler.java` back to its original
BUILD_STAIR case, `SiegeInteractionHandlerRiserFillGameTests.java` deleted — it pinned the wrong
behavior). Added `SiegeLineTracer.finishNaturalTermination`: when a trace's *entire* result is one
tread plus its terminal `WALK` (`orderedSteps.size() == 2` — i.e. nothing between the tread and the
anchor), and that tread's action is specifically `BUILD_STAIR`, and `tread.pos().below()` is already
`isWalkableTerrain`, the whole trace aborts instead of committing to the unnecessary build —
deferring entirely to the ordinary flood, which can reach that same ground for free once its
frontier gets there by normal means. Deliberately **not** generalized to longer chains (same
dy=-2-hop stranding risk as before), and deliberately **not** generalized to the other
support-triggered actions: an advisor review caught that a one-segment vertical trace
(`BUILD_PILLAR`/`BUILD_LADDER`/`BUILD_SPIRAL`, `dx=0, dz=0`) has `tread.pos().below() == anchorPos`
itself — the cell the mob is already standing on, walkable by construction. Checking that case would
have silently aborted every one-segment vertical climb, mistaking "the anchor is walkable" (always
true) for "the tread overshot real terrain" (the diagonal-only failure this fix targets). Caught
before it shipped; not covered by the existing suite, since the one test that traces `dy=+1`
(`SiegeProjectManagerTest`) terminates in a single hop (`orderedSteps.size() == 1`), never reaching
this guard.

Covered by a new fast unit test, `SiegeLineTracerTest` (calls `SiegeLineTracer.trace()` directly
against a `FakeTerrain`, no GameTest server round-trip needed) — confirmed RED against the
unmodified tracer, GREEN after the fix. Full unit suite green, no regressions.

**Live GameTest re-run after the fix:** all 4 previously-failing tests still fail, but the failure
signature changed for 3 of the 4 — `testSingleRatBuildsStaircaseAcrossSmallGap` and
`testSmallGroupBuildsStaircaseAcrossSmallGap` now report **2 stair block(s) built** (was 0);
`testLargeGroupBuildsChainedStaircaseAcrossGiantGap` also now reports 2 (was 0).
`testLargeGroupBuildsStaircaseAcrossSmallGap` (the large-group small-gap variant) still reports 0,
same as baseline, unchanged by this fix. Not investigated further — no evidence yet for why it
differs from the other three; the shared failure mode below already explains why zero rats make it
across regardless of how many stairs get built.

All 4 failures now show the SAME remaining signature: the mob is reported still at ground Y
(`y=-58`, matching before-crossing ground level) and "has not yet arrived within 3.0 blocks of the
nexus" — i.e. real stairs are getting built (where they weren't before, in 3/4 cases), but the mob
still isn't climbing across them. This matches, and does not newly explain, the still-open
`FollowFlowFieldGoal.tryClimb` oscillation bug documented above (the ballistic jump setting/clearing
`activeClimbTarget` without net Y progress) — that bug remains the last blocker for these 4 tests,
unchanged by this session's two fixes. The goal-preemption hypothesis for it (`pathingUpdateTimer`
reset via `start()` without clearing `activeClimbTarget`/`climbCyclesElapsed`) is still unverified
and is the next lead whenever this is picked back up.
