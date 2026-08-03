# Project-Scoped Clanrat Construction — Design

## Goal

Replace clanrat construction's per-block targeting/claiming model with a project-scoped one:
clanrats no longer claim an individual block to build — they register as workers on the
`SiegeProject` they're near, and the project accumulates build progress per tick proportional to
worker count (capped, with diminishing returns), placing its instructions in order as the
accumulated work crosses each one's cost. Build speed thereby scales with how many rats are
actually working a project, instead of being a fixed per-mob rate gated by one-claimant-per-block
exclusivity.

## Background

The region-based flow field (`docs/superpowers/specs/2026-07-24-region-based-flow-field-design.md`)
already decides *where* siege projects go: `SiegeProjectManager`/`SiegeLineTracer` trace obstacle
crossings into `SiegeProject`s, each holding a `Map<BlockPos, SiegeNode> instructions` describing
every block position and action needed to complete it.

*Executing* that plan is currently per-block: `AbstractSiegeConstructionGoal` (the shared skeleton
for `BuildFlowFieldGoal`, `WidenStairsGoal`, `SmartBreachGoal`) has each mob claim one specific
`BlockPos` via `RegionFlowField.tryClaimTarget` (mutual exclusion, one claimant per block), run a
fixed-duration animation (`BuildFlowFieldGoal`: 15 ticks, regardless of headcount elsewhere on the
same project), then place that one block. There is no concept of "N rats assigned to this project"
at the project level — `SiegeProject` tracks no workers and no progress, only which instructions are
or aren't yet satisfied in the world. A group of ten rats converging on the same staircase spend
most of their time waiting for a single claimable block to open up rather than genuinely
parallelizing the work.

This is also implicated in `StaircaseSiegeGroupGameTests`' still-open flakiness
(`docs/superpowers/plans/2026-08-01-flow-field-planning-gap-fix.md`,
`docs/superpowers/plans/2026-07-31-clanrat-gap-crossing-pathing-fix-plan.md`) — per-block claiming
and lookahead-snap distance tuning have been a repeated source of subtle bugs. Moving to
project-level coordination is expected to remove an entire class of that contention, though this
design's own verification (below) is what will actually confirm it.

## Scope

**In scope:**
- `SiegeProject` gains an ordered build sequence, a worker registry, an accumulated-work counter,
  and (for laterally-widenable actions) a width that grows with worker demand.
- `BuildFlowFieldGoal`, `SpiralSapperGoal`, and `DeployClimbableGoal` move from per-block claiming to
  project-worker registration.
- `WidenStairsGoal` is deleted; its intent (relieve crowding on a saturated lane) is replaced by
  project auto-widening.
- Facing for placed blocks is derived from the project's own trace geometry instead of from
  whichever mob happens to execute the placement.

**Explicitly out of scope:**
- `SmartBreachGoal` (`MINE`) and `WidenStairsGoal`'s formerly-shared `RegionFlowField.claimedTargets`
  map stay on the current per-block claiming model. `SmartBreachGoal` is demolition, not group
  construction, and gets its own pass later.
- `RegionFlowField.tryOccupyLane`/`MAX_LANE_OCCUPANTS` (physical crowding on a narrow connector lane
  during *movement*) is unrelated to this change and untouched.
- Danger/exposure costing, non-clanrat construction methods, event-driven dirty tracking — already
  out of scope per the foundational region-based flow field design, still out of scope here.

## Architecture

### `SiegeProject`: from a static instruction map to a live work site

Today `SiegeProject.instructions` is an unordered `HashMap`. `SiegeLineTracer.trace` already
computes an ordered `List<SiegeNode> orderedSteps` for each traced line (entry → exit order) but it's
discarded when `SiegeProjectManager.evaluateSingleLine` constructs the `SiegeProject`
(`new SiegeProject(result.instructions(), endPos, totalCost)` — `orderedSteps()` unused). This design
threads that ordered sequence through instead of discarding it, giving every project a deterministic
build order with no new sequencing logic required.

New state on `SiegeProject`:
- `buildOrder: List<BlockPos>` — from `orderedSteps`, entry-to-exit. Widened lanes (below) append
  their own ordered sub-sequences.
- `workers: Set<Mob>` — currently-registered workers (pruned each tick: dead, or now farther than
  `Config.projectWorkRadius` from the nearest unbuilt instruction).
- `accumulatedWork: double`.
- `width: int` (starts at 1; only meaningful for `BUILD_STAIR`/`BUILD_BRIDGE` projects).

New behavior on `SiegeProject`:
- `nextUnbuiltInstruction(terrain, evaluator)` — first entry in `buildOrder` not yet satisfied in the
  world (delegates to the existing `TerrainEvaluator.isActionCompleted`).
- `tryRegisterWorker(mob)` — succeeds only if the mob is within `Config.projectWorkRadius` of
  `nextUnbuiltInstruction()` and `workers.size() < effectiveCap()`, where `effectiveCap()` is
  `Config.maxProjectWorkers` for non-widenable actions, or `width * Config.workersPerWidenStep` for
  `BUILD_STAIR`/`BUILD_BRIDGE`. On rejection for a widenable project, triggers a widen attempt (see
  below); if it succeeds, the caller's registration is retried once against the new, larger cap.
- `unregisterWorker(mob)`.
- `tick(level, evaluator)` — prunes `workers`, adds `min(workers.size(), effectiveCap()) *
  Config.workPerRatPerTick` to `accumulatedWork`, then, while `accumulatedWork` covers the cost of
  `nextUnbuiltInstruction()`, places it via the existing `SiegeInteractionHandler.constructSiegeBlock`
  and subtracts its cost — looping in case one tick's contribution covers more than one cheap
  instruction (e.g. a large group finishing the last few, already-cheap steps of a project at once).

**Facing** is computed from `buildOrder` adjacency (the direction from a step's predecessor to
itself, both already known from the trace) rather than from any specific mob's position. This
removes `BuildFlowFieldGoal.computeApproachFacing`'s dependency on "which mob happened to execute
this," which no longer makes sense once placement isn't tied to one mob's own action timer — and
is deterministic where the old approach was incidental.

### Auto-widening (replaces `WidenStairsGoal`)

`WidenStairsGoal` never reliably worked and is deleted outright, along with its two dedicated
`PathingGoalRecalculationGameTests` cases and its entry in `ClanratEntity.registerGoals()`. Its
physical constraints (solid support below, two blocks of headroom above the new lane) carry over
into the new mechanic; its buggy crowd-detection trigger does not.

When a `BUILD_STAIR`/`BUILD_BRIDGE` project rejects a registration because it's at
`effectiveCap()`, it attempts to trace one additional parallel lane: `SiegeLineTracer.trace` reused
with the project's original direction vector, anchored one step further along the perpendicular
horizontal axis than the current widest lane, alternating sides (left, right, left, ...) across
successive widens so the structure grows outward on both sides rather than drifting. A successful
trace's instructions are appended into `buildOrder` and `width` increments (raising `effectiveCap()`
immediately, so the rat that triggered the widen — and others waiting — can now register). A failed
trace (terrain doesn't support it, out of bounds, too much mining) simply leaves `width` unchanged;
the rejected rat waits and no retry is attempted again until the next rejected registration, bounding
retry frequency to actual demand rather than polling every tick.

`width` is capped at `Config.maxProjectWidth` (default 4): once reached, `effectiveCap()` stops
growing and additional rats simply wait, rather than a horde being able to widen a staircase without
bound.

### Goal-side changes

A new sibling base, `AbstractSiegeProjectGoal`, sits alongside the existing
`AbstractSiegeConstructionGoal` (which `WidenStairsGoal` no longer needs, and which `SmartBreachGoal`
keeps using unchanged). It reuses the same hardened plumbing — mid-leap guard, look-at/swing
animation, region-dirty-on-completion debounce (`onChainComplete`) — but replaces the claim/execute
core:

- `canUse()`: uses the existing `findEffectiveNode()` to spot a nearby unbuilt build node (unchanged
  — this is how a mob notices there's relevant work nearby at all), resolves the owning project via
  a new `RegionFlowField.findProjectFor(pos)` (thin delegate to a new
  `SiegeProjectManager.findProjectContaining(pos)`), and calls `project.tryRegisterWorker(mob)`.
- `tick()`: no fixed action-duration countdown driving this mob's own placement. The mob keeps
  station (approach + periodic swing animation) near the project for as long as it's registered and
  the project remains incomplete. Placement happens inside `SiegeProject.tick()`, independent of any
  one goal instance.
- `stop()`: `project.unregisterWorker(mob)` instead of `flowField.releaseTarget(pos)`.

`BuildFlowFieldGoal`, `SpiralSapperGoal`, and `DeployClimbableGoal` move onto this base. Their
specialized placement math (spiral rotation, ladder wall-finding) is unchanged — it's still what
`SiegeInteractionHandler` calls to actually place a given instruction type — only the goal-level
claiming/timing wrapper around it changes. `SmartBreachGoal` and `WidenStairsGoal`'s prior use of
`AbstractSiegeConstructionGoal` is otherwise untouched (`WidenStairsGoal` is deleted, not migrated).

`AwaitFormationGoal` currently calls `peekClaimedTarget()` on sibling goals to detect "this block is
already spoken for, wait your turn." For project-scoped goals, "spoken for" becomes "the project is
at `effectiveCap()`" — `AbstractSiegeProjectGoal` gets an equivalent peek so formation-waiting
behavior doesn't silently regress.

### Config additions

Following the existing `Config.buildingBasePenalty`-style tunables:
- `projectWorkRadius` (default ~3.5 blocks — larger than the existing 2.5-block
  `MAX_TARGET_CLAIM_DISTANCE` tolerance, since this is "near the project," not "adjacent to my exact
  claimed block").
- `maxProjectWorkers` (flat cap for non-widenable project types).
- `workersPerWidenStep` (default 10 — also serves as the per-lane worker cap for widenable types).
- `workPerRatPerTick` (tuned so a single rat working alone reproduces roughly today's ~15-tick-per-
  block pace — no regression for solo building).
- `maxProjectWidth` (default 4).

## Data Flow

1. `SiegeProjectManager` discovers/promotes a `SiegeProject` exactly as today, now also capturing its
   `orderedSteps` as `buildOrder`.
2. Each tick, every clanrat running a project-scoped goal calls `findEffectiveNode()` →
   `findProjectFor(pos)` → `tryRegisterWorker(mob)`. Successful registrants keep station near the
   project; a mob whose registration is rejected either waits (cap reached, widen failed/maxed) or
   benefits from an immediately-successful widen.
3. Each tick, every active project runs `tick()`: prune, accumulate, place completed instructions in
   `buildOrder` order. A project with no registered workers simply accumulates zero work that tick —
   whether the implementation skips zero-worker projects outright or runs them as a no-op is a
   plan-level optimization detail, not a behavioral difference.
4. On project completion, existing behavior is preserved: `onChainComplete`-style region-dirty
   debouncing fires, remaining registered workers naturally fail their next `canUse()` (no unbuilt
   instruction left) and unregister via `stop()`.

## Testing

- **`SiegeConstructionActionsGameTests`** hand-injects raw flow-field instructions per lane,
  bypassing `SiegeProjectManager`. In the real system every `BUILD_*` instruction always belongs to a
  `SiegeProject` — only `SiegeProjectManager` ever writes them — so this test is updated to also
  construct a real, minimal `SiegeProject` per lane rather than adding a "no owning project" fallback
  path to production code for a case that can't occur in production.
- **`PathingGoalRecalculationGameTests.testBuildFlowFieldGoalCompletesMultiStepMacroChainWithNoPriorSupport`**
  is reworked around project completion instead of per-goal claim state.
  `testMaxCandidateProjectLengthCaps*` (search-time region budget, unrelated to this change) should
  keep passing unmodified — a useful sanity check that this overhaul doesn't touch project discovery.
- **`testWidenStairsGoalMarksRegionDirty`/`testWidenStairsGoalDoesNotPlaceFloatingStairWhenSupportRemovedMidAction`**
  are deleted with the goal they tested; replaced with tests on the new mechanic: a project widens
  when registration is rejected at cap, and does *not* widen (nor place a floating stair) when the
  parallel lane's terrain can't support it — preserving the old test's safety-net intent at the
  project level.
- **`StaircaseSiegeGroupGameTests`** currently only asserts eventual completion within a generous
  timeout per group size. Since "speed scales with headcount" is the behavior actually being added,
  add an explicit assertion that the large-group test completes in meaningfully fewer ticks than the
  single-rat test — otherwise nothing directly proves scaling works, only that things still
  eventually finish. This suite is also the fix's verification target for the previously-open
  `testSingleRatBuildsStaircaseAcrossSmallGap` flakiness (see Background) — confirmed via repeated
  runs, not a single green run, consistent with that bug's known non-determinism.
- **New plain-JUnit tests** (matching `FlowFieldCalculatorTest`'s existing pattern for pure logic)
  cover `SiegeProject`'s registration/cap/width-threshold arithmetic and accumulated-work →
  placement-threshold advancement in isolation, without needing a real Minecraft world — only the
  actual block-placement call requires a GameTest.

## Known risks

- Fixing `testSingleRatBuildsStaircaseAcrossSmallGap` is an explicit goal of this overhaul (per
  discussion), but its prior investigation found genuinely non-deterministic symptoms with no single
  confirmed cause. If this overhaul doesn't resolve it, that's a real finding to report, not a
  reason to force a claim of success — verify via repeated runs as described above.
- Auto-widening reuses `SiegeLineTracer` for parallel-lane discovery, which was designed for
  project-manager-driven tracing, not goal-triggered on-demand tracing. Trace cost/frequency should
  be checked in practice (triggered only on rejected registrations, not per-tick, to bound this) but
  is worth watching if projects with heavy churn (rats registering/unregistering rapidly near a full
  cap) cause more retracing than expected.
- `maxProjectWidth` (4) and `workersPerWidenStep` (10) are judgment-call defaults, not derived from
  any in-game balance testing — reasonable starting points, easy to retune via `Config` once played.
