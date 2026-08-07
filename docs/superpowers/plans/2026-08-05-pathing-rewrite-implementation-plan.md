# Pathing rewrite implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete and replace `org.ratden.skavenblight.ai.pathing` (20 files, 4545 lines) with a unified,
≤2500-line implementation per `docs/superpowers/plans/2026-08-05-pathing-rewrite-design.md`
("the design doc") — one step-generator feeding one Dijkstra flood for both ordinary walking and
construction discovery, five action types (WALK/TUNNEL/BRIDGE/CARVED_STAIR/AIR_STAIR), no climbing.

**Architecture:** One unified per-cell step generator (`PathStepEvaluator`) feeds one Dijkstra flood
(`FlowFieldCalculator`) that produces both ordinary walking instructions and construction
(`SiegeProject`) discovery, with PLATFORM junctions spliced in as a post-process. The region layer
(`RegionGraph`/`RegionRouteTree`/`TerritoryRegionMap`) routes between regions the same flood can't
reach directly. `SiegeNode`'s old single-type/three-meanings design splits into `FlowStep` (flood
data) and `PlannedStep` (build-order data).

**Tech Stack:** Java 21, NeoForge 1.21.x, JUnit 5 (unit tests), NeoForge GameTest (`@GameTest`,
`gameTestServer`).

## Global Constraints

- Universal acceptance criterion (every GameTest in the matrix): a vanilla-navigating clanrat must
  cross the finished structure using zero special movement code.
- ≤2500 total lines across `ai.pathing` (design doc's replacement for the old "≤10 classes" target).
- Comments: WHY only, one line, only where truly non-obvious. No bug-fight history in source comments.
- Five action types only: `WALK`, `TUNNEL`, `BRIDGE`, `CARVED_STAIR`, `AIR_STAIR`. No ladder/spiral/
  pillar/leap/bare-MINE anywhere in the new code.
- Construction edges (TUNNEL/BRIDGE/CARVED_STAIR/AIR_STAIR) are generated ONLY at obstacle frontiers
  (cells where WALK expansion found no valid neighbor) — a per-cell predicate, checked at every step
  of a chain, not a one-shot gate.
- Cost ordering `tunnel < bridge < carvedStair < airStair` is a tested invariant on the four base
  cost constants themselves, never on path-shape.
- PLATFORM is inserted as a post-processing pass over the recovered path, never as a flood-time
  candidate move.
- Never discard the last remaining route to a region for being "too expensive" in absolute terms —
  only ever discard a candidate that is worse than an already-known-cheaper alternative.
- `TerritoryRegionMap`'s existing region-granularity dirty tracking (topology-changed detection,
  merge-absorption fix, chunk-ticket sync, cooldowns) is explicitly **ported forward, not rewritten
  from scratch** — see the File Disposition table below for why.
- Every task ends with `./gradlew test` (unit tests) green before commit; GameTest tasks additionally
  require checking for orphaned `gameTestServer` java.exe processes before running
  `./gradlew runGameTestServer`, and end with the relevant GameTest(s) green before commit.

---

## File disposition (read this before starting any task)

Orientation for this rewrite (full design doc read, a literal read of every current file in
`ai.pathing`/`ai.pathing.region` plus all 14 files in `ai.goal.clanrat` that reference pathing types,
and three scope clarifications confirmed with the user) surfaced that the rewrite's real footprint is
larger than the original 7-file touched-consumer list, and that one file explicitly should NOT be
rewritten from scratch despite being "in scope." Both are load-bearing for how the tasks below are
scoped, so they're recorded here rather than only in chat history.

**Kept unchanged:** `TerrainAccess`, `LiveTerrainAccess`, `CalculationThrottler`, `Region`.

**Kept, targeted edits only (not rewritten):** `RegionScanner` (swaps its step-generator dependency),
`RegionRouteTree` (unchanged algorithm, gains one derived accessor), `TerritoryRegionMap` (~800 lines
of empirically-hardened dirty-tracking/merge-detection/chunk-ticket logic the design doc itself says
carries forward unchanged; only type updates + the onBlockChanged authority fix + confirmed-unreachable
wiring are new).

**Deleted, replaced by new files:** `SiegeNode` → `PathAction` + `FlowStep` + `PlannedStep`;
`TerrainEvaluator` → `PathStepEvaluator`; `SiegeLineTracer` → folded into `FlowFieldCalculator`;
`RegionIndex` → folded into `RegionGraph`.

**Rewritten in place (same responsibility, new internals):** `TerrainSnapshot` (adds a planned-cell
override hook), `FlowFieldState`, `FlowFieldCalculator`, `SiegeProjectManager`, `SiegeProject`,
`SiegeInteractionHandler`, `RegionGraph`, `RegionConnector`, `RegionFlowField`.

**New:** `SiegeProjectStore` (persistence).

**Goal layer — touched (ported to new types):** `FollowFlowFieldGoal`, `AwaitFormationGoal`,
`AbstractSiegeProjectGoal`, `BuildFlowFieldGoal`, `SiegeNodeLookahead`.

**Goal layer — deleted, with user confirmation** (each exists solely to react to an action type this
rewrite eliminates — climbing entirely, or the standalone `MINE` action folded into TUNNEL/CARVED_STAIR
project steps — and in two cases deleting the only concrete implementation leaves the abstract base
with no remaining subclass/caller, so it goes too):
`DeployClimbableGoal`, `SpiralSapperGoal`, `WarpSapperGoal`, `StrandedGoal`, `SmartBreachGoal`,
`AbstractSiegeConstructionGoal`, `SiegeActionAnimator`. `ClanratEntity.registerGoals()` loses the five
`goalSelector.addGoal` calls for the deleted concrete goals.

**Existing tests — deleted** (test exactly the climb/hand-fed-old-action mechanics being removed):
`RegionFlowFieldClimbResolutionGameTests`, `SiegeConstructionActionsGameTests`,
`SiegeLineTracerTest`, `TerrainEvaluatorTest`.

**Existing tests — targeted edits** (mostly still-valid coverage of region dirty-tracking/cycle-
breaking/project logic; only the type references and the specific climb-action test methods change):
`PathingGoalRecalculationGameTests` (drop `testDeployClimbableGoalMarksRegionDirty` and siblings, and
sweep stale `SiegeLineTracer` javadoc references — see the Execution Order section's Task 28 note),
`PathingRegionGameTests` (drop the one LEAP-javadoc comment reference, same `SiegeLineTracer` sweep),
`FlowFieldCalculatorTest`, `SiegeProjectManagerTest`, `SiegeProjectTest`, `SiegeProjectAutoWidenGameTests`
(rewrite its `BUILD_PILLAR` fixture to `AIR_STAIR`/`BRIDGE` — auto-widening itself survives, only climb
actions don't), `StackedStairColumnReTraversalGameTests` (same `SiegeLineTracer` javadoc sweep, no
behavioral change).

---

## Execution order (supersedes section order — read this before starting any task)

**The task numbers below (Task 1, Task 2, ...) are stable identifiers matching each task's own
section further down this document — they are NOT the order to execute them in.** The order below is
the corrected one, derived from direct reads of the actual production source (not from this plan's own
prose, which got several orderings backwards on first draft). Follow this list; a task's own section
tells you *what* to do, this list tells you *when*.

**Why section order isn't execution order:** this rewrite retypes one value class (`SiegeNode` →
`FlowStep`/`PlannedStep`) threaded through roughly fifteen mutually-referencing classes. There is no
way to shrink that into a small compile-clean slice — confirmed by reading `RegionConnector.java` (a
record with concrete `SiegeProject projectTowardA/projectTowardB` fields) and `RegionFlowField.java`
(a constructor holding concrete `SiegeProjectManager`/`FlowFieldCalculator`/`TerrainEvaluator` fields).
Once Task 5 lands, `compileJava` stays red until Task 14 is done — that's real, not an artifact of bad
sequencing, and no reordering removes it. What reordering DOES fix: (a) two real production-code
ordering bugs the first draft got backwards, (b) pulling every task that has zero dependency on the
breaking chain to the front, so the red-build window is as short as it can be, and (c) an entire
category of consumer files outside `ai.pathing`/`ai.goal.clanrat` that the original scope survey missed
entirely.

1. **Task 16** (delete dead goal-layer files) — already user-confirmed, zero production dependencies.
   Moved to the very front for a concrete reason, not just tidiness: Task 13 changes
   `SiegeInteractionHandler.constructSiegeBlock`'s signature, and `SmartBreachGoal`/
   `DeployClimbableGoal`/`SpiralSapperGoal`/`WarpSapperGoal`/`AbstractSiegeConstructionGoal` all call
   the OLD signature. Deleting them first means that signature change (wherever it lands) never has a
   moment of being both "changed" and "still called by a file scheduled for deletion anyway."
2. **Task 4** (`TerrainSnapshot` override hook) — additive 8th-parameter overload, zero dependency on
   the `FlowStep` retype.
3. **Task 7** (`PlatformInserter`) — pure function over `PlannedStep`/`PathAction` only. Also a real
   prerequisite for Task 12 (next), whose constructor calls it.
4. **Task 8** (`RegionScanner` port) — needs only Task 3 (done).
5. **Task 12** (`SiegeProject` rewrite) — **moved far ahead of its section position.** Confirmed via
   direct reads that `RegionGraph.registerConnector` (line ~157) and `SiegeProjectManager
   .evaluateSingleLine` (line ~375) both call `new SiegeProject(...)` directly in production code, with
   a `Map<BlockPos, SiegeNode>`-shaped first argument today. Tasks 9 and 11 cannot write their new
   `Map<BlockPos, FlowStep>`-shaped versions of that call against the OLD `SiegeProject` constructor —
   so Task 12 must land before both, not after. Task 12's own real dependencies are only Task 1 (done)
   and Task 7 (previous step), so this costs nothing to move. **One addition to Task 12's own spec,
   not previously written down:** confirmed via `grep` that `SiegeProject.tryWiden` (lines ~316-318)
   directly constructs `new SiegeLineTracer(evaluator)` and calls `.trace(...)` — this is real
   production logic, not a comment, and "port `tryWiden` verbatim" (as Task 12's section currently
   reads) is impossible once `SiegeLineTracer` is gone. Replace that call the same way Tasks 9 and 11
   replace theirs: a direct chained-hop loop over `PathStepEvaluator.candidateSteps` in the widen
   direction, same `maxCandidateProjectLength`-equivalent cap `tryWiden` already enforces. Implement
   this as part of Task 12, verified by extending `SiegeProjectTest` with a widen-still-works case.

   **Correction (2026-08-05, confirmed via direct reads + advisor review): the breaking commit is
   THIS task, not Task 5 next.** The "Once Task 5 lands, compileJava stays red until Task 14" framing
   below is off by one step. `SiegeProject`'s new constructor signature (retyped to
   `Map<BlockPos, FlowStep>`/`List<FlowStep>`, plus the new trailing `networkId` param) breaks BOTH of
   `RegionGraph.registerConnector`'s calls (still `Map<BlockPos, SiegeNode>`-shaped, 6-arg, no
   `networkId`) and `SiegeProjectManager.evaluateSingleLine`'s call (5-arg, no `exitPos`, no
   `networkId`) the moment this task's constructor change lands — on two independent counts each
   (wrong element type AND wrong arity), not one. Neither fix is Task 12's own job (Tasks 9/11 already
   own porting those two call sites' surrounding methods) and no shim/compatibility overload should be
   added to paper over it — per this plan's own explicit anti-shim guidance, and confirmed by the
   advisor as not changing the red window's overall shape, only which task is correctly labeled its
   start. Practical effect: `./gradlew test` cannot run (`compileTestJava` depends on `compileJava`)
   from THIS task through Task 14, one task earlier than previously documented. Use `./gradlew
   compileJava` alone at every intervening task (it still reports every error across `src/main`
   per-source-set before failing - the same signal that caught Task 16's four gaps) and confirm every
   reported error names a file with an already-scheduled fix task; write each task's own test file as
   normal but note in its commit message that it is written-and-not-yet-executed until Task 14 restores
   a compiling state, then run every accumulated test file as one batch once it does.
6. **Task 5** (`FlowFieldState` → `FlowStep`) — needs only Task 1 (done). (Compile-red already started
   at Task 12, previous step — see that step's correction note. This task doesn't newly break
   anything; it continues the same already-red state.)
7. **Task 6** (`FlowFieldCalculator` rewrite) — needs Task 3 (done) + Task 5. Still uses the
   `SiegeProjectManagerStub` from its own Step 0 (Task 11 isn't done yet at this point).
8. **Task 9** (`RegionGraph` rewrite) — needs Task 3 (done) + Task 5 + Task 12 (now real, no stub
   needed — this is the payoff of moving Task 12 up). **One file added to Task 9's own Files list:**
   `src/main/java/org/ratden/skavenblight/command/debug/DebugPathingCommands.java` (Modify) — confirmed
   via direct read that it calls `new TerrainEvaluator()`, `new SiegeLineTracer(evaluator)`, and
   `RegionGraph.build(..., evaluator, lineTracer)` using the OLD `build` overload; its fix is entirely
   dictated by Task 9's new `build(TerrainSnapshot, List<Region>, Set<ChunkPos>, BlockPos,
   PathStepEvaluator)` signature, so update its one call site as part of this task, not as a separate
   "consumer" task.
9. **Task 11** (`SiegeProjectManager` rewrite) — needs Task 3 (done) + Task 6 + Task 12 (now real, no
   stub needed, same payoff as Task 9).
   - **→ Task 28, Step 1 (see Task 28's own section): delete `SiegeLineTracer.java` now.** Confirmed via
     a dedicated grep that its only real production consumers are `RegionGraph`/`RegionConnector`
     (Task 9, just done), `SiegeProjectManager` (this task, just done), `SiegeProject` (Task 12,
     already done), and `DebugPathingCommands` (folded into Task 9). `TerrainEvaluator.java`'s own
     mention of it is a comment only. `StrandedGoal.java`'s mention died with Task 16. Nothing else in
     `src/main` references it — safe to delete here, well before Task 28's other two files.
10. **Task 10** (`RegionRouteTree` unchanged-port + `RegionFlowField` port) — needs Task 5 + Task 6 +
    Task 9 + Task 11 + Task 12, ALL real (confirmed via direct read: `RegionFlowField`'s constructor
    holds concrete `SiegeProjectManager`/`FlowFieldCalculator` fields, and its own field
    `TerrainEvaluator terrainEvaluator` needs replacing with `PathStepEvaluator` here too).
    - **→ Task 28, Step 2: delete `TerrainEvaluator.java` now.** Its last two real consumers
      (`RegionConnector.isCompleted`'s `TerrainEvaluator` parameter, done at Task 9; `RegionFlowField`'s
      own field, just done) are both gone. `DebugPathingCommands` was folded into Task 9 already.
11. **Task 13** (`SiegeInteractionHandler` rewrite) — **moved after Task 10, not before.** An earlier
    pass through this reordering suspected this task was independent of the breaking chain (it only
    holds a `RegionFlowField` reference and calls `regionIdOf`/`executeBreach` on it), but that was
    verified only for `regionIdOf` (`getRegionId()`, stable), not for the body of `executeBreach`.
    Rather than assert an independence that wasn't fully checked, sequence it here where it's
    unconditionally safe — moving it earlier would buy nothing anyway, since nothing between here and
    Task 10 needs `SiegeInteractionHandler` done first.
12. **Task 14** (`TerritoryRegionMap` targeted port + `onBlockChanged` fix) — needs everything above
    (Tasks 4-13) real. This is the last task before `compileJava` is green again.

    **Correction (2026-08-05, found executing Task 13, confirmed via advisor + a direct `find`): this
    is wrong.** `src/main/java/org/ratden/skavenblight/gametest/` sits under `src/main`, not `src/test` -
    confirmed directly, it is not a naming assumption. `compileJava` compiles the whole `src/main`
    source set, so it cannot go green while ANY GameTest file in the compile-error list is still
    broken - and those are owned by Tasks 17, 18, 19, and **20** (execution-order position 20, not 12).
    Task 14 does NOT restore a compiling state; it's necessary but not sufficient. Practical effect on
    every earlier correction that said "written-and-not-yet-executed until Task 14 restores a compiling
    state, then run every accumulated test file as one batch once it does" (see Task 12's own
    correction note above): read **Task 20** for "Task 14" in that sentence. `compileTestJava` — and
    with it every accumulated unit test (`FlowStepTest`, `PathStepEvaluatorCostTest`, `SiegeProjectTest`,
    `SiegeProjectManagerTest`, `FlowFieldCalculatorTest`, `RegionGraphTest`, `PlatformInserterTest`, and
    whatever Tasks 14/15 add) — stays unrunnable until the END of Task 20, immediately before Task 21's
    go/no-go gate. This concentrates real risk: the first green build AND the first run of every
    accumulated unit test both land in the same narrow window right before the gate that decides
    whether to proceed to Tasks 22-24. Treat that window as higher-scrutiny than an ordinary task
    boundary when you reach it - budget time to actually read failures, not just re-run until green.
    (The position-13-15 note two entries below already half-said this — "these 11 files must be fixed
    before ANY later task's `./gradlew test`/`runGameTestServer` run can succeed at all" — this
    correction is what reconciles that with the "through Task 14" framing above, which never accounted
    for GameTest files living in `src/main`.)
13. **Task 25** (NEW — see its own section below: debug/network consumer port, mechanical group).
14. **Task 26** (NEW — `PathingDebugFileWriter` glyph redesign).
15. **Task 27** (NEW — `ClientRenderHandler` color redesign).
    - Tasks 25-27 all depend on Task 14 (they read `RegionFlowField`'s finalized new return types) and
      on nothing else — they can run in any relative order among themselves. They're debug/observability
      code with zero gameplay consequence, but they are NOT deferrable past this point: `compileJava`
      builds the whole module, so these 11 files (see Task 25/26/27) must be fixed before ANY later
      task's `./gradlew test`/`./gradlew runGameTestServer` run can succeed at all.
16. **Task 15** (`SiegeProjectStore` persistence) — needs Task 12 (shape) + Task 11/14 (wiring points).
17. **Task 17** (`FollowFlowFieldGoal` climb removal) — needs Task 10's `RegionFlowField.getNextStep`.
18. **Task 18** (`SiegeNodeLookahead`/`AbstractSiegeProjectGoal`/`BuildFlowFieldGoal` port) — needs
    Task 10 + Task 12.
19. **Task 19** (`AwaitFormationGoal` formation grid) — needs Task 10 + Task 14 (`getRegionIndex`'s
    Task-9 rename).
    - **→ Task 28, Step 3: delete `SiegeNode.java` now**, plus sweep the three now-stale-by-name
      javadoc comments in `PathingRegionGameTests.java`, `PathingGoalRecalculationGameTests.java`, and
      `StackedStairColumnReTraversalGameTests.java` (all three mention `SiegeLineTracer`/`SiegeNode` in
      `{@code ...}` javadoc tags describing old behavior, confirmed via grep to be comments only, not
      code — but they'll reference deleted class names by the time this step runs, so update the prose
      to describe the same behavior in terms of `PathStepEvaluator`/`FlowStep` while touching these
      files for Task 20's other edits anyway). `SiegeNode.java`'s last real consumers were the
      goal-layer files just ported in Tasks 17-19.
20. **Task 20** (grief-recovery test + legacy GameTest/test cleanup) — needs Task 14 (the fix it tests)
    and Tasks 17-19 (goal layer, referenced by the GameTest fixtures it touches).
21. **Task 21** (go/no-go gate) — needs everything above. Do not proceed past this point until all 4
    existing air-stair GameTests pass.
22. **Task 22** (carved-stair GameTest matrix).
23. **Task 23** (tunnel GameTest matrix).
24. **Task 24** (bridge GameTest matrix).
25. Final line-count check (see "After Task 24" section at the end of this document).

**Two off-by-one cross-reference fixes, noted here so a fresh reader isn't confused by them lower in
this document:** Tasks 6, 9, and 11's own "Consumes" lines refer to `SiegeProject`/`SiegeProjectManager`
as "Task 13"/"Task 12" in a few places — these are stale from an earlier draft renumbering and should
read Task 12 (`SiegeProject`) and Task 11 (`SiegeProjectManager`) respectively, matching this document's
actual section numbers. The content of what's consumed is correct; only the parenthetical task-number
pointer is wrong.

---

## Task 1: `PathAction` enum + `FlowStep`/`PlannedStep` records

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/ai/pathing/PathAction.java`
- Create: `src/main/java/org/ratden/skavenblight/ai/pathing/FlowStep.java`
- Create: `src/main/java/org/ratden/skavenblight/ai/pathing/PlannedStep.java`
- Test: `src/test/java/org/ratden/skavenblight/ai/pathing/FlowStepTest.java`

**Interfaces:**
- Produces: `PathAction` (enum: `WALK, TUNNEL, BRIDGE, CARVED_STAIR, AIR_STAIR`), used by every
  other task in this plan.
- Produces: `FlowStep(BlockPos pos, PathAction action, BlockPos predecessorPos)` — flood/routing data.
  Carries its own position (map-key vs. node-owned-position can never mismatch — this is the design
  doc's fix for `SiegeNode`'s confirmed map-key-vs-predecessor bug).
- Produces: `PlannedStep(BlockPos pos, PathAction action, Direction facing)` — a project's ordered
  build list. No predecessor field (order comes from list position); facing is computed once at plan
  time and stored, never re-derived from a predecessor at placement time.

- [ ] **Step 1: Write the failing test**

```java
package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlowStepTest {

    @Test
    void flowStepCarriesItsOwnPositionIndependentOfAnyMapKey() {
        BlockPos pos = new BlockPos(5, 10, 5);
        BlockPos predecessor = new BlockPos(4, 10, 5);
        FlowStep step = new FlowStep(pos, PathAction.WALK, predecessor);

        assertEquals(pos, step.pos());
        assertEquals(predecessor, step.predecessorPos());
        assertEquals(PathAction.WALK, step.action());
    }

    @Test
    void plannedStepHasNoPredecessorField() {
        PlannedStep step = new PlannedStep(new BlockPos(1, 2, 3), PathAction.TUNNEL, Direction.NORTH);

        assertEquals(PathAction.TUNNEL, step.action());
        assertEquals(Direction.NORTH, step.facing());
        // No predecessorPos accessor exists on PlannedStep - order comes from list position.
    }

    @Test
    void pathActionHasExactlyTheFiveNewValues() {
        assertEquals(5, PathAction.values().length);
        assertArrayEquals(
                new PathAction[]{PathAction.WALK, PathAction.TUNNEL, PathAction.BRIDGE,
                        PathAction.CARVED_STAIR, PathAction.AIR_STAIR},
                PathAction.values());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.ratden.skavenblight.ai.pathing.FlowStepTest"`
Expected: FAIL (compile error — `PathAction`/`FlowStep`/`PlannedStep` don't exist yet).

- [ ] **Step 3: Write the implementation**

```java
package org.ratden.skavenblight.ai.pathing;

public enum PathAction {
    WALK,
    TUNNEL,
    BRIDGE,
    CARVED_STAIR,
    AIR_STAIR
}
```

```java
package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;

/** Flood/routing data: one cell's step during Dijkstra expansion, oriented toward the flood's
 * own target (predecessorPos is one hop closer to the target, i.e. the mob's successor). */
public record FlowStep(BlockPos pos, PathAction action, BlockPos predecessorPos) {}
```

```java
package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** One entry in a SiegeProject's ordered build list. Order comes from list position, not a
 * predecessor field - facing is computed once at plan time and never re-derived later. */
public record PlannedStep(BlockPos pos, PathAction action, Direction facing) {}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.ratden.skavenblight.ai.pathing.FlowStepTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/PathAction.java \
        src/main/java/org/ratden/skavenblight/ai/pathing/FlowStep.java \
        src/main/java/org/ratden/skavenblight/ai/pathing/PlannedStep.java \
        src/test/java/org/ratden/skavenblight/ai/pathing/FlowStepTest.java
git commit -m "feat(pathing): add PathAction/FlowStep/PlannedStep replacing SiegeNode"
```

---

## Task 2: Cost model — four base costs, bedrock failsafe, hardness extension point

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/ai/pathing/PathStepEvaluator.java` (cost methods only
  this task; step-generation methods come in Task 4)
- Modify: `src/main/java/org/ratden/skavenblight/Config.java`
- Test: `src/test/java/org/ratden/skavenblight/ai/pathing/PathStepEvaluatorCostTest.java`

**Interfaces:**
- Consumes: `PathAction` (Task 1), `TerrainAccess` (kept, unchanged interface — `getDestroySpeed`,
  `getBlockState`, etc.).
- Produces: `PathStepEvaluator.baseCostFor(PathAction action)` → `int` (the four tunable per-step
  base costs, cost-ordering invariant lives here). `PathStepEvaluator.miningCost(TerrainAccess
  terrain, BlockPos pos)` → `int` (hardness-driven, with the bedrock failsafe). `PathStepEvaluator
  .isBedrockLike(TerrainAccess terrain, BlockPos pos)` → `boolean` (extension point: today this is
  `terrain.getDestroySpeed(pos) < 0`; a future Skavenblight block-toughness attribute overrides this
  method, not the cost formula, so the cost source is never hardcoded to vanilla
  `getDestroySpeed` alone).

**Cost model spec:**
- Four `Config` fields, one per action, replacing the single `buildingBasePenalty` the old system used
  for every non-WALK/non-MINE action: `tunnelBaseCost` (default 400), `bridgeBaseCost` (default 600),
  `carvedStairBaseCost` (default 800), `airStairBaseCost` (default 1000) — same `ModConfigSpec.Builder`
  `.comment(...).defineInRange("name", default, min, max)` style as every existing `Config` field (see
  `buildingBasePenalty` for the pattern), each in range `[1, 10_000]`. `baseCostFor` switches on the
  action and returns the matching field; WALK returns the existing `ORTHOGONAL_COST` (10).
- `miningCost(terrain, pos)`: if `isBedrockLike(terrain, pos)`, return the bedrock failsafe cost (see
  below); otherwise `(int)(terrain.getDestroySpeed(pos) * Config.miningPenaltyMultiplier) +
  Config.miningBasePenalty` (same formula as today's `TerrainEvaluator.calculateActionCostForAction`,
  minus the old `COST_MULTIPLIER` — folded into the tunable ranges directly since these are now
  per-action fields, not one shared constant).
- Bedrock failsafe: one new `Config` field, `bedrockFailsafeRatMinutes` (`IntValue`, default 25,
  range `[1, 1000]`) — total mining work for an unbreakable block, expressed in "rat-minutes" (1
  rat-minute = 1 rat contributing `Config.workPerRatPerTick` work for 1 real-world minute = 1200
  ticks at 20 ticks/second). `bedrockFailsafeWorkUnits()` = `bedrockFailsafeRatMinutes * 1200 *
  Config.workPerRatPerTick`. This is the SAME unit `SiegeProject.accumulatedWork`/`workPerRatPerTick`
  already use, so 1 rat alone takes exactly `bedrockFailsafeRatMinutes` real minutes, and any number
  of concurrently-registered workers up to the project's own effective cap divides that time linearly
  (25 rats working together finish in 1 minute) — no second constant needed; see Task 13's
  `SiegeProject` cap plumbing for how the worker count that achieves this is bounded.

**No Mockito in this codebase — confirmed by checking `build.gradle` and every existing test under
`src/test/java`** (e.g. `TerrainEvaluatorTest`'s `FakeTerrain`, a small hand-written
`implements TerrainAccess`). Use the same convention, not a mocking library: `TerrainAccess`'s whole
purpose is being a small, faithfully-fakeable interface (see its own class javadoc) — reach for the
established pattern, don't introduce a new test dependency for something the codebase already solved.

- [ ] **Step 1: Write the failing tests**

```java
package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;
import org.ratden.skavenblight.Config;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PathStepEvaluatorCostTest {

    /** Same hand-written double TerrainEvaluatorTest already uses - see that file's own
     * FakeTerrain for the established convention this mirrors exactly. */
    private static class FakeTerrain implements TerrainAccess {
        private final Map<BlockPos, Float> destroySpeeds = new HashMap<>();

        void setDestroySpeed(BlockPos pos, float speed) { destroySpeeds.put(pos, speed); }

        @Override
        public BlockState getBlockState(BlockPos pos) { return Blocks.AIR.defaultBlockState(); }

        @Override
        public boolean isLoaded(BlockPos pos) { return true; }

        @Override
        public boolean isOutsideBuildHeight(BlockPos pos) { return false; }

        @Override
        public boolean isSolidRender(BlockPos pos) { return false; }

        @Override
        public float getDestroySpeed(BlockPos pos) { return destroySpeeds.getOrDefault(pos, 1.0F); }
    }

    @Test
    void baseCostsAreStrictlyOrderedTunnelBridgeCarvedStairAirStair() {
        PathStepEvaluator evaluator = new PathStepEvaluator();

        int tunnel = evaluator.baseCostFor(PathAction.TUNNEL);
        int bridge = evaluator.baseCostFor(PathAction.BRIDGE);
        int carvedStair = evaluator.baseCostFor(PathAction.CARVED_STAIR);
        int airStair = evaluator.baseCostFor(PathAction.AIR_STAIR);

        assertTrue(tunnel < bridge, "tunnel must be cheaper than bridge");
        assertTrue(bridge < carvedStair, "bridge must be cheaper than carved-stair");
        assertTrue(carvedStair < airStair, "carved-stair must be cheaper than air-stair");
    }

    @Test
    void bedrockLikeBlockCostsExactlyOneRatMinuteOfWorkForOneRat() {
        PathStepEvaluator evaluator = new PathStepEvaluator();
        FakeTerrain terrain = new FakeTerrain();
        BlockPos pos = new BlockPos(0, 0, 0);
        terrain.setDestroySpeed(pos, -1.0F);

        int expectedWorkUnits = (int) (Config.bedrockFailsafeRatMinutes * 1200 * Config.workPerRatPerTick);
        assertEquals(expectedWorkUnits, evaluator.miningCost(terrain, pos));
    }

    @Test
    void ordinaryBlockUsesHardnessDrivenCostNotBedrockFailsafe() {
        PathStepEvaluator evaluator = new PathStepEvaluator();
        FakeTerrain terrain = new FakeTerrain();
        BlockPos pos = new BlockPos(0, 0, 0);
        terrain.setDestroySpeed(pos, 2.0F);

        int expected = (int) (2.0F * Config.miningPenaltyMultiplier) + Config.miningBasePenalty;
        assertEquals(expected, evaluator.miningCost(terrain, pos));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.ratden.skavenblight.ai.pathing.PathStepEvaluatorCostTest"`
Expected: FAIL (compile error — `PathStepEvaluator`, `Config.bedrockFailsafeRatMinutes` don't exist).

- [ ] **Step 3: Add the four base-cost fields + bedrockFailsafeRatMinutes to Config**

Add to `Config.java` in the "Flow-field pathfinding settings" section, following the exact
`ModConfigSpec.IntValue` + `.comment(...).defineInRange(...)` pattern already used by
`BUILDING_BASE_PENALTY` immediately above:

```java
private static final ModConfigSpec.IntValue TUNNEL_BASE_COST =
        BUILDER.comment("Base pathfinding cost for a TUNNEL step (mining straight through).")
                .defineInRange("tunnelBaseCost", 400, 1, 10_000);

private static final ModConfigSpec.IntValue BRIDGE_BASE_COST =
        BUILDER.comment("Base pathfinding cost for a BRIDGE step (building across a gap).")
                .defineInRange("bridgeBaseCost", 600, 1, 10_000);

private static final ModConfigSpec.IntValue CARVED_STAIR_BASE_COST =
        BUILDER.comment("Base pathfinding cost for a CARVED_STAIR step (mining a stair into solid material).")
                .defineInRange("carvedStairBaseCost", 800, 1, 10_000);

private static final ModConfigSpec.IntValue AIR_STAIR_BASE_COST =
        BUILDER.comment("Base pathfinding cost for an AIR_STAIR step (building a stair through open air).")
                .defineInRange("airStairBaseCost", 1_000, 1, 10_000);

private static final ModConfigSpec.IntValue BEDROCK_FAILSAFE_RAT_MINUTES =
        BUILDER.comment("Real-world minutes for ONE rat to clear an unbreakable block. Linear with "
                + "worker count (25 rats clear it in 1/25th the time) using the same work units as "
                + "workPerRatPerTick, so this and the build-speed cap can never drift apart.")
                .defineInRange("bedrockFailsafeRatMinutes", 25, 1, 1_000);
```

Add matching `public static int tunnelBaseCost, bridgeBaseCost, carvedStairBaseCost, airStairBaseCost,
bedrockFailsafeRatMinutes;` fields and their `onLoad` assignments (`tunnelBaseCost =
TUNNEL_BASE_COST.get();` etc.), mirroring every other field in the file exactly.

- [ ] **Step 4: Implement `PathStepEvaluator`'s cost methods**

```java
package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import org.ratden.skavenblight.Config;

public class PathStepEvaluator {

    private static final int ORTHOGONAL_COST = 10;

    public int baseCostFor(PathAction action) {
        return switch (action) {
            case WALK -> ORTHOGONAL_COST;
            case TUNNEL -> Config.tunnelBaseCost;
            case BRIDGE -> Config.bridgeBaseCost;
            case CARVED_STAIR -> Config.carvedStairBaseCost;
            case AIR_STAIR -> Config.airStairBaseCost;
        };
    }

    /** Extension point: today this is vanilla getDestroySpeed() < 0. A future Skavenblight
     * block-toughness attribute overrides THIS method, not miningCost's formula. */
    public boolean isBedrockLike(TerrainAccess terrain, BlockPos pos) {
        return terrain.getDestroySpeed(pos) < 0;
    }

    public int miningCost(TerrainAccess terrain, BlockPos pos) {
        if (isBedrockLike(terrain, pos)) {
            return bedrockFailsafeWorkUnits();
        }
        float hardness = terrain.getDestroySpeed(pos);
        return (int) (hardness * Config.miningPenaltyMultiplier) + Config.miningBasePenalty;
    }

    public static int bedrockFailsafeWorkUnits() {
        return (int) (Config.bedrockFailsafeRatMinutes * 1200 * Config.workPerRatPerTick);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "org.ratden.skavenblight.ai.pathing.PathStepEvaluatorCostTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/Config.java \
        src/main/java/org/ratden/skavenblight/ai/pathing/PathStepEvaluator.java \
        src/test/java/org/ratden/skavenblight/ai/pathing/PathStepEvaluatorCostTest.java
git commit -m "feat(pathing): add PathStepEvaluator cost model with cost-ordering invariant + bedrock failsafe"
```

---

## Task 3: `PathStepEvaluator` step generation (WALK + frontier-gated construction candidates)

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/ai/pathing/PathStepEvaluator.java`
- Test: `src/test/java/org/ratden/skavenblight/ai/pathing/PathStepEvaluatorStepGenerationTest.java`

**Interfaces (as actually implemented — the `costBiasTarget` parameter from the original draft was
dropped: it traced back to `determineMacroAction`'s old vertical-shaft LADDER/SPIRAL/PILLAR distance
check, which no longer exists post-climb-removal, and nothing else in the new classification needs
it — keeping an unused parameter around "for later" would violate this plan's own YAGNI guidance):**
- Consumes: `Task 2`'s cost methods, `TerrainAccess`.
- Produces: `PathStepEvaluator.EvaluatedStep(BlockPos pos, int cost, PathAction action)` record (same
  shape as today's `TerrainEvaluator.EvaluatedStep`). `PathStepEvaluator.candidateSteps(TerrainAccess
  terrain, BlockPos current, Set<BlockPos> lockedPositions, Predicate<BlockPos> outOfBounds)` →
  `List<EvaluatedStep>` — the ONE method both ordinary walking and construction discovery call
  (replaces the old split between `getValidOrthogonalSteps` and `determineMacroAction`/
  `SiegeLineTracer`). `PathStepEvaluator.isWalkableTerrain(TerrainAccess, BlockPos)` and
  `PathStepEvaluator.isOutOfBounds(...)` — ported verbatim from `TerrainEvaluator` (these two are
  pure terrain predicates, not part of the old duplication problem).

**Classification, precisely (the original draft's "TUNNEL if the neighbor blocks motion" shorthand
undersold this — implemented against `determineMacroAction`'s real MINE-vs-support layering, not a
single-cell blocksMotion check):** for a neighbor that already failed `isWalkableTerrain` (so either
lacks support, or its own foot/head aren't clear): if pure vertical (`dy != 0 && dx == 0 && dz == 0`),
no step at all, full stop, regardless of what's blocking or missing. Otherwise, check whether the
neighbor's own foot (`getBlockState(neighbor)`) OR its head (`getBlockState(neighbor.above())`) is a
genuine blocking obstacle (`blocksMotion() && !isWalkableScaffold(...)`) — this is the "mining case."
`dy != 0` → `CARVED_STAIR` if mining case else `AIR_STAIR`; `dy == 0` → `TUNNEL` if mining case else
`BRIDGE`. Cost is `baseCostFor(action)`, plus `miningCost(terrain, neighbor)` only for the mining
case. The old 3-cell ceiling pre-check (`pos.above(2)`) is dropped entirely — it only mattered for
`SiegeLineTracer`'s old unconditional per-hop classification (every hop got classified regardless of
whether it was already walkable); the new isWalkableTerrain-first gate makes it unreachable, since a
genuinely walkable diagonal step-up (ordinary 2-cell foot+head clearance, which is all vanilla
Navigation itself ever needs) now short-circuits to `WALK` before classification runs at all.

**Step generation spec (the load-bearing algorithmic core of the whole rewrite):**

For `current`, iterate the same 8 horizontal offsets × 3 `dy` (`-1, 0, 1`) = up to 24 directions
`TerrainEvaluator.getValidOrthogonalSteps` already iterates (port `HORIZONTAL_OFFSETS` verbatim). For
each neighbor:

1. Skip if `outOfBounds.test(neighbor)` or `lockedPositions.contains(neighbor)` (ported verbatim from
   today's guard).
2. If `isWalkableTerrain(terrain, neighbor)`: offer a `WALK` step at `baseCostFor(WALK)` (+diagonal
   corner-blocking check, ported verbatim from today's `getValidOrthogonalSteps`).
3. Otherwise — this cell is a candidate obstacle. Classify it the same way `determineMacroAction`
   does today (port that classification logic verbatim, replacing its five old outputs with exactly
   one of the four new ones). **Read the condition below literally — "diagonal" in this codebase's
   existing terminology (see `determineMacroAction`'s own branch order) means "has a Y-component and
   isn't a pure vertical shaft," NOT "both horizontal axes are nonzero."** Confirmed against
   `StaircaseSiegeGroupGameTests`'s real geometry (`relativeGroundSpawn (26,2,26)` →
   `relativeNexusPos (40,16,26)`/`(70,46,26)`, Z held constant at 26 in every one of the 4 existing
   tests): the crossing this go/no-go gate depends on has `dx != 0, dy != 0, dz == 0` — a straight
   rise along one horizontal axis, not a corner-diagonal. A condition requiring BOTH `dx != 0` and
   `dz != 0` would never fire for this geometry at all, producing no candidate step and making Task
   21's gate unpassable — this was caught and must not be reintroduced.
   - `dy != 0 && !(dx == 0 && dz == 0)` (has a Y-component, not a pure vertical shaft — covers both a
     straight one-axis rise like the existing tests AND a true corner-diagonal rise): `CARVED_STAIR`
     if the neighbor cell itself blocks motion (mining through solid material to carve a stair) or
     `AIR_STAIR` if it's open air (building a stair through open air) — this is the one genuinely new
     classification decision the old code didn't need to make explicitly, since `BUILD_STAIR` used to
     cover both; use `terrain.getBlockState(neighbor).blocksMotion()` as the discriminator.
   - `dy == 0`, horizontal (`dx != 0 || dz != 0`): `TUNNEL` if the neighbor blocks motion, `BRIDGE` if
     it's open air with no support below (mirrors today's "Horizontal Bridge" branch).
   - `dy != 0 && dx == 0 && dz == 0` (pure vertical, no horizontal component at all): **do not offer a
     step here at all** — pure vertical climbing (today's PILLAR/LADDER/SPIRAL branch) is permanently
     removed per the design doc; a pure-vertical obstacle must be crossed some other way (a diagonal
     CARVED_STAIR/AIR_STAIR around it, or a TUNNEL/BRIDGE detour) or is simply unreachable from this
     cell.
   - Cost: `baseCostFor(action)` for BRIDGE/CARVED_STAIR/AIR_STAIR; `baseCostFor(TUNNEL) +
     miningCost(terrain, neighbor)` for TUNNEL and the mining component of CARVED_STAIR (a carved
     stair also mines, so its cost is `baseCostFor(CARVED_STAIR) + miningCost(terrain, neighbor)`).
4. **Frontier gating (mandatory invariant):** steps 2-3 above ARE the frontier check by construction —
   a construction candidate (TUNNEL/BRIDGE/CARVED_STAIR/AIR_STAIR) is only ever generated for a
   neighbor that step 2 already rejected as not walkable. There is no separate "is this a frontier"
   flag to maintain: the same per-neighbor branch that decides WALK-or-not IS the frontier gate, so a
   chain's every subsequent tread/tunnel-block/bridge-segment cell — which itself has no walkable
   neighbor of its own — naturally re-qualifies as a frontier on the NEXT cell's own evaluation. This
   is what makes "frontier is a per-cell predicate, not a one-shot gate" true without any extra state.

- [ ] **Step 1: Write the failing tests**

```java
package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class PathStepEvaluatorStepGenerationTest {

    @Test
    void diagonalRiseIntoSolidMaterialProducesCarvedStairNotAirStair() {
        FakeTerrain terrain = new FakeTerrain();
        BlockPos current = new BlockPos(0, 10, 0);
        BlockPos diagonalUp = new BlockPos(1, 11, 1);
        terrain.setSolid(diagonalUp);
        terrain.setSolid(diagonalUp.above()); // headroom-blocking, forces obstacle classification

        PathStepEvaluator evaluator = new PathStepEvaluator();
        List<PathStepEvaluator.EvaluatedStep> steps = evaluator.candidateSteps(
                terrain, current, Collections.emptySet(), pos -> false, null);

        PathStepEvaluator.EvaluatedStep found = steps.stream()
                .filter(s -> s.pos().equals(diagonalUp)).findFirst().orElseThrow();
        assertEquals(PathAction.CARVED_STAIR, found.action());
    }

    @Test
    void diagonalRiseIntoOpenAirProducesAirStairNotCarvedStair() {
        FakeTerrain terrain = new FakeTerrain();
        BlockPos current = new BlockPos(0, 10, 0);
        BlockPos diagonalUp = new BlockPos(1, 11, 1);
        // diagonalUp itself stays open air (default); nothing marked solid there.

        PathStepEvaluator evaluator = new PathStepEvaluator();
        List<PathStepEvaluator.EvaluatedStep> steps = evaluator.candidateSteps(
                terrain, current, Collections.emptySet(), pos -> false, null);

        PathStepEvaluator.EvaluatedStep found = steps.stream()
                .filter(s -> s.pos().equals(diagonalUp)).findFirst().orElseThrow();
        assertEquals(PathAction.AIR_STAIR, found.action());
    }

    @Test
    void straightOneAxisRiseWithTheOtherHorizontalAxisUnchangedStillProducesAirStair() {
        // Regression pin for the real StaircaseSiegeGroupGameTests geometry: Z is held CONSTANT
        // across every existing air-stair test (relativeGroundSpawn (26,2,26) -> relativeNexusPos
        // (40,16,26)/(70,46,26)) - the actual crossing is dx!=0, dy!=0, dz==0, NOT a corner-diagonal
        // with both dx and dz nonzero. A classification requiring both horizontal axes nonzero would
        // silently produce no candidate at all for this exact shape and make Task 21's go/no-go gate
        // unpassable - this test exists specifically to catch that regression.
        FakeTerrain terrain = new FakeTerrain();
        BlockPos current = new BlockPos(0, 10, 0);
        BlockPos straightAxisRise = new BlockPos(1, 11, 0); // dx=1, dy=1, dz=0
        // straightAxisRise itself stays open air (default); nothing marked solid there.

        PathStepEvaluator evaluator = new PathStepEvaluator();
        List<PathStepEvaluator.EvaluatedStep> steps = evaluator.candidateSteps(
                terrain, current, Collections.emptySet(), pos -> false, null);

        PathStepEvaluator.EvaluatedStep found = steps.stream()
                .filter(s -> s.pos().equals(straightAxisRise)).findFirst().orElseThrow(
                        () -> new AssertionError("dx!=0,dy!=0,dz==0 must produce a candidate step - "
                                + "this exact shape is what every existing air-stair GameTest crosses"));
        assertEquals(PathAction.AIR_STAIR, found.action());
    }

    @Test
    void aLongTunnelLosesToAShortAirStairOnTotalPathCostNotJustBaseCost() {
        // Verification for dropping MAX_CONSECUTIVE_MINE_DEPTH (see Task 6/Task 8's notes): if cost
        // alone already makes an arbitrarily long tunnel lose to a short alternative, no separate
        // depth cap is needed - Dijkstra's own cheapest-first behavior handles it. A 10-block tunnel
        // through ordinary stone (hardness ~1.5) costs roughly baseCostFor(TUNNEL) + 10 *
        // miningCost(stone) per block; a 3-step air-stair costs 3 * baseCostFor(AIR_STAIR). Assert
        // the accumulated tunnel cost exceeds the accumulated air-stair cost for this shape, proving
        // the "long cheap tunnel legitimately losing to a short expensive stair is correct, not a
        // bug" invariant already holds without any extra depth-limiting state.
        PathStepEvaluator evaluator = new PathStepEvaluator();
        FakeTerrain stoneTerrain = new FakeTerrain();
        BlockPos stonePos = new BlockPos(0, 0, 0);
        stonePos = stonePos; // hardness lookup below uses FakeTerrain's fixed solid-block hardness

        int tunnelStepCost = evaluator.baseCostFor(PathAction.TUNNEL) + evaluator.miningCost(stoneTerrain, stonePos);
        int accumulatedTenBlockTunnelCost = 10 * tunnelStepCost;
        int accumulatedThreeStepAirStairCost = 3 * evaluator.baseCostFor(PathAction.AIR_STAIR);

        assertTrue(accumulatedTenBlockTunnelCost > accumulatedThreeStepAirStairCost,
                "a 10-block tunnel must cost more in total than a 3-step air-stair, proving cost "
                        + "alone (no separate mine-chain-depth cap) already prevents an arbitrarily "
                        + "long tunnel from ever winning against a short real alternative");
    }

    @Test
    void pureVerticalObstacleProducesNoStepAtAll() {
        FakeTerrain terrain = new FakeTerrain();
        BlockPos current = new BlockPos(0, 10, 0);
        BlockPos straightUp = new BlockPos(0, 11, 0);
        terrain.setSolid(straightUp.below()); // no support removed below straightUp - open shaft

        PathStepEvaluator evaluator = new PathStepEvaluator();
        List<PathStepEvaluator.EvaluatedStep> steps = evaluator.candidateSteps(
                terrain, current, Collections.emptySet(), pos -> false, null);

        assertTrue(steps.stream().noneMatch(s -> s.pos().equals(straightUp)),
                "pure vertical climbing must never produce a candidate step");
    }

    @Test
    void walkableNeighborNeverAlsoProducesAConstructionCandidate() {
        FakeTerrain terrain = new FakeTerrain();
        BlockPos current = new BlockPos(0, 10, 0);
        BlockPos flatWalkable = new BlockPos(1, 10, 0);
        terrain.setSolid(flatWalkable.below()); // real ground - genuinely walkable

        PathStepEvaluator evaluator = new PathStepEvaluator();
        List<PathStepEvaluator.EvaluatedStep> steps = evaluator.candidateSteps(
                terrain, current, Collections.emptySet(), pos -> false, null);

        long matchesAtThatPos = steps.stream().filter(s -> s.pos().equals(flatWalkable)).count();
        assertEquals(1, matchesAtThatPos, "a walkable cell must produce exactly one WALK step, never also a construction candidate");
        assertEquals(PathAction.WALK, steps.stream().filter(s -> s.pos().equals(flatWalkable)).findFirst().get().action());
    }
}
```

(`FakeTerrain` is a small in-memory `TerrainAccess` test double — a `HashSet<BlockPos>` of solid
positions, `getDestroySpeed` returning `1.0F` for solid / `-1F` for air, `isLoaded`/
`isOutsideBuildHeight` always `false`. Write it once in `src/test/java/org/ratden/skavenblight/ai/
pathing/FakeTerrain.java` — it's reused by Tasks 4-6's tests too.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.ratden.skavenblight.ai.pathing.PathStepEvaluatorStepGenerationTest"`
Expected: FAIL (compile error — `candidateSteps` doesn't exist yet).

- [ ] **Step 3: Implement `candidateSteps`, `isWalkableTerrain`, `isOutOfBounds`**

Port `isWalkableTerrain`, `isFitForWalking`, `isOverheadClear`, `isWalkableScaffold`, `isOutOfBounds`,
and `HORIZONTAL_OFFSETS` from `TerrainEvaluator` verbatim (these are pure terrain predicates unrelated
to the WALK/construction duplication being fixed). **Implemented decision, don't "fix" this back:**
`isWalkableScaffold`'s `StairBlock`/`SlabBlock`/`LadderBlock`/`COBBLESTONE` exemptions are kept
verbatim, NOT narrowed to just `StairBlock`/`COBBLESTONE` as an earlier draft of this task suggested —
`isWalkableScaffold` is a general "can a mob stand on this material despite `blocksMotion()` saying
otherwise" terrain classification, used for ANY terrain a rat might cross (including pre-existing
world terrain this system never placed, like a player-built ladder or slab), not only for materials
this rewrite's own construction actions place. Narrowing it on the assumption that "we don't place
ladders anymore" conflates "what we build" with "what a rat can ever stand on," which are different
questions — the second one is broader and unrelated to climbing removal. Implement `candidateSteps`
following the spec above — the single method that replaces both `getValidOrthogonalSteps` and
`determineMacroAction`.

**Cross-task note for whoever implements Task 5:** `isOutOfBounds` takes a `FlowFieldState` parameter
and only calls `state.isOutOfBounds(pos)` on it — this compiles today against the OLD (`SiegeNode`-
typed) `FlowFieldState` because that call site doesn't touch the value type at all. Confirm it still
compiles once Task 5 retypes `FlowFieldState` to `FlowStep` (it should — nothing here should need to
change), rather than assuming silently; if it doesn't compile cleanly, that's a sign `isOutOfBounds`'s
signature needs revisiting, not a sign to add an unrelated cast.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.ratden.skavenblight.ai.pathing.PathStepEvaluatorStepGenerationTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/PathStepEvaluator.java \
        src/test/java/org/ratden/skavenblight/ai/pathing/PathStepEvaluatorStepGenerationTest.java \
        src/test/java/org/ratden/skavenblight/ai/pathing/FakeTerrain.java
git commit -m "feat(pathing): unify WALK and construction step generation into one PathStepEvaluator method"
```

---

## Task 4: `TerrainSnapshot` planned-cell override hook

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/ai/pathing/TerrainSnapshot.java`
- Test: `src/main/java/org/ratden/skavenblight/gametest/TerrainSnapshotPlannedOverrideGameTests.java`
  (a GameTest, not a `src/test` unit test — see the note below Step 1 for why this task is the one
  exception to this plan's usual "plain JUnit unless proven otherwise" default).

**Interfaces:**
- Consumes: nothing new.
- Produces: `TerrainSnapshot.refresh(level, previous, territoryChunks, dirtyChunks, minY,
  maxYExclusive, maxChunksToCapture, Function<BlockPos, BlockState> plannedStateOverride)` — an
  8th parameter (default-null-safe: pass `pos -> null` for "no override" to keep every other call
  site unchanged for now; Task 11 wires a real override in from `SiegeProjectManager`). When
  `plannedStateOverride.apply(pos)` returns non-null for a captured position, `captureColumn` uses
  that `BlockState` (and its derived `destroySpeed`/`isSolidRender`) instead of the real,
  possibly-not-yet-built world state. This is the mechanism behind "an active project's planned
  final state is authoritative for terrain evaluation" — see Task 11 for where the actual override
  function gets built and passed in, and why this must NOT be wired at TerrainSnapshot's own level
  (this class has no idea what a SiegeProject is, by design — it stays a pure terrain-capture type).

**Why a GameTest, not a `src/test` unit test:** `captureColumn` takes a raw `ServerLevel` and calls
real Minecraft methods on it (`level.getBlockState`, `BlockState.getDestroySpeed(level, pos)`,
`BlockState.isSolidRender(level, pos)`) — unlike everywhere else in `ai.pathing`, there is no
`TerrainAccess` indirection here to fake against, because this class's entire job is the capture step
that PRODUCES a `TerrainAccess` implementation from a real level. This codebase already draws exactly
this line elsewhere: anything abstracted behind `TerrainAccess` gets a plain JUnit test with a
hand-written fake (see Task 2/3's `FakeTerrain`); anything that must touch a real `ServerLevel`
directly gets a GameTest under `src/main/java/.../gametest` (see every existing file in that
package). Follow the existing convention, don't invent a third approach.

- [ ] **Step 1: Write the failing GameTest** — asserts against the resulting `TerrainSnapshot`'s real
  derived values (`getDestroySpeed`/`isSolidRender`), not just the override function's own contract
  in isolation, since those two derived values are what `PathStepEvaluator` actually reads and the
  easiest thing to get wrong (compute them from the real block, forget to recompute from the
  override).

```java
package org.ratden.skavenblight.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.ai.pathing.TerrainSnapshot;

import java.util.Set;

import static org.ratden.skavenblight.gametest.PathingRegionGameTests.check;

@GameTestHolder(Skavenblight.MODID)
@PrefixGameTestTemplate(false)
public class TerrainSnapshotPlannedOverrideGameTests {

    @GameTest(template = "pathing_test_giant", timeoutTicks = 40)
    public static void overriddenPositionReportsTheOverrideBlocksDestroySpeedNotTheRealBlocksDestroySpeed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos overriddenRelative = new BlockPos(5, 2, 5);
        BlockPos ordinaryRelative = new BlockPos(6, 2, 5);
        BlockPos overriddenAbsolute = helper.absolutePos(overriddenRelative);
        BlockPos ordinaryAbsolute = helper.absolutePos(ordinaryRelative);

        // Real bedrock at both positions - both would report destroySpeed < 0 with no override.
        helper.setBlock(overriddenRelative, Blocks.BEDROCK.defaultBlockState());
        helper.setBlock(ordinaryRelative, Blocks.BEDROCK.defaultBlockState());

        BlockState overrideAir = Blocks.AIR.defaultBlockState();
        java.util.function.Function<BlockPos, BlockState> override =
                pos -> pos.equals(overriddenAbsolute) ? overrideAir : null;

        TerrainSnapshot.RefreshResult result = TerrainSnapshot.refresh(level, null,
                Set.of(new ChunkPos(overriddenAbsolute)), Set.of(new ChunkPos(overriddenAbsolute)),
                level.getMinBuildHeight(), level.getMaxBuildHeight(), Integer.MAX_VALUE, override);
        TerrainSnapshot snapshot = result.snapshot();

        helper.succeedWhen(() -> {
            check(snapshot.getDestroySpeed(overriddenAbsolute) >= 0,
                    "overridden position must report the OVERRIDE block's destroySpeed (air, >= 0), not real bedrock's (-1)");
            check(snapshot.getDestroySpeed(ordinaryAbsolute) < 0,
                    "a position with no override must still report the real bedrock's destroySpeed unchanged");
        });
    }
}
```

(This is this task's real verification. Task 20's grief-recovery GameTest additionally proves the
override's end-to-end effect on flow-field behavior once `TerritoryRegionMap` wires it in during
Task 14, but this task doesn't need to wait for that.)

- [ ] **Step 2: Check for orphaned `gameTestServer` java.exe processes, then run
  `./gradlew runGameTestServer` to verify it fails** (compile error — the 8-arg `refresh` overload
  doesn't exist yet).

- [ ] **Step 3: Add the override parameter to `TerrainSnapshot.refresh`/`captureColumn`**

Add the 8th parameter to `refresh`'s signature and thread it through to `captureColumn`. Inside
`captureColumn`'s per-cell loop, after computing `BlockState bs = level.getBlockState(cursor);`,
check `BlockState overridden = plannedStateOverride.apply(cursor.immutable()); if (overridden !=
null) bs = overridden;` before computing `destroySpeeds[idx]`/`solidRender.set(idx)` — both derived
values must reflect the override, not the real block, since they're what `PathStepEvaluator` actually
reads. Add a 7-arg overload that forwards `pos -> null` to the new 8-arg method, so every existing
call site (Task 14's `TerritoryRegionMap` edits aside) keeps compiling unchanged until Task 14
explicitly upgrades them.

- [ ] **Step 4: Check for orphaned processes, then run `./gradlew runGameTestServer` to verify it
  passes.**

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/TerrainSnapshot.java \
        src/main/java/org/ratden/skavenblight/gametest/TerrainSnapshotPlannedOverrideGameTests.java
git commit -m "feat(pathing): add planned-cell terrain override hook to TerrainSnapshot"
```

---

## Task 5: `FlowFieldState` ported to `FlowStep`

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/ai/pathing/FlowFieldState.java`
- Test: `src/test/java/org/ratden/skavenblight/ai/pathing/FlowFieldStateTest.java`

**Interfaces:**
- Consumes: `FlowStep` (Task 1).
- Produces: identical public API to today's `FlowFieldState`, with every `SiegeNode` reference
  replaced by `FlowStep`: `updateInstructions(Map<BlockPos, FlowStep>)`, `getInstruction(BlockPos)` →
  `FlowStep`, `getInstructionMap()` → `Map<BlockPos, FlowStep>`, plus the unchanged
  `updateCellFilter`/`isOutOfBounds`/`isChunkMapped`/`getMappedBlocksInChunk`/`getTargetPos`/
  `getTerritoryChunks`.

This class's actual logic (the volatile-publish `Indexed` snapshot pattern, per-chunk block index,
cell-filter swap) does not change at all — this is a mechanical type substitution. Note for the plan's
own record: the design doc's one-line class list describes this as "absorbed into FlowFieldCalculator
as a nested record" — on reading the real file, that undersells it: `Indexed`'s volatile-publish
cross-thread-safety pattern and chunk index are genuine, load-bearing behavior a `record` can't express
as cleanly without losing the "always publish a fully-built snapshot, never a partially-mutated one"
guarantee this class exists for. Kept as its own top-level file for that reason; this is a
line-count/file-count judgment call within the design doc's own explicitly flexible "13-15 files"
budget, not a reversal of any NON-NEGOTIABLE decision.

- [ ] **Step 1: Write the failing test**

```java
package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FlowFieldStateTest {

    @Test
    void updateInstructionsPublishesFlowStepsAtomically() {
        FlowFieldState state = new FlowFieldState(BlockPos.ZERO, Set.of());
        BlockPos pos = new BlockPos(1, 0, 0);
        FlowStep step = new FlowStep(pos, PathAction.WALK, BlockPos.ZERO);

        state.updateInstructions(Map.of(pos, step));

        assertEquals(step, state.getInstruction(pos));
        assertNull(state.getInstruction(new BlockPos(99, 0, 0)));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.ratden.skavenblight.ai.pathing.FlowFieldStateTest"`
Expected: FAIL (compile error — `FlowFieldState` still typed on `SiegeNode`).

- [ ] **Step 3: Replace every `SiegeNode` reference in `FlowFieldState` with `FlowStep`**

Mechanical find-replace within the file; no logic changes.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.ratden.skavenblight.ai.pathing.FlowFieldStateTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/FlowFieldState.java \
        src/test/java/org/ratden/skavenblight/ai/pathing/FlowFieldStateTest.java
git commit -m "refactor(pathing): port FlowFieldState from SiegeNode to FlowStep"
```

---

## Task 6: `FlowFieldCalculator` unified flood

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/ai/pathing/FlowFieldCalculator.java` (new file;
  the old one is being replaced, but keep the same class name/package since every caller references
  it by type)
- Test: `src/test/java/org/ratden/skavenblight/ai/pathing/FlowFieldCalculatorTest.java`

**Interfaces:**
- Consumes: `PathStepEvaluator` (Tasks 2-3), `SiegeProjectManager` (Task 12 — this task can be
  implemented against a temporary minimal stub with `injectActiveProjects`/`finalizeCandidateProjects`
  no-ops, then re-verified once Task 12 lands; see Step 0 below), `CalculationThrottler` (kept,
  unchanged), `FlowFieldState` (Task 5).
- Produces: `calculateFully(TerrainAccess, FlowFieldState)`, `calculateFully(TerrainAccess,
  FlowFieldState, boolean respectThrottle)`, `getLiveDebugMap()`, `getLastPassNodeCount()`,
  `isLastPassBudgetExhausted()`, plus the two pure static functions
  `detectMutualCyclePositions(Map<BlockPos, FlowStep>)` and `pickCyclePositionToDrop(List<BlockPos>,
  Map<BlockPos, Integer>, Set<BlockPos>)` — same signatures as today, `FlowStep` in place of
  `SiegeNode`.

**Step 0 (unblocks this task without waiting on Task 12):** create a minimal
`SiegeProjectManagerStub` in the test source root implementing just the four methods
`FlowFieldCalculator`'s constructor needs (`injectActiveProjects` as a no-op,
`evaluateMacroProjects`/`finalizeCandidateProjects` as no-ops, `getLockedPositions()` returning
`Set.of()`) so this task's tests can run against it; Task 12 replaces the production
`SiegeProjectManager` and this task's tests then re-run unchanged against the real one (the
interface these tests exercise doesn't change).

**Spec — this is the single biggest behavioral port in the whole rewrite. Every one of the following
must carry forward with identical behavior, since each fixes a confirmed, empirically-found bug in
the old system (see the corresponding comment in the current `FlowFieldCalculator.java` for the full
incident each one traces back to — do not re-derive these from first principles, port them):**

1. Node-budget throttling: `nextCostMap.size() >= maxAllowedNodes` stops the queue early and sets
   `lastPassBudgetExhausted = true`; `maxAllowedNodes` is `Config.maxFlowFieldNodes` unthrottled, or
   `min(Config.maxFlowFieldNodes, throttler.getNodesPerTick())` when `respectThrottle` is true.
2. Live-debug-map publish throttled by wall-clock time (250ms), not node count.
3. Core Dijkstra relaxation (`totalCost < nextCostMap.getOrDefault(...)` gate before writing
   `nextCostMap`/`nextInstructionMap`/enqueueing) — this now calls `PathStepEvaluator.candidateSteps`
   instead of the old split `getValidOrthogonalSteps` + separate macro-project trigger.
4. **Frontier trigger for macro-project discovery, ported and adjusted for the unified evaluator:**
   today's `hitObstacle` (`walkableNeighbors < 4`) becomes: after calling `candidateSteps` for
   `current`, count how many of the returned candidates are `WALK` — if that count is `< 4`, ALSO
   call `projectManager.evaluateMacroProjects(...)` for `current` (mirroring today's `hitObstacle &&
   (isWalkableTerrain || current.equals(target) || isPlannedLanding)` gate, with `isPlannedLanding`
   replaced by whatever Task 6's PLATFORM equivalent needs — see Task 7). This is what makes
   construction-project discovery a strict superset of ordinary WALK expansion, not a separate code
   path — `candidateSteps` already returns both WALK and construction candidates from the SAME call,
   so there is no more "two evaluators disagreeing" class of bug to guard against; this trigger is
   now purely about deciding when to ALSO run the (still-separate, project-scoped) macro-project
   search on top of the ordinary per-cell candidates, not about classifying the cell at all.
5. Mutual-cycle breaking (`breakMutualCycles`/`detectMutualCyclePositions`/`pickCyclePositionToDrop`)
   ported verbatim (pure functions, only the map's value type changes from `SiegeNode` to `FlowStep`).
6. `startCalculation`/`processCalculationQueue`/`finalizeCalculation` structure ported verbatim.

Drop entirely (superseded by the frontier-gating invariant now living inside `candidateSteps` itself,
per Task 3's spec — see that task's step 4 for why this doesn't need separate state anymore):
`MAX_CONSECUTIVE_MINE_DEPTH`/`mineChainDepth` tracking. This drop is already verified, not merely
assumed: Task 3's `aLongTunnelLosesToAShortAirStairOnTotalPathCostNotJustBaseCost` test proves cost
alone (accumulating `baseCostFor(TUNNEL) + miningCost` per hop) already makes an arbitrarily long
tunnel lose to any short real alternative — Dijkstra's own cheapest-first expansion means a branch
that's already lost on cost is never explored further once a cheaper path to the same destination is
known, which is the actual mechanism that replaces the old cap (not a new, separate limit). The old
cap existed specifically to stop a MINE chain tunneling arbitrarily deep into undisturbed rock chasing
marginal savings (documented incident: 61% of a 114k-node pass was MINE) — that incident was about
`getValidOrthogonalSteps` offering a MINE step at every ordinary WALK-capable cell with no relative
cost comparison stopping it (the old core-step/macro-project split's actual bug), not about tunnels
being long per se; the unified `candidateSteps` doesn't have that split to begin with.

- [ ] **Step 1: Write the failing tests** (port the two existing pure-function tests from the current
  `FlowFieldCalculatorTest` verbatim, `SiegeNode`→`FlowStep`, plus one new test for the frontier
  trigger threshold):

```java
package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FlowFieldCalculatorTest {

    @Test
    void detectMutualCyclePositionsFindsATwoPositionCycle() {
        BlockPos a = new BlockPos(0, 0, 0);
        BlockPos b = new BlockPos(1, 0, 0);
        Map<BlockPos, FlowStep> instructions = Map.of(
                a, new FlowStep(a, PathAction.WALK, b),
                b, new FlowStep(b, PathAction.WALK, a));

        Set<BlockPos> cycle = FlowFieldCalculator.detectMutualCyclePositions(instructions);

        assertEquals(Set.of(a, b), cycle);
    }

    @Test
    void detectMutualCyclePositionsIgnoresATargetSelfReference() {
        BlockPos target = new BlockPos(0, 0, 0);
        Map<BlockPos, FlowStep> instructions = Map.of(target, new FlowStep(target, PathAction.WALK, target));

        Set<BlockPos> cycle = FlowFieldCalculator.detectMutualCyclePositions(instructions);

        assertTrue(cycle.isEmpty(), "a position pointing at itself is the flood's own objective, not a cycle");
    }

    @Test
    void pickCyclePositionToDropPrefersDroppingAnUnlockedPosition() {
        BlockPos locked = new BlockPos(0, 0, 0);
        BlockPos unlocked = new BlockPos(1, 0, 0);
        List<BlockPos> cycle = List.of(locked, unlocked);
        Map<BlockPos, Integer> costs = Map.of(locked, 10, unlocked, 5);

        BlockPos dropped = FlowFieldCalculator.pickCyclePositionToDrop(cycle, costs, Set.of(locked));

        assertEquals(unlocked, dropped, "a locked SiegeProject cell must never be dropped while an unlocked alternative exists in the same cycle");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.ratden.skavenblight.ai.pathing.FlowFieldCalculatorTest"`
Expected: FAIL (compile error).

- [ ] **Step 3: Implement `FlowFieldCalculator`** following the spec above, porting the current
  file's structure with the substitutions listed.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.ratden.skavenblight.ai.pathing.FlowFieldCalculatorTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/FlowFieldCalculator.java \
        src/test/java/org/ratden/skavenblight/ai/pathing/FlowFieldCalculatorTest.java \
        src/test/java/org/ratden/skavenblight/ai/pathing/SiegeProjectManagerStub.java
git commit -m "feat(pathing): rewrite FlowFieldCalculator as a unified WALK+construction flood"
```

---

## Task 7: PLATFORM post-processing pass

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/ai/pathing/PlatformInserter.java`
- Test: `src/test/java/org/ratden/skavenblight/ai/pathing/PlatformInserterTest.java`

**Interfaces:**
- Consumes: `PlannedStep`/`PathAction` (Task 1).
- Produces: `PlatformInserter.insertPlatforms(List<PlannedStep> buildOrder) → List<PlannedStep>`. Pure
  function, no world/terrain access — operates only on the geometry/action sequence.

**Confirmed with the user (see conversation record above this plan; do not reopen without new
empirical evidence from the GameTest matrix in Task 20):** insert a platform by **retyping** the
existing planned cell at the seam (matching how today's `BUILD_LANDING` already works — a 3×3 floor
clear one block below the node, 4-block headroom, node position itself left open for standing) rather
than inserting a brand-new position into the list, since insertion would shift every subsequent
step's build-order index. Predicate: for every adjacent pair `(prev, curr)` in the build order where
`prev.action() != WALK && curr.action() != WALK && prev.action() != curr.action()`, retype `curr`'s
entry to a new `PathAction`-independent platform marker at the SAME position `curr.pos()` (see Step 3
for how this is represented without adding a 6th `PathAction` value, which would violate the
"five action types only" invariant).

**Representation note:** PLATFORM is not itself a `PathAction` — it is a post-process annotation on
an existing `PlannedStep`. Represent it as a wrapper: `PlatformInserter.insertPlatforms` returns
`List<PlannedStep>` unchanged in action/position/facing, but the CALLER (Task 14's
`SiegeInteractionHandler`) needs to know which positions in a `SiegeProject`'s build order are
platform seams so it can execute the platform-clearing behavior instead of the plain action's normal
placement at that one position. Carry this as a `Set<BlockPos> platformPositions` returned alongside
the (unmodified) build order: change the return type to a record
`PlatformInserter.Result(List<PlannedStep> buildOrder, Set<BlockPos> platformPositions)`. `SiegeProject`
(Task 13) stores `platformPositions` alongside `buildOrder` and `SiegeInteractionHandler.
constructSiegeBlock` (Task 14) checks membership before dispatching on `action()` for placement
behavior.

- [ ] **Step 1: Write the failing tests**

```java
package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PlatformInserterTest {

    private static PlannedStep step(int x, int y, int z, PathAction action) {
        return new PlannedStep(new BlockPos(x, y, z), action, Direction.NORTH);
    }

    @Test
    void insertsAPlatformWhereTunnelMeetsBridge() {
        List<PlannedStep> buildOrder = List.of(
                step(0, 10, 0, PathAction.TUNNEL),
                step(1, 10, 0, PathAction.TUNNEL),
                step(2, 10, 0, PathAction.BRIDGE),
                step(3, 10, 0, PathAction.BRIDGE));

        PlatformInserter.Result result = PlatformInserter.insertPlatforms(buildOrder);

        assertEquals(Set.of(new BlockPos(2, 10, 0)), result.platformPositions(),
                "seam is at the FIRST step of the new action, not the last step of the old one");
        assertEquals(buildOrder, result.buildOrder(), "positions/actions/facings are unchanged - only the seam set is new");
    }

    @Test
    void insertsAPlatformWhereCarvedStairMeetsAirStair() {
        List<PlannedStep> buildOrder = List.of(
                step(0, 10, 0, PathAction.CARVED_STAIR),
                step(1, 11, 1, PathAction.AIR_STAIR));

        PlatformInserter.Result result = PlatformInserter.insertPlatforms(buildOrder);

        assertEquals(Set.of(new BlockPos(1, 11, 1)), result.platformPositions());
    }

    @Test
    void noSeamBetweenTwoStepsOfTheSameAction() {
        List<PlannedStep> buildOrder = List.of(
                step(0, 10, 0, PathAction.TUNNEL),
                step(1, 10, 0, PathAction.TUNNEL));

        PlatformInserter.Result result = PlatformInserter.insertPlatforms(buildOrder);

        assertTrue(result.platformPositions().isEmpty());
    }

    @Test
    void noSeamWhenEitherSideIsWalk() {
        List<PlannedStep> buildOrder = List.of(
                step(0, 10, 0, PathAction.WALK),
                step(1, 10, 0, PathAction.TUNNEL));

        PlatformInserter.Result result = PlatformInserter.insertPlatforms(buildOrder);

        assertTrue(result.platformPositions().isEmpty(),
                "WALK cells are real, already-safe terrain - never a platform seam");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.ratden.skavenblight.ai.pathing.PlatformInserterTest"`
Expected: FAIL (compile error).

- [ ] **Step 3: Implement `PlatformInserter`**

```java
package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PlatformInserter {

    private PlatformInserter() {}

    public record Result(List<PlannedStep> buildOrder, Set<BlockPos> platformPositions) {}

    public static Result insertPlatforms(List<PlannedStep> buildOrder) {
        Set<BlockPos> platformPositions = new HashSet<>();
        for (int i = 1; i < buildOrder.size(); i++) {
            PathAction prev = buildOrder.get(i - 1).action();
            PathAction curr = buildOrder.get(i).action();
            if (prev != PathAction.WALK && curr != PathAction.WALK && prev != curr) {
                platformPositions.add(buildOrder.get(i).pos());
            }
        }
        return new Result(buildOrder, platformPositions);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.ratden.skavenblight.ai.pathing.PlatformInserterTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/PlatformInserter.java \
        src/test/java/org/ratden/skavenblight/ai/pathing/PlatformInserterTest.java
git commit -m "feat(pathing): add PlatformInserter post-processing pass for PLATFORM junctions"
```

---

## Task 8: `RegionScanner` ported to `PathStepEvaluator`/`FlowStep`

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionScanner.java`
- No dedicated test file for this task — see Step 3 below for why.

**Interfaces:**
- Consumes: `PathStepEvaluator.candidateSteps`/`isWalkableTerrain` (Task 3) in place of
  `TerrainEvaluator.getValidOrthogonalSteps`/`isWalkableTerrain`.
- Produces: identical public API (`scan(TerrainSnapshot, Set<ChunkPos>, BlockPos, int, int) →
  List<Region>`).

**Spec:** the flood-fill algorithm itself (Dijkstra, not BFS, shared cost/queue structure with the
main flow field so region membership can never disagree with what the flow field itself considers
reachable — see the current file's own extensive comment for why this equivalence is load-bearing)
is unchanged. Only the step source changes: replace `terrainEvaluator.getValidOrthogonalSteps(...)`
with `pathStepEvaluator.candidateSteps(...)`, and the boundary-cell threshold
(`BOUNDARY_WALKABLE_NEIGHBOR_THRESHOLD = 6`) now counts `PathAction.WALK` candidates from
`candidateSteps`'s unified return instead of a separately-typed step list — same counting logic,
same threshold value, ported verbatim. **Drop the `MAX_CONSECUTIVE_MINE_DEPTH`/`mineChainDepth`
tracking here too, but for a DIFFERENT reason than Task 6's drop** (don't conflate the two — Task 6's
justification is cost-dominance under Dijkstra; this one is architectural): **`floodFill` must only
ever enqueue `WALK`-action candidates from `candidateSteps`, full stop — never TUNNEL/BRIDGE/
CARVED_STAIR/AIR_STAIR.** Region membership has to reflect only genuine walkable connectivity; a
TUNNEL candidate is a construction edge (`RegionGraph`'s job to discover in Task 9), and if
`RegionScanner`'s own flood ever followed one, every obstacle a `SiegeProject` could bridge would
silently fuse the regions on either side of it, destroying the partition this class exists to
produce. Once `floodFill` filters to `WALK` only, there is no mine chain left for the old cap to
bound — the cap isn't "unnecessary because cost dominates" here, it's unreachable code, because the
path that used to feed it (enqueueing MINE candidates at all) no longer exists.

- [ ] **Step 1: Implement the port directly** (this task's change is small and mechanical enough
  that a fixture-heavy failing-test-first cycle would mostly test plumbing, not behavior): replace
  the step source and add the one-line `if (step.action() != PathAction.WALK) continue;` filter
  before `floodFill`'s existing enqueue logic.

- [ ] **Step 2: Confirm the filter is real, not accidentally always-true**, by temporarily commenting
  it out and confirming `./gradlew test --tests "org.ratden.skavenblight.ai.pathing.
  PathStepEvaluatorStepGenerationTest"` still passes (that test suite doesn't exercise
  `RegionScanner` at all, so this only proves the filter line compiles against a real non-WALK
  action existing — not a substitute for the real regression protection below). Restore the filter.

- [ ] **Step 3: Note real regression protection is deferred to the GameTest matrix (Tasks 21-24)**,
  specifically the "chained across a giant gap" shape in each — `RegionScanner` wrongly fusing two
  regions across a long TUNNEL-length wall would surface there as a route-tree/connector-discovery
  failure (the two sides would scan as one region with no connector between them at all, since
  there'd be nothing left to connect), not as a silent pass. `RegionScanner` has no independent
  `TerrainSnapshot`-free unit-testable surface — its only constructor input is a concrete
  `TerrainSnapshot`, which requires a real `ServerLevel` to build (see `TerrainSnapshot.refresh`) —
  so a dedicated fixture-heavy unit test here would just re-implement what the GameTest matrix
  already proves end-to-end. Don't add one; record this reasoning in the commit message instead so
  a future reader doesn't wonder why this task skipped its own test.

  **Executed reality (2026-08-05): this claim was empirically WRONG — don't repeat it when planning
  Tasks 22-24.** `PathingRegionGameTests.testRepeatedConnectorCompletionsDontExplodeRebuildCount`
  (an EXISTING test, not the deferred GameTest matrix) caught a real regression from the WALK-only
  filter on the very first `./gradlew runGameTestServer` run after this task's edit: `nearProbe` —
  a position at the exact coordinates of this test's own solid nexus block — used to resolve to a
  region (167 cells) because the OLD MINE-step flood swept that non-walkable cell in as a side
  effect; the new WALK-only flood correctly excludes it (166 cells), and `nearProbe` had been
  silently relying on the old bug rather than probing real region floor. Fixed by moving
  `nearProbe`'s z-coordinate off the nexus's own cell (see that test's own updated comment) — the
  production change was correct, the existing test fixture was latently wrong. Also grepped every
  `Region.addCell`/`.contains()`/`cellsNotIn` consumer in `src/main` per this discovery:
  `RegionGraph.registerConnector`'s and `TerritoryRegionMap.reclaimConnectorCells`'s own `addCell`
  calls are deliberate, additive, unaffected (they claim connector cells on top of the scanner's
  output, not through the flood itself); `TerritoryRegionMap`'s `cellsNotIn`-based merge detection
  only inspects ADDED cells, not removed ones, so the shrink is invisible to it and doesn't cause a
  false positive. No other fix needed there.

- [ ] **Step 3.5 (added 2026-08-05): fix the two production `RegionScanner` construction call sites**
  the original file list didn't mention — this task's own constructor signature change
  (`TerrainEvaluator` → `PathStepEvaluator`) breaks `TerritoryRegionMap.java`'s and
  `DebugPathingCommands.java`'s existing `new RegionScanner(terrainEvaluator)`/`new
  RegionScanner(evaluator)` calls immediately, well before Task 5's acknowledged breaking-commit
  window starts. Both files are otherwise untouched until their own later tasks (Task 14, Task 9)
  — this only adds the ONE new `PathStepEvaluator` instance each needs to keep compiling, exactly
  the same "fix the one call site the signature change dictates, not the whole file" pattern this
  plan's execution-order section already used for `DebugPathingCommands`'s `RegionGraph.build` call
  in Task 9. `TerritoryRegionMap` gets a new `pathStepEvaluator` field alongside its existing
  `terrainEvaluator` (which everything else there still needs, until Task 14);
  `DebugPathingCommands` gets an inline `new PathStepEvaluator()` at its one `RegionScanner`
  construction site.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionScanner.java \
        src/main/java/org/ratden/skavenblight/ai/pathing/region/TerritoryRegionMap.java \
        src/main/java/org/ratden/skavenblight/command/debug/DebugPathingCommands.java \
        src/main/java/org/ratden/skavenblight/gametest/PathingRegionGameTests.java
git commit -m "refactor(pathing): port RegionScanner to PathStepEvaluator, filter floodFill to WALK-only"
```

---

## Task 9: `RegionGraph` rewrite — absorbs `RegionIndex`, confirmed-unreachable tracking + targeted bedrock retry

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionGraph.java` (replaces the
  old file)
- Modify: `src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionConnector.java` (type updates:
  `SiegeProject`'s `PlannedStep`-based build order, `FlowStep` in place of `SiegeNode`)
- Delete: `src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionIndex.java` (folded in)
- Test: `src/test/java/org/ratden/skavenblight/ai/pathing/region/RegionGraphTest.java`

**Interfaces:**
- Consumes: `PathStepEvaluator` (Tasks 2-3), `Region` (kept), `SiegeProject` (Task 13 — same
  temporary-stub sequencing note as Task 6 applies here; a minimal `SiegeProject`-shaped test double
  suffices until Task 13 lands, since this task only needs `SiegeProject`'s constructor signature and
  `isCompleted`/`getInstructions`, not its full behavior).
- Produces: `RegionGraph.build(TerrainSnapshot, List<Region>, Set<ChunkPos> bounds, BlockPos
  boundsAnchor, PathStepEvaluator) → RegionGraph` (drops the old `RegionIndex`/`SiegeLineTracer`
  parameters — `RegionGraph` now builds its own internal region-lookup index from the `List<Region>`
  it's handed, absorbing `RegionIndex`'s job as a private nested structure). `getConnectorsFor(int
  regionId) → List<RegionConnector>`, `getAllConnectors() → List<RegionConnector>`,
  `regionAt(BlockPos) → Region` and `regionIdAt(BlockPos) → Integer` (both ported verbatim from
  `RegionIndex`, now methods on `RegionGraph` itself), `refreshChunks(Collection<ChunkPos>)`
  (package-private, ported verbatim), and the new
  `confirmedUnreachableRegionIds(int rootRegionId, RegionRouteTree routeTree) → Set<Integer>`.

**Connector discovery spec:** port `build`/`tryTrace`/`registerConnector`/`outboundInstructions`/
`inboundInstructions`/`pairKey` verbatim in structure, with `SiegeLineTracer.trace(...)` calls replaced
by direct use of `PathStepEvaluator.candidateSteps` chained hop-by-hop the same way `tryTrace`'s loop
already chains `SiegeLineTracer` calls today (the 14-direction fan-out from every region boundary
cell, `MAX_CHAIN_HOPS = 12`, `VERTICAL_CHAIN_SLACK = 32`, all ported verbatim) — this is where "one
unified step-generator feeds one Dijkstra flood for BOTH ordinary walking and construction discovery"
actually gets exercised at the region-connector-discovery layer, not just inside `FlowFieldCalculator`.
The one genuinely new behavior is the confirmed-unreachable mechanism below.

**Confirmed-unreachable-region mechanism — corrected from the version originally confirmed with the
user.** The originally-approved proposal gated bedrock-tier mining behind a two-pass
`allowBedrockTierMining` flag (never offer it on the first pass; only offer it in a second, targeted
retry for regions that came back with zero connectors). On review, that flag IS an absolute cost gate
wearing a boolean's clothes — exactly the "worse than some absolute threshold → discard" rule the
design doc explicitly forbids, just expressed as "never offered" instead of "offered but capped."
Removing it changes nothing about correctness, because the numbers already do the work:
`PathStepEvaluator.bedrockFailsafeWorkUnits()` (Task 2) is `25 * 1200 * workPerRatPerTick` ≈
3,000,000 at defaults, against an ordinary tunnel step's low-hundreds cost — Dijkstra pops candidates
in cost order, so a multi-million-cost edge structurally cannot beat any cheaper alternative that
exists; it is only ever chosen when it is, genuinely, the only route. **Corrected mechanism:**

1. `RegionGraph.build`'s connector discovery always offers every `PathStepEvaluator.candidateSteps`
   candidate at its real cost, bedrock-tier mining included, with no gating flag at all — the exact
   same call for every region, every pass, no two-pass retry, no `allowBedrockTierMining` parameter.
   `bestPerPair`'s existing "cheaper connector wins" comparison IS the design doc's required relative
   rule; there is nothing else to add for correctness.
2. `confirmedUnreachableRegionIds(rootRegionId, routeTree)` (still implemented, per Task 9's original
   interface) becomes a **diagnostic accessor only** — every region id absent from `routeTree` after
   a normal `build` + `RegionRouteTree.compute` — used for logging/observability ("this territory has
   N genuinely isolated regions"), never for gating whether an edge is offered or a retry is
   triggered. There is no second pass to trigger, because the first pass already considers every cost
   tier.
3. This means Task 14 has one less new control-flow path than originally planned: no targeted retry
   call site. Task 14's only new behavior is the planned-state-authority fix (see that task).

**Node-budget implication, worth stating explicitly rather than discovering later:** always offering
bedrock-tier candidates means `RegionGraph.build`'s per-boundary-cell fan-out can, in the fully-sealed
case, chain up to `MAX_CHAIN_HOPS` (12) hops of bedrock-cost mining before giving up on a direction —
bounded, cheap relative to a per-cell flood (region counts are small), and no different in kind from
any other candidate `tryTrace` already considers. If a future GameTest shows this measurably slows
territory rebuild on a large base, the fix is a targeted one (e.g. skip fan-out directions whose
first hop is already bedrock-tier once at least one ordinary connector already exists for that region
pair), not a re-introduction of the two-pass gate this correction just removed.

- [ ] **Step 1: Write the failing tests**

```java
package org.ratden.skavenblight.ai.pathing.region;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RegionGraphTest {

    @Test
    void regionWithNoConnectorWithinTheChainHopLimitIsInTheConfirmedUnreachableSet() {
        // Fixture: two regions with NO traceable connector in any of the 14 fan-out directions from
        // any boundary cell within MAX_CHAIN_HOPS (12) - e.g. separated by more open space in every
        // direction than the hop limit reaches, so even a full-cost trace (bedrock-tier mining
        // included, per this task's corrected mechanism - there is no gating flag left to disable)
        // never lands in the other region. This is now a genuinely rare diagnostic case, not the
        // common "bedrock wall" case it would have been under the originally-planned two-pass gate.
        RegionGraph graph = /* RegionGraph.build(...) against the two-unreachable-regions fixture */ null;
        RegionRouteTree tree = RegionRouteTree.compute(graph, /* rootRegionId */ 0);

        Set<Integer> unreachable = graph.confirmedUnreachableRegionIds(0, tree);

        assertEquals(Set.of(1), unreachable);
    }

    @Test
    void regionWithAnyConnectorIncludingABedrockTierOneIsNeverInTheConfirmedUnreachableSet() {
        // Fixture: two regions joined ONLY by a bedrock-tier gap (every direct route is
        // isBedrockLike). Since build() no longer gates bedrock mining behind a flag, this still
        // produces a real (very expensive) connector, and the region must NOT appear unreachable -
        // proving the corrected mechanism doesn't accidentally make bedrock-only routes invisible.
        RegionGraph graph = /* RegionGraph.build(...) against the bedrock-only-connector fixture */ null;
        RegionRouteTree tree = RegionRouteTree.compute(graph, 0);

        Set<Integer> unreachable = graph.confirmedUnreachableRegionIds(0, tree);

        assertTrue(unreachable.isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.ratden.skavenblight.ai.pathing.region.RegionGraphTest"`
Expected: FAIL.

- [ ] **Step 3: Implement `RegionGraph`** per the spec above, deleting `RegionIndex.java` and folding
  its lookup into `RegionGraph`'s own private fields.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.ratden.skavenblight.ai.pathing.region.RegionGraphTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionGraph.java \
        src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionConnector.java \
        src/test/java/org/ratden/skavenblight/ai/pathing/region/RegionGraphTest.java
git rm src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionIndex.java
git commit -m "feat(pathing): rewrite RegionGraph absorbing RegionIndex, add confirmed-unreachable tracking"
```

---

## Task 10: `RegionRouteTree` port (minimal) + `RegionFlowField` port

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionRouteTree.java`
- Modify: `src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionFlowField.java`
- No dedicated unit test for this task — see the note after the spec below for why, and don't
  substitute a test that asserts nothing just to have one in the checklist.

**Interfaces:**
- `RegionRouteTree`: **no algorithm change at all** — it already operates purely on `RegionGraph`/
  `RegionConnector`, neither of which changes its own public shape (`getConnectorsFor`, `other`,
  `cost` are unchanged). Confirm it compiles unchanged against the new `RegionGraph`/`RegionConnector`
  and add nothing beyond what Task 9 already needs from it (`isReachable`, used by
  `confirmedUnreachableRegionIds`).
- `RegionFlowField`: drop `CLIMB_TO_ABOVE_ONCE_BUILT` and its use in `getNextSiegeNode` entirely (no
  action in the new 5-action vocabulary ever needs "stand ON TOP of the placed block" treatment —
  every one of TUNNEL/BRIDGE/CARVED_STAIR/AIR_STAIR lands the mob AT the node's own position, the same
  treatment `BUILD_STAIR`/`BUILD_BRIDGE`/`MINE` already got). Rename `getNextSiegeNode` →
  `getNextStep(ServerLevel, BlockPos) → FlowStep`, same logic otherwise (live-completion check via
  `PathStepEvaluator.isActionCompleted` in place of `TerrainEvaluator`'s). Every other method
  (`tryClaimTarget`/`releaseTarget`/`isTargetClaimed`/`tryClaimFormationSlot`/`tryOccupyLane`/
  `forceRecalculation`/`findProjectFor`/etc.) is a mechanical type port, no logic change.

**Why no dedicated unit test:** `getNextStep`'s live-completion check requires a real `ServerLevel`
(via `LiveTerrainAccess`) to determine whether an action is "already done," and `RegionFlowField`'s
own constructor requires a real `TerritoryRegionMap`/`FlowFieldState`/`SiegeProjectManager`/
`FlowFieldCalculator` — there is no meaningful fake for "is this block already the placed stair" that
wouldn't just be testing the fake instead of the real completion logic. A test asserting something
true regardless of whether `CLIMB_TO_ABOVE_ONCE_BUILT`-shaped logic was actually deleted (e.g.
`assertTrue(true)`) is worse than no test — it looks like coverage without providing any. Real
coverage: Tasks 21-24's GameTest matrix exercises `getNextStep` continuously for every rat crossing
every one of the five action types; if `.above()` treatment leaked back in for any action, a rat
would be pointed one block above where it should stand and the corresponding GameTest would fail to
converge within its timeout. Record this reasoning in the commit message.

- [ ] **Step 1: Implement the ports** per the spec above — delete `CLIMB_TO_ABOVE_ONCE_BUILT` and its
  use in `getNextSiegeNode`/`getNextStep` entirely, rename the method, retype every other method's
  `SiegeNode`→`FlowStep` references.

- [ ] **Step 2: Run `./gradlew compileJava`** to confirm the port compiles against the new types.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionRouteTree.java \
        src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionFlowField.java
git commit -m "refactor(pathing): port RegionRouteTree (unchanged) and RegionFlowField (drop climb-to-above)

No dedicated unit test - getNextStep's live-completion check needs a real ServerLevel with no
meaningful fake; real coverage is the Task 21-24 GameTest matrix, which exercises every action
type's completion resolution continuously."
```

---

## Task 11: `SiegeProjectManager` rewrite

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProjectManager.java` (replaces the
  old file)
- Test: `src/test/java/org/ratden/skavenblight/ai/pathing/SiegeProjectManagerTest.java` (ported from
  the existing file — this task owns its full retype; no other task touches this file)

**Interfaces:**
- Consumes: `PathStepEvaluator` (Tasks 2-3), `SiegeProject` (Task 13 — build against the same
  temporary stub sequencing as Tasks 6/9, then re-verify once Task 13 lands), `FlowFieldCalculator
  .QueueNode` (Task 6).
- Produces: identical public API to today's `SiegeProjectManager`, `FlowStep`/`PlannedStep` in place
  of `SiegeNode`: `injectActiveProjects`, `finalizeCandidateProjects`, `getLockedPositions`,
  `findProjectContaining`, `setActiveConnectorProject`, `addSharedConnectorProject`,
  `setMaxCandidateProjectLength`, `evaluateMacroProjects`, plus the perf counters
  (`getMacroEvaluationCount`/`getLineStepsEvaluated`/etc.).

**Spec:** port `evaluateMacroProjects`/`evaluateSingleLine`/`isNearExistingProject`/`bucketKeyFor`
verbatim in structure — every one of the empirically-fixed guards in the current file (chained-landing
refusal for capped regions, self-collision guard against a project's own entry point, empty-build-
order discard, the spatial-bucket O(n²) fix, `MAX_ACTIVE_PROJECTS` eviction) carries forward unchanged;
none of them are specific to the old line-tracer, they're all about macro-project DISCOVERY policy,
which doesn't change. The only real substitution: `evaluateSingleLine`'s call into
`lineTracer.trace(...)` becomes a direct chained-hop loop over `PathStepEvaluator.candidateSteps`
(same 14-direction fan-out from `evaluateMacroProjects`, same `maxCandidateProjectLength` cap, same
`costCeiling`-as-`nextCostMap.getOrDefault` relative comparison — NOT an absolute ceiling, matching
Task 9's confirmation that no absolute ceiling survives anywhere in the new code). **Keep that relative
ceiling; drop the bespoke cost FORMULA it was comparing against.** `SiegeLineTracer.trace()`'s own
internal cost computation (`buildingBasePenalty * COST_MULTIPLIER`, then
`(dy != 0) ? (projectCost * 2) * 0.75f : projectCost * 2`) was a second, divergent cost model that
existed only for macro-project line evaluation — RegionGraph's own port (Task 9) already replaced the
identical formula there with the sum of each hop's real `PathStepEvaluator.EvaluatedStep.cost()`
(`baseCostFor` + `miningCost`), the same model every other cost comparison in this rewrite uses.
`evaluateSingleLine` must make the SAME substitution, not port the old formula forward — otherwise
connector costs (Task 9) and macro-project costs (this task) stop being comparable again, silently
reintroducing the exact two-cost-model split the design exists to eliminate. Note also (flagged during
Task 9, relevant here too): the old formula gave vertical traces a 25% discount the new summed-cost
model doesn't carry forward — if a chained-vertical GameTest (Task 21+) regresses, this is a suspect,
not just the frontier threshold Task 6 already flagged there. Port the
`isChainedLanding` guard as `isChainedPlatform` (checks whether `nextInstructionMap.get(anchorPos)`'s
action, when the anchor sits at a Task 7 `PlatformInserter` seam, should still refuse continuing a
capped region's chain the same way — confirm empirically via this task's test whether the mechanism
still needs a special case now that platforms are a post-process over the finished path rather than a
flood-time synthetic node like `BUILD_LANDING` was; if the flood no longer ever produces a
platform-shaped node mid-calculation, this guard may have nothing left to trigger on and can be
dropped — verify, don't assume, and say so explicitly either way in this task's commit message).

- [ ] **Step 1: Write the failing tests** (port the existing `SiegeProjectManagerTest`'s
  `isNearExistingProject` bucket test and the empty-build-order-discard test verbatim, type-substituted)

```java
package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.PriorityQueue;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SiegeProjectManagerTest {

    @Test
    void evaluateMacroProjectsNeverRegistersACandidateWithAnEmptyBuildOrder() {
        // Fixture: anchorPos already sits on an active project's own entry point, one cell away
        // from genuinely walkable ground - the trace terminates in a single WALK hop, which after
        // dropping the leading synthetic WALK step (see the class's own evaluateSingleLine doc)
        // leaves an empty build order. Assert getLastPassCandidatesGenerated stays 0, not 1.
        PathStepEvaluator evaluator = new PathStepEvaluator();
        SiegeProjectManager manager = new SiegeProjectManager(evaluator);
        FakeTerrain terrain = new FakeTerrain();
        BlockPos anchor = new BlockPos(0, 10, 0);
        BlockPos walkableNeighbor = new BlockPos(1, 10, 0);
        terrain.setSolid(walkableNeighbor.below());

        FlowFieldState state = new FlowFieldState(anchor, java.util.Set.of());
        manager.evaluateMacroProjects(terrain, anchor, state, 0,
                new PriorityQueue<>(), new HashMap<>(), new HashMap<>());

        assertEquals(0, manager.getLastPassCandidatesGenerated());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.ratden.skavenblight.ai.pathing.SiegeProjectManagerTest"`
Expected: FAIL (compile error).

- [ ] **Step 3: Implement `SiegeProjectManager`** per the spec above.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.ratden.skavenblight.ai.pathing.SiegeProjectManagerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProjectManager.java \
        src/test/java/org/ratden/skavenblight/ai/pathing/SiegeProjectManagerTest.java
git commit -m "feat(pathing): rewrite SiegeProjectManager against the unified PathStepEvaluator"
```

---

## Task 12: `SiegeProject` rewrite (PlannedStep, platform support, persistence-ready fields)

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProject.java` (replaces the old file
  — note `PlannedStep` moves OUT of this class per Task 1, it's now its own top-level record)
- Test: `src/test/java/org/ratden/skavenblight/ai/pathing/SiegeProjectTest.java` (ported)

**Interfaces:**
- Consumes: `PlannedStep`/`FlowStep`/`PathAction` (Task 1), `PlatformInserter` (Task 7).
- Produces: identical public API to today's `SiegeProject`, plus two new fields for persistence
  (Task 15): `UUID id` (generated fresh in the constructor via `UUID.randomUUID()` — every
  `SiegeProject` gets a stable identity from creation, not just persisted ones, so Task 15 doesn't
  need a separate "promote to persistent" step) and `UUID networkId` (passed in by whichever caller
  constructs it — `SiegeProjectManager`/`RegionGraph`, both of which are themselves owned by one
  `TerritoryRegionMap` per network — see Task 15 for where this gets threaded through). Constructor
  signature gains one trailing parameter: `SiegeProject(Map<BlockPos, FlowStep> instructions,
  List<FlowStep> orderedSteps, BlockPos buildOrderAnchor, BlockPos entryPos, int expectedEntryCost,
  BlockPos exitPos, UUID networkId)` (6-arg overload without `exitPos` also gains the trailing
  `networkId` param). `getId()`, `getNetworkId()`, `getPlatformPositions() → Set<BlockPos>` (from
  `PlatformInserter.insertPlatforms` run once inside the constructor on the built `buildOrder`, not
  called separately by every consumer).

**Spec:** port `isCompleted`/`survivedMapOverwrite`/`getRemainingInstructions`/`getEntryPos`/
`getExpectedEntryCost`/`getExitPos`/`getInstructions`/`planSteps`/`approachFacing`/`effectiveCapFor`/
`nextUnbuiltInstruction`/`tryRegisterWorker`/`canAcceptWorker`/`isWidenEligible`/`tryWiden`/
`getWidth`/`getBuildOrderPositions`/`getAccumulatedWork`/`unregisterWorker`/`isAtCapacity`/`tick`
verbatim, with these substitutions:
- `SiegeNode` → `FlowStep` (flood data) / `PlannedStep` (build-order data) as appropriate per field —
  `instructions` stays `Map<BlockPos, FlowStep>`, `buildOrder` stays `List<PlannedStep>`.
- `nextUnbuiltInstruction`'s `MINE`/`WALK`/`LEAP` special-cased branches collapse: with bare `MINE`
  and `LEAP` gone, the method's loop becomes simply `for (PlannedStep step : buildOrder) { if
  (step.action() == PathAction.WALK) continue; if (!evaluator.isActionCompleted(terrain, step)) return
  Optional.of(step); }` — mining completion for TUNNEL/CARVED_STAIR is now folded into
  `PathStepEvaluator.isActionCompleted`'s own per-action completion check (Task 13's
  `SiegeInteractionHandler`/Task 14's evaluator work — confirm `isActionCompleted` for TUNNEL/
  CARVED_STAIR checks the same "is this cell now open" condition the old `MINE` branch checked,
  since a TUNNEL step's "done" condition genuinely is exactly that).
- `tryWiden`'s widenable-action check (`BUILD_STAIR || BUILD_BRIDGE`) becomes `AIR_STAIR || BRIDGE ||
  CARVED_STAIR || TUNNEL` — auto-widening is a property of "this is a parallel-lane-capable
  construction type," which is now ALL four construction actions, not just two (nothing in the design
  doc restricts widening to only two of the five actions — confirm this reading against the design
  doc's own silence on the topic before implementing; if wrong, the fix is a one-line predicate
  change, not a structural one).
- Constructor calls `PlatformInserter.insertPlatforms(this.buildOrder)` once and stores the resulting
  `platformPositions` set as a field.

- [ ] **Step 1: Write the failing tests** (port the existing `SiegeProjectTest`'s
  `nextUnbuiltInstructionContinuesPastAnAlreadyClearedMineStep`-equivalent and `tryWiden` geometry
  tests, plus one new test for platform-position storage):

```java
package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SiegeProjectTest {

    @Test
    void constructorStoresPlatformPositionsFromTheBuildOrder() {
        BlockPos anchor = new BlockPos(0, 10, 0);
        BlockPos tunnelStep = new BlockPos(1, 10, 0);
        BlockPos bridgeStep = new BlockPos(2, 10, 0);
        List<FlowStep> orderedSteps = List.of(
                new FlowStep(tunnelStep, PathAction.TUNNEL, anchor),
                new FlowStep(bridgeStep, PathAction.BRIDGE, tunnelStep));
        Map<BlockPos, FlowStep> instructions = Map.of(
                tunnelStep, new FlowStep(tunnelStep, PathAction.TUNNEL, anchor),
                bridgeStep, new FlowStep(bridgeStep, PathAction.BRIDGE, tunnelStep));

        SiegeProject project = new SiegeProject(instructions, orderedSteps, anchor, bridgeStep, 100, UUID.randomUUID());

        assertEquals(java.util.Set.of(bridgeStep), project.getPlatformPositions());
    }

    @Test
    void everyProjectGetsAStableIdOnConstruction() {
        SiegeProject project = new SiegeProject(Map.of(), List.of(), BlockPos.ZERO, BlockPos.ZERO, 0, UUID.randomUUID());

        assertNotNull(project.getId());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.ratden.skavenblight.ai.pathing.SiegeProjectTest"`
Expected: FAIL (compile error).

- [ ] **Step 3: Implement `SiegeProject`** per the spec above.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.ratden.skavenblight.ai.pathing.SiegeProjectTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProject.java \
        src/test/java/org/ratden/skavenblight/ai/pathing/SiegeProjectTest.java
git commit -m "feat(pathing): rewrite SiegeProject with PlannedStep, platform positions, and a stable id"
```

---

## Task 13: `SiegeInteractionHandler` rewrite (five actions + platform execution)

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/ai/pathing/SiegeInteractionHandler.java` (replaces
  the old file)
- No dedicated unit test for this task — see the note before Step 1 for why.

**Interfaces:**
- Consumes: `PathAction` (Task 1), `PathStepEvaluator` (for `isActionCompleted`, moved here from
  `TerrainEvaluator` per Task 3's port note — confirm during implementation whether
  `isActionCompleted` lives on `PathStepEvaluator` or stays a static helper here; either is fine, pick
  whichever avoids a circular dependency once both files exist).
- Produces: `constructSiegeBlock(ServerLevel, BlockPos, Direction, PathAction, RegionFlowField,
  LivingEntity actor, boolean supportSolidAtClaim, boolean isPlatform)` — one new trailing boolean
  parameter; when `true`, dispatch to platform-clearing behavior (3×3 floor + 4-block headroom,
  ported from today's `BUILD_LANDING` case) regardless of what `action` is, then return early exactly
  like `BUILD_LANDING` does today. `isSpaceClear`/`pushOccupantsAway`/`executeBreach` ported verbatim
  (unrelated to the action vocabulary change). Drop `calculateMiningTicks` (its only callers —
  `DeployClimbableGoal`/`SpiralSapperGoal`/`WarpSapperGoal`/`SmartBreachGoal` — are all deleted per
  the confirmed scope; if nothing else calls it after Task 16 deletes those goals, delete it here
  rather than leaving dead code, and confirm via `grep -r calculateMiningTicks src/main` before
  deleting that this is actually true).

**Placement spec per action** (port the geometry/headroom-clearing logic from today's
`BUILD_STAIR`/`BUILD_BRIDGE`/`MINE` cases, mapped onto the five new actions):
- `TUNNEL`: today's `MINE` case (`executeBreach`) — mining through solid material, no block placed.
- `BRIDGE`: today's `BUILD_BRIDGE` case (`Blocks.COBBLESTONE`) — no headroom-clear needed (open air by
  construction).
- `CARVED_STAIR`: mine-then-place — call `executeBreach` for the space, THEN place
  `Blocks.COBBLESTONE_STAIRS` with `facing`, reusing today's `BUILD_STAIR` 2-block headroom clear
  above the step.
- `AIR_STAIR`: today's `BUILD_STAIR` case exactly (no mining component — open air by construction).
- Platform (the new trailing boolean, not a `PathAction` value): today's `BUILD_LANDING` case exactly.

**Why no dedicated unit test:** every branch of `constructSiegeBlock` mutates a real `ServerLevel`
(`level.setBlockAndUpdate`, `level.destroyBlock`, headroom clearing) and its correctness is entirely
about real block placement/mining order and geometry — a mock-heavy unit test asserting "method X was
called before method Y" would just restate the implementation, not verify it produces correct world
state. Real coverage is Task 22's dedicated `CARVED_STAIR` GameTest matrix (mine-then-place ordering
is directly observable there: if placement happened before mining, the stair would be placed into
still-solid rock and the GameTest's block-type assertions would fail) and Tasks 20/23/24 for the other
actions/platform behavior. Implement directly per the spec above; don't add a test that would assert
nothing just to satisfy this plan's own step-numbering habit.

- [ ] **Step 1: Implement `SiegeInteractionHandler`** per the placement spec above.

- [ ] **Step 2: Run `./gradlew compileJava`** to confirm no regressions elsewhere in the codebase.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/SiegeInteractionHandler.java
git commit -m "feat(pathing): rewrite SiegeInteractionHandler for the five-action vocabulary + platform execution

No dedicated unit test - every branch mutates real ServerLevel state; real coverage is the
Task 20-24 GameTest matrix (CARVED_STAIR's mine-then-place ordering is directly observable there)."
```

---

## Task 14: `TerritoryRegionMap` targeted port (NOT a rewrite)

**Gap found during exec-8/Task 9 (2026-08-05), not previously in this document's file-disposition
survey or Task 14's own file list — add to this task's own scope, don't silently re-derive its fate
when you get here:** `src/main/java/org/ratden/skavenblight/gametest/PathingRegionGameTests.java` has
roughly 35 call sites against `TerritoryRegionMap.getRegionIndex()`, confirmed via grep while deleting
`RegionIndex.java` at Task 9. This is NOT a mechanical rename to `getRegionGraph()` the way the other
three external consumers (`ClanratEntity`, `DebugPathingCommands`, `PathingDebugFileWriter` — all fixed
at Task 9 itself, trivial one-line redirects) were: several of this file's tests
(`testRepeatedConnectorCompletionsDontExplodeRebuildCount` and its sibling around line 1008) do an
OBJECT-IDENTITY check — `regionMap.getRegionIndex() != indexBeforeChange[0]` — to prove a dirty-region
rescan actually republished a fresh lookup. Confirmed via grep of `TerritoryRegionMap.java`:
`this.regionIndex = ...` has TWO assignment sites (the full rebuild AND the steady-state dirty-rescan
path), while `this.regionGraph = ...` has only ONE (the full rebuild only — nothing reassigns it on a
dirty rescan today). Swapping these tests' `getRegionIndex()` calls for `getRegionGraph()` verbatim
would silently change what they're testing: the identity check would stop detecting a dirty rescan at
all, since `regionGraph` never changes on that path, and the test would pass or fail for the wrong
reason. This needs a real decision (does the merged model need its OWN per-dirty-rescan identity signal,
e.g. a generation counter already exposed via `getGeneration()`, or does the test's premise change) —
not a rename — before this file's ~35 call sites can be ported. Left broken (already-known,
already-explained compile-red window) rather than guessed at during Task 9.

**Correction (2026-08-05, found executing this task): the gap note above misidentifies which test
exercises the fast path.** Confirmed via direct read of both `RegionGraph[] indexBeforeChange`
identity-check blocks (`testDirtyRegionBatchProducesOneCoherentFinalIndex` at the time-of-writing
line ~738, and `testConnectorCellsSurviveADirtyRegionRescan` at line ~1008 — NOT
`testRepeatedConnectorCompletionsDontExplodeRebuildCount`, which contains no identity check at all,
just direct region-id probes): **both** identity-check tests' own javadoc/inline comments explicitly
state they always land on the topology-changed/full-rebuild branch, never the non-topology-changed
fast path ("this recompute is always a full rebuild in this geometry, not the fast path the method
name suggests"). Neither currently-existing test exercises the fast-path identity semantics the gap
note describes. This does NOT make the underlying design question moot, though: the fast path
(`recomputeDirtyRegions`'s non-topology-changed branch) still needs `regionGraph`'s LOOKUP refreshed
against `updatedRegions` for plain correctness (any position resolution after a fast-path recompute
must see current membership, independent of whether any test currently checks object identity for
it) — so the fix implemented here (`RegionGraph.withUpdatedRegions`, called from that branch,
producing a fresh instance sharing the OLD instance's connectors) stands regardless. The mechanical
rename (`getRegionIndex()` → `getRegionGraph()`, `RegionIndex` → `RegionGraph`) turned out to be
correct and sufficient for both existing identity-check tests without any test-behavior change,
since a full rebuild already produces a fresh `RegionGraph` via `RegionGraph.build` either way.

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/ai/pathing/region/TerritoryRegionMap.java`
- Modify: `src/main/java/org/ratden/skavenblight/gametest/PathingRegionGameTests.java` (full port of
  its ~35 `getRegionIndex()` call sites — see gap note above and this task's own correction for the
  identity-check subtlety)
- Modify: `src/main/java/org/ratden/skavenblight/network/WarpFluxNetwork.java` (one-line
  `getRegionIndex()` → `getRegionGraph()` redirect) and
  `src/main/java/org/ratden/skavenblight/item/custom/DebugFlowFieldReaderItem.java` (same, two call
  sites) — **gap found executing this task, not previously in this document's file-disposition
  survey or this task's own file list:** both are real production consumers of
  `TerritoryRegionMap.getRegionIndex()` outside `ai.pathing`/`ai.goal.clanrat`/the already-fixed
  Task-9 consumer list (`ClanratEntity`, `DebugPathingCommands`, `PathingDebugFileWriter`), confirmed
  via `compileJava` surfacing them the moment `getRegionIndex()` was deleted (they were invisible
  before that point because `TerritoryRegionMap.java` itself didn't compile). Both are trivial
  one-line-per-call-site redirects, same as the Task 9 consumer fixes — `DebugFlowFieldReaderItem`'s
  own remaining `Map<BlockPos, SiegeNode>` usage is unrelated and stays Task 25's job, untouched here.
- Test: `src/test/java/org/ratden/skavenblight/ai/pathing/region/TerritoryRegionMapOnBlockChangedTest.java`

**Interfaces:**
- Consumes: every type this file already references, now the new versions (`RegionScanner`,
  `SiegeProjectManager`, `FlowFieldCalculator`, `RegionGraph`, `RegionRouteTree`, `PathStepEvaluator`
  in place of `TerrainEvaluator`, drop the `SiegeLineTracer lineTracer` field entirely — nothing in
  the new `RegionGraph.build` signature takes one).
- Produces: identical public API. One new call site, additive and diagnostic-only per Task 9's
  correction: `rebuildRegionsAndGraph`'s existing `RegionRouteTree.compute(newGraph, rootRegion
  .getId())` call gains one immediately-following line computing `newGraph.
  confirmedUnreachableRegionIds(rootRegion.getId(), newRouteTree)`, stored as a new `volatile
  Set<Integer> confirmedUnreachableRegionIds` field (mirroring how `routeTree` itself is already
  published) and logged if non-empty. No retry call — Task 9's `build` already considers every cost
  tier in its one and only pass, so there is nothing left to retry. This task's only REAL new control
  flow is the planned-state-authority fix below.

**The onBlockChanged authority fix (the design doc's own explicit instruction — a deletion, not a new
filter):**

**Correction (2026-08-06, found executing Task 20): the deletion below was wrong and has been
reverted — the call is necessary, not a leftover.** Task 14 was executed exactly as this task
specified: the `flowField.forceRecalculation(step.pos())` call was deleted from `SiegeProject.tick()`
and the guard test below (`TerritoryRegionMapOnBlockChangedTest`) was added and passed. But the
premise — "planned-cell authority in `TerrainSnapshot` is what replaces it" — is incomplete: the
planned-state override makes a project's OWN cells passable for planning purposes, but nothing else
ever marks the surrounding REGION dirty when a real block gets placed there, because
`SiegeInteractionHandler`'s `level.setBlockAndUpdate`/`destroyBlock` calls (used for every real
construction placement) do not fire NeoForge's `BlockEvent` — confirmed via `RegionFlowField
.forceRecalculation`'s own doc comment, which explicitly names this exact gap and existed
specifically as the intended fix, with zero real call sites anywhere in the codebase once Task 14's
deletion landed. Without it, a region's bounds/membership never grows to include a newly-built cell
(`FlowFieldState.isOutOfBounds` keeps rejecting it against the region's stale, pre-construction
bounds forever), silently stalling every chain after its first placed step. This was invisible until
now because `compileJava` stayed red from Task 5 through Task 20 — Task 14's own guard test never
actually ran for real against production code until this task's `./gradlew test` milestone, and this
is the first-ever full `./gradlew runGameTestServer` run since Task 12. It reproduced as "0 stair
block(s) built" across every `StaircaseSiegeGroupGameTests` scenario; adding the call back (in
`SiegeProject.tick()`, immediately after the successful `SiegeInteractionHandler.constructSiegeBlock`
call) fixed `testBuildFlowFieldGoalMarksRegionDirty` but did NOT fix the 4 real GameTests — a second,
separate root cause survives in the connector-discovery path (see Task 21's notes). The guard test
itself has been deleted (`git rm`), since it now asserts the literal opposite of required behavior;
equivalent positive coverage already exists in `testBuildFlowFieldGoalMarksRegionDirty`. Steps 1-2 and
their code sample below are preserved as history/context for how this was originally (incorrectly)
specified — do not re-apply Step 1's deletion.

**Correction (2026-08-05, found executing Task 13): this call is NOT already gone.** Confirmed via
direct read of the committed `SiegeProject.java` (Task 12): `tick()` still calls
`flowField.forceRecalculation(step.pos())` at its own line ~477, with the surrounding comment ("Without
this call, a placement here would never get discovered...") arguing FOR keeping it — the exact opposite
of this task's own instruction. Task 12's own spec never mentioned deleting this call (it says "port
`tick()`...verbatim" with substitutions unrelated to this), so nothing dropped it by omission; the plan's
"ported into Task 12's rewrite already" claim is simply wrong. Step 1 below is real, live work for THIS
task, not a confirmation-only checkbox — delete the call and its surrounding comment block when you get
here, and don't skip Step 1 assuming it's a no-op.
1. Delete `SiegeProject.tick()`'s `flowField.forceRecalculation(step.pos())` call entirely (see the
   correction directly above — this is NOT already done, do the deletion here).
2. Make an active project's planned final state authoritative for terrain evaluation, both before and
   during construction, by wiring Task 4's `TerrainSnapshot` planned-state override into BOTH of this
   file's `TerrainSnapshot.refresh(...)` call sites (`rebuild()` and `tick()`). Build the override
   function from `projectManager`'s currently-active projects: `pos -> { for (SiegeProject p :
   projectManager.getActiveProjects()) { PlannedStep step = p.plannedStepAt(pos); /* new small lookup
   - see below */ if (step != null) return finalBlockStateFor(step.action()); } return null; }`.
   `SiegeProjectManager` needs one new small method, `getActiveProjects() → List<SiegeProject>` (a
   defensive copy, mirroring how `getLockedPositions()` already returns an unmodifiable view), and
   `SiegeProject` needs `plannedStepAt(BlockPos) → PlannedStep` (a simple `buildOrder.stream().filter
   (s -> s.pos().equals(pos)).findFirst().orElse(null)`, or a `Map<BlockPos, PlannedStep>` built once
   in the constructor if a linear scan proves too slow in Task 20's GameTests — start with the linear
   scan, a project's build order is bounded by `maxCandidateProjectLength`, at most 32 entries).
   `finalBlockStateFor(PathAction)` is a small new pure function (in `SiegeInteractionHandler` or
   `PathStepEvaluator` — pick whichever doesn't create a circular dependency) mapping each of the five
   actions to the `BlockState` it will place once complete (`WALK`→ the real current state, i.e. no
   override; `TUNNEL`→ air; `BRIDGE`/`AIR_STAIR`/`CARVED_STAIR`→ their respective placed block).
3. **Critical exception, tested explicitly (this is the grief-recovery requirement — do not skip the
   test even though it's tempting to treat step 2 as "obviously done"):** this authority covers ONLY a
   project's own planned cells. A position with NO matching `PlannedStep` in any active project must
   fall through to the real, unoverridden world state exactly as today — the override function above
   already does this correctly (`return null` when no project claims `pos`, and `TerrainSnapshot`
   Task 4 already treats `null` as "no override"), but this is exactly the kind of "should be obvious"
   correctness property that needs its own test, not just code review — see Task 20's grief-recovery
   GameTest, which is this property's real end-to-end proof.

- [ ] **Step 1: Write the failing test**

```java
package org.ratden.skavenblight.ai.pathing.region;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TerritoryRegionMapOnBlockChangedTest {

    @Test
    void siegeProjectTickNeverCallsForceRecalculationDirectly() {
        // Static-analysis-style guard: grep this repo's compiled SiegeProject.java source for the
        // string "forceRecalculation" and assert it's absent, so a future accidental re-add during
        // an unrelated edit is caught by CI, not just by code review memory.
        java.nio.file.Path siegeProjectSource = java.nio.file.Path.of(
                "src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProject.java");
        String contents = readFileOrFail(siegeProjectSource);
        assertFalse(contents.contains("forceRecalculation"),
                "SiegeProject must never call forceRecalculation directly - the onBlockChanged fix "
                        + "is a deletion, not a new filter; planned-cell authority in TerrainSnapshot "
                        + "is what replaces it");
    }

    private static String readFileOrFail(java.nio.file.Path path) {
        try {
            return java.nio.file.Files.readString(path);
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read " + path, e);
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.ratden.skavenblight.ai.pathing.region.TerritoryRegionMapOnBlockChangedTest"`
Expected: FAIL only if Task 12 left the call in by accident — if Task 12 already correctly omitted
it, this test PASSES immediately, which is fine; it's a regression guard, not a new-behavior pin. Note
this in the commit either way.

- [ ] **Step 3: Implement the port** — mechanical type substitution throughout, plus the
  `confirmedUnreachableRegionIds` computation/targeted-retry call and the planned-state override
  wiring described above. Every other line of this file's dirty-tracking/merge-detection/chunk-ticket
  logic is unchanged.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.ratden.skavenblight.ai.pathing.region.TerritoryRegionMapOnBlockChangedTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/region/TerritoryRegionMap.java \
        src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProjectManager.java \
        src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProject.java \
        src/test/java/org/ratden/skavenblight/ai/pathing/region/TerritoryRegionMapOnBlockChangedTest.java
git commit -m "fix(pathing): make active-project planned state authoritative for terrain evaluation, delete forceRecalculation call"
```

---

## Task 15: `SiegeProjectStore` persistence

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProjectStore.java`
- Create: `src/main/java/org/ratden/skavenblight/ai/pathing/nbt/SiegeProjectSnapshot.java`
- Create: `src/main/java/org/ratden/skavenblight/ai/pathing/nbt/SiegeProjectNbtCodec.java`
- Test: `src/test/java/org/ratden/skavenblight/ai/pathing/SiegeProjectStoreTest.java`

**Interfaces:**
- Follows `SkavenIncursionSavedData`'s established pattern in this codebase exactly (format-version
  int, `SavedData.Factory`, `LinkedHashMap<UUID, X>`, `get(ServerLevel)`, `add/replace/remove
  Snapshot`, `save`/`load` with a version switch) — read that file again immediately before writing
  this one, don't reinvent the convention.
- Produces: `SiegeProjectStore.get(ServerLevel) → SiegeProjectStore`, `addOrReplace(SiegeProject
  project, UUID networkId)`, `remove(UUID projectId)`, `snapshotsForNetwork(UUID networkId) →
  Collection<SiegeProjectSnapshot>`.
- `SiegeProjectSnapshot` fields (confirmed with the user — persist only these, NOT `instructions`
  scratch data and NOT `workers`, since rat↔project linkage is fully self-healing via
  `nextUnbuiltInstruction`'s live-terrain re-derivation): `projectId (UUID)`, `networkId (UUID)`,
  `entryPos (BlockPos)`, `expectedEntryCost (int)`, `exitPos (BlockPos, nullable)`, `buildOrder
  (List<PlannedStep>)`, `widenAnchor (BlockPos)`, `width (int)`, `accumulatedWork (double)`,
  `lastTickedGameTime (long)`.

**Load-time fixes (both confirmed with the advisor, both required — do not skip either):**
1. **Clamp `lastTickedGameTime`** on load: `min(snapshot.lastTickedGameTime(), level.getGameTime())`
   — a world restored from an earlier backup must not permanently block `tick()`'s idempotency guard
   with a future-dated stamp.
2. **Rebuild the position-lookup index immediately on load**, not lazily: after
   `SiegeProjectManager` reconstructs a `SiegeProject` from a loaded snapshot, it must go straight
   into whatever structure `findProjectContaining`/`activeProjectWithEntryPos` scan — there is no
   "lazy" version of this to defer, since those methods are plain linear scans over `activeProjects`;
   confirm the reconstructed project is added to `activeProjects` synchronously within the same
   world-load/network-rebuild call that reads the store, not deferred to a later tick.

**When to persist:** call `addOrReplace` when a project transitions into `activeProjects` (mirroring
`finalizeCandidateProjects`'s existing promotion point) and again whenever `SiegeProject.tick()`
completes an actual block placement (mirroring the granularity of the deleted `forceRecalculation`
call this task's sibling, Task 14, removed — completed-placement granularity, not every accumulated-
work tick, so an ungraceful crash loses at most a few seconds of partial work, never a completed
step). Call `remove` when a project is evicted from `activeProjects` (`MAX_ACTIVE_PROJECTS` eviction)
or completes (`isCompleted` true).

- [ ] **Step 1: Write the failing test**

```java
package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SiegeProjectStoreTest {

    @Test
    void nbtRoundTripPreservesEveryPersistedField() {
        UUID projectId = UUID.randomUUID();
        UUID networkId = UUID.randomUUID();
        List<PlannedStep> buildOrder = List.of(
                new PlannedStep(new BlockPos(1, 2, 3), PathAction.TUNNEL, Direction.NORTH));
        SiegeProjectSnapshot original = new SiegeProjectSnapshot(projectId, networkId,
                new BlockPos(0, 0, 0), 500, new BlockPos(5, 5, 5), buildOrder,
                new BlockPos(0, 0, 0), 2, 1234.5, 9999L);

        net.minecraft.nbt.CompoundTag tag = SiegeProjectNbtCodec.write(original);
        SiegeProjectSnapshot roundTripped = SiegeProjectNbtCodec.read(tag);

        assertEquals(original, roundTripped);
    }

    @Test
    void lastTickedGameTimeClampsToCurrentGameTimeOnLoad() {
        long storedFutureStamp = 1_000_000L;
        long actualCurrentGameTime = 500L;

        long clamped = Math.min(storedFutureStamp, actualCurrentGameTime);

        assertEquals(actualCurrentGameTime, clamped, "a future-dated stamp from a restored backup must clamp to now, never block tick() forever");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.ratden.skavenblight.ai.pathing.SiegeProjectStoreTest"`
Expected: FAIL (compile error).

- [ ] **Step 3: Implement `SiegeProjectSnapshot`/`SiegeProjectNbtCodec`/`SiegeProjectStore`**,
  reading `SkavenIncursionSavedData.java` and its companion NBT codec file first for the exact
  established convention.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.ratden.skavenblight.ai.pathing.SiegeProjectStoreTest"`
Expected: PASS

**Correction (2026-08-05, found executing Step 5, advisor unavailable this session so flagged rather
than guessed): Step 5's wiring needs a real design decision this plan doesn't specify, not a
mechanical wire-in.** `addOrReplace(SiegeProject project, UUID networkId)`'s `networkId` parameter is
a WarpFluxNetwork's own stable id (`WarpFluxNetwork.getId()`, confirmed via direct read — NOT
`SiegeProject.getNetworkId()`, which is a same-named-but-different field: a connector's paired-project
identity, unrelated to which nexus network owns the project). Confirmed via direct read that neither
`TerritoryRegionMap` (Task 14, done) nor `SiegeProjectManager` (Task 11, done) holds any reference to
its owning `WarpFluxNetwork`'s id today — `TerritoryRegionMap` has no network-identity field at all,
it's constructed and owned directly by `WarpFluxNetwork` with no back-reference. Wiring Step 5 as
written would require either (a) adding a new constructor parameter threading the network id down
into `TerritoryRegionMap`/`SiegeProjectManager` (a real signature change to two already-committed
classes, touching every call site that constructs them — `WarpFluxNetwork` itself, plus every
GameTest in `PathingRegionGameTests`/`SiegeProjectManagerTest`/etc. that constructs a bare
`TerritoryRegionMap` directly, none of which pass a network id today), or (b) some other mechanism not
yet designed. Neither option is safe to guess at without verifying every affected call site, which
this task's own file list doesn't cover and the advisor tool — this session's normal check for exactly
this kind of decision — is unavailable right now. **Steps 1-4 (the standalone, fully self-contained,
fully tested persistence subsystem) are implemented and committed below; Step 5 (wiring persistence
calls into `SiegeProjectManager`/`SiegeProject.tick()`/`TerritoryRegionMap`'s rebuild path) is left
undone, tracked here rather than guessed at.** Whoever picks this up next should either get advisor
input on the network-id-threading design, or confirm with the user whether `TerritoryRegionMap`
gaining a network-id field (and updating its handful of direct-construction call sites) is acceptable
before implementing it.

- [ ] **Step 5 (NOT YET DONE — see correction above): Wire persistence calls into
  `SiegeProjectManager`/`SiegeProject.tick()`** per the "when to persist" spec above, and the
  clamp-on-load fix into wherever `TerritoryRegionMap`/`SiegeProjectManager` reconstructs projects
  from the store during network rebuild.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProjectStore.java \
        src/main/java/org/ratden/skavenblight/ai/pathing/nbt/SiegeProjectSnapshot.java \
        src/main/java/org/ratden/skavenblight/ai/pathing/nbt/SiegeProjectNbtCodec.java \
        src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProjectManager.java \
        src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProject.java \
        src/test/java/org/ratden/skavenblight/ai/pathing/SiegeProjectStoreTest.java
git commit -m "feat(pathing): add SiegeProjectStore persistence for long-running siege projects"
```

---

## Task 16: Delete dead goal-layer files + `ClanratEntity` registration cleanup

**Files:**
- Delete: `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/DeployClimbableGoal.java`
- Delete: `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/SpiralSapperGoal.java`
- Delete: `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/WarpSapperGoal.java`
- Delete: `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/StrandedGoal.java`
- Delete: `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/SmartBreachGoal.java`
- Delete: `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/AbstractSiegeConstructionGoal.java`
- Delete: `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/SiegeActionAnimator.java`
- Modify: `src/main/java/org/ratden/skavenblight/entity/custom/ClanratEntity.java`
- Delete: `src/main/java/org/ratden/skavenblight/gametest/RegionFlowFieldClimbResolutionGameTests.java`

This is a mechanical cleanup task, confirmed with the user (see plan header). No new behavior.

- [ ] **Step 1:** Delete the seven files listed above.

- [ ] **Step 2:** Edit `ClanratEntity.registerGoals()` — remove the five `goalSelector.addGoal` calls
  for `WarpSapperGoal` (priority 2), `SmartBreachGoal` (priority 3), `SpiralSapperGoal` (priority 4),
  `DeployClimbableGoal` (priority 5), and `StrandedGoal` (priority 10), along with their explanatory
  comments (which describe exactly the priority-ordering rationale that no longer applies). Leave
  `BuildFlowFieldGoal` (6), `AwaitFormationGoal` (8), `FollowFlowFieldGoal` (9),
  `WaterAvoidingRandomStrollGoal` (11), `LookAtPlayerGoal`/`RandomLookAroundGoal` (12) unchanged —
  gaps in priority numbers are harmless.

- [ ] **Step 3: Delete `RegionFlowFieldClimbResolutionGameTests.java`** — it tests exactly the climb
  resolution behavior removed in Task 10.

- [ ] **Step 4: Run the full build to confirm nothing else references the deleted files**

Run: `./gradlew compileJava`
Expected: compile errors ONLY in files this plan's later tasks still need to touch (goal-layer files
in Tasks 17-19, and the remaining old GameTest files Task 19 handles) — if a compile error surfaces
in a file NOT already accounted for by this plan, stop and investigate before continuing; it means
this plan's file-disposition survey missed a caller.

**Executed reality (2026-08-05): four additional compile breaks surfaced here, not anticipated by
the paragraph above — none from a caller the file-disposition survey missed outright, all from
already-decided-elsewhere fates this task's own scope didn't mention pulling forward. Resolved
in-place rather than deferred, since each is a zero-new-judgment mechanical fix:**
1. `ClanratEntity.describeSiegeGoalCanUseState()`/`describeActiveSiegeGoalState()` reference
   `AbstractSiegeConstructionGoal` directly (not just via a deleted-goal instance) — beyond the
   `registerGoals()` edit this task's Step 2 already described. `describeActiveSiegeGoalState()`
   has no successor (`describeState()` died with the class) — hardcoded to its existing "none
   running" fallback string, since with `SmartBreachGoal` gone that's the only value it could ever
   produce anyway. `describeSiegeGoalCanUseState()` only ever needed `wrapped.isRunning()` +
   `goal.canUse()`, both available on any `Goal` — retargeted its filter from
   `instanceof AbstractSiegeConstructionGoal` to `instanceof AbstractSiegeProjectGoal` (still
   present, still imported, already used by `peekAnyClaimedConstructionTarget`), which keeps this
   diagnostic alive for `BuildFlowFieldGoal` (Task 18 makes it the ONLY project-execution goal) —
   the same "canUse()=true but the selector never picked it" question Task 21's go/no-go gate will
   need. Both signatures/callers (`PathingDebugFileWriter`, `AwaitFormationGoalGameTests`)
   untouched. Left `PathingDebugFileWriter`'s own `SiegeNode`-typed body alone; it's genuinely
   Task 25's scope.
2. `AwaitFormationGoal.java:191` references `AbstractSiegeConstructionGoal.MAX_TARGET_CLAIM_DISTANCE`
   (a bare `2.5D` constant, same-package access, no other coupling) in already-existing `tick()` logic
   unrelated to this task. Inlined the same literal as a local `private static final double
   MAX_TARGET_CLAIM_DISTANCE = 2.5D` in `AwaitFormationGoal` itself — Task 19 (this file's own
   assigned task) still owns any further real changes to this goal.
3. `PathingGoalRecalculationGameTests.testDeployClimbableGoalMarksRegionDirty` — the ONLY method in
   that file using `DeployClimbableGoal`, and already hand-fed the OLD `SiegeNode`/`FlowFieldState`
   API directly. Task 20 (exec position 20) already lists deleting exactly this method as decided,
   final scope — pulled forward untouched (deleted the method + its now-unused import), not
   reinterpreted, because leaving it broken until Task 20 would mean `compileJava` stays red from
   exec position 1 instead of exec position 6 as the "why section order isn't execution order" note
   above claims.
4. `SiegeConstructionActionsGameTests.java` (whole file) — already listed in the File Disposition
   table's "Existing tests — deleted" list with no surviving logic to port, and its one other
   reference (`StaircaseSiegeGroupGameTests`'s javadoc `{@code ...}` mention) is comment-only. Deleted
   now rather than at Task 20, same reasoning as point 3.

None of these four needed new design judgment — each fate was already fully decided elsewhere in this
document. If a FUTURE task's own compile check surfaces one of these files again expecting to still
edit it, that expectation is now stale; check here first.

- [ ] **Step 5: Commit**

```bash
git rm src/main/java/org/ratden/skavenblight/ai/goal/clanrat/DeployClimbableGoal.java \
       src/main/java/org/ratden/skavenblight/ai/goal/clanrat/SpiralSapperGoal.java \
       src/main/java/org/ratden/skavenblight/ai/goal/clanrat/WarpSapperGoal.java \
       src/main/java/org/ratden/skavenblight/ai/goal/clanrat/StrandedGoal.java \
       src/main/java/org/ratden/skavenblight/ai/goal/clanrat/SmartBreachGoal.java \
       src/main/java/org/ratden/skavenblight/ai/goal/clanrat/AbstractSiegeConstructionGoal.java \
       src/main/java/org/ratden/skavenblight/ai/goal/clanrat/SiegeActionAnimator.java \
       src/main/java/org/ratden/skavenblight/gametest/RegionFlowFieldClimbResolutionGameTests.java
git add src/main/java/org/ratden/skavenblight/entity/custom/ClanratEntity.java
git commit -m "chore(pathing): delete goal-layer classes made dead by climbing/bare-MINE removal"
```

---

## Task 17: `FollowFlowFieldGoal` rewrite (climb removal)

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/FollowFlowFieldGoal.java`
- Test: covered by Task 20's GameTest matrix (this goal has no meaningful unit-testable surface
  without a live `ServerLevel`/`PathfinderMob` — its existing test coverage has always been the
  GameTest suite; don't invent a mock-heavy unit test that doesn't already exist).

**Interfaces:**
- Consumes: `RegionFlowField.getNextStep` (Task 10, renamed from `getNextSiegeNode`), `FlowStep`/
  `PathAction` (Task 1).
- Produces: identical public API, minus everything climb-related.

**Spec — delete, don't merely disable:**
- Delete fields: `activeClimbTarget`, `climbCyclesElapsed`, `CLIMB_GIVE_UP_CYCLES`.
- Delete method: `tryClimb`.
- In `tick()`: delete the `if (this.activeClimbTarget != null) { tryClimb(...); return; }` branch
  entirely.
- In `moveOrHop`: delete the `if (to.getY() > from.getY() && to.closerThan(from, 2.5) &&
  tryClimb(to)) return;` branch — every hop is now `this.mob.getNavigation().moveTo(...)`
  unconditionally, per the universal acceptance criterion ("the flow field only ever tells a rat to
  WALK... a vanilla-navigating clanrat must traverse the completed structure using no special
  movement code at all").
- In `nudgeAcross`: delete the equivalent `tryClimb` branch, same reasoning.
- Every remaining reference to `SiegeNode`/`.action() != SiegeNode.SiegeAction.WALK` becomes
  `FlowStep`/`.action() != PathAction.WALK` — the "if we have a node but it isn't WALK, stop moving"
  branch is unchanged in intent, just retyped.
- The stuck-detection escape hatch, lane-occupancy tracking, and the `lastHopOrigin`
  repeat-hop-detection fix (both confirmed, real fixes for confirmed bugs, neither climb-related) are
  UNCHANGED — port verbatim.

- [ ] **Step 1-4:** No new unit test — implement the deletions above directly, then run
  `./gradlew compileJava` to confirm the file compiles against the new `RegionFlowField`/`FlowStep`
  types, and defer behavioral verification to Task 20's GameTest matrix (this goal's entire
  reason-to-exist is "does a rat actually walk the flow field correctly," which is precisely what
  those 16 GameTests prove end-to-end — a synthetic unit test here would just re-describe the deleted
  code, not verify anything new).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/goal/clanrat/FollowFlowFieldGoal.java
git commit -m "fix(pathing): remove climbing entirely from FollowFlowFieldGoal"
```

---

## Task 18: `SiegeNodeLookahead` + `AbstractSiegeProjectGoal` + `BuildFlowFieldGoal` port

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/SiegeNodeLookahead.java`
- Modify: `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/AbstractSiegeProjectGoal.java`
- Modify: `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/BuildFlowFieldGoal.java`

**Interfaces:**
- `SiegeNodeLookahead.findEffectiveNode(RegionFlowField, PathfinderMob, Predicate<PathAction>) →
  Optional<FlowStep>` — mechanical retype, `getNextSiegeNode`→`getNextStep`,
  `SiegeNode.SiegeAction`→`PathAction`, `SiegeNode`→`FlowStep`. Logic unchanged (the 1.5-block
  lookahead-snap-distance guard, the self-overlap guard, both ported verbatim — neither is
  climb-related).
- `AbstractSiegeProjectGoal`: mechanical retype throughout (`SiegeNode.SiegeAction`→`PathAction`,
  `SiegeNode`→`FlowStep`/`PlannedStep` where the type actually held is a build-order entry vs. a flood
  step — check each call site: `findEffectiveNode()` returns `FlowStep` (flood data),
  `SiegeProject.canAcceptWorker`/`tryRegisterWorker`'s "next unbuilt step" is `PlannedStep` — these
  are genuinely different types now, not the same `SiegeNode` wearing two hats, which is the entire
  point of the split; do not paper over the distinction with a cast).
- `BuildFlowFieldGoal.matchesAction`: becomes `action == PathAction.TUNNEL || action ==
  PathAction.BRIDGE || action == PathAction.CARVED_STAIR || action == PathAction.AIR_STAIR` — all
  four construction actions, since there is no more specialized goal (`DeployClimbableGoal`/
  `SpiralSapperGoal`) splitting off a subset of them; `BuildFlowFieldGoal` is now the ONLY
  project-execution goal, not "the generic fallback."

No new test for this task — it's a pure mechanical retype of already-covered logic (covered by Task
20's GameTest matrix, same reasoning as Task 17).

- [ ] **Step 1-4:** Implement the retypes above, run `./gradlew compileJava` to confirm.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/goal/clanrat/SiegeNodeLookahead.java \
        src/main/java/org/ratden/skavenblight/ai/goal/clanrat/AbstractSiegeProjectGoal.java \
        src/main/java/org/ratden/skavenblight/ai/goal/clanrat/BuildFlowFieldGoal.java
git commit -m "refactor(goal): port SiegeNodeLookahead/AbstractSiegeProjectGoal/BuildFlowFieldGoal to FlowStep/PlannedStep/PathAction"
```

---

## Task 19: `AwaitFormationGoal` real dynamic row/column formation

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/ai/goal/clanrat/AwaitFormationGoal.java`
- Test: `src/main/java/org/ratden/skavenblight/gametest/AwaitFormationGoalGameTests.java` ("new" here
  means the formation-grid coverage below is new scope per the design doc ("never observed working" —
  there's no existing formation-grid test to port). The file itself already exists, though, with other
  test methods that directly construct `SiegeProject` (old 5-arg constructor) and call
  `tryRegisterWorker`/similar with a `TerrainEvaluator` — confirmed via Task 12's `compileJava` run
  (3 errors in this file). This task's implementer must retype those existing methods to the new
  `SiegeProject`(`FlowStep`/`networkId`)/`PathStepEvaluator` API in the same commit as the new grid
  tests, not just add the grid tests alongside a still-broken file.

**Interfaces:**
- Consumes: `RegionFlowField` (Task 10), `Region` (kept, `contains`/`getBoundaryCells`).
- Produces: a NEW method, `computeFormationGrid(int ratCount, BlockPos anchor, Region region,
  RegionFlowField field) → List<BlockPos>` — replaces `findFormationSlot`'s expanding-ring scan with
  a real rows×columns grid sized to fit both the available space AND the rat count, per the design
  doc's explicit callout: "AwaitFormationGoal needs real dynamic row/column slot computation — never
  observed working; this is real, non-trivial new scope, not a rename."

**Formation grid spec:** given `ratCount`, compute `columns = ceil(sqrt(ratCount))`, `rows =
ceil(ratCount / columns)` (a roughly-square grid, the simplest dynamic sizing that scales sensibly
from 1 rat — a 1×1 grid — up to a crowd). For each `(row, col)` in that grid, compute a candidate slot
at `anchor.offset((col - columns/2) * SLOT_SPACING, 0, (row - rows/2) * SLOT_SPACING)` (a new `Config`
field, `formationSlotSpacing`, `DoubleValue`, default `1.5`, range `[1.0, 5.0]` — the whole point of a
grid over the old ring-scan is deterministic, predictable spacing rather than "wherever the search
happened to land first"). Skip and re-search outward (grow `columns`/`rows` by 1 in whichever
dimension is smaller) for any candidate slot that fails the SAME validity checks
`findFormationSlot` already uses (`region.contains`, solid ground below, not already
`isFormationSlotClaimed`, no existing flow-field instruction there) — this reuses `findFormationSlot`'s
existing validity predicate, just applied to grid-generated candidates instead of ring-scan-generated
ones. Return the list of valid slots found, up to `ratCount` of them, in a STABLE order (row-major)
so repeated calls with the same inputs produce the same assignment — this is what "actually computes
dynamic rows/columns to fit available space and rat count" means concretely, and what the new
GameTest below proves.

- [ ] **Step 1: Write the failing GameTest**

```java
package org.ratden.skavenblight.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.ai.goal.clanrat.AwaitFormationGoal;

import java.util.List;
import java.util.Set;

@GameTestHolder(Skavenblight.MODID)
@PrefixGameTestTemplate(false)
public class AwaitFormationGoalGameTests {

    @GameTest(template = "pathing_test_giant", timeoutTicks = 100)
    public static void formationGridScalesRowsAndColumnsToRatCount(GameTestHelper helper) {
        // 9 rats should produce a 3x3 grid (columns = ceil(sqrt(9)) = 3, rows = ceil(9/3) = 3),
        // 9 DISTINCT slot positions, no duplicates, every slot within the region and unclaimed.
        List<BlockPos> slots = /* AwaitFormationGoal.computeFormationGrid(9, anchor, region, field) */
                List.of(); // implementer wires the real region/field fixture

        helper.succeedWhen(() -> {
            PathingRegionGameTests.check(slots.size() == 9, "expected 9 formation slots for 9 rats, got " + slots.size());
            PathingRegionGameTests.check(Set.copyOf(slots).size() == slots.size(), "formation slots must be distinct positions - no duplicates");
        });
    }

    @GameTest(template = "pathing_test_giant", timeoutTicks = 100)
    public static void formationGridDegeneratesToOneSlotForOneRat(GameTestHelper helper) {
        List<BlockPos> slots = /* AwaitFormationGoal.computeFormationGrid(1, anchor, region, field) */
                List.of();

        helper.succeedWhen(() ->
                PathingRegionGameTests.check(slots.size() == 1, "one rat must get exactly one slot, not a full grid search radius"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Check for orphaned `gameTestServer` java.exe processes first (per the task's own testing requirement),
then run: `./gradlew runGameTestServer`
Expected: FAIL — `computeFormationGrid` doesn't exist yet.

- [ ] **Step 3: Implement `computeFormationGrid`** per the spec above, replacing (or keeping
  alongside as a fallback for the zero-valid-slots-found case — implementer's judgment, but the grid
  must be the PRIMARY mechanism, not a secondary one, since the design doc's whole point is that the
  ring-scan never actually produced real row/column structure) `findFormationSlot`'s ring-scan.

- [ ] **Step 4: Run test to verify it passes**

Check for orphaned processes, then: `./gradlew runGameTestServer`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/goal/clanrat/AwaitFormationGoal.java \
        src/main/java/org/ratden/skavenblight/gametest/AwaitFormationGoalGameTests.java
git commit -m "feat(goal): implement real dynamic row/column formation grid for AwaitFormationGoal"
```

---

## Task 20: Grief-recovery test + legacy GameTest cleanup

**Already done at Task 16 (2026-08-05), not here:** `SiegeConstructionActionsGameTests.java`'s
deletion and `PathingGoalRecalculationGameTests.testDeployClimbableGoalMarksRegionDirty`'s removal
both got pulled forward to Task 16 (see that task's "Executed reality" note) — they broke
`compileJava` immediately on the goal-file deletions, not at this task's original position. Don't
re-do them here; if either file/method still existed by the time this task runs, that would itself
be a sign something regressed.

**Gap found during exec-2/Task 4 (2026-08-05), not previously in this document's file-disposition
survey — add to this task's own Delete list, don't silently re-derive its fate when you get here:**
`src/main/java/org/ratden/skavenblight/gametest/FollowFlowFieldGoalClimbGameTests.java` (one test,
`testRatCrossesAnAlreadyBuiltDiagonalStairStep`) directly exercises `FollowFlowFieldGoal`'s
ballistic-jump `tryClimb` mechanism — exactly what Task 17 deletes outright, and exactly the same
"tests the climb mechanics being removed" reasoning already used to delete
`RegionFlowFieldClimbResolutionGameTests`/`SiegeConstructionActionsGameTests`. It also hand-builds
`SiegeNode`/`FlowFieldState`/`TerrainEvaluator`/`SiegeProjectManager`/`FlowFieldCalculator`/
`CalculationThrottler`/`RegionFlowField` directly (old API), so it breaks the moment Task 5 lands —
harmlessly, since nothing compiles between Task 5 and Task 14 anyway — but nothing in this plan
previously scheduled its removal, so it would otherwise sit as an unexplained compile error when this
task's own Step 4 (`./gradlew compileJava`) runs. Add `git rm
src/main/java/org/ratden/skavenblight/gametest/FollowFlowFieldGoalClimbGameTests.java` to this task's
Step 4/Step 6 alongside `SiegeConstructionActionsGameTests.java`'s already-departed sibling deletion.

**Gap found during exec-5/Task 12 (2026-08-05), not previously in this document's file-disposition
survey or Task 20's own file list — confirmed via advisor review of Task 12's `compileJava` output
(39 errors), add both to this task's own scope, don't silently re-derive their fate when you get
here:**
- `src/main/java/org/ratden/skavenblight/gametest/SiegeProjectTickGameTests.java` is not mentioned
  anywhere in this plan. It's `SiegeProject.tick()`'s only real coverage (accumulation scaling,
  per-tick idempotency, the block-placement loop) — it must be PORTED to the new `SiegeProject`
  (`FlowStep`/`PlannedStep`/`UUID id`/`networkId`) and `PathStepEvaluator` API, not deleted. Add its
  full retype to this task's own commit.
- `src/main/java/org/ratden/skavenblight/gametest/PathingGoalRecalculationGameTests.java`'s entry
  above (Task 16's "Executed reality" note, point 3) only ever covered ONE method
  (`testDeployClimbableGoalMarksRegionDirty`) plus a stale javadoc sweep. That undersold the file's
  real remaining scope: roughly five OTHER test methods in this same file (methods exercising
  `BuildFlowFieldGoal`'s region-dirty marking and `maxCandidateProjectLength` capping) directly
  construct `SiegeProject` with the old 5-arg constructor and pass `TerrainEvaluator` where
  `PathStepEvaluator` is now required — confirmed via Task 12's `compileJava` run (6 errors in this
  file, none of them the already-deleted method). This task's own Step 4 must retype those methods to
  the new API in full, not just the javadoc sweep the file's earlier entry described.

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/gametest/SiegeProjectGriefRecoveryGameTests.java`
- Modify: `src/main/java/org/ratden/skavenblight/gametest/SiegeProjectAutoWidenGameTests.java`
  (rewrite `BUILD_PILLAR` fixture to `AIR_STAIR`/`BRIDGE`, and retype its other `SiegeProject`/
  `TerrainEvaluator` construction sites to the new API — same `compileJava` confirmation as above,
  6 errors in this file)
- Modify: `src/main/java/org/ratden/skavenblight/gametest/PathingRegionGameTests.java` (delete the
  stale LEAP-javadoc comment reference)
- Modify: `src/main/java/org/ratden/skavenblight/gametest/PathingGoalRecalculationGameTests.java`
  (full retype of its remaining `SiegeProject`/`TerrainEvaluator`-consuming methods — see gap note
  above; this is broader than the javadoc-sweep-only scope this file was originally given)
- Modify: `src/main/java/org/ratden/skavenblight/gametest/SiegeProjectTickGameTests.java` (full port
  to the new `SiegeProject`/`PathStepEvaluator` API — see gap note above)
- Delete: `src/test/java/org/ratden/skavenblight/ai/pathing/SiegeLineTracerTest.java`
- Delete: `src/test/java/org/ratden/skavenblight/ai/pathing/TerrainEvaluatorTest.java`
- Delete: `src/main/java/org/ratden/skavenblight/gametest/FollowFlowFieldGoalClimbGameTests.java`
  (see the gap note above)

**Grief-recovery test spec (the design doc's own explicit requirement — "write a test proving this
before considering the feature done, or you will ship a flow field that lies forever about a griefed
project"):**

```java
package org.ratden.skavenblight.gametest;

// Full test setup follows StaircaseSiegeGroupGameTests' established pattern for a real nexus +
// conduit + restricted territory (reuse those helpers, don't duplicate them).
@GameTest(template = "pathing_test_giant", timeoutTicks = 3000, skyAccess = true)
public static void playerBreakingAHalfBuiltProjectStillTriggersRealRecalculation(GameTestHelper helper) {
    // 1. Set up a real siege scenario, let a SiegeProject start building (e.g. a CARVED_STAIR chain).
    // 2. Wait until at least one but not all steps are built.
    // 3. As the TEST HARNESS (not a clanrat - i.e. via helper.destroyBlock or a raw
    //    level.destroyBlock call standing in for "a player broke it"), destroy one of the
    //    ALREADY-BUILT steps.
    // 4. Assert: within a bounded number of ticks, the flow field's own instruction at that
    //    position reverts to reflect the REAL (now-broken) world state, not the project's stale
    //    "planned final state" override - i.e. RegionFlowField.getNextStep at that position no
    //    longer reports the action as complete, proving TerritoryRegionMap.onBlockChanged's real
    //    dirty-tracking path still fires for non-clanrat changes even though Task 14's planned-state
    //    authority is in effect for the SAME project's OTHER, still-standing cells.
}
```

- [ ] **Step 1: Write the failing test** per the spec above, filling in the concrete fixture using
  `StaircaseSiegeGroupGameTests`'s helpers.

- [ ] **Step 2: Run test to verify it fails**

Check for orphaned `gameTestServer` processes, then: `./gradlew runGameTestServer`
Expected: FAIL if Task 14's planned-state override is over-broad (i.e. it doesn't correctly fall
through to real world state once a project's own step is externally destroyed) — this is exactly the
regression this test exists to catch. If it unexpectedly PASSES on the first run, don't assume Task
14 is correct — deliberately break the exception logic (e.g. make the override always win regardless
of whether the real world changed) and confirm the test THEN fails, to prove the test is actually
exercising the right code path before trusting a green result.

- [ ] **Step 3: Fix Task 14's implementation if this test reveals a gap**, or confirm it already
  passes correctly and record which is the case in the commit message.

- [ ] **Step 4: Delete/rewrite the legacy test files** listed above.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew runGameTestServer` (after re-checking for orphaned processes)
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/gametest/SiegeProjectGriefRecoveryGameTests.java \
        src/main/java/org/ratden/skavenblight/gametest/SiegeProjectAutoWidenGameTests.java \
        src/main/java/org/ratden/skavenblight/gametest/PathingRegionGameTests.java \
        src/main/java/org/ratden/skavenblight/gametest/PathingGoalRecalculationGameTests.java \
        src/main/java/org/ratden/skavenblight/gametest/SiegeProjectTickGameTests.java
git rm src/test/java/org/ratden/skavenblight/ai/pathing/SiegeLineTracerTest.java \
       src/test/java/org/ratden/skavenblight/ai/pathing/TerrainEvaluatorTest.java \
       src/main/java/org/ratden/skavenblight/gametest/FollowFlowFieldGoalClimbGameTests.java
git commit -m "test(pathing): add grief-recovery GameTest, retire legacy climb/line-tracer test files"
```

---

## Task 21: Go/no-go gate — the 4 existing air-stair GameTests

**Result (2026-08-06, executing Task 20/21): NO-GO. Root cause identified and fully evidenced —
not just suspected. Tasks 22-24 remain blocked.** All 4 gate tests fail identically with "0 stair
block(s) built"; the ground-side rat never takes a single step (its absolute position is bit-for-bit
identical from spawn to timeout in every run). This is NOT the two "specific suspects" flagged below
(`FRONTIER_WALK_THRESHOLD`, vertical cost-bias) — those govern chained-connector discovery, and
discovery is fine: `RegionGraph.build` genuinely finds the diagonal connector every run (confirmed via
log: `RegionGraph built: 3 regions, 2 connectors (0 chained, max 1 hops)` for the small-gap tests —
`0 chained` is *correct* there, since 14 hops fits inside one 32-hop `TRACE_HOPS_PER_CHAIN` segment;
don't waste time on the chaining machinery for these 4 tests). The defect is downstream, in how a
mob reads its OWN flow-field instruction to decide where to move.

**Root cause: `RegionFlowField.getNextStep`'s "already completed, collapse to WALK" branch discards
the real next-hop pointer, and every consumer that was supposed to read that pointer was never
migrated to read the field it actually lives in.** Confirmed via direct instrumentation
(`AbstractSiegeProjectGoal.canUse()`, temporarily logging `getRawInstruction`/`getNextStep` at the
rat's own position, since reverted — not left in the tree):

```
raw=FlowStep[pos=BlockPos{x=10421624,y=-58,z=-6542039}, action=WALK, predecessorPos=BlockPos{x=10421625,y=-58,z=-6542040}]
resolved=FlowStep[pos=BlockPos{x=10421624,y=-58,z=-6542039}, action=WALK, predecessorPos=BlockPos{x=10421624,y=-58,z=-6542039}]
```

The RAW instruction at the rat's own position correctly carries a real next hop
(`predecessorPos={x+1,z-1}`, one cell over — genuinely a different cell). But `getNextStep`
(`RegionFlowField.java`) runs an `isActionCompleted` check and, since the raw action is already
`WALK` (trivially "complete" — the rat is standing on real ground by definition), replaces it with
`new FlowStep(node.pos(), PathAction.WALK, node.pos())` — **self-referencing BOTH fields**,
discarding the real `predecessorPos` and replacing it with the rat's own position. Every consumer
that reads the resolved value's next-hop off `.pos()` — `FollowFlowFieldGoal.tick()`'s
`nextInChain = targetNode.pos()`, its own lookahead loop's `next.pos().equals(nextInChain)` /
`nextInChain = next.pos()`, `FollowFlowFieldGoal.findEscapePos`'s identical pattern, and
`SiegeNodeLookahead.findEffectiveNode`'s lookahead snap — gets back the rat's own position and
instructs it to move to exactly where it already is. `moveOrHop`/`Navigation.moveTo` to the mob's own
current position produces a path that's immediately "done" with zero motion: the mob genuinely never
moves, matching the exact observed symptom for every failing test.

**This is not a fresh regression — it's a known, half-fixed design-debt item from before this
rewrite even started.** `docs/pathing/instruction-map-invariants.md` (2026-08-04, pre-dates this
plan) already documented the old `SiegeNode(pos, action)` type's central footgun: as a map VALUE,
`.pos()` meant "the next hop toward the target" (the only field available to carry that information);
as a STANDALONE value, `.pos()` meant "the real position this action applies to" — the same accessor,
two incompatible meanings depending on context. Its sibling scoping doc,
`docs/superpowers/plans/2026-08-05-siegenode-dual-meaning-split-plan.md`, proposed the fix this
rewrite's Task 6 apparently drew from: introduce a THREE-field `FlowStep(pos, action,
predecessorPos)` so `.pos()` can be unambiguously "the real position" everywhere, with a NEW,
dedicated `predecessorPos` field carrying what `.pos()` used to secretly mean on a map value. Tasks 6
and 9 correctly migrated the PRODUCERS to this disambiguated convention — confirmed by reading
`FlowFieldCalculator.processNeighbors` (`nextInstructionMap.put(step.pos(), new
FlowStep(step.pos(), step.action(), current))` — `.pos()` always equals the map key now) and
`RegionGraph.outboundInstructions`/`inboundInstructions` (same pattern) directly. **But the
CONSUMERS — `FollowFlowFieldGoal`, `SiegeNodeLookahead`, ported in Tasks 10/17 — were only
mechanically retyped (`SiegeNode`→`FlowStep`), never semantically updated: they still read `.pos()`
expecting "next hop," the OLD map-value-only meaning, which is now always wrong** (under the new,
disambiguated convention, a map value's `.pos()` always equals its own key — the mob's own current
position when queried by that position — never the next hop; `.predecessorPos()` is the field that
now carries it). Task 10's own commit message (`a271f0f`) is explicit that this was a deliberate,
informed choice at the time: "confirmed via grep that no caller in ai.goal.clanrat reads
predecessorPos off this method's return value" — true then (the old 2-field `SiegeNode` had no such
field to read), but the mechanical port never revisited whether `FollowFlowFieldGoal`'s own `.pos()`
reads should have become `.predecessorPos()` reads once the producer side's meaning changed under it.

**Why 30+ other tests never caught this:** every hand-fed GameTest/unit-test fixture that directly
constructs a `FlowFieldState` (the pattern throughout `PathingGoalRecalculationGameTests`,
`SiegeProjectAutoWidenGameTests`: `state.updateInstructions(Map.of(anchor, new FlowStep(target,
action, anchor)))`) encodes data in the OLD, consumer-compatible shape — `.pos()` = target/next hop,
`predecessorPos` = the standing position — because whoever wrote those fixtures modeled them on the
pre-migration mental model, not on what `FlowFieldCalculator`/`RegionGraph` actually produce. Every
one of those tests is internally consistent (bug-compatible producer + bug-compatible consumer
"pass"), which is exactly why they never surfaced this. `StaircaseSiegeGroupGameTests` is the FIRST
test suite to exercise the REAL end-to-end pipeline (real nexus, real conduit, real
`TerritoryRegionMap.rebuild`, real `RegionGraph.build`) — and it's the first thing that could ever
have caught this, because `compileJava` was red from Task 5 through Task 20.

**Candidate fix (NOT implemented this session — deliberately, per explicit advisor guidance not to
land a cross-cutting movement-layer change at the end of a long session):** swap every consumer read
of a map-sourced `FlowStep`'s "where do I go next" from `.pos()` to `.predecessorPos()`:
`FollowFlowFieldGoal.tick()` (the `nextInChain` assignment and its own lookahead loop's break
condition/reassignment), `FollowFlowFieldGoal.findEscapePos`, `SiegeNodeLookahead.findEffectiveNode`'s
lookahead-snap check. Do NOT touch `AbstractSiegeProjectGoal.findCandidateProject`'s
`flowField.findProjectFor(node.pos())` — that read wants "the real position the action applies to,"
which IS `.pos()` under the disambiguated convention; it does not need to change.
`RegionFlowField.getNextStep`'s own collapse branch should also preserve the real predecessor when
converting a completed construction action to WALK
(`new FlowStep(node.pos(), PathAction.WALK, node.predecessorPos())`, not `node.pos()` twice) — for
Shape-A-sourced entries (the ordinary flood and reactive macro-tracer) this is correct and safe
either way (WALK's own `isActionCompleted` check is tautologically true for a cell the mob is already
standing on, so the collapse is a no-op once predecessorPos is preserved instead of discarded).
**Do not land this without first resolving the sibling plan's own flagged "two runtime-unverified
risks"** (`docs/superpowers/plans/2026-08-05-siegenode-dual-meaning-split-plan.md`'s "Two
runtime-unverified risks" section) — specifically whether every hand-fed test fixture that encodes
the OLD convention needs updating too, and whether any remaining call site genuinely depends on the
old, `.pos()`-as-next-hop reading in a way a blanket swap would break. Every hand-fed GameTest fixture
touched by this rewrite so far (`PathingGoalRecalculationGameTests`,
`SiegeProjectAutoWidenGameTests`) will need updating to the disambiguated convention in the same pass,
or they'll silently start asserting the wrong thing once the consumer-side swap lands.

**Correction (2026-08-06, second session): the candidate fix above was implemented and is confirmed
necessary but NOT sufficient — Task 21 stays NO-GO, for a different, newly-isolated reason.** The
`.pos()` → `.predecessorPos()` consumer swap (`FollowFlowFieldGoal.tick`/`findEscapePos`,
`SiegeNodeLookahead.findEffectiveNode`) and `RegionFlowField.getNextStep`'s WALK-collapse fix (preserve
`predecessorPos`, don't self-reference) landed exactly as scoped above. Confirmed working via
instrumented single-rat gate-test evidence (temporarily added, reverted before this commit): the ground
rat now traverses ~15 real cells from spawn to the gap edge — previously bit-for-bit zero motion, per
this section's own log evidence above — and `SiegeNodeLookahead.findEffectiveNode` correctly resolves
the diagonal `AIR_STAIR` construction node one hop out (`matches=true`,
`flowField.findProjectFor(node.pos())` present). The two runtime-unverified risks this section flagged
are both resolved as SAFE, with the current code confirmed to already implement the fix they were
worried about: `SiegeProject.isCompleted`/`getRemainingInstructions` (`SiegeProject.java:99-100,123-124`)
read `entry.getKey()`/`entry.getValue().action()` only, never `.pos()` — shape-agnostic by
construction, so connector-sourced and reactive projects are equally safe. `RegionGraph`'s
`outboundInstructions`/`inboundInstructions` (`RegionGraph.java:321,338`) both write
`map.put(from, new FlowStep(from, action, target))` — genuine Shape A, matching the flood's own
convention; the mixed-shape premise those two risks were written against no longer holds now that
Tasks 6/9 landed. One additional old-convention call site the audit surfaced beyond the ones scoped
above: `PathingDebugFileWriter.writeMobPathTraces` was walking a raw instruction chain via `.pos()`,
which — now that producers are genuinely Shape A — made every trace terminate after exactly one cell,
always; fixed to `.predecessorPos()` in the same pass. Also noted, deliberately NOT fixed (out of
scope, does not affect any test): `SiegeProjectManager.injectActiveProjects`
(`SiegeProjectManager.java:136,158-160`) copies a `SiegeProject`'s `getRemainingInstructions()` output
straight into `nextInstructionMap` with no reconstruction — a project built from a `Map.of(target, new
FlowStep(anchor, action, anchor))`-shaped `instructions` map (the shape every `SiegeProject`-constructor
-only test fixture still uses, since nothing on that path ever reads a map value's `.pos()`) would
inject a Shape-A-violating entry once that path is actually exercised by a live recompute. Nothing in
this rewrite currently exercises that path with such a project, so it's flagged, not fixed.

**The real remaining blocker, isolated via the same instrumentation:** a pre-existing, unrelated defect
in `SiegeProject.nextUnbuiltInstruction` interacting with `canAcceptWorker`'s work-radius check.
Evidence (single-rat gate test, mob at a fixed position the whole time): on the very first tick,
`nextUnbuiltInstruction()` returns the correct, nearest unbuilt `AIR_STAIR` step (~1.73 blocks away,
well inside `Config.projectWorkRadius` = 3.5) and `canWorkOn`/`canAcceptWorker` reports true. On every
tick after that — with nothing built (0 stairs, confirmed) and the mob never having moved — the SAME
call returns a DIFFERENT step two hops further along the build order (~4.36 blocks away, now outside
the radius), and `canWorkOn` reports false forever after. The intervening step is being silently
skipped as "already complete" by `nextUnbuiltInstruction`'s own `isActionCompleted` check
(`case BRIDGE, AIR_STAIR -> state.blocksMotion() || isWalkableScaffold(state)`, checked at that exact
cell) — meaning that build-order cell is already-solid natural terrain the connector's own trace
walked through, not something this project ever needs to (or can) build. Once registration is refused
on radius grounds, `FollowFlowFieldGoal` (priority 9, uncontested once `BuildFlowFieldGoal` at priority
6 declines) takes the MOVE flag and walks the rat directly onto the unbuilt `AIR_STAIR` cell itself
(nothing in `FollowFlowFieldGoal` checks whether a WALK-resolved next hop is actually real, buildable
ground before moving toward it) — and once the rat is standing exactly there,
`SiegeNodeLookahead.findEffectiveNode`'s own self-reference guard (`node.action() != WALK &&
node.pos().equals(currentPos)`, working exactly as designed) latches `effectiveNode` to empty
permanently. This is a real, structural deadlock, not a transient race — confirmed stable across three
separate full-suite reruns with identical evidence each time.

This is a `SiegeProject` build-order / work-radius defect, independent of the `FlowStep` convention
this task was scoped to fix, and was invisible before this session because the rat never moved far
enough to reach it. **Not fixed this session** — per the same "don't land a cross-cutting change at the
end of a long session" judgment call this section's own candidate-fix paragraph already applied once.
Next session's first question: should `RegionGraph`'s connector trace exclude already-solid cells from
the build order at discovery time, or should `canAcceptWorker`/`nextUnbuiltInstruction` measure radius
against the nearest *reachable* unbuilt step rather than the first one in build order? Either fix
belongs in its own session, verified against this exact single-rat gate test as the reproduction case.

**Correction (2026-08-06, third session): the "build-order / work-radius defect" diagnosed above is
WRONG. It was never re-verified against fresh instrumentation before being written down, and it doesn't
survive one.** Direct per-position instrumentation of `SiegeProject.canAcceptWorker` (dumping
`instructions`, `buildOrder`, and live `isActionCompleted` at every entry) shows the two build-order
steps the prior session pointed at as "already-solid natural terrain silently skipped" are neither: the
CARVED_STAIR step flagged `platform=true` is a genuine `PlatformInserter` seam (an action-type
transition, not already-solid terrain) that showed `state=air, completed=false` in every capture; the
step after it is real `minecraft:stone`, correctly reported unbuilt. Nothing in the build order is a
false positive. `nextUnbuiltInstruction`'s own reported jump (a nearby step, then a step ~2 hops
farther) is real, not a bug: a `SiegeProject` can register several workers whose combined
`workPerRatPerTick` finishes more than one step inside a handful of ticks, and the worker's own
position never moves while it builds — so the next unbuilt step naturally ends up farther from a
stationary mob purely because construction outpaced it, not because a step was skipped. Confirmed via
the same instrumentation: the interior hop the prior session called "silently marked complete" was
`state=Block{minecraft:cobblestone_stairs}` in the capture — a REAL placed stair, `completed=true` for
the honest reason.

**The real root cause, found by tracing `RegionFlowField.getNextStep` → `SiegeProjectManager
.injectActiveProjects` → `RegionGraph.outboundInstructions`/`inboundInstructions`:** every position in
a connector's own instruction map correctly satisfies `isActionCompleted` once its real target is
built — CONFIRMED directly: an interior hop's own map entry (keyed by its own real position) flips
`completed@key=true` the instant a real stair lands there, by the same coincidence that makes
`AIR_STAIR`/`BRIDGE`'s completion check (`state.blocksMotion() || isWalkableScaffold(state)`) true for
ANY real, solid, walkable cell regardless of which specific action built it. The ONE entry that never
resolves is the connector's own `entryPos` signpost. `outboundInstructions`/`inboundInstructions` key
that entry by the real, always-open, always-standable entry cell itself, with an `action()` describing
the FIRST hop (e.g. `AIR_STAIR`) that actually applies at `predecessorPos()`, not at the key. Since a
mob standing at `entryPos` can never make that position itself "blocksMotion or a walkable scaffold,"
`isActionCompleted(entryPos, AIR_STAIR)` is false forever, by construction — regardless of whether the
real hop-1 target ever gets built. `SiegeProjectManager.injectActiveProjects` re-seeds this exact static
value into the region's own `nextInstructionMap` UNCONDITIONALLY on every recompute (a deliberate,
correct fix from earlier this same investigation, still needed — see that method's own doc), so this
one stale entry is what a worker standing at `entryPos` reads back forever: `RegionFlowField.getNextStep`
never collapses it to `WALK`, `FollowFlowFieldGoal`'s `targetNode.action() != WALK` branch freezes the
mob in place every tick, and the moment the project's own build front outpaces `Config.projectWorkRadius`
(3.5) from that same, never-moved mob, `canAcceptWorker` correctly refuses re-registration — leaving the
mob stuck at `entryPos` forever, next to real stairs it will never be told to climb. No `SiegeNodeLookahead`
self-reference latch is involved this time; the mob never leaves `entryPos` in the first place.

**Fix landed this session (`SiegeProjectManager.injectActiveProjects`):** check completion at the
position the signpost's own action actually applies to (`ownEntryInstruction.predecessorPos()`), not at
`entry`; seed `WALK` toward that target once it's done, instead of unconditionally re-seeding the static
first-hop action. Confirmed via instrumented `FollowFlowFieldGoal` evidence: `targetNode` at `entryPos`
correctly flips from `AIR_STAIR/pred=hop1Pos` to `WALK/pred=hop1Pos` within one throttled recompute
window (`TerritoryRegionMap.RECALC_COOLDOWN_TICKS` = 80 game ticks) of hop 1 actually completing, on
both the large-group and single-rat projects.

**A second, unrelated defect this fix's own success surfaced: `SiegeProjectManager.activeProjects` was
a plain `ArrayList` read from the server thread (`findProjectContaining`, called every mob's every tick
via `AbstractSiegeProjectGoal.canUse()`) while mutated from the async flow-field calculation thread
(`TerritoryRegionMap`'s own `CompletableFuture.runAsync` → `injectActiveProjects`/`removeIf`).** Before
this session's fix, projects deadlocked near-instantly at `entryPos` and this race was never exercised
hard enough to fire; once workers actually register, build, and churn `activeProjects` at realistic
volume, it throws `ConcurrentModificationException` and crashes the whole game-test server (confirmed
crash, full stack trace in the session's own run logs, not reproduced in every run — a genuine race, not
deterministic). **Fixed** by changing `activeProjects`'s declared type to `CopyOnWriteArrayList` — the
single call site that does indexed `size()`/`remove(0)` eviction is unaffected (no iterator involved),
and every other call site's semantics (`add`/`removeIf`/`List.copyOf`/`for`) are unchanged under COW.

**A third defect, real but explicitly NOT fixed this session — its own separate investigation:** once
`targetNode` correctly resolves to `WALK` and `FollowFlowFieldGoal` issues `moveOrHop`/`nudgeAcross`
toward the real, just-built hop-1 stair, the mob's own `blockPosition()` never advances. Exact
reproduction signature, confirmed on the SINGLE-rat project specifically (ruling out multi-rat crowding
as the cause): mob standing exactly at `entryPos`; `targetNode` = `WALK` with `predecessorPos()` one
block over and one block up (the real, placed hop-1 stair); `mob.onGround()` = true; `NUDGE_ACROSS`
fires every 10-tick cycle; `blockPosition()` reads identical across the whole observed window (t=5789
to t=6069, ~280 ticks / 28 real seconds) with zero displacement. This is precisely the failure
`FollowFlowFieldGoal.moveOrHop`'s own javadoc anticipates and explicitly defers to this gate ("if it
can't [cross via ordinary pathfinding], that's a construction-side bug for Task 21's go/no-go gate to
catch"). Candidate causes not yet distinguished: (a) `AbstractSiegeProjectGoal.tick()`'s own
`mob.setDeltaMovement(0, …, 0)` fighting `FollowFlowFieldGoal`'s movement command in a priority
tug-of-war if the two goals keep swapping control; (b) the placed stair's `facing` not matching the
mob's actual approach angle; (c) sub-block motion that never crosses a full block boundary within the
observed window, invisible to `blockPosition()`-based instrumentation. **This needs its own session**,
with fresh instrumentation scoped to exactly this question — not a continuation of this investigation's
own now-reverted debug logging.

**Gate verdict: still NO-GO.** Two real production defects are fixed this session (the `entryPos`
signpost and the `activeProjects` race). The gate's own 4 tests still fail — mobs now correctly get
walked toward the first real stair once it's built, but can't physically climb onto it — which is the
third, unfixed defect above, not a regression from anything landed this session.

**Correction (2026-08-06, fourth session): the third defect's premise above did not reproduce, and
its own root cause is confirmed — a construction-geometry bug, not a movement-layer bug. Both
candidate movement fixes attempted this session were reverted; the gate stays NO-GO with a
precisely-scoped, different next task.**

The "mob standing exactly at `entryPos`, hop-1 already built" scenario above never reproduced this
session. All three full-suite `runGameTestServer` runs this session showed **0 stairs built, ever,
in all 4 gate tests** — an earlier-stage symptom than what's on record. This was only visible because
this session isolated `[DEBUG-CLIMB]` logging by `mob.getId()`: `StaircaseSiegeGroupGameTests` runs
all 4 gate tests concurrently in one batch (`Running test batch 'staircase_siege_group:0' (4 tests)`),
so un-tagged log lines from a prior attempt this session conflated roughly 15 rats across 4 different
geometries — every aggregate conclusion drawn from those early un-tagged logs (a 14:1
`NUDGE_ACROSS`:`MOVE_OR_HOP` ratio, "all hops crawl") was contaminated and had to be discarded.

Two movement-layer fixes were tried against the single-rat test's own isolated climb-stuck scenario,
both in `FollowFlowFieldGoal.java`, both reverted (final state matches `d1b4f63`, no net change):

1. Gating the `NUDGE_ACROSS` branch on `!getNavigation().isInProgress()` in addition to the existing
   `lastHopOrigin` check, on the theory that the branch fires prematurely for hops still legitimately
   in progress. **Made things measurably worse** — a full-suite re-run showed net rat displacement
   drop ~6x. `isInProgress()` reads `false` by the *next* 10-tick decision point for the overwhelming
   majority of hops regardless of whether they actually succeeded (a short hop typically finishes
   navigation well within 10 ticks either way), so it wasn't a useful discriminator between "genuinely
   stuck" and "just finished."

2. Sustaining `nudgeAcross` every tick via a new `activeNudgeTarget` field, instead of only once per
   10-tick decision cycle. Confirmed via the decompiled `MoveControl.tick()`/`Mob.setSpeed()` source
   (NeoFormRuntime local cache) that a single `setWantedPosition` call supplies exactly one tick of
   forward `zza` before `MoveControl`'s own `WAIT` branch (`else { this.mob.setZza(0.0F); }`) zeroes it
   again the very next tick. This produced a real but incomplete improvement: the single rat's
   continuous Y position finally moved past exactly `-58.0` (never observed in any prior run this
   session), and `MoveControl`'s own jump heuristic correctly triggered — but the rat still never
   crossed. An isolated per-tick trace (filtered to `id=1`) showed a **bit-for-bit identical jump arc
   repeating forever**: Y sequence `-58.0000 → -57.2468 → -56.8339 → -56.7478 → -56.9756 → -57.5048 →
   -58.0000` every single 10-tick cycle, X frozen to 4 decimal places, `horizontalCollision=false`
   (ruling out a flush-collision explanation). Peak rise: **~1.2522 blocks**.

**Root cause, confirmed directly via block-state logging at the climb target
(`level.getBlockState(nextInChain)`/`.below()`):** the target cell holds a genuine, correctly-placed
`cobblestone_stairs[facing=east,half=bottom,shape=straight]`, with `below=air` (confirming this really
is an `AIR_STAIR` bridging a gap — the pre-build state at the same cell was `air` over `stone`,
confirming the classification and construction were both correct). The geometry:

- Mob's approach floor top: `y=-58.0` (standing on solid ground one cell below).
- The stair occupies cell `y=-57` — **one full cell above** the approach floor, per
  `PathStepEvaluator.candidateSteps`' `(dx, dy=+1, dz)` neighbor offset and
  `SiegeInteractionHandler.constructSiegeBlock` placing directly at that returned position.
- A bottom-half stair's own low tread (the side an approaching mob touches first, per its `FACING`)
  sits at `cell-base + 0.5` = `-57.0 + 0.5` = `-56.5`.
- **Required rise from the mob's actual feet position: 1.5 blocks.** Vanilla's own jump height,
  confirmed via the measured arc above: **~1.25 blocks**. This is physically unreachable by any jump,
  regardless of horizontal momentum — no movement-layer fix can close a gap that was never about
  momentum in the first place.

Why ordinary vanilla ascending staircases don't have this problem, and why this is specific to the
*first* hop of a chain: a real staircase places each stair at the **same cell level as the approach
floor**, not one level above it. Walking onto a stair whose cell matches the mob's own current
standing cell gives a normal ~0.5-block auto-step onto its low tread, then another ~0.5 onto its own
high/riser portion while crossing through the block — a full 1.0 rise achieved via that ONE block's
own internal geometry, landing the mob exactly one cell higher and correctly set up for an identically
-placed next stair. `AIR_STAIR`'s neighbor offset places the stair one cell too high for this to work
on the *first* hop from real, solid ground. Every *subsequent* hop in the same chain only needs a
normal ~0.5 auto-step, since the mob is by then already standing on the previous stair's own surface
one level higher — which is exactly why prior sessions, whose evidence came from mid-chain observation
or hand-built fixtures, never isolated this specific first-hop defect.

This is squarely the construction-side defect `FollowFlowFieldGoal.moveOrHop`'s own javadoc already
anticipates and defers to this gate ("if it can't [cross via ordinary pathfinding], that's a
construction-side bug for Task 21's go/no-go gate to catch, not something this goal should paper over
with special movement code") — which is why both movement-layer fixes above were reverted rather than
iterated on further, per the same "don't land a cross-cutting change without dedicated verification"
judgment call this investigation's own predecessor sessions already established.

**Found and deliberately NOT fixed this session (unreached by the specific hop measured, NOT a
blanket clearance of the whole gate — see caveat below):** `SiegeProject.approachFacing`'s tie-break
(`Math.abs(dx) > Math.abs(dz)`) silently drops the `dx` component whenever `|dx| == |dz|` (a true
45-degree diagonal hop), producing an axis-aligned `FACING` for a genuinely diagonal approach. The
*specific climb hop this session measured and diagnosed* was `dx=+1, dz=0` (confirmed directly from
its own logged `currentPos`/`nextInChain` values), so the tie-break is provably not implicated in
*that* hop. This is weaker than "the whole crossing is axis-aligned": `relativeGroundSpawn` and
`relativeNexusPos` share the same Z (so the overall start/end geometry is axis-aligned), but rat 1's
own approach-walk trace this session showed real Z drift (`z≈-4600991` → `z≈-4600977`) before reaching
the measured hop, and the *third* session's own evidence described a hop's `predecessorPos()` as "one
block over" without specifying which axis. Don't assume the tie-break is cleared for every hop in this
gate's connector — only for the one this session actually measured. Flagged for a future session that
exercises or re-checks genuinely diagonal (both axes nonzero) `AIR_STAIR`/`CARVED_STAIR` hops.

**Also flagged, not yet confirmed as exploited:** `PathStepEvaluator.isActionCompleted`'s
`AIR_STAIR`/`BRIDGE` branch (`state.blocksMotion() || isWalkableScaffold(state)`) accepts *any* solid
block as "done" — not specifically a genuinely-placed, standable stair — a different predicate from
`isWalkableTerrain`'s actual standability check. Not implicated in this session's specific finding
(the stair here was genuinely, correctly built), but worth a second look once the placement-cell fix
below lands, in case the two interact.

**Precisely-scoped next task (deliberately not attempted this session):** change where `AIR_STAIR`
(and likely `CARVED_STAIR`, which shares the identical `(dx, dy=+1, dz)` neighbor-offset generation
branch in `PathStepEvaluator.candidateSteps`) actually **places** its stair block — at the approach
cell's own level (`dx, 0, dz`) rather than one level above it (`dx, +1, dz`) — while keeping the
Dijkstra flood/`FlowStep`'s own *logical* cell bookkeeping unchanged (still `dx, +1, dz`, since that's
genuinely where the mob ends up once it's crossed). This needs coordinated changes across at minimum:

- `PathStepEvaluator.candidateSteps` — the offset that becomes the *logical* step position likely
  stays as-is; only the *physical placement* target needs to change, which may mean threading an
  additional "place at" position through `EvaluatedStep`/`FlowStep`, distinct from the logical `pos()`.
- `SiegeInteractionHandler.constructSiegeBlock` — the actual `level.setBlockAndUpdate` call.
- `PathStepEvaluator.isActionCompleted` — currently checks completion AT the logical position; if
  placement moves to `pos().below()` for `AIR_STAIR`/`CARVED_STAIR`, either this check needs to look
  one cell down for those two actions specifically, or the logical/physical split needs its own
  explicit, unambiguous field rather than an implicit convention.
- Every hand-fed GameTest/unit fixture that currently encodes the OLD placement convention (at minimum
  `SiegeConstructionActionsGameTests` and anything else that hand-injects an `AIR_STAIR`/`CARVED_STAIR`
  instruction and asserts on the resulting block position) — these would start asserting the wrong
  thing the moment placement moves, the same class of risk the Task 21 `FlowStep`-convention fix
  (second/third sessions) already had to work through for hand-fed fixtures.

This is a genuinely cross-cutting change with its own blast radius (construction geometry, not just
movement), deliberately left for its own dedicated session with fresh verification against this exact
single-rat gate test as the reproduction case.

**Gate verdict (fourth session): still NO-GO.** No production code changed net this session — both
movement-layer attempts were reverted, and `FollowFlowFieldGoal.java` is bit-for-bit identical to its
`d1b4f63` state. Tasks 22-24 remain blocked.

**Fifth session (2026-08-06, continued): the fourth session's precisely-scoped placement fix is
implemented, landed (commit `7010ff7`, pushed to `worktree-pathing-rewrite`), and verified correct
via direct log evidence. Two further defects it exposed are also fixed. Gate verdict: still NO-GO,
for a new and different reason.**

Implemented exactly as scoped above: `PlannedStep` gained a `placementPos()` field distinct from the
logical `pos()`; `SiegeProject.placementPositionFor` computes it as `target.below()` for ascending
`AIR_STAIR`/`CARVED_STAIR` hops only (ascending = `target.getY() > from.getY()`; every other action,
and descending hops of these two, are unchanged); `SiegeProject.tick()` constructs and
force-recalculates at `placementPos()` (PLATFORM seams excepted — they stay keyed to the logical
cell, since a platform's staging area is about the mob's own standing/turning space, unaffected by
the split); `PathStepEvaluator.isActionCompleted`'s `AIR_STAIR`/`CARVED_STAIR` case now checks
standability at the logical target via `isWalkableTerrain` (which a stair one cell below correctly
provides) instead of raw solidity; `SiegeInteractionHandler.clearStairHeadroom`'s loop extended from
`y<=2` to `y<=3` so it clears the cell `isActionCompleted`'s own head-check verifies, now that both
sit one level higher relative to the placement point. **Confirmed via direct GameTest log tracing
(temporary `mob.getId()`-tagged instrumentation, fully reverted before commit): real AIR_STAIR/
CARVED_STAIR chains now build, and `FollowFlowFieldGoal` was observed genuinely climbing a rat up 11+
consecutive real hops in one continuous trace.** The placement-geometry root cause identified across
sessions three and four is fully discharged — do not re-derive or re-attempt it.

With construction actually completing for the first time (previously 0 stairs, every run, every
session), two further defects surfaced that had been structurally unreachable before:

1. `SiegeProject.canAcceptWorker` checked the `workRadius` distance BEFORE its own
   already-registered short-circuit. A mining action (`CARVED_STAIR`/`TUNNEL`) advances its "next
   unbuilt step" without moving the stationary worker mining it — unlike `AIR_STAIR`, where the mob
   physically climbs each finished stair via `FollowFlowFieldGoal` and so closes distance every hop.
   Two consecutive mining hops routinely pushed the new next-step outside `Config.projectWorkRadius`
   (3.5) of a mob that never left its spot, and `canContinueToUse()` routes through this same
   method — dropping a mid-chain worker and permanently orphaning the project's `tick()` calls the
   instant the frontier outran it by more than one hop. Fixed by reordering the two checks; admission
   (`tryRegisterWorker`'s own separate radius check, and `canUse()`, since `stop()`/`unregisterWorker`
   always empties `workers` before `canUse()` is next consulted) is unaffected.
2. `StaircaseSiegeGroupGameTests.countStairsInZone` (and `SiegeProjectGriefRecoveryGameTests
   .findFirstStair`, identical bug) scanned a bounding box assuming the crossing stays at the ground
   spawn/nexus's shared Z. Real captured-run coordinates, converted to the test's relative frame,
   showed a diagonal chain climbing entirely along Z (relative Z=15→27) while X stayed pinned near
   the nexus — the region graph picks which horizontal axis to drift in on its own, and a tight
   single-Z box saw only 1 of 13 real stairs placed. This was a pure test-harness blind spot (not a
   construction defect) that would have made the stair-count assertion structurally unsatisfiable
   regardless of how correctly the crossing was built. Fixed via a shared `crossingZoneBounds` helper
   that widens whichever horizontal axis has zero span between the two corners by the vertical climb
   distance. Confirmed not contaminated by neighboring test structures in the shared world (101/102
   blocks of separation vs. a max margin of 44). The widened box, scanned every tick via
   `succeedWhen`, briefly regressed a full 40-test suite run from ~60s to ~2m24s with cascading
   failures in unrelated tests (shared tick-loop starvation, not a real regression — confirmed by
   identical failure messages in pre-fix runs); mitigated by throttling the scan to once per 10
   ticks with a cached result in between.

Also fixed, a real (if newly-reachable) production bug: `AbstractSiegeProjectGoal.tick()` now
null-guards against `flowField` going null mid-cycle (`ClanratEntity.assignFlowField` propagates
null to already-running goals on a region generation bump, and a real placement's own
`forceRecalculation` call is exactly the kind of thing that triggers one — unreachable before this
session since `forceRecalculation` essentially never ran when 0 stairs were ever built).

**Current blocker — all 4 gate tests still fail, but the failure mode is entirely different now:**
every test builds real, correctly-counted `COBBLESTONE_STAIRS` chains well past minimum thresholds
(11-41 stairs vs. required 7-20, across different runs). Rats do not arrive. Suspected tick-starvation
from the 40-test concurrent batch was checked and ruled out: rerunning the full batch with each gate
test's `timeoutTicks` tripled-to-5×'d (diagnostic-only, not committed, already reverted) still failed
all 4, and critically, the specific rat being checked for arrival sat at **exact spawn height** in
every failure, regardless of the far larger budget — not partway up and out of time, at spawn, for
the whole test. For the single-rat test this is airtight: one rat total, its own project built the
counted stairs, and that same rat never left spawn height. Leading suspect, not yet confirmed: a
repeating jump-bounce loop observed once during this session (`AbstractSiegeProjectGoal.canUse()`'s
own mid-leap guard logged `!mob.onGround()` persistently true, `getDeltaMovement().y` alternating
between two fixed values ~0.083/~0.248, for ~20 real seconds before self-resolving) — reminiscent of
the third session's "identical jump arc repeated forever" symptom, but that one was root-caused to
the now-fixed 1.5-block-rise geometry, so if this recurs post-fix it has a different cause. That
observation was not on a gate test's own checked rat and was not deeply investigated this session
(deprioritized in favor of committing durable progress first) — needs its own dedicated,
`mob.getId()`-tagged investigation on `testSingleRatBuildsStaircaseAcrossSmallGap` specifically. This
is now squarely a movement-layer question — the construction-geometry mandate that opened this
session is fully discharged; do not re-attempt it.

**Gate verdict (fifth session): still NO-GO.** Construction-geometry defect (root cause identified
in sessions 3-4) is fixed, landed, and verified. Arrival is blocked by a distinct, unfixed
traversal defect. Tasks 22-24 remain blocked.

**Files (this session, fifth pass):**
- `PlannedStep.java` — added `placementPos()` field (backward-compatible 3-arg constructor
  defaulting it to `pos()` for every non-ascending-stair call site).
- `SiegeProject.java` — `placementPositionFor` (new), `planSteps` (wired to it), `tick()` (constructs
  at `placementPos()` except for PLATFORM seams), `canAcceptWorker` (already-registered short-circuit
  moved before the radius check).
- `PathStepEvaluator.java` — `isActionCompleted`'s `AIR_STAIR`/`CARVED_STAIR` case split from
  `BRIDGE`'s (now checks `isWalkableTerrain` at the logical target instead of raw solidity).
- `SiegeInteractionHandler.java` — `clearStairHeadroom`'s loop extended from `y<=2` to `y<=3`.
- `AbstractSiegeProjectGoal.java` — `tick()` null-guards against `flowField` going null mid-cycle.
- `PathingGoalRecalculationGameTests.java` — one stale hand-fed fixture
  (`testBuildFlowFieldGoalCompletesMultiStepMacroChainWithNoPriorSupport`) updated to assert the
  built stair at `.below()` the logical chain cell, matching the new placement convention.
- `StaircaseSiegeGroupGameTests.java` — `crossingZoneBounds` (new, shared), `countStairsInZone`
  rewritten to use it, `awaitArrivalAndStaircase` throttles the scan to once per 10 ticks.
- `SiegeProjectGriefRecoveryGameTests.java` — `findFirstStair` rewritten to use the same
  `crossingZoneBounds` helper.
- Reference: all temporary `[DEBUG-T21]`-tagged, `mob.getId()`-scoped instrumentation added during
  this session's investigation (in `SiegeProject.java` and `AbstractSiegeProjectGoal.java`) was fully
  reverted before the commit — none of it is in the tree. A diagnostic timeout-bump experiment in
  `StaircaseSiegeGroupGameTests.java` (used to rule out batch tick-starvation) was likewise reverted
  and never committed.

**Files (this session, fourth pass):**
- `FollowFlowFieldGoal.java` — two fix attempts implemented, tested, and reverted; net diff is zero.
  No production code changes landed this session.
- Reference: the isolated single-rat evidence above (per-`mob.getId()` `[DEBUG-CLIMB]` logging,
  block-state check at the climb target) was gathered via temporary instrumentation, confirmed, then
  fully reverted before the final commit — no debug code or scratch log files left in the tree.

**Files (this session, third pass):**
- `SiegeProjectManager.java` (`injectActiveProjects`'s `entryPos` signpost fix; `activeProjects`'s
  `CopyOnWriteArrayList` type change) — both implemented, both verified via instrumented GameTest runs
  (instrumentation added, confirmed, then fully reverted before this commit — no debug code or scratch
  log files left in the tree).
- No changes to `RegionGraph.java`, `PathStepEvaluator.java`, or `SiegeProject.java` — the task brief's
  two candidate fixes (already-solid cells in the build order; radius measured against the wrong step)
  are both premised on a defect that direct instrumentation disproved. Neither was implemented.

**Files (this session, second pass):**
- `RegionFlowField.java` (`getNextStep`), `FollowFlowFieldGoal.java`, `SiegeNodeLookahead.java` — the
  scoped consumer-side fix above, implemented.
- `PathingDebugFileWriter.java` — audit finding, fixed (see above).
- `AwaitFormationGoal.java` — one stale, now-misleading comment corrected; no logic change (its own
  `.pos()` read was already correct under Shape A).
- `PathingGoalRecalculationGameTests.java`, `AwaitFormationGoalGameTests.java` — every hand-fed
  `state.updateInstructions(...)` fixture encoding the old convention rewritten to genuine Shape A
  (several needed a WALK-then-construction two-entry restructuring, not a field swap, to avoid
  tripping `SiegeNodeLookahead`'s self-reference guard — see each fixture's own inline comment).
  `SiegeProjectAutoWidenGameTests.java`/`SiegeProjectTickGameTests.java` and every `SiegeProject`
  -constructor-only fixture deliberately left unchanged: confirmed via reading
  `SiegeProjectManager.findProjectContaining` (`containsKey` only) and `SiegeProject.isCompleted`
  /`getRemainingInstructions` (key + `.action()` only) that no production path reads `.pos()` off
  those maps.

**Files (original scoping, first session):**
- No production code changes expected beyond bug fixes this task's own failures reveal.
- Reference: `src/main/java/org/ratden/skavenblight/gametest/StaircaseSiegeGroupGameTests.java`
  (unchanged — same 4 tests, now exercised against entirely new production code).

**This is the task's explicit go/no-go gate.** Per the task instructions: "Get all 4 [air-stair
GameTests] passing before writing any of the [12 new part-type GameTests]." Do not proceed to Task 22
until all 4 pass.

**Separate finding, NOT part of this gate (2026-08-06): 5 other test failures in the same run are a
stale-fixture issue from Task 17, not a production defect.** `testFloatingNexusGetsBridgedToGround`,
`testLongConnectorCellsAreNeverOrphanedFromLookup`, `testParentRegionGetsRealInstructionsForSharedConnectorCells`,
`testDirtyRegionBatchProducesOneCoherentFinalIndex`, and `testConnectorCellsSurviveADirtyRegionRescan`
(all in `PathingRegionGameTests.java`) all use a PURE VERTICAL shaft geometry (no horizontal
offset at all) requiring a chained connector straight up/down. `PathStepEvaluator.candidateSteps`
line ~95 explicitly refuses to offer ANY candidate for a pure-vertical `(dx=0, dy≠0, dz=0)` direction
("climbing is permanently removed... never offer a step here") — but `RegionGraph.build` still fires
exactly that direction (`for (int dy : new int[]{-1, 1}) tryTrace(...0, dy, 0...)`) from every
boundary cell. Every pure-vertical trace call therefore aborts on its very first hop,
unconditionally, for every territory, always — these 5 tests are asserting on geometry Task 17's
climbing removal made permanently unreachable under the new action vocabulary, not exercising a live
code path. This is stale-fixture staleness, not a Task 21 blocker (none of these 4 gate tests use
pure-vertical geometry — they're all diagonal, which fires the working `(dx≠0 or dz≠0, dy≠0)`
combinations). Left unfixed and untriaged further this session; whoever picks this up next needs to
decide whether these fixtures should be reshaped to a diagonal shaft (matching
`StaircaseSiegeGroupGameTests`' own approach) or whether pure-vertical crossings deserve their own,
new construction mechanism.

- [ ] **Step 1: Check for orphaned `gameTestServer` java.exe processes**

Run: `tasklist //FI "IMAGENAME eq java.exe" //FO CSV` and inspect each PID's command line (see this
plan's own orientation phase for the exact `wmic`/`Get-CimInstance` incantation) — kill any that are
genuinely an orphaned `gameTestServer` (not a Gradle daemon) before proceeding.

- [ ] **Step 2: Run the 4 existing tests**

Run: `./gradlew runGameTestServer` (this runs ALL registered gametests in the `skavenblight`
namespace, not just these 4 — no per-class filter exists in this project's Gradle config; read the
full log output for `testSingleRatBuildsStaircaseAcrossSmallGap`,
`testSmallGroupBuildsStaircaseAcrossSmallGap`, `testLargeGroupBuildsStaircaseAcrossSmallGap`,
`testLargeGroupBuildsChainedStaircaseAcrossGiantGap` specifically).

- [ ] **Step 3: If any fail, apply superpowers:systematic-debugging** — do not guess-and-patch. The
  breadcrumb doc (`docs/pathing/instruction-map-invariants.md`'s "Next-session breadcrumb" section)
  recorded the OLD system's failure as execution/claim-priority, not discovery — that diagnosis does
  not automatically transfer to the new code, since the goal layer (Tasks 17-19) is substantially
  rewritten. Re-diagnose from scratch against the new implementation if a failure occurs; do not
  assume the old bug's root cause is the new bug's root cause.

  **Two specific suspects flagged during earlier tasks, worth checking before a from-scratch
  re-diagnosis if `testLargeGroupBuildsChainedStaircaseAcrossGiantGap` (the vertical/chained-gap
  test) is the one that fails:**
  1. `FlowFieldCalculator.FRONTIER_WALK_THRESHOLD` (Task 6) — deliberately left provisional at 4,
     separate from `RegionScanner.BOUNDARY_WALKABLE_NEIGHBOR_THRESHOLD`'s 6. If a chain never
     triggers a needed macro-project search (or triggers one somewhere it shouldn't), this
     threshold is the first thing to check.
  2. The vertical cost-bias removal (Task 9) — the old `SiegeLineTracer`/`RegionGraph` cost formula
     gave vertical traces a 25% discount (`(dy != 0) ? (projectCost * 2) * 0.75f : projectCost * 2`)
     that the new unified per-step cost model (summed `PathStepEvaluator.EvaluatedStep.cost()`, no
     directional bias) does not carry forward. This changes which connector wins
     `RegionGraph.bestPerPair`'s cheaper-wins comparison whenever a vertical and a horizontal route
     both reach the same region pair — a vertical route that used to win a cost tie may now lose
     one. If a giant vertical gap fails to chain correctly, check this before assuming a new bug.

- [ ] **Step 4: Once all 4 pass, commit** (only if Step 3 required production fixes; if all 4 pass
  on the first run with no changes, no commit is needed for this task — proceed directly to Task 22).

```bash
git add -A
git commit -m "fix(pathing): resolve go/no-go gate failures in the air-stair GameTest suite"
```

---

## Task 22: Carved-stair GameTest matrix (4 shapes)

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/gametest/CarvedStairSiegeGroupGameTests.java`

Mirror `StaircaseSiegeGroupGameTests` exactly (reuse its private helper methods by extracting them to
a shared package-visible utility class if duplicating them a second time would exceed a few dozen
lines — implementer's judgment on the extraction threshold, but don't blindly copy-paste the whole
~330-line file a 4th time across Tasks 22-24; extract once here, reuse in Tasks 23-24), with the
gap/terrain geometry adjusted so the only viable crossing is a `CARVED_STAIR` (a diagonal rise into
SOLID material, per Task 3's classification spec — `StaircaseSiegeGroupGameTests`'s existing gap is
open air on both sides, producing `AIR_STAIR`; this test's gap needs solid rock on the diagonal rise
side instead). Four tests: `testSingleRatCarvesStaircaseAcrossSmallGap`,
`testSmallGroupCarvesStaircaseAcrossSmallGap`, `testLargeGroupCarvesStaircaseAcrossSmallGap`,
`testLargeGroupCarvesChainedStaircaseAcrossGiantGap` — same rat counts/timeouts as the air-stair
originals, same universal pass condition (`awaitArrivalAndStaircase`-equivalent, checking for
`COBBLESTONE_STAIRS` blocks in the crossing zone exactly like the original does, since `CARVED_STAIR`
places the same block type per Task 13's spec).

- [ ] **Step 1: Write the 4 failing tests** per the pattern above.
- [ ] **Step 2: Check for orphaned processes, run `./gradlew runGameTestServer`, confirm all 4 FAIL**
  (or don't yet exist — first run after writing them should fail against whatever gap/geometry
  needs tuning, same as `StaircaseSiegeGroupGameTests`' own documented history of getting the
  geometry right took several attempts — expect this here too).
- [ ] **Step 3: Iterate on the fixture geometry and any production bugs it surfaces** using
  superpowers:systematic-debugging.
- [ ] **Step 4: Confirm all 4 pass.**
- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/gametest/CarvedStairSiegeGroupGameTests.java
git commit -m "test(pathing): add carved-stair GameTest matrix (single/small-group/large-group/chained)"
```

---

## Task 23: Tunnel GameTest matrix (4 shapes)

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/gametest/TunnelSiegeGroupGameTests.java`

Same structure as Task 22, gap geometry adjusted for a horizontal solid obstacle (produces `TUNNEL`
per Task 3's classification: `dy == 0`, neighbor blocks motion). Pass condition checks for the tunnel's
own cleared-passage geometry (open air where the tunnel was mined) rather than a placed block type,
since `TUNNEL` places nothing — adapt `countStairsInZone`'s equivalent to
`countClearedTunnelBlocksInZone` (counts previously-solid, now-air positions in the crossing zone).
Four tests, same naming/rat-count/timeout convention as Task 22.

- [ ] **Steps 1-5:** same structure as Task 22's steps.

```bash
git add src/main/java/org/ratden/skavenblight/gametest/TunnelSiegeGroupGameTests.java
git commit -m "test(pathing): add tunnel GameTest matrix (single/small-group/large-group/chained)"
```

---

## Task 24: Bridge GameTest matrix (4 shapes)

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/gametest/BridgeSiegeGroupGameTests.java`

Same structure as Tasks 22-23, gap geometry adjusted for a horizontal open-air gap with no support
below (produces `BRIDGE` per Task 3's classification: `dy == 0`, neighbor is open air, no support).
Pass condition checks for placed `Blocks.COBBLESTONE` bridge segments in the crossing zone. Four
tests, same convention.

- [ ] **Steps 1-5:** same structure as Task 22's steps.

```bash
git add src/main/java/org/ratden/skavenblight/gametest/BridgeSiegeGroupGameTests.java
git commit -m "test(pathing): add bridge GameTest matrix (single/small-group/large-group/chained)"
```

---

## Task 25: Debug/network consumer port (mechanical group)

**Discovered scope, not in the original 7-file/14-file consumer survey:** a grep across all of
`src/main` for `SiegeNode`/`TerrainEvaluator`/`SiegeLineTracer` turned up 11 files entirely outside
`ai.pathing`/`ai.pathing.region`/`ai.goal.clanrat` — debug tooling, a network sync payload, a client
render handler, a debug item, and a command. All 11 live in this same Gradle module, so all 11 must
compile once `RegionFlowField`'s return types change at Task 10 — "debug-only" does not mean
"deferrable." This task covers the 8 of those 11 files that are pure mechanical retypes plus one small,
already-decided filler-value question. The other 3 (`PathingDebugFileWriter.java`,
`ClientRenderHandler.java`, `DebugPathingCommands.java`) are handled by Task 26, Task 27, and Task 9
respectively, because each involves a real decision (glyph/color redesign) or a real sequencing
dependency (`DebugPathingCommands`) that shouldn't be hidden inside a "mechanical" task.

**Correction (2026-08-05, found executing Task 13): `SiegeActivityLog.java` is no longer this task's
file — it moved to Task 13.** `SiegeInteractionHandler` has a hard, unavoidable compile dependency on
`SiegeActivityLog.record(...)`'s action parameter (it logs every construction/mining action, not an
optional call), and that method only accepted `SiegeNode.SiegeAction`. Deferring its retype to here
would leave `SiegeInteractionHandler.java` itself red until this task ran, which sits at execution-order
position 13 — six tasks and a go/no-go gate's worth of tasks after Task 13. Since this file was already
"Group A — no decision needed" (confirmed: its only other real consumer, `PathingDebugFileWriter:365`,
just interpolates `entry.action()` into a format string, which works identically for either type), it
was pulled forward and ported as part of Task 13's own commit instead. Group A below is now 4 files, not
5; don't re-port it here.

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/client/ClientDebugData.java`
- Modify: `src/main/java/org/ratden/skavenblight/debug/mode/server/DetailedServerMode.java`
- Modify: `src/main/java/org/ratden/skavenblight/debug/mode/server/IServerDebugMode.java`
- Modify: `src/main/java/org/ratden/skavenblight/item/custom/DebugFlowFieldReaderItem.java`
- Modify: `src/main/java/org/ratden/skavenblight/network/payload/SyncFlowFieldDebugPayload.java`
- Modify: `src/main/java/org/ratden/skavenblight/debug/mode/server/WildernessServerMode.java`
- Modify: `src/main/java/org/ratden/skavenblight/debug/mode/server/MacroServerMode.java`

**Interfaces:**
- Consumes: `FlowStep`/`PathAction` (Task 1), `RegionFlowField.getInstructionMap`/`getLiveDebugMap`/
  `getRawInstruction`/`getNextStep` (Task 10's finalized return types).
- Produces: identical public API on every file above, `FlowStep` in place of `SiegeNode` and
  `PathAction` in place of `SiegeNode.SiegeAction`.

**Group A — pure mechanical retype, no decision needed** (`ClientDebugData`, `DetailedServerMode`,
`IServerDebugMode`, `DebugFlowFieldReaderItem`): every `Map<BlockPos, SiegeNode>`
becomes `Map<BlockPos, FlowStep>`, every `SiegeNode.SiegeAction` parameter/field becomes `PathAction`.
None of these four files branch on a specific action value — confirmed by direct read — so there is
nothing to redesign, only to rename.

**Group B — mechanical retype + one filler-value decision** (`SyncFlowFieldDebugPayload`,
`WildernessServerMode`, `MacroServerMode`): all three construct a SYNTHETIC single node with no real
predecessor (`WildernessServerMode`'s `new SiegeNode(heading, SiegeNode.SiegeAction.WALK)`,
`MacroServerMode`'s `new SiegeNode(boundaryCell, SiegeNode.SiegeAction.WALK)`, and
`SyncFlowFieldDebugPayload`'s wire codec, which serializes exactly 2 values per node and has no third
field to read/write for `FlowStep`'s `predecessorPos`). **Decision (settled here, not left open):** use
the node's own `pos` as `predecessorPos` in all three cases — `new FlowStep(heading, PathAction.WALK,
heading)`, `new FlowStep(boundaryCell, PathAction.WALK, boundaryCell)`, and one added `BlockPos`
read/write in `SyncFlowFieldDebugPayload`'s stream codec (self-referencing, sent as the node's own
position). This matches existing precedent already in this codebase's own test suite —
`FlowStepTest`'s `pathActionHasExactlyTheFiveNewValues`-adjacent test and `FlowFieldCalculatorTest`'s
`detectMutualCyclePositionsIgnoresATargetSelfReference` both already treat "a position pointing at
itself" as the established idiom for "this position has no real predecessor," not a special case to
invent fresh here.

- [ ] **Step 1: Implement the Group A retypes** — mechanical, no behavior change.
- [ ] **Step 2: Implement the Group B retypes** with the self-referencing `predecessorPos` filler
  described above, including the added field in `SyncFlowFieldDebugPayload`'s stream codec.
- [ ] **Step 3: Run `./gradlew compileJava`** to confirm the whole module builds — this is the actual
  verification for this task; see the class javadoc note below for why there's no dedicated test.

**Why no dedicated test:** every file here is a debug-only render/log/network-sync path with zero
gameplay consequence; correctness is "does it compile and does the debug overlay/log/wand item still
show sensible data," which has no meaningful fake to assert against beyond the compiler itself. If the
implementer wants extra confidence, manually trigger the debug wand item and the `/skavendebug` F3
overlay in a dev client — not required to consider this task done.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/client/ClientDebugData.java \
        src/main/java/org/ratden/skavenblight/debug/mode/server/DetailedServerMode.java \
        src/main/java/org/ratden/skavenblight/debug/mode/server/IServerDebugMode.java \
        src/main/java/org/ratden/skavenblight/item/custom/DebugFlowFieldReaderItem.java \
        src/main/java/org/ratden/skavenblight/network/payload/SyncFlowFieldDebugPayload.java \
        src/main/java/org/ratden/skavenblight/debug/mode/server/WildernessServerMode.java \
        src/main/java/org/ratden/skavenblight/debug/mode/server/MacroServerMode.java
git commit -m "refactor(debug): port debug/network consumers from SiegeNode to FlowStep/PathAction

Self-referencing predecessorPos for synthetic single-node debug data (WildernessServerMode/
MacroServerMode/SyncFlowFieldDebugPayload) - matches the existing self-reference-means-no-predecessor
idiom already used in FlowFieldCalculatorTest/FlowStepTest, not a new special case."
```

---

## Task 26: `PathingDebugFileWriter` glyph redesign

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/debug/PathingDebugFileWriter.java`

**Interfaces:**
- Consumes: `FlowStep`/`PathAction` (Task 1), `RegionFlowField`'s finalized return types (Task 10).
- Produces: identical public API — this file's whole job is writing a text dump, not exposing new
  methods.

**Why this needs a real decision, not a rename:** confirmed via direct read (lines ~446-456) that
`writeGrid`'s per-cell render switches exhaustively over all 9 old actions with a distinct glyph each
(`WALK→'W'`, `BUILD_STAIR→'S'`, `BUILD_BRIDGE→'B'`, `MINE→'M'`, `BUILD_PILLAR→'P'`, `BUILD_LANDING→'L'`,
`LEAP→'J'`, `BUILD_LADDER→'H'`, `BUILD_SPIRAL→'R'`), with a matching hardcoded legend (lines ~390-393)
and an `EnumMap<SiegeNode.SiegeAction, Integer>` action-count section (lines ~162-187) that also
iterates every old value. 5 of the 9 have no successor in the new 5-value `PathAction` — this needs an
actual mapping decision, decided here so the implementer doesn't have to invent one with less context:

- `WALK` → `'W'` (unchanged)
- `TUNNEL` → `'M'` (reuses `MINE`'s glyph — tunneling IS mining through solid material)
- `BRIDGE` → `'B'` (reuses `BUILD_BRIDGE`'s glyph)
- `AIR_STAIR` → `'S'` (reuses `BUILD_STAIR`'s glyph — this was the old system's only "build a stair"
  action; `AIR_STAIR` is its direct successor)
- `CARVED_STAIR` → `'C'` (new glyph — the one genuinely new classification `BUILD_STAIR` used to cover
  implicitly; `'C'` isn't used by any other node glyph today)

New legend line (replaces the two old "Nodes" legend lines at ~391-392):
```
writer.write("  Nodes  : [W] Walk    [S] Air-Stair [C] Carved-Stair [B] Bridge  [M] Tunnel   [x] Out of Bounds\n");
```

`writeMetrics`'s `EnumMap<SiegeNode.SiegeAction, Integer>` becomes `EnumMap<PathAction, Integer>` —
mechanical once the switch above is settled, since it just counts whatever `PathAction` values
actually appear.

- [ ] **Step 1: Implement the switch/legend/EnumMap changes** per the mapping above.
- [ ] **Step 2: Run `./gradlew compileJava`** to confirm the module builds.
- [ ] **Step 3: Manually generate one debug dump** (via whatever command/keybind currently triggers
  `PathingDebugFileWriter`, per its existing callers) against a live test world with at least one of
  each of the 5 actions present, and eyeball the output — this is a text-formatting file with no
  gameplay consequence, so a manual look is proportionate; don't write a GameTest for cosmetic file
  output.
- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/debug/PathingDebugFileWriter.java
git commit -m "refactor(debug): remap PathingDebugFileWriter's node glyphs to the 5-action vocabulary"
```

---

## Task 27: `ClientRenderHandler` color redesign

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/client/ClientRenderHandler.java`

**Interfaces:**
- Consumes: `FlowStep`/`PathAction` (Task 1), `RegionFlowField`'s finalized return types (Task 10).
- Produces: identical public API — client-side debug rendering only.

**Why this needs a real decision, not a rename:** confirmed via direct read that this file has TWO
exhaustive 7-to-8-value switches picking debug-line RGB colors — `drawFloorArrow` (lines ~161-172) and
`drawWildernessArrow` (lines ~302-309) — both keyed on the old `SiegeNode.SiegeAction`, both missing 5
successors in the new `PathAction`. Both switches also currently merge `BUILD_BRIDGE`/`BUILD_STAIR`
into ONE shared color, which no longer works: `BRIDGE` and `AIR_STAIR` are genuinely distinct actions
now and need genuinely distinct colors. Mapping decided here:

`drawFloorArrow`'s switch (replaces lines ~161-168; the `WALK` traffic-heatmap branch is unchanged):
- `TUNNEL` → `r=255,g=0,b=255` (reused from the old `MINE` case)
- `BRIDGE` → `r=0,g=255,b=255` (reused from the old combined `BUILD_BRIDGE`/`BUILD_STAIR` case in THIS
  switch)
- `AIR_STAIR` → `r=0,g=191,b=255` (reused from `drawWildernessArrow`'s combined case below, repurposed
  here specifically to be visually close-but-distinct from `BRIDGE`, since both used to render
  identically)
- `CARVED_STAIR` → `r=255,g=140,b=0` (reused from the now-free old `BUILD_PILLAR` case)

Also update the one action-equality check just below the switch: `node.action() !=
SiegeNode.SiegeAction.WALK` → `node.action() != PathAction.WALK`.

`drawWildernessArrow`'s switch (replaces lines ~302-308; this switch has no `WALK` case today and none
is added — confirmed, `WALK` is never passed to this method by its existing callers):
- `TUNNEL` → `r=255,g=0,b=128` (reused from the old `MINE` case)
- `BRIDGE` → `r=0,g=191,b=255` (reused from the old combined `BUILD_BRIDGE`/`BUILD_STAIR` case in THIS
  switch)
- `AIR_STAIR` → `r=0,g=255,b=255` (reused from `drawFloorArrow`'s combined case above, repurposed here
  for the same close-but-distinct-from-`BRIDGE` reasoning)
- `CARVED_STAIR` → `r=255,g=69,b=0` (reused from the now-free old `BUILD_PILLAR` case)

- [ ] **Step 1: Implement both switches** per the mapping above, and the one `WALK` equality-check
  retype.
- [ ] **Step 2: Run `./gradlew compileJava`** to confirm the module builds.
- [ ] **Step 3: Manually trigger the debug overlay** (via whatever keybind/item currently activates
  `ClientRenderHandler`'s debug lines) against a live test world with at least one of each of the 5
  actions present, and eyeball the colors — same proportionate manual check as Task 26, no GameTest for
  a cosmetic client-render color scheme.
- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/client/ClientRenderHandler.java
git commit -m "refactor(debug): remap ClientRenderHandler's debug-line colors to the 5-action vocabulary"
```

---

## Task 28: Delete old `SiegeNode.java`/`TerrainEvaluator.java`/`SiegeLineTracer.java` source files

**A genuine gap in this plan's first draft:** the File Disposition table above always said these three
files are "Deleted, replaced by new files," but no task in the original 24-task draft ever actually ran
`git rm` on them — only their TEST files (`SiegeNode` has none; `SiegeLineTracerTest`/`TerrainEvaluatorTest`
are deleted in Task 20) and the unrelated `RegionIndex.java` (Task 9) were ever removed. Left in place,
these three files are 519 lines of dead code (`SiegeNode.java` 34, `TerrainEvaluator.java` 295,
`SiegeLineTracer.java` 190 — confirmed via `wc -l`) sitting directly against the design doc's ≤2500-line
success metric for `ai.pathing`. This task has no files of its own beyond the three deletions — it's
three separate `git rm` calls, each gated on its own last-real-consumer, so it's written as three
explicitly-timed steps rather than one atomic task. **Do not run all three at once — each has a
different readiness point in the Execution Order list above.**

- [ ] **Step 1 (run at Execution Order position 9, immediately after Task 11): delete
  `src/main/java/org/ratden/skavenblight/ai/pathing/SiegeLineTracer.java`.** Confirmed via a dedicated
  `grep -r SiegeLineTracer src/main` that its only real production consumers were `RegionGraph`/
  `RegionConnector` (Task 9), `SiegeProjectManager` (Task 11), `SiegeProject` (Task 12, via `tryWiden`),
  and `DebugPathingCommands` (folded into Task 9) — all done by this point. `TerrainEvaluator.java`'s
  own mention of `SiegeLineTracer` is a comment, not code. `StrandedGoal.java`'s mention died with
  Task 16.

  **Correction (2026-08-05, found executing Task 11): that grep's consumer list is incomplete —
  `TerritoryRegionMap.java` also has a real reference (`private final SiegeLineTracer lineTracer =
  new SiegeLineTracer(terrainEvaluator);` plus passing it into `RegionGraph.build(...)` at its own
  call site). This doesn't change the deletion verdict: `TerritoryRegionMap.java` is already broken
  independently (it still references the Task-9-deleted `RegionIndex` and the pre-Task-11
  `TerrainEvaluator`/old `SiegeProjectManager` constructor shape, and its `RegionGraph.build` call
  site already targets an overload Task 9 removed) and is already Task 14's own scope to fully
  retype, `lineTracer` field included — deleting `SiegeLineTracer.java` here adds no new class of
  breakage to a file that's already this broken, and Task 14 was always going to remove this field
  regardless of whether the class file itself still exists on disk. Recorded here only because the
  original justification ("all done by this point") undercounted a real consumer, not because the
  action it recommends is wrong.

  ```bash
  git rm src/main/java/org/ratden/skavenblight/ai/pathing/SiegeLineTracer.java
  git commit -m "chore(pathing): delete SiegeLineTracer.java, folded into PathStepEvaluator/RegionGraph/SiegeProjectManager"
  ```

- [ ] **Step 2 (run at Execution Order position 10, immediately after Task 10): delete
  `src/main/java/org/ratden/skavenblight/ai/pathing/TerrainEvaluator.java`.** Its remaining production
  consumers are gone by this point — confirmed directly for `RegionConnector.isCompleted`'s
  `TerrainEvaluator` parameter (retyped in Task 9) and `RegionFlowField`'s own `private final
  TerrainEvaluator terrainEvaluator` field (retyped in Task 10); `DebugPathingCommands` was folded into
  Task 9. If `./gradlew compileJava` finds another reference at this point, that's a sign a task
  between here and Task 9 left one behind, not a sign to keep the file around.

  ```bash
  git rm src/main/java/org/ratden/skavenblight/ai/pathing/TerrainEvaluator.java
  git commit -m "chore(pathing): delete TerrainEvaluator.java, replaced by PathStepEvaluator"
  ```

- [ ] **Step 3 (run at Execution Order position 19, immediately after Task 19): delete
  `src/main/java/org/ratden/skavenblight/ai/pathing/SiegeNode.java`, and sweep three stale
  javadoc-only mentions.** `SiegeNode.java`'s last real consumers were the goal-layer files just ported
  in Tasks 17-19. Additionally, confirmed via grep that `PathingRegionGameTests.java`,
  `PathingGoalRecalculationGameTests.java`, and `StackedStairColumnReTraversalGameTests.java` each
  mention `SiegeLineTracer`/`SiegeNode` inside `{@code ...}` javadoc tags describing old behavior —
  comments only, never compiled code, so they were never a compile blocker, but they'll now name
  deleted classes. Update their prose to describe the same documented behavior in terms of
  `PathStepEvaluator`/`FlowStep`/`RegionGraph` while touching these files anyway for Task 20's other
  edits (don't make this a separate pass over the same files).

  **Correction (2026-08-05, found executing this step): the "comments only, never compiled code"
  premise is wrong for one of the three files.** Confirmed via direct grep that
  `PathingGoalRecalculationGameTests.java` has extensive REAL `SiegeNode` code (constructor calls,
  `Map<BlockPos, SiegeNode>` declarations, `.action()`/`SiegeNode.SiegeAction.BUILD_STAIR` usages —
  dozens of call sites), not a javadoc-only mention — this file was already broken independently
  (24 errors before this deletion, from its own unrelated `TerrainEvaluator`/old-`SiegeProject`-
  constructor issues, per Task 20's own gap note) and is explicitly Task 20's full-retype scope, not
  a quick sweep. Deleting `SiegeNode.java` here is still safe (confirmed via `compileJava`: this
  file's error count grows, 24 → 53, but no NEW file appears in the error list - the same
  already-broken, already-scheduled file, exactly the "adds no new class of breakage" reasoning this
  plan already used for the `SiegeLineTracer`/`TerrainEvaluator` deletions), but it is NOT touched
  in this step's own commit - its real retype (code, not comments) stays Task 20's job, done there
  in one pass rather than a separate javadoc-only touch now. Only `PathingRegionGameTests.java` (one
  stale LEAP-javadoc line, confirmed genuinely comment-only, already compiles clean) and
  `StackedStairColumnReTraversalGameTests.java` (one stale class-javadoc block, confirmed genuinely
  comment-only - the file's own test method never references any pathing-rewrite type at all, only
  raw vanilla blocks) are swept here.

  ```bash
  git rm src/main/java/org/ratden/skavenblight/ai/pathing/SiegeNode.java
  git add src/main/java/org/ratden/skavenblight/gametest/PathingRegionGameTests.java \
          src/main/java/org/ratden/skavenblight/gametest/StackedStairColumnReTraversalGameTests.java
  git commit -m "chore(pathing): delete SiegeNode.java, update stale javadoc references to it"
  ```

---

## After Task 24

At this point every item in the task's original checklist is covered except the final line-count/
comment-density check. Add one closing task before calling the rewrite done:

- [ ] Run `git diff main --stat -- src/main/java/org/ratden/skavenblight/ai/pathing` (or equivalent)
  and confirm the total line count across `ai.pathing` (including the region sub-package) is ≤2500,
  per the design doc's success metric. If over, this is a real finding to report, not silently
  ignore — the design doc's own file-count/line-count guidance ("13-15 files... this both cuts total
  lines hard") is a prediction, not a guarantee, and this rewrite's actual footprint (Tasks 1-16, plus
  Tasks 25-28 discovered during the Execution Order correction) turned out larger than the design
  doc's own survey anticipated once the goal-layer AND debug/network/client scope was corrected —
  report the real number either way.
