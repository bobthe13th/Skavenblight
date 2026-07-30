package org.ratden.skavenblight.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.ai.goal.clanrat.DeployClimbableGoal;
import org.ratden.skavenblight.ai.goal.clanrat.WidenStairsGoal;
import org.ratden.skavenblight.ai.pathing.*;
import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;
import org.ratden.skavenblight.ai.pathing.region.TerritoryRegionMap;
import org.ratden.skavenblight.entity.ModEntities;
import org.ratden.skavenblight.entity.custom.ClanratEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import static org.ratden.skavenblight.gametest.PathingRegionGameTests.check;

/**
 * Regression coverage for Task 3 of the region-pathing-hardening plan: {@code WidenStairsGoal},
 * {@code SmartBreachGoal} (both via {@code AbstractSiegeConstructionGoal}'s {@code onChainComplete}
 * default) and {@code DeployClimbableGoal} must call {@code RegionFlowField#forceRecalculation}
 * after mutating the world, or the region they just changed can never be told about its own
 * construction (the same bug the branch's history already fixed once for {@code BuildFlowFieldGoal}).
 *
 * <p><b>Deviation from the task-3 brief's proposed assertion (see
 * docs/pathing/region-pathing-hardening-findings.md's Finding C, "Task 3", for the full
 * writeup):</b> the brief's original {@code succeedWhen} checked
 * {@code owner.getRegionIndex().regionIdAt(...) != null || owner.getGeneration() > 0} after driving
 * {@code TerritoryRegionMap#tick}. That can never pass here: a freshly-constructed
 * {@code TerritoryRegionMap} (no {@code rebuild()} ever called - these tests build their
 * {@code RegionFlowField} by hand, the same way {@code PathingRegionGameTests} does) starts with an
 * EMPTY {@code RegionIndex}. {@code TerritoryRegionMap#tick}'s dirty-marking loop only adds a region
 * to {@code dirtyRegionIds} when {@code regionIndex.regionIdAt(candidate)} already resolves to a
 * real region - which can never happen against an empty index, regardless of whether
 * {@code forceRecalculation}/{@code onBlockChanged} was ever called. So {@code recomputeDirtyRegions}
 * (the only path to bumping {@code generation} outside an explicit {@code rebuild()}) is unreachable,
 * and the brief's {@code succeedWhen} times out in BOTH the broken and fixed states - it can't
 * discriminate the bug at all. (Calling {@code rebuild()} first doesn't fix this either: that bumps
 * {@code generation} on its own, independent of the goal ever running, making the assertion
 * trivially true instead of trivially false.)
 *
 * <p>Instead, these tests observe {@code onBlockChanged} directly via a recording subclass -
 * {@code TerritoryRegionMap} is a plain, non-final class with a public, non-final
 * {@code onBlockChanged(BlockPos)} - which sidesteps every one of {@code TerritoryRegionMap}'s
 * unrelated gating mechanisms (settle delay, recalculation cooldown, the topology-unchanged fast
 * path that doesn't bump {@code generation}) and asserts the one thing the bug is actually about:
 * did {@code forceRecalculation} get called, and with the right position.
 */
@GameTestHolder(Skavenblight.MODID)
@PrefixGameTestTemplate(false)
public class PathingGoalRecalculationGameTests {

    /** See the class javadoc: records every {@code onBlockChanged} call instead of relying on it visibly propagating through region/generation state. */
    static final class RecordingRegionMap extends TerritoryRegionMap {
        final List<BlockPos> changes = new ArrayList<>();

        @Override
        public void onBlockChanged(BlockPos pos) {
            this.changes.add(pos.immutable());
            super.onBlockChanged(pos);
        }
    }

    @GameTest(template = "pathing_test", timeoutTicks = 200, skyAccess = true)
    public static void testDeployClimbableGoalMarksRegionDirty(GameTestHelper helper) {
        // helper-Y=1 is the template's solid floor, helper-Y=2 is the first open/walkable layer
        // above it (see PathingRegionGameTests' class javadoc) - the mob must stand at Y=2, not
        // Y=1, or its feet land inside the solid floor block instead of on top of it.
        BlockPos relativeMobPos = new BlockPos(4, 2, 4);
        BlockPos relativeWallPos = relativeMobPos.relative(Direction.NORTH);
        helper.setBlock(relativeWallPos, Blocks.STONE.defaultBlockState());
        // 2 blocks of open headroom above the mob so checkOverhang() skips straight to
        // PLACING_LADDER instead of MINING_OVERHANG.
        helper.setBlock(relativeMobPos.above(), Blocks.AIR.defaultBlockState());
        helper.setBlock(relativeMobPos.above(2), Blocks.AIR.defaultBlockState());

        BlockPos mobPos = helper.absolutePos(relativeMobPos);
        BlockPos wallPos = helper.absolutePos(relativeWallPos);
        // DeployClimbableGoal places the ladder at targetWallPos.relative(wallFacing), and
        // wallFacing is the direction FROM the mob TO the wall, reversed - i.e. back at the mob's
        // own standing position. isSpaceClear() deliberately excludes the builder (see this mod's
        // CLAUDE.md), so placing into the mob's own cell is expected to succeed.
        BlockPos placePos = mobPos;

        RecordingRegionMap owner = new RecordingRegionMap();
        FlowFieldState state = new FlowFieldState(mobPos, Set.of(new ChunkPos(mobPos)));
        state.updateInstructions(Map.of(mobPos, new SiegeNode(mobPos, SiegeNode.SiegeAction.BUILD_LADDER)));
        TerrainEvaluator evaluator = new TerrainEvaluator();
        SiegeProjectManager projectManager = new SiegeProjectManager(evaluator);
        CalculationThrottler throttler = new CalculationThrottler();
        FlowFieldCalculator calculator = new FlowFieldCalculator(evaluator, projectManager, throttler);
        RegionFlowField flowField = new RegionFlowField(owner, 0, state, projectManager, calculator, throttler);

        ClanratEntity mob = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
        mob.setPos(mobPos.getX() + 0.5, mobPos.getY(), mobPos.getZ() + 0.5);
        helper.getLevel().addFreshEntity(mob);

        DeployClimbableGoal goal = new DeployClimbableGoal(mob);
        goal.setFlowField(flowField);

        check(goal.canUse(), "goal should trigger: BUILD_LADDER node + solid wall to the north");
        goal.start();
        for (int i = 0; i < 12; i++) goal.tick();

        helper.assertBlockState(relativeWallPos.relative(Direction.SOUTH),
                s -> s.is(Blocks.LADDER), () -> "ladder should have been placed");

        check(owner.changes.contains(placePos),
                "region map was never told about the ladder placement at " + placePos
                        + " - forceRecalculation was never called (recorded changes: " + owner.changes + ")");

        helper.succeed();
    }

    @GameTest(template = "pathing_test", timeoutTicks = 200, skyAccess = true)
    public static void testWidenStairsGoalMarksRegionDirty(GameTestHelper helper) {
        // Same Y correction as above: the walkable layer (and therefore the existing stair the
        // mob widens alongside, and the mob's own standing cell) is helper-Y=2, not Y=1.
        BlockPos relativeStairPos = new BlockPos(4, 2, 4);
        BlockPos relativeMobPos = relativeStairPos.relative(Direction.EAST);
        helper.setBlock(relativeStairPos, Blocks.COBBLESTONE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.NORTH));

        BlockPos mobPos = helper.absolutePos(relativeMobPos);

        RecordingRegionMap owner = new RecordingRegionMap();
        // Empty instruction map: currentPos must NOT be on an active path for the reactive
        // branch to trigger (see WidenStairsGoal.findTarget()).
        FlowFieldState state = new FlowFieldState(mobPos, Set.of(new ChunkPos(mobPos)));
        state.updateInstructions(Map.of());
        TerrainEvaluator evaluator = new TerrainEvaluator();
        SiegeProjectManager projectManager = new SiegeProjectManager(evaluator);
        CalculationThrottler throttler = new CalculationThrottler();
        FlowFieldCalculator calculator = new FlowFieldCalculator(evaluator, projectManager, throttler);
        RegionFlowField flowField = new RegionFlowField(owner, 0, state, projectManager, calculator, throttler);

        ClanratEntity mob = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
        mob.setPos(mobPos.getX() + 0.5, mobPos.getY(), mobPos.getZ() + 0.5);
        helper.getLevel().addFreshEntity(mob);

        WidenStairsGoal goal = new WidenStairsGoal(mob);
        goal.setFlowField(flowField);

        check(goal.canUse(), "goal should trigger: off-path, replaceable, adjacent to an existing stair");
        goal.start();
        for (int i = 0; i < 16; i++) goal.tick();

        helper.assertBlockState(relativeMobPos, s -> s.is(Blocks.COBBLESTONE_STAIRS),
                () -> "widened stair should have been placed at the mob's own cell");

        check(!owner.changes.isEmpty(),
                "region map was never told about the widened stair - onChainComplete's default never fired");

        helper.succeed();
    }

    /**
     * Task 7 (region-pathing-hardening): mechanical proof that
     * {@code SiegeProjectManager.setMaxCandidateProjectLength} actually bounds how far a candidate
     * line traced by {@code evaluateMacroProjects}/{@code SiegeLineTracer.trace} can reach - see
     * docs/pathing/region-pathing-hardening-findings.md's Finding C ("Task 7") for why this can't
     * be shown through any {@code TerritoryRegionMap}/region-graph
     * scenario: every region-scoped {@code FlowFieldState} is built with a {@code cellFilter}
     * ({@code region::contains}), and {@code SiegeLineTracer.trace} checks that filter on EVERY step,
     * not just the endpoint, so a candidate line can never reach further than whatever
     * {@code RegionScanner} already put in the SAME region - which is itself capped at ~6 by the
     * pre-existing {@code MAX_CONSECUTIVE_MINE_DEPTH}/{@code MAX_CONSECUTIVE_MINE} = 5. That makes
     * the new cap of 6 a no-op against an already-lower ceiling in every region-graph GameTest, even
     * though the parameter itself works correctly.
     *
     * <p>This test proves the parameter itself, using the SAME hand-built-pathing-objects pattern
     * this file already established for {@link #testDeployClimbableGoalMarksRegionDirty}: a real
     * {@code FlowFieldState}, but constructed via the 2-arg constructor so {@code cellFilter} is
     * {@code null} and {@code territoryChunks} is empty ("Global scope" per
     * {@code FlowFieldState#isOutOfBounds}) - deliberately bypassing the region-scoping machinery
     * that makes the cap unobservable elsewhere. What's under test here is purely "does
     * {@code SiegeLineTracer.trace}'s line length actually respect the {@code maxLength} argument
     * {@code SiegeProjectManager} now forwards to it," independent of whether any current
     * region-graph scenario can trigger the difference.
     *
     * <p>Geometry: the anchor sits on ordinary open floor (helper-Y=2, template-Y=1 - see
     * {@code PathingRegionGameTests}' class javadoc for the +1 helper-Y offset), untouched. Directly
     * east, the floor (helper-Y=1) is removed for 10 consecutive columns (x+1..x+10) - open air with
     * no support, so {@code determineMacroAction} returns {@code BUILD_BRIDGE} for every one of
     * those positions (see that method's "Horizontal Bridge" branch: {@code dy==0}, some horizontal
     * offset, support not solid). The floor is left intact (untouched) starting at x+11, a REAL
     * walkable landing - far enough out that only an uncapped (default 32) trace can reach it, but
     * well within reach if the cap weren't working at all.
     *
     * <p>Two independent {@code SiegeProjectManager} instances (avoiding any
     * {@code isNearExistingProject} cross-talk) fire {@code evaluateMacroProjects} from the same
     * anchor against the same terrain: one left at the default cap, one set to
     * {@code setMaxCandidateProjectLength(6)}. The default-cap run must reach the real landing at
     * x+11 with a {@code WALK} action (the real ground {@code determineMacroAction} computes once
     * support is solid again). The capped run must NOT reach x+11 at all, and must instead terminate
     * exactly at x+6 with a synthetic {@code BUILD_LANDING} - the same substitution
     * {@code SiegeLineTracer.trace} makes at any {@code maxLength} boundary it hits before finding
     * real ground (mirroring {@code RegionGraph}'s own {@code MAX_CHAIN_HOPS} cap on its own, longer
     * chains). Only the east-bound, same-Y (dy=0) line reaches these exact positions - the other 13
     * directions {@code evaluateMacroProjects} also fires (2 pure-vertical, plus the same east
     * direction's own dy=-1/+1 variants, plus north/south/west/up/down) all move off this Y or X
     * axis and can never collide with the two checked keys.
     */
    @GameTest(template = "pathing_test", timeoutTicks = 200, skyAccess = true)
    public static void testMaxCandidateProjectLengthCapsMacroProjectReach(GameTestHelper helper) {
        BlockPos relativeAnchor = new BlockPos(4, 2, 4);
        BlockPos anchorPos = helper.absolutePos(relativeAnchor);

        // Remove the floor (helper-Y=1) for the 10 columns directly east of the anchor, forcing
        // BUILD_BRIDGE the whole way - see method javadoc. Floor at x+11 onward is left untouched
        // (the default solid stone floor), the real landing.
        for (int i = 1; i <= 10; i++) {
            helper.setBlock(relativeAnchor.offset(i, -1, 0), Blocks.AIR.defaultBlockState());
        }
        BlockPos realLanding = helper.absolutePos(relativeAnchor.offset(11, 0, 0));
        BlockPos cappedLanding = helper.absolutePos(relativeAnchor.offset(6, 0, 0));

        TerrainEvaluator evaluator = new TerrainEvaluator();
        LiveTerrainAccess terrain = new LiveTerrainAccess(helper.getLevel());
        // Unconstrained FlowFieldState (2-arg constructor: no cellFilter, empty territoryChunks =
        // "Global scope") - see method javadoc for why this is deliberate.
        FlowFieldState state = new FlowFieldState(anchorPos, Set.of());

        Map<BlockPos, Integer> uncappedCostMap = new HashMap<>();
        Map<BlockPos, SiegeNode> uncappedInstructionMap = new HashMap<>();
        PriorityQueue<FlowFieldCalculator.QueueNode> uncappedQueue = new PriorityQueue<>();

        SiegeProjectManager uncapped = new SiegeProjectManager(evaluator);
        uncapped.evaluateMacroProjects(terrain, anchorPos, state, 0, uncappedQueue, uncappedCostMap, uncappedInstructionMap);

        check(uncappedInstructionMap.containsKey(realLanding),
                "uncapped (default 32) macro-project search should have reached the real landing 11 blocks east at "
                        + realLanding + " - found instructions at: " + uncappedInstructionMap.keySet());
        check(uncappedInstructionMap.get(realLanding).action() == SiegeNode.SiegeAction.WALK,
                "the far landing should be the REAL walkable ground the trace found (action WALK), not a synthetic "
                        + "one - found: " + uncappedInstructionMap.get(realLanding));

        Map<BlockPos, Integer> cappedCostMap = new HashMap<>();
        Map<BlockPos, SiegeNode> cappedInstructionMap = new HashMap<>();
        PriorityQueue<FlowFieldCalculator.QueueNode> cappedQueue = new PriorityQueue<>();

        // A fresh SiegeProjectManager - setMaxCandidateProjectLength is the one thing under test.
        SiegeProjectManager capped = new SiegeProjectManager(evaluator);
        capped.setMaxCandidateProjectLength(6);
        capped.evaluateMacroProjects(terrain, anchorPos, state, 0, cappedQueue, cappedCostMap, cappedInstructionMap);

        check(!cappedInstructionMap.containsKey(realLanding),
                "setMaxCandidateProjectLength(6) should have stopped the east-bound trace before reaching the real "
                        + "landing 11 blocks east - found instructions at: " + cappedInstructionMap.keySet());
        SiegeNode cappedNode = cappedInstructionMap.get(cappedLanding);
        check(cappedNode != null && cappedNode.action() == SiegeNode.SiegeAction.BUILD_LANDING,
                "setMaxCandidateProjectLength(6) should have terminated the east-bound trace with a synthetic "
                        + "BUILD_LANDING exactly 6 blocks out at " + cappedLanding + " (found: " + cappedNode + ")");

        helper.succeed();
    }
}
