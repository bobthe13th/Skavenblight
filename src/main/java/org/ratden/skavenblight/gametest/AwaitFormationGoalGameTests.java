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
import org.ratden.skavenblight.ai.goal.clanrat.WidenStairsGoal;
import org.ratden.skavenblight.ai.pathing.*;
import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;
import org.ratden.skavenblight.entity.ModEntities;
import org.ratden.skavenblight.entity.custom.ClanratEntity;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.ratden.skavenblight.gametest.PathingRegionGameTests.check;

/**
 * Coverage for the formation-waiting feature (see
 * docs/superpowers/plans/2026-07-30-formation-waiting-goal.md): when a rat's nearest
 * construction work is already claimed by someone else, it should redirect to any other
 * unclaimed climb-type work in its region, or - if nothing else is available - wait at a
 * real, terrain-validated slot instead of piling into a crowd.
 */
@GameTestHolder(Skavenblight.MODID)
@PrefixGameTestTemplate(false)
public class AwaitFormationGoalGameTests {

    /** Hand-built flow field with no owning TerritoryRegionMap - matches the pattern already established in PathingGoalRecalculationGameTests. */
    private static RegionFlowField buildFlowField(BlockPos anchorPos) {
        FlowFieldState state = new FlowFieldState(anchorPos, Set.of(new ChunkPos(anchorPos)));
        state.updateInstructions(Map.of());
        TerrainEvaluator evaluator = new TerrainEvaluator();
        SiegeProjectManager projectManager = new SiegeProjectManager(evaluator);
        CalculationThrottler throttler = new CalculationThrottler();
        FlowFieldCalculator calculator = new FlowFieldCalculator(evaluator, projectManager, throttler);
        return new RegionFlowField(null, 0, state, projectManager, calculator, throttler);
    }

    @GameTest(template = "pathing_test", timeoutTicks = 100, skyAccess = true)
    public static void testPeekClaimedTargetEmptyWhenUnclaimed(GameTestHelper helper) {
        BlockPos relativeStairPos = new BlockPos(4, 2, 4);
        BlockPos relativeMobPos = relativeStairPos.relative(Direction.EAST);
        helper.setBlock(relativeStairPos, Blocks.COBBLESTONE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.NORTH));

        BlockPos mobPos = helper.absolutePos(relativeMobPos);
        RegionFlowField flowField = buildFlowField(mobPos);

        ClanratEntity mob = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
        mob.setPos(mobPos.getX() + 0.5, mobPos.getY(), mobPos.getZ() + 0.5);
        helper.getLevel().addFreshEntity(mob);

        WidenStairsGoal goal = new WidenStairsGoal(mob);
        goal.setFlowField(flowField);

        check(goal.peekClaimedTarget().isEmpty(),
                "an unclaimed target should not be reported as claimed - a goal would just claim it normally");

        helper.succeed();
    }

    @GameTest(template = "pathing_test", timeoutTicks = 100, skyAccess = true)
    public static void testPeekClaimedTargetReturnsPositionWhenClaimedByAnotherMob(GameTestHelper helper) {
        BlockPos relativeStairPos = new BlockPos(4, 2, 4);
        BlockPos relativeMobPos = relativeStairPos.relative(Direction.EAST);
        helper.setBlock(relativeStairPos, Blocks.COBBLESTONE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.NORTH));

        BlockPos mobPos = helper.absolutePos(relativeMobPos);
        RegionFlowField flowField = buildFlowField(mobPos);

        ClanratEntity mob = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
        mob.setPos(mobPos.getX() + 0.5, mobPos.getY(), mobPos.getZ() + 0.5);
        helper.getLevel().addFreshEntity(mob);

        ClanratEntity otherMob = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
        otherMob.setPos(mobPos.getX() + 5.5, mobPos.getY(), mobPos.getZ() + 5.5);
        helper.getLevel().addFreshEntity(otherMob);
        flowField.tryClaimTarget(mobPos, otherMob);

        WidenStairsGoal goal = new WidenStairsGoal(mob);
        goal.setFlowField(flowField);

        Optional<BlockPos> claimed = goal.peekClaimedTarget();
        check(claimed.isPresent() && claimed.get().equals(mobPos),
                "target claimed by a different, living mob should be reported - found: " + claimed);

        helper.succeed();
    }
}
