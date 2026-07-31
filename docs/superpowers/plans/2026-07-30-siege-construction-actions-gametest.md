# Siege Construction Actions GameTest Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an end-to-end GameTest that drives a single `ClanratEntity` through all 7 non-trivial `SiegeNode.SiegeAction` values (`MINE`, `BUILD_STAIR`, `BUILD_BRIDGE`, `BUILD_PILLAR`, `BUILD_LANDING`, `BUILD_LADDER`, `BUILD_SPIRAL`), proving the rat can actually *execute* every siege construction action via the real production goals and `SiegeInteractionHandler`, not just that a flow field computes one.

**Architecture:** One new `@GameTestHolder` class, `SiegeConstructionActionsGameTests`, reusing the `skavenblight:pathing_test` template (flat 32x8x32 platform) already established by `PathingRegionGameTests`/`PathingGoalRecalculationGameTests`. A single `ClanratEntity` and a single hand-built `RegionFlowField` (bypassing `FlowFieldCalculator` entirely — its correctness is covered by other tests) are reused across 7 independent "lanes" laid out along one Z row, each lane exercising one action: terrain setup → point the flow field's instruction map at that lane's target → drive the appropriate goal (`SmartBreachGoal` for `MINE`, `BuildFlowFieldGoal` for everything else) through `canUse()`/`start()`/`tick()` → assert the resulting block state.

**Tech Stack:** Java 21, NeoForge 1.21.1 GameTest framework (`net.minecraft.gametest.framework`, `net.neoforged.neoforge.gametest`), existing `ai/goal/clanrat` and `ai/pathing` packages.

## Global Constraints

- Every `@GameTest` method reusing `pathing_test` MUST set `skyAccess = true` — omitting it makes the harness auto-encase the structure with a barrier roof that the region scanner treats as a second, bogus floor (irrelevant to this specific plan since we never touch region scanning, but it's a template-wide rule this codebase's other GameTests already rely on — keep it for consistency and to avoid the encasement roof shadowing our open-air headroom checks).
- Helper-relative Y is template-relative Y + 1: helper Y=1 is the template's solid floor, helper Y=2 is the first open/walkable layer above it. All coordinates in this plan are already given in helper-relative terms.
- No unit test framework existed historically per this repo's CLAUDE.md, but `src/test` (plain JUnit 5, via NeoForge's `unitTest` Gradle integration) and `src/main/java/org/ratden/skavenblight/gametest` (GameTest framework) both now exist and are the two real test mechanisms in this repo. This plan uses GameTest, since the deliverable is "a rat actually building things in a live simulated world," not a pure-logic unit.
- Follow existing code style in `org.ratden.skavenblight.gametest`: `@GameTestHolder(Skavenblight.MODID)` + `@PrefixGameTestTemplate(false)` on the class, `check(...)` (a package-private static helper already defined in `PathingRegionGameTests`, imported via `import static org.ratden.skavenblight.gametest.PathingRegionGameTests.check;`) instead of raw assertions, hand-built `FlowFieldState`/`RegionFlowField` objects instead of mocking.

---

## File Structure

One new file, no modifications to existing files:

| File | Change |
|---|---|
| `src/main/java/org/ratden/skavenblight/gametest/SiegeConstructionActionsGameTests.java` | New GameTest class: one `@GameTest` method driving 7 lanes, one per siege construction action. |

---

### Task 1: `SiegeConstructionActionsGameTests` — all 7 siege actions, one rat, one test

**Files:**
- Create: `src/main/java/org/ratden/skavenblight/gametest/SiegeConstructionActionsGameTests.java`

**Interfaces:**
- Consumes: `AbstractSiegeConstructionGoal` (`setFlowField(RegionFlowField)`, `canUse()`, `start()`, `tick()` — all public, inherited from `net.minecraft.world.entity.ai.goal.Goal` / `SiegeGoal`), `BuildFlowFieldGoal(PathfinderMob)`, `SmartBreachGoal(PathfinderMob)`, `FlowFieldState(BlockPos targetPos, Set<ChunkPos> territoryChunks)` + `updateInstructions(Map<BlockPos, SiegeNode>)`, `RegionFlowField(TerritoryRegionMap owner, int regionId, FlowFieldState state, SiegeProjectManager projectManager, FlowFieldCalculator calculator, CalculationThrottler throttler)`, `SiegeNode(BlockPos pos, SiegeNode.SiegeAction action)`, `PathingRegionGameTests.check(boolean, String)`.
- Produces: nothing consumed by later tasks — this plan is a single task.

**Why:** The reported production bug was "a large group of rats are stuck directly below the nexus, and aren't actually able to build anything to get up." Every existing pathing GameTest either checks that the flow field/region graph *computes* the right instruction (`PathingRegionGameTests`), or drives exactly ONE action type in isolation (`PathingGoalRecalculationGameTests` covers `BUILD_LADDER` via `DeployClimbableGoal`, `BUILD_STAIR` via `WidenStairsGoal` and a multi-step `BuildFlowFieldGoal` chain). Nothing exercises `MINE`, `BUILD_BRIDGE`, `BUILD_PILLAR`, `BUILD_LANDING`, or `BUILD_SPIRAL` end-to-end, and nothing proves a single rat can successfully execute every action type the flow field might hand it in one connected scenario.

- [ ] **Step 1: Write the GameTest file**

Create `src/main/java/org/ratden/skavenblight/gametest/SiegeConstructionActionsGameTests.java` with exactly this content:

```java
package org.ratden.skavenblight.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.ai.goal.clanrat.AbstractSiegeConstructionGoal;
import org.ratden.skavenblight.ai.goal.clanrat.BuildFlowFieldGoal;
import org.ratden.skavenblight.ai.goal.clanrat.SmartBreachGoal;
import org.ratden.skavenblight.ai.pathing.*;
import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;
import org.ratden.skavenblight.ai.pathing.region.TerritoryRegionMap;
import org.ratden.skavenblight.entity.ModEntities;
import org.ratden.skavenblight.entity.custom.ClanratEntity;

import java.util.Map;
import java.util.Set;

import static org.ratden.skavenblight.gametest.PathingRegionGameTests.check;

/**
 * End-to-end coverage proving a single clanrat can actually EXECUTE every siege construction
 * action the flow field can hand it - not just that the flow field computes one, which is all
 * the region/macro-project tests elsewhere in this package check. Reported production bug: rats
 * pile up under a build site and never place anything. This test drives one ClanratEntity
 * through all 7 non-trivial SiegeNode.SiegeAction values (MINE, BUILD_STAIR, BUILD_BRIDGE,
 * BUILD_PILLAR, BUILD_LANDING, BUILD_LADDER, BUILD_SPIRAL - WALK/LEAP are pure movement, see
 * SiegeInteractionHandler.constructSiegeBlock's early return for both) via the same
 * SmartBreachGoal/BuildFlowFieldGoal + SiegeInteractionHandler path production code uses, laid
 * out as 7 independent lanes along the same Z row so each action's terrain setup can't interfere
 * with its neighbors (see runBuildLane's own comments for the exact spacing reasoning).
 *
 * <p>Uses the same hand-built-pathing-objects pattern established in
 * {@code PathingGoalRecalculationGameTests}: a real {@code FlowFieldState} with its instruction
 * map set directly via {@code updateInstructions}, bypassing {@code FlowFieldCalculator} entirely
 * (its correctness is covered elsewhere), one {@code RegionFlowField} reused across all 7 lanes.
 */
@GameTestHolder(Skavenblight.MODID)
@PrefixGameTestTemplate(false)
public class SiegeConstructionActionsGameTests {

    @GameTest(template = "pathing_test", timeoutTicks = 200, skyAccess = true)
    public static void testClanratExecutesEverySiegeConstructionAction(GameTestHelper helper) {
        TerrainEvaluator evaluator = new TerrainEvaluator();
        SiegeProjectManager projectManager = new SiegeProjectManager(evaluator);
        CalculationThrottler throttler = new CalculationThrottler();
        FlowFieldCalculator calculator = new FlowFieldCalculator(evaluator, projectManager, throttler);
        TerritoryRegionMap owner = new TerritoryRegionMap();

        // helper-Y=2 is the template's first open/walkable layer above its solid floor
        // (helper-Y=1) - see PathingRegionGameTests' class javadoc for the +1 helper-Y offset.
        // 7 lanes along the same Z=4 row, X anchors spaced 4 apart (2, 6, 10, 14, 18, 22, 26):
        // wide enough that BUILD_LANDING's 3x3 platform (the widest side-effect of any single
        // action) never reaches a neighboring lane's own target - see runBuildLane's own comment.
        BlockPos relativeAnchor = new BlockPos(4, 2, 4);
        FlowFieldState state = new FlowFieldState(helper.absolutePos(relativeAnchor), Set.of());
        RegionFlowField flowField = new RegionFlowField(owner, 0, state, projectManager, calculator, throttler);

        ClanratEntity mob = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
        helper.getLevel().addFreshEntity(mob);

        runBuildLane(helper, mob, flowField, state, 2, SiegeNode.SiegeAction.MINE);
        runBuildLane(helper, mob, flowField, state, 6, SiegeNode.SiegeAction.BUILD_STAIR);
        runBuildLane(helper, mob, flowField, state, 10, SiegeNode.SiegeAction.BUILD_BRIDGE);
        runBuildLane(helper, mob, flowField, state, 14, SiegeNode.SiegeAction.BUILD_PILLAR);
        runBuildLane(helper, mob, flowField, state, 18, SiegeNode.SiegeAction.BUILD_LANDING);
        runBuildLane(helper, mob, flowField, state, 22, SiegeNode.SiegeAction.BUILD_LADDER);
        runBuildLane(helper, mob, flowField, state, 26, SiegeNode.SiegeAction.BUILD_SPIRAL);

        helper.succeed();
    }

    /**
     * Terrain setup + goal drive + assertion for one lane, dispatched by action type. Lanes are
     * spaced 4 blocks apart on X, each target 1 block east of its own mob anchor - BUILD_LANDING
     * (the widest side-effect: a 3x3 platform centered on target.below(), plus headroom one
     * block on every side) reaches at most target X +/- 1, e.g. lane X=18's target at X=19
     * spans platform X=18..20 - 2 clear blocks from lane X=14's target (X=15) and lane X=22's mob
     * anchor (X=22), so no lane's setup or execution can touch a neighbor's.
     */
    private static void runBuildLane(GameTestHelper helper, ClanratEntity mob, RegionFlowField flowField,
                                      FlowFieldState state, int mobX, SiegeNode.SiegeAction action) {
        BlockPos relativeMobPos = new BlockPos(mobX, 2, 4);
        BlockPos relativeTargetPos = relativeMobPos.relative(Direction.EAST);

        switch (action) {
            case MINE ->
                // Solid obstruction to breach - SmartBreachGoal.findTarget requires the target
                // NOT be air.
                helper.setBlock(relativeTargetPos, Blocks.STONE.defaultBlockState());
            case BUILD_BRIDGE ->
                // Genuine gap: no support below the target, matching what BUILD_BRIDGE represents
                // (not required for execution - BUILD_BRIDGE isn't climb-dependent, see
                // SiegeNode.SiegeAction#isClimbDependent - but keeps the scenario meaningful).
                helper.setBlock(relativeTargetPos.below(), Blocks.AIR.defaultBlockState());
            case BUILD_LANDING -> {
                // Platform loop (SiegeInteractionHandler#constructSiegeBlock's BUILD_LANDING case)
                // only replaces a platform cell that's canBeReplaced() - the default solid floor
                // isn't, so clear the center cell to actually see it get filled with cobblestone.
                helper.setBlock(relativeTargetPos.below(), Blocks.AIR.defaultBlockState());
                // Obstruction directly above the target so the headroom-clear half of BUILD_LANDING
                // has something to destroy.
                helper.setBlock(relativeTargetPos.above(), Blocks.STONE.defaultBlockState());
            }
            case BUILD_LADDER ->
                // Solid wall so constructSiegeBlock's BUILD_LADDER branch finds a wallDirection and
                // places an actual ladder instead of falling back to a plain block.
                helper.setBlock(relativeTargetPos.relative(Direction.NORTH), Blocks.STONE.defaultBlockState());
            default -> { /* BUILD_STAIR, BUILD_PILLAR, BUILD_SPIRAL: default solid-floor support, open target - no setup needed. */ }
        }

        BlockPos mobPos = helper.absolutePos(relativeMobPos);
        BlockPos targetPos = helper.absolutePos(relativeTargetPos);
        mob.setPos(mobPos.getX() + 0.5, mobPos.getY(), mobPos.getZ() + 0.5);

        state.updateInstructions(Map.of(mobPos, new SiegeNode(targetPos, action)));

        AbstractSiegeConstructionGoal goal = (action == SiegeNode.SiegeAction.MINE)
                ? new SmartBreachGoal(mob)
                : new BuildFlowFieldGoal(mob);
        goal.setFlowField(flowField);

        check(goal.canUse(), action + " lane: goal should trigger for target " + targetPos.toShortString());
        goal.start();
        // Tick EXACTLY the goal's own action duration, not duration-plus-margin: actionTicks is
        // only reset by start(), so any tick() call past the duration re-enters the
        // requiresClearSpace/execute branch and fires execute() a second time - harmless for some
        // actions but not something to rely on. SmartBreachGoal's duration is 20 ticks,
        // BuildFlowFieldGoal's is 15 - matching the exact-duration convention already established
        // by this package's own multi-step BUILD_STAIR chain test.
        int actionDurationTicks = (action == SiegeNode.SiegeAction.MINE) ? 20 : 15;
        for (int i = 0; i < actionDurationTicks; i++) goal.tick();

        switch (action) {
            case MINE -> helper.assertBlockState(relativeTargetPos, s -> s.isAir(),
                    () -> "MINE should have destroyed the stone obstruction at " + relativeTargetPos);
            case BUILD_STAIR, BUILD_SPIRAL -> helper.assertBlockState(relativeTargetPos, s -> s.is(Blocks.COBBLESTONE_STAIRS),
                    () -> action + " should have placed cobblestone stairs at " + relativeTargetPos);
            case BUILD_BRIDGE, BUILD_PILLAR -> helper.assertBlockState(relativeTargetPos, s -> s.is(Blocks.COBBLESTONE),
                    () -> action + " should have placed cobblestone at " + relativeTargetPos);
            case BUILD_LADDER -> helper.assertBlockState(relativeTargetPos, s -> s.is(Blocks.LADDER),
                    () -> "BUILD_LADDER should have placed a ladder at " + relativeTargetPos + " against its north wall");
            case BUILD_LANDING -> {
                helper.assertBlockState(relativeTargetPos.below(), s -> s.is(Blocks.COBBLESTONE),
                        () -> "BUILD_LANDING should have filled its platform center at " + relativeTargetPos.below());
                helper.assertBlockState(relativeTargetPos.above(), s -> s.isAir(),
                        () -> "BUILD_LANDING should have cleared the headroom obstruction above " + relativeTargetPos);
            }
            default -> throw new IllegalArgumentException("no assertion wired for " + action);
        }
    }
}
```

- [ ] **Step 2: Compile check**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL` (pre-existing `EventBusSubscriber.Bus()` deprecation warnings are unrelated and fine).

- [ ] **Step 3: Run the GameTest**

Run: `./gradlew runGameTestServer`

This launches a headless `GameTestServer`, runs every registered `skavenblight`-namespace GameTest (all of them, not just the new one — there is no per-test filter flag wired into this project's `build.gradle`), and exits.

Expected: console output includes a passing result line for
`org.ratden.skavenblight.gametest.SiegeConstructionActionsGameTests.testClanratExecutesEverySiegeConstructionAction`
(vanilla's GameTest framework logs `[GameTest] ... passed!` per test, or reports the first failing
`GameTestAssertException`/message from `check(...)`/`assertBlockState(...)` if a lane's setup or
assertion is wrong). Confirm no OTHER previously-passing test in the suite starts failing —
this test only adds a new entity/structure setup and doesn't touch shared mutable state any other
test depends on, so a regression elsewhere would indicate a mistake in this file (e.g. a lane
whose spacing assumption was wrong after all), not a coincidence.

If a specific lane's assertion fails, the `check(...)`/`assertBlockState` message names the exact
action and position — re-examine that lane's terrain setup in Step 1 against
`SiegeInteractionHandler.constructSiegeBlock`'s exact per-action behavior
(`src/main/java/org/ratden/skavenblight/ai/pathing/SiegeInteractionHandler.java`) rather than
guessing.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/ratden/skavenblight/gametest/SiegeConstructionActionsGameTests.java
git commit -m "test(pathing): GameTest a clanrat executing every siege construction action

Reported production bug: rats pile up under a build site and never
place anything. Existing pathing GameTests check that the flow
field/region graph COMPUTES the right instruction, or drive exactly one
action type in isolation (BUILD_LADDER via DeployClimbableGoal,
BUILD_STAIR via WidenStairsGoal/a multi-step BuildFlowFieldGoal chain).

Nothing exercised MINE, BUILD_BRIDGE, BUILD_PILLAR, BUILD_LANDING, or
BUILD_SPIRAL end-to-end, and nothing proved a single rat can
successfully EXECUTE every action type in one connected scenario. This
drives one ClanratEntity through all 7 non-trivial SiegeNode.SiegeAction
values as 7 independent lanes (spaced so BUILD_LANDING's 3x3 platform -
the widest side-effect of any single action - can never reach a
neighboring lane), via the real SmartBreachGoal/BuildFlowFieldGoal +
SiegeInteractionHandler path production code uses."
```

---

## Manual Validation (not part of task completion, informational only)

- If a future change to `SiegeInteractionHandler.constructSiegeBlock`'s per-action block-placement
  logic is made, re-run `./gradlew runGameTestServer` and confirm this test still passes - it's
  the one place in the codebase that pins down the exact resulting `BlockState` for all 7 actions
  in one file.
- `WALK` and `LEAP` are deliberately out of scope: `SiegeInteractionHandler.constructSiegeBlock`
  returns immediately for both (pure movement, no world change to assert against) - see that
  method's first `if` check.
