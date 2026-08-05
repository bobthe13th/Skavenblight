package org.ratden.skavenblight.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.ai.pathing.*;
import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;
import org.ratden.skavenblight.ai.pathing.region.TerritoryRegionMap;
import org.ratden.skavenblight.entity.ModEntities;
import org.ratden.skavenblight.entity.custom.ClanratEntity;

import java.util.Map;
import java.util.Set;

import static org.ratden.skavenblight.gametest.PathingRegionGameTests.check;

/**
 * Regression coverage for {@code FollowFlowFieldGoal}'s single-block climb execution: given a
 * WALK instruction whose next hop is one block up-and-across, and the target cell is already a
 * real, completed {@code COBBLESTONE_STAIRS} block (not a gap still needing to be built), does the
 * mob actually cross it via ordinary movement?
 *
 * <p><b>This does NOT currently reproduce the {@code StaircaseSiegeGroupGameTests} failures</b> -
 * in this minimal, single-goal setup (no competing construction/formation goals, no periodic
 * territory re-check, nothing preempting {@code FollowFlowFieldGoal} mid-climb), the existing
 * ballistic-jump climb mechanism (a {@code JumpControl.jump()} + {@code MoveControl.setWantedPosition}
 * impulse) succeeds and this test passes even without any fix. A same-session attempt to replace
 * that mechanism with plain {@code Navigation.moveTo} was reverted after full-suite verification
 * showed vanilla pathfinding genuinely cannot generate a path across this diagonal ascend at all
 * (confirmed via {@code navIsInProgress=false navIsDone=true} even with the real stair already
 * placed) - not a bug, a structural limitation of {@code WalkNodeEvaluator} for diagonal+vertical
 * moves, which is exactly why the ballistic-jump mechanism exists. The real failure only
 * reproduces in the full multi-rat, multi-goal {@code StaircaseSiegeGroupGameTests} scenario;
 * see docs/superpowers/plans/2026-08-04-staircase-siege-zero-stairs-investigation-plan.md for the
 * open question this test doesn't yet answer (what's different about that scenario - a leading
 * hypothesis being frequent goal preemption resetting {@code pathingUpdateTimer} mid-climb, not
 * yet verified). Kept anyway as a real, narrower regression guard: it protects the "climbs a
 * single already-built diagonal step when nothing else is competing for the goal" case, which is
 * a genuinely different (and passing) claim from "climbs it under real multi-goal contention."
 */
@GameTestHolder(Skavenblight.MODID)
@PrefixGameTestTemplate(false)
public class FollowFlowFieldGoalClimbGameTests {

    @GameTest(template = "pathing_test", timeoutTicks = 200, skyAccess = true)
    public static void testRatCrossesAnAlreadyBuiltDiagonalStairStep(GameTestHelper helper) {
        BlockPos relativeMobPos = new BlockPos(4, 2, 4);
        BlockPos relativeStairPos = relativeMobPos.relative(Direction.EAST).above();

        helper.setBlock(relativeStairPos, Blocks.COBBLESTONE_STAIRS.defaultBlockState());

        BlockPos mobPos = helper.absolutePos(relativeMobPos);
        BlockPos stairPos = helper.absolutePos(relativeStairPos);

        TerrainEvaluator evaluator = new TerrainEvaluator();
        SiegeProjectManager projectManager = new SiegeProjectManager(evaluator);
        CalculationThrottler throttler = new CalculationThrottler();
        FlowFieldCalculator calculator = new FlowFieldCalculator(evaluator, projectManager, throttler);
        TerritoryRegionMap owner = new TerritoryRegionMap();

        FlowFieldState state = new FlowFieldState(stairPos, Set.of());
        state.updateInstructions(Map.of(mobPos, new SiegeNode(stairPos, SiegeNode.SiegeAction.WALK)));
        RegionFlowField flowField = new RegionFlowField(owner, 0, state, projectManager, calculator, throttler);

        ClanratEntity mob = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
        mob.setPos(mobPos.getX() + 0.5, mobPos.getY(), mobPos.getZ() + 0.5);
        helper.getLevel().addFreshEntity(mob);
        mob.assignFlowField(flowField);

        helper.succeedWhen(() -> check(mob.blockPosition().getY() >= stairPos.getY(),
                "rat should have crossed onto the stair at " + stairPos.toShortString()
                        + " but is still at " + mob.blockPosition().toShortString()
                        + " - the climb mechanism never made progress even though the stair "
                        + "already exists"));
    }
}
