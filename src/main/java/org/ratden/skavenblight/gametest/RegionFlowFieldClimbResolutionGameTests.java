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

import java.util.Map;
import java.util.Set;

import static org.ratden.skavenblight.gametest.PathingRegionGameTests.check;

/**
 * Regression coverage for {@code RegionFlowField#getNextSiegeNode}'s post-completion
 * resolution: once a climb action's target block exists, does the mob get pointed at the right
 * cell to actually stand on?
 *
 * <p>{@code BUILD_STAIR} and {@code BUILD_SPIRAL} both place a half-height
 * {@code cobblestone_stairs} block AT the node's own position - the mob's foot cell coincides
 * with the placed block's cell, so completion should resolve to {@code node.pos()} itself (see
 * {@code RegionFlowField}'s own doc comment on {@code CLIMB_TO_ABOVE_ONCE_BUILT}, which already
 * documents this exact reasoning - and an already-fixed past bug - for {@code BUILD_STAIR}).
 * {@code BUILD_PILLAR}/{@code BUILD_LADDER} place a full block the mob stands ON TOP of, so
 * those correctly resolve to {@code node.pos().above()}. {@code BUILD_SPIRAL} was left grouped
 * with the pillar/ladder case despite sharing {@code BUILD_STAIR}'s own placement geometry,
 * which strands a mob one block above the spiral step it just built.
 */
@GameTestHolder(Skavenblight.MODID)
@PrefixGameTestTemplate(false)
public class RegionFlowFieldClimbResolutionGameTests {

    @GameTest(template = "pathing_test", timeoutTicks = 100, skyAccess = true)
    public static void testCompletedSpiralStepResolvesToOwnPosition(GameTestHelper helper) {
        BlockPos relativeAnchor = new BlockPos(4, 2, 4);
        BlockPos relativeTarget = relativeAnchor.relative(Direction.EAST);
        BlockPos anchor = helper.absolutePos(relativeAnchor);
        BlockPos target = helper.absolutePos(relativeTarget);

        // Simulate a spiral step already built - SiegeInteractionHandler's BUILD_SPIRAL case
        // places COBBLESTONE_STAIRS at the node's own position, same as BUILD_STAIR.
        helper.setBlock(relativeTarget, Blocks.COBBLESTONE_STAIRS.defaultBlockState());

        FlowFieldState state = new FlowFieldState(anchor, Set.of());
        state.updateInstructions(Map.of(anchor, new SiegeNode(target, SiegeNode.SiegeAction.BUILD_SPIRAL)));

        TerrainEvaluator evaluator = new TerrainEvaluator();
        SiegeProjectManager projectManager = new SiegeProjectManager(evaluator);
        CalculationThrottler throttler = new CalculationThrottler();
        FlowFieldCalculator calculator = new FlowFieldCalculator(evaluator, projectManager, throttler);
        TerritoryRegionMap owner = new TerritoryRegionMap();
        RegionFlowField flowField = new RegionFlowField(owner, 0, state, projectManager, calculator, throttler);

        SiegeNode resolved = flowField.getNextSiegeNode(helper.getLevel(), anchor);

        check(resolved.action() == SiegeNode.SiegeAction.WALK,
                "a completed BUILD_SPIRAL step should resolve to WALK, was " + resolved.action());
        check(resolved.pos().equals(target),
                "completed BUILD_SPIRAL at " + target.toShortString() + " should resolve to its own position "
                        + "(the placed stair block doubles as the standing cell) - got "
                        + resolved.pos().toShortString() + " instead, stranding the mob one block above the stair it just built");

        helper.succeed();
    }
}
