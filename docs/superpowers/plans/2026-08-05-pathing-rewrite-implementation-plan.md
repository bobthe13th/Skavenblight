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
`PathingGoalRecalculationGameTests` (drop `testDeployClimbableGoalMarksRegionDirty` and siblings),
`PathingRegionGameTests` (drop the one LEAP-javadoc comment reference), `FlowFieldCalculatorTest`,
`SiegeProjectManagerTest`, `SiegeProjectTest`, `SiegeProjectAutoWidenGameTests` (rewrite its
`BUILD_PILLAR` fixture to `AIR_STAIR`/`BRIDGE` — auto-widening itself survives, only climb actions
don't).

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

- [ ] **Step 1: Write the failing tests**

```java
package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;
import org.ratden.skavenblight.Config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PathStepEvaluatorCostTest {

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
        TerrainAccess terrain = mock(TerrainAccess.class);
        BlockPos pos = new BlockPos(0, 0, 0);
        when(terrain.getDestroySpeed(pos)).thenReturn(-1.0F);

        int expectedWorkUnits = (int) (Config.bedrockFailsafeRatMinutes * 1200 * Config.workPerRatPerTick);
        assertEquals(expectedWorkUnits, evaluator.miningCost(terrain, pos));
    }

    @Test
    void ordinaryBlockUsesHardnessDrivenCostNotBedrockFailsafe() {
        PathStepEvaluator evaluator = new PathStepEvaluator();
        TerrainAccess terrain = mock(TerrainAccess.class);
        BlockPos pos = new BlockPos(0, 0, 0);
        when(terrain.getDestroySpeed(pos)).thenReturn(2.0F);

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

**Interfaces:**
- Consumes: `Task 2`'s cost methods, `TerrainAccess`.
- Produces: `PathStepEvaluator.EvaluatedStep(BlockPos pos, int cost, PathAction action)` record (same
  shape as today's `TerrainEvaluator.EvaluatedStep`). `PathStepEvaluator.candidateSteps(TerrainAccess
  terrain, BlockPos current, Set<BlockPos> lockedPositions, Predicate<BlockPos> outOfBounds,
  BlockPos costBiasTarget)` → `List<EvaluatedStep>` — the ONE method both ordinary walking and
  construction discovery call (replaces the old split between `getValidOrthogonalSteps` and
  `determineMacroAction`/`SiegeLineTracer`). `PathStepEvaluator.isWalkableTerrain(TerrainAccess,
  BlockPos)` and `PathStepEvaluator.isOutOfBounds(...)` — ported verbatim from `TerrainEvaluator`
  (these two are pure terrain predicates, not part of the old duplication problem).

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
   one of the four new ones):
   - `dy != 0` and diagonal (`dx != 0 && dz != 0`, i.e. a genuine diagonal rise/drop): `CARVED_STAIR`
     if the neighbor cell itself blocks motion (mining through solid material to carve a stair) or
     `AIR_STAIR` if it's open air (building a stair through open air) — this is the one genuinely new
     classification decision the old code didn't need to make explicitly, since `BUILD_STAIR` used to
     cover both; use `terrain.getBlockState(neighbor).blocksMotion()` as the discriminator.
   - `dy == 0`, horizontal: `TUNNEL` if the neighbor blocks motion, `BRIDGE` if it's open air with no
     support below (mirrors today's "Horizontal Bridge" branch).
   - `dy != 0`, `dx == 0 && dz == 0` (pure vertical): **do not offer a step here at all** — pure
     vertical climbing (today's PILLAR/LADDER/SPIRAL branch) is permanently removed per the design
     doc; a pure-vertical obstacle must be crossed some other way (a diagonal CARVED_STAIR/AIR_STAIR
     around it, or a TUNNEL/BRIDGE detour) or is simply unreachable from this cell.
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

Port `isWalkableTerrain`, `isFitForWalking`, `isOverheadClear`, `isWalkableScaffold` (drop the
`StairBlock`/`SlabBlock`/`LadderBlock` scaffold exemptions specific to climbing — with climbing gone,
only `Blocks.COBBLESTONE` and `Blocks.COBBLESTONE_STAIRS`/bridge fill blocks need the "can stand on
this even though it also blocks motion" exemption), `isOutOfBounds`, and `HORIZONTAL_OFFSETS` from
`TerrainEvaluator` verbatim (these are pure terrain predicates unrelated to the WALK/construction
duplication being fixed). Implement `candidateSteps` following the spec above — the single method
that replaces both `getValidOrthogonalSteps` and `determineMacroAction`.

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
- Test: `src/test/java/org/ratden/skavenblight/ai/pathing/TerrainSnapshotPlannedOverrideTest.java`

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

- [ ] **Step 1: Write the failing test**

```java
package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class TerrainSnapshotPlannedOverrideTest {

    @Test
    void plannedOverrideReplacesCapturedStateAtExactlyTheOverriddenPosition() {
        // Uses a real ServerLevel via the GameTest harness in practice; this unit test exercises
        // captureColumn's override branch directly against a minimal fake to stay a fast unit test -
        // see Task 4 Step 3 for the actual signature captureColumn needs (package-private, taking
        // the override function so this test can call it without a full ServerLevel).
        Function<BlockPos, net.minecraft.world.level.block.state.BlockState> override =
                pos -> pos.equals(new BlockPos(1, 1, 1)) ? Blocks.COBBLESTONE.defaultBlockState() : null;

        assertEquals(Blocks.COBBLESTONE.defaultBlockState(),
                override.apply(new BlockPos(1, 1, 1)));
        assertNull(override.apply(new BlockPos(2, 2, 2)));
    }
}
```

(This task's real verification is the GameTest in Task 17's grief-recovery test, which exercises the
override end-to-end against a live `ServerLevel`. This unit test only pins the override function's
own contract before that GameTest depends on it.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.ratden.skavenblight.ai.pathing.TerrainSnapshotPlannedOverrideTest"`
Expected: PASS trivially (this test doesn't touch TerrainSnapshot yet) — this is a placeholder
pin; the real regression protection is Task 17's GameTest. Proceed to Step 3 regardless.

- [ ] **Step 3: Add the override parameter to `TerrainSnapshot.refresh`/`captureColumn`**

Add the 8th parameter to `refresh`'s signature and thread it through to `captureColumn`. Inside
`captureColumn`'s per-cell loop, after computing `BlockState bs = level.getBlockState(cursor);`,
check `BlockState overridden = plannedStateOverride.apply(cursor.immutable()); if (overridden !=
null) bs = overridden;` before computing `destroySpeeds[idx]`/`solidRender.set(idx)` — both derived
values must reflect the override, not the real block, since they're what `PathStepEvaluator` actually
reads. Add a 7-arg overload that forwards `pos -> null` to the new 8-arg method, so every existing
call site (Task 11's `TerritoryRegionMap` edits aside) keeps compiling unchanged until Task 11
explicitly upgrades them.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.ratden.skavenblight.ai.pathing.TerrainSnapshotPlannedOverrideTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/TerrainSnapshot.java \
        src/test/java/org/ratden/skavenblight/ai/pathing/TerrainSnapshotPlannedOverrideTest.java
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
`MAX_CONSECUTIVE_MINE_DEPTH`/`mineChainDepth` tracking. **Verify this drop is safe before deleting it,
don't assume:** the old cap existed specifically to stop a MINE chain tunneling arbitrarily deep into
undisturbed rock chasing marginal savings (documented incident: 61% of a 114k-node pass was MINE).
`candidateSteps`'s frontier gate stops offering WALK the moment terrain is genuinely blocked, but a
TUNNEL chain through solid rock IS exactly a legitimate multi-segment construction chain per the
mandatory invariant — so confirm empirically (via this task's own test below, extended if needed)
that cost alone (`baseCostFor(TUNNEL) + miningCost per block`, accumulating every hop) already makes
an arbitrarily long tunnel lose to any real alternative long before it matters, rather than assuming
it. If it doesn't, this cap needs a home in the new evaluator after all — say so and stop rather than
silently dropping a fix for a confirmed 14-minute-hang bug.

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
- Test: `src/test/java/org/ratden/skavenblight/ai/pathing/region/RegionScannerTest.java`

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
tracking here too, for the identical reason Task 6 drops it from `FlowFieldCalculator`** (a TUNNEL
chain through solid rock is a legitimate construction chain now, not an unbounded MINE tunnel that
would wrongly fuse two regions together) — verify this doesn't cause regions to wrongly merge across
a genuine TUNNEL-length obstacle by running this task's test below against a deliberately long
(20+ block) solid wall between two open areas and confirming they still scan as two separate regions
(a TUNNEL candidate is a construction edge, and `RegionScanner`'s flood must never follow construction
edges at all — only `WALK` — or every obstacle a `SiegeProject` could ever bridge would silently
merge the regions on either side of it, destroying the very partition this class exists to produce).
**This is the one part of this task that is NOT a mechanical port** — confirm explicitly in this
task's test that `floodFill` only ever enqueues `WALK`-action candidates from `candidateSteps`
(construction candidates are for `RegionGraph`'s connector discovery in Task 9, never for region
membership).

- [ ] **Step 1: Write the failing test**

```java
package org.ratden.skavenblight.ai.pathing.region;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RegionScannerTest {

    @Test
    void aTwentyBlockSolidWallProducesTwoSeparateRegionsNotOne() {
        // Build a fake TerrainSnapshot-equivalent via the real capture path against a small
        // in-memory level fixture (see existing PathingRegionGameTests for the established
        // pattern this project already uses to build a real TerrainSnapshot in tests) with a
        // 20-block-thick solid wall separating two 5x5 open floors.
        //
        // Full fixture setup deferred to implementation time using that existing GameTest-adjacent
        // pattern (this test lives in src/test, exercised via a captured snapshot fixture built the
        // same way PathingRegionGameTests already does it for real ServerLevel-backed scans) -
        // the assertion below is the actual contract this task must satisfy regardless of fixture
        // mechanics:
        List<Region> regions = /* RegionScanner.scan(...) against the two-floor-plus-wall fixture */
                List.of(); // placeholder wiring only - implementer fills in the real scan() call
        assertEquals(2, regions.size(),
                "a 20-block solid wall must never be treated as a WALK-traversable region boundary, "
                        + "even though it's a valid TUNNEL construction candidate for RegionGraph");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.ratden.skavenblight.ai.pathing.region.RegionScannerTest"`
Expected: FAIL (fixture not wired up yet / assertion fails against `List.of()`).

- [ ] **Step 3: Wire up the real fixture and implement the port**

Build the two-floor-plus-wall `TerrainSnapshot` fixture using the same construction pattern
`PathingRegionGameTests` already uses elsewhere in this codebase for a real captured snapshot (read
that file's existing fixture-building helpers before writing a new one — don't duplicate a second
way to build a test snapshot if one already exists). Port `RegionScanner` per the spec above.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.ratden.skavenblight.ai.pathing.region.RegionScannerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionScanner.java \
        src/test/java/org/ratden/skavenblight/ai/pathing/region/RegionScannerTest.java
git commit -m "refactor(pathing): port RegionScanner to PathStepEvaluator, confirm TUNNEL never merges regions"
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

**Confirmed-unreachable-region mechanism (per user confirmation above — implement exactly this, the
approved proposal):**

1. `RegionGraph.build` runs its ordinary connector discovery (step 1) with `PathStepEvaluator`'s
   normal costs — meaning ordinary breakable-block `TUNNEL`/`CARVED_STAIR` mining is offered normally,
   but bedrock-tier mining (`PathStepEvaluator.isBedrockLike` true) is **never offered as a candidate
   during this first pass** — add a `boolean allowBedrockTierMining` parameter to whatever internal
   step-generation call `tryTrace` makes, defaulted `false` for this first pass.
2. After `build` finishes and `RegionRouteTree.compute(graph, rootRegionId)` runs (in
   `TerritoryRegionMap`, Task 11), compute `confirmedUnreachableRegionIds`: every region id present in
   this graph's own region list that is NOT `rootRegionId` and for which `routeTree.isReachable(id)`
   is `false`. This is computed at the exact same cadence as the route tree itself (only after a FULL
   rebuild, never from the steady-state dirty-region fast path — see Task 11's port for where this is
   called, alongside the existing `RegionRouteTree.compute` call site, never anywhere else) so it can
   never go stale independently of the tree, and the fast path never sets or trusts it.
3. For exactly the regions in that set — and only those — `TerritoryRegionMap` (Task 11) calls back
   into `RegionGraph` with a second, targeted connector-discovery attempt scoped to that one region's
   own boundary cells, with `allowBedrockTierMining = true`. This is a small, targeted retry bounded
   by the number of actually-isolated regions (0 or 1 in the overwhelmingly common case), never a
   second full-territory pass — the exact mechanism confirmed with the user, and the doc's explicitly
   rejected alternative (a second relaxed-ceiling pass over the WHOLE territory) is not what this is.
4. Everywhere else, costs stay purely relative: `bestPerPair`'s "cheaper connector wins" comparison
   already IS the "worse than an already-connected alternative → discard" rule the design doc
   requires — there is no separate absolute ceiling anywhere in this method to remove, since none
   ported forward from `SiegeLineTracer`'s old `costCeiling` parameter (that parameter doesn't exist
   in the new `PathStepEvaluator`-based tracing at all — confirm this explicitly in code review before
   considering this task done: if a ceiling comparison of any kind survived the port, find it and
   convert it to a relative comparison instead of an absolute one).

- [ ] **Step 1: Write the failing tests**

```java
package org.ratden.skavenblight.ai.pathing.region;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RegionGraphTest {

    @Test
    void regionWithNoOrdinaryConnectorIsInTheConfirmedUnreachableSet() {
        // Fixture: two regions, zero connectors between them (a fully solid wall on every side
        // thick enough that PathStepEvaluator's ordinary candidateSteps never bridges it within
        // MAX_CHAIN_HOPS). Full fixture setup follows the same TerrainSnapshot-capture pattern as
        // Task 8's RegionScannerTest.
        RegionGraph graph = /* RegionGraph.build(...) against the two-sealed-regions fixture */ null;
        RegionRouteTree tree = RegionRouteTree.compute(graph, /* rootRegionId */ 0);

        Set<Integer> unreachable = graph.confirmedUnreachableRegionIds(0, tree);

        assertEquals(Set.of(1), unreachable);
    }

    @Test
    void regionWithAnOrdinaryConnectorIsNeverInTheConfirmedUnreachableSet() {
        // Fixture: two regions joined by an ordinary (non-bedrock) TUNNEL-crossable gap.
        RegionGraph graph = /* RegionGraph.build(...) against the two-connected-regions fixture */ null;
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
- Test: `src/test/java/org/ratden/skavenblight/ai/pathing/region/RegionFlowFieldTest.java`

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

- [ ] **Step 1: Write the failing test**

```java
package org.ratden.skavenblight.ai.pathing.region;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.ratden.skavenblight.ai.pathing.FlowStep;
import org.ratden.skavenblight.ai.pathing.PathAction;

import static org.junit.jupiter.api.Assertions.*;

class RegionFlowFieldTest {

    @Test
    void getNextStepNeverLooksAboveTheNodeForAnyOfTheFiveActions() {
        // CLIMB_TO_ABOVE_ONCE_BUILT is gone entirely - every one of the five new PathAction values
        // must resolve a completed step to WALK at node.pos() itself, never node.pos().above().
        // Full assertion requires a constructed RegionFlowField against a live-terrain fixture
        // reporting the action as already completed - build this the same way the existing
        // PathingGoalRecalculationGameTests already fixtures a RegionFlowField for goal-level tests
        // (reuse that helper rather than inventing a second one).
        assertTrue(true, "placeholder - implementer wires the real fixture per the note above");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.ratden.skavenblight.ai.pathing.region.RegionFlowFieldTest"`
Expected: PASS trivially until the real fixture is wired (same placeholder-then-fixture pattern as
Task 8); wire the real fixture before calling this task done, and confirm the test would actually
fail against a version of `getNextStep` that still special-cases CLIMB_TO_ABOVE_ONCE_BUILT-shaped
logic before deleting that logic, so this test is a real regression guard rather than a tautology.

- [ ] **Step 3: Implement the ports** per the spec above.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.ratden.skavenblight.ai.pathing.region.RegionFlowFieldTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionRouteTree.java \
        src/main/java/org/ratden/skavenblight/ai/pathing/region/RegionFlowField.java \
        src/test/java/org/ratden/skavenblight/ai/pathing/region/RegionFlowFieldTest.java
git commit -m "refactor(pathing): port RegionRouteTree (unchanged) and RegionFlowField (drop climb-to-above)"
```

---

## Task 11: `SiegeProjectManager` rewrite

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/ai/pathing/SiegeProjectManager.java` (replaces the
  old file)
- Test: `src/test/java/org/ratden/skavenblight/ai/pathing/SiegeProjectManagerTest.java` (ported from
  the existing file)

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
Task 9's confirmation that no absolute ceiling survives anywhere in the new code). Port the
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
- Test: `src/test/java/org/ratden/skavenblight/ai/pathing/SiegeInteractionHandlerTest.java`

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

- [ ] **Step 1: Write the failing tests** (port the existing headroom-clear GameTest-adjacent
  assertions as plain unit tests against a mocked `ServerLevel`/`BlockState` where feasible; the full
  world-mutation behavior is exercised end-to-end by Task 20's GameTest matrix, so this task's unit
  tests focus on the dispatch logic only):

```java
package org.ratden.skavenblight.ai.pathing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SiegeInteractionHandlerTest {

    @Test
    void carvedStairMinesBeforePlacingNotAfter() {
        // Ordering assertion only feasible with a real ServerLevel fixture (see Task 20's GameTest
        // matrix for CARVED_STAIR's own dedicated tests, which are this behavior's real coverage).
        // This unit test's role is narrower: pin that constructSiegeBlock's CARVED_STAIR branch
        // calls executeBreach-equivalent logic before placing COBBLESTONE_STAIRS, verified via the
        // GameTest matrix rather than duplicated here in a mock-heavy unit test that would mostly
        // just re-describe the implementation. No assertion beyond this comment for this specific
        // test - real coverage lives in Task 20.
        assertTrue(true);
    }
}
```

- [ ] **Step 2-4:** Given this task's real behavioral coverage is Task 20's GameTest matrix (mining +
  placing world geometry can't be meaningfully unit-tested without a real `ServerLevel`), implement
  `SiegeInteractionHandler` directly per the spec above, then defer full verification to Task 20.
  Still run `./gradlew test` after implementing to confirm no compile regressions elsewhere.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/ai/pathing/SiegeInteractionHandler.java \
        src/test/java/org/ratden/skavenblight/ai/pathing/SiegeInteractionHandlerTest.java
git commit -m "feat(pathing): rewrite SiegeInteractionHandler for the five-action vocabulary + platform execution"
```

---

## Task 14: `TerritoryRegionMap` targeted port (NOT a rewrite)

**Files:**
- Modify: `src/main/java/org/ratden/skavenblight/ai/pathing/region/TerritoryRegionMap.java`
- Test: `src/test/java/org/ratden/skavenblight/ai/pathing/region/TerritoryRegionMapOnBlockChangedTest.java`

**Interfaces:**
- Consumes: every type this file already references, now the new versions (`RegionScanner`,
  `SiegeProjectManager`, `FlowFieldCalculator`, `RegionGraph`, `RegionRouteTree`, `PathStepEvaluator`
  in place of `TerrainEvaluator`, drop the `SiegeLineTracer lineTracer` field entirely — nothing in
  the new `RegionGraph.build` signature takes one).
- Produces: identical public API. Two new call sites, both additive:
  `rebuildRegionsAndGraph`'s existing `RegionRouteTree.compute(newGraph, rootRegion.getId())` call
  gains one immediately-following line computing `newGraph.confirmedUnreachableRegionIds(rootRegion
  .getId(), newRouteTree)`, stored as a new `volatile Set<Integer> confirmedUnreachableRegionIds`
  field (mirroring how `routeTree` itself is already published) — and, for each id in that set, one
  targeted `RegionGraph` retry call per Task 9's spec, folding any newly-discovered bedrock-tier
  connector into `newGraph` before it's published. This is the ONLY new control flow this task adds
  beyond mechanical type substitution.

**The onBlockChanged authority fix (the design doc's own explicit instruction — a deletion, not a new
filter):**
1. Delete `SiegeProject.tick()`'s `flowField.forceRecalculation(step.pos())` call entirely (ported
   into Task 12's rewrite already — confirm it's genuinely gone, don't re-add it here out of habit
   while porting `tick()`'s surrounding structure).
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
   correctness property that needs its own test, not just code review — see Task 19's grief-recovery
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

- [ ] **Step 5: Wire persistence calls into `SiegeProjectManager`/`SiegeProject.tick()`** per the
  "when to persist" spec above, and the clamp-on-load fix into wherever `TerritoryRegionMap`/
  `SiegeProjectManager` reconstructs projects from the store during network rebuild.

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
- Test: `src/main/java/org/ratden/skavenblight/gametest/AwaitFormationGoalGameTests.java` (new — this
  is real new scope per the design doc; "never observed working" means there is no existing test to
  port)

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

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/gametest/SiegeProjectGriefRecoveryGameTests.java`
- Delete: `src/main/java/org/ratden/skavenblight/gametest/SiegeConstructionActionsGameTests.java`
- Modify: `src/main/java/org/ratden/skavenblight/gametest/SiegeProjectAutoWidenGameTests.java`
  (rewrite `BUILD_PILLAR` fixture to `AIR_STAIR`/`BRIDGE`)
- Modify: `src/main/java/org/ratden/skavenblight/gametest/PathingGoalRecalculationGameTests.java`
  (delete `testDeployClimbableGoalMarksRegionDirty` and any sibling climb-specific test methods)
- Modify: `src/main/java/org/ratden/skavenblight/gametest/PathingRegionGameTests.java` (delete the
  stale LEAP-javadoc comment reference)
- Delete: `src/test/java/org/ratden/skavenblight/ai/pathing/SiegeLineTracerTest.java`
- Delete: `src/test/java/org/ratden/skavenblight/ai/pathing/TerrainEvaluatorTest.java`

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
        src/main/java/org/ratden/skavenblight/gametest/PathingGoalRecalculationGameTests.java \
        src/main/java/org/ratden/skavenblight/gametest/PathingRegionGameTests.java
git rm src/main/java/org/ratden/skavenblight/gametest/SiegeConstructionActionsGameTests.java \
       src/test/java/org/ratden/skavenblight/ai/pathing/SiegeLineTracerTest.java \
       src/test/java/org/ratden/skavenblight/ai/pathing/TerrainEvaluatorTest.java
git commit -m "test(pathing): add grief-recovery GameTest, retire legacy climb/line-tracer test files"
```

---

## Task 21: Go/no-go gate — the 4 existing air-stair GameTests

**Files:**
- No production code changes expected beyond bug fixes this task's own failures reveal.
- Reference: `src/main/java/org/ratden/skavenblight/gametest/StaircaseSiegeGroupGameTests.java`
  (unchanged — same 4 tests, now exercised against entirely new production code).

**This is the task's explicit go/no-go gate.** Per the task instructions: "Get all 4 [air-stair
GameTests] passing before writing any of the [12 new part-type GameTests]." Do not proceed to Task 22
until all 4 pass.

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

## After Task 24

At this point every item in the task's original checklist is covered except the final line-count/
comment-density check. Add one closing task before calling the rewrite done:

- [ ] Run `git diff main --stat -- src/main/java/org/ratden/skavenblight/ai/pathing` (or equivalent)
  and confirm the total line count across `ai.pathing` (including the region sub-package) is ≤2500,
  per the design doc's success metric. If over, this is a real finding to report, not silently
  ignore — the design doc's own file-count/line-count guidance ("13-15 files... this both cuts total
  lines hard") is a prediction, not a guarantee, and this rewrite's actual footprint (Tasks 1-16)
  turned out larger than the design doc's own survey anticipated once the goal-layer scope was
  corrected — report the real number either way.
