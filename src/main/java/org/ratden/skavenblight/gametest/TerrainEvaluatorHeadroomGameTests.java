package org.ratden.skavenblight.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.ai.pathing.LiveTerrainAccess;
import org.ratden.skavenblight.ai.pathing.SiegeNode;
import org.ratden.skavenblight.ai.pathing.TerrainEvaluator;

import static org.ratden.skavenblight.gametest.PathingRegionGameTests.check;

/**
 * Regression coverage for the "floating second stair, mob stuck at the base" bug reported from
 * {@code testSingleRatBuildsStaircaseAcrossSmallGap}: {@code TerrainEvaluator} treated a
 * {@code StairBlock} as clear headroom via {@code isWalkableScaffold} at HEAD height, not just foot
 * height, in two independently-duplicated places - {@code isFitForWalking} (gating the core
 * WALK-step Dijkstra expansion via {@code isWalkableTerrain}) and {@code determineMacroAction}
 * (gating the diagonal {@code BUILD_STAIR} macro-line trace in {@code SiegeLineTracer}). A stair
 * block occupying the cell above a mob's foot cell still has substantial real collision - it is not
 * clear enough for a mob's head/body to pass through - so both call sites must report that cell as
 * NOT walkable/NOT already-clear, not "fine because it's a scaffold type".
 *
 * <p>Uses the same direct, non-integration style as {@code PathingGoalRecalculationGameTests}
 * ({@code TerrainEvaluator} + {@code LiveTerrainAccess} against a real {@code ServerLevel}, no flow
 * field or entity involved) rather than {@code StaircaseSiegeGroupGameTests}' full end-to-end style,
 * since both bugs live entirely inside {@code TerrainEvaluator}'s own per-cell evaluation and don't
 * need a mob or a network to reproduce.
 */
@GameTestHolder(Skavenblight.MODID)
@PrefixGameTestTemplate(false)
public class TerrainEvaluatorHeadroomGameTests {

    @GameTest(template = "pathing_test", timeoutTicks = 100, skyAccess = true)
    public static void testStairAtHeadHeightIsNotWalkableTerrain(GameTestHelper helper) {
        // helper-Y=1 is the template's solid floor, helper-Y=2 is the first open/walkable layer
        // above it (see PathingRegionGameTests' class javadoc).
        BlockPos relativeFootPos = new BlockPos(4, 2, 4);
        BlockPos relativeHeadPos = relativeFootPos.above();

        helper.setBlock(relativeHeadPos, Blocks.COBBLESTONE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.NORTH));

        TerrainEvaluator evaluator = new TerrainEvaluator();
        LiveTerrainAccess terrain = new LiveTerrainAccess(helper.getLevel());
        BlockPos footPos = helper.absolutePos(relativeFootPos);

        check(!evaluator.isWalkableTerrain(terrain, footPos),
                "a stair occupying the HEAD cell above " + footPos + " still has real collision - "
                        + "it must not be reported as walkable terrain a mob can pass through");

        helper.succeed();
    }

    @GameTest(template = "pathing_test", timeoutTicks = 100, skyAccess = true)
    public static void testStairAtHeadHeightForcesMineInMacroAction(GameTestHelper helper) {
        // Diagonal step: pos is one step further along the crossing than the mob's current
        // position, dy=1/dx=1/dz=0 matches the diagonal shape determineMacroAction requires to
        // ever consider BUILD_STAIR in the first place (see this file's class javadoc / the
        // StaircaseSiegeGroupGameTests class javadoc on why the gap must be diagonal).
        BlockPos relativePos = new BlockPos(4, 3, 4);
        BlockPos relativeHeadPos = relativePos.above();
        // No support below pos - this is a genuine mid-chain macro-project step, same as any
        // not-yet-built staircase step (see SiegeInteractionHandler's own doc on
        // supportSolidAtClaim for why "support is air" is expected and NOT itself the bug).
        helper.setBlock(relativePos.below(), Blocks.AIR.defaultBlockState());

        helper.setBlock(relativeHeadPos, Blocks.COBBLESTONE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.NORTH));

        TerrainEvaluator evaluator = new TerrainEvaluator();
        LiveTerrainAccess terrain = new LiveTerrainAccess(helper.getLevel());
        BlockPos pos = helper.absolutePos(relativePos);

        SiegeNode.SiegeAction action = evaluator.determineMacroAction(terrain, pos, 1, 1, 0, null);

        check(action == SiegeNode.SiegeAction.MINE,
                "a stair occupying the HEAD cell above the traced position " + pos + " still blocks "
                        + "real headroom - determineMacroAction must return MINE, not " + action
                        + " (which would let SiegeLineTracer treat this obstruction as already clear)");

        helper.succeed();
    }
}
