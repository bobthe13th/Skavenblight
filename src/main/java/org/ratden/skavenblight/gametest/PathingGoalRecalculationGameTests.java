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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.ratden.skavenblight.gametest.PathingRegionGameTests.check;

/**
 * Regression coverage for Task 3 of the region-pathing-hardening plan: {@code WidenStairsGoal},
 * {@code SmartBreachGoal} (both via {@code AbstractSiegeConstructionGoal}'s {@code onChainComplete}
 * default) and {@code DeployClimbableGoal} must call {@code RegionFlowField#forceRecalculation}
 * after mutating the world, or the region they just changed can never be told about its own
 * construction (the same bug the branch's history already fixed once for {@code BuildFlowFieldGoal}).
 *
 * <p><b>Deviation from the task-3 brief's proposed assertion (see task-3-report.md for the full
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
}
