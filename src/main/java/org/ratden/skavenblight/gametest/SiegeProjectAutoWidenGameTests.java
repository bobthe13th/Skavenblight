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
import org.ratden.skavenblight.entity.ModEntities;
import org.ratden.skavenblight.entity.custom.ClanratEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.ratden.skavenblight.gametest.PathingRegionGameTests.check;

@GameTestHolder(Skavenblight.MODID)
@PrefixGameTestTemplate(false)
public class SiegeProjectAutoWidenGameTests {

    /** Registers `count` distinct mobs, one at a time, at ever-so-slightly different but all
     * within-radius positions near `target`, to simulate several rats converging simultaneously. */
    private static List<ClanratEntity> registerNRatsNear(GameTestHelper helper, SiegeProject project, BlockPos target,
                                                          TerrainEvaluator evaluator, int count) {
        List<ClanratEntity> rats = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ClanratEntity rat = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
            rat.setPos(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
            helper.getLevel().addFreshEntity(rat);
            rats.add(rat);
            project.tryRegisterWorker(rat, new LiveTerrainAccess(helper.getLevel()), evaluator,
                    org.ratden.skavenblight.Config.projectWorkRadius,
                    org.ratden.skavenblight.Config.maxProjectWorkers, org.ratden.skavenblight.Config.workersPerWidenStep);
        }
        return rats;
    }

    @GameTest(template = "pathing_test", timeoutTicks = 100, skyAccess = true)
    public static void testWidensWhenRegistrationRejectedAtCap(GameTestHelper helper) {
        TerrainEvaluator evaluator = new TerrainEvaluator();
        BlockPos relativeAnchor = new BlockPos(4, 2, 4);
        BlockPos relativeTarget = relativeAnchor.relative(Direction.EAST);
        BlockPos anchor = helper.absolutePos(relativeAnchor);
        BlockPos target = helper.absolutePos(relativeTarget);

        // Flat, open floor on both sides of the lane (Z-1 and Z+1) so a widened lane has
        // somewhere valid to go - default template floor already provides this.
        List<SiegeNode> orderedSteps = List.of(new SiegeNode(target, SiegeNode.SiegeAction.BUILD_STAIR));
        Map<BlockPos, SiegeNode> instructions = Map.of(target, new SiegeNode(anchor, SiegeNode.SiegeAction.BUILD_STAIR));
        SiegeProject project = new SiegeProject(instructions, orderedSteps, anchor, target, 500);

        // Config.workersPerWidenStep default is 10 - register exactly that many first (all should
        // succeed against width=1's cap), then one more, which must trigger a widen.
        List<ClanratEntity> firstBatch = registerNRatsNear(helper, project, target, evaluator,
                org.ratden.skavenblight.Config.workersPerWidenStep);

        ClanratEntity extraRat = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
        extraRat.setPos(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        helper.getLevel().addFreshEntity(extraRat);

        boolean registered = project.tryRegisterWorker(extraRat, new LiveTerrainAccess(helper.getLevel()), evaluator,
                org.ratden.skavenblight.Config.projectWorkRadius,
                org.ratden.skavenblight.Config.maxProjectWorkers, org.ratden.skavenblight.Config.workersPerWidenStep);

        check(registered, "the (workersPerWidenStep + 1)th rat should trigger a widen and then successfully register");
        check(project.getWidth() > 1, "project width should have grown past 1 after the widen");

        helper.succeed();
    }

    @GameTest(template = "pathing_test", timeoutTicks = 100, skyAccess = true)
    public static void testDoesNotWidenWhenActionIsNotLaterallyWidenable(GameTestHelper helper) {
        TerrainEvaluator evaluator = new TerrainEvaluator();
        BlockPos relativeAnchor = new BlockPos(4, 2, 4);
        BlockPos relativeTarget = relativeAnchor.above();
        BlockPos anchor = helper.absolutePos(relativeAnchor);
        BlockPos target = helper.absolutePos(relativeTarget);

        List<SiegeNode> orderedSteps = List.of(new SiegeNode(target, SiegeNode.SiegeAction.BUILD_PILLAR));
        Map<BlockPos, SiegeNode> instructions = Map.of(target, new SiegeNode(anchor, SiegeNode.SiegeAction.BUILD_PILLAR));
        SiegeProject project = new SiegeProject(instructions, orderedSteps, anchor, target, 500);

        registerNRatsNear(helper, project, target, evaluator, org.ratden.skavenblight.Config.maxProjectWorkers);

        ClanratEntity extraRat = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
        extraRat.setPos(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        helper.getLevel().addFreshEntity(extraRat);

        boolean registered = project.tryRegisterWorker(extraRat, new LiveTerrainAccess(helper.getLevel()), evaluator,
                org.ratden.skavenblight.Config.projectWorkRadius,
                org.ratden.skavenblight.Config.maxProjectWorkers, org.ratden.skavenblight.Config.workersPerWidenStep);

        check(!registered, "BUILD_PILLAR is not laterally widenable - an over-cap registration must stay rejected");
        check(project.getWidth() == 1, "width must not have changed for a non-widenable action");

        helper.succeed();
    }

    /** Fix-round-1 regression test: a single widen alone can't distinguish a correct sideways
     * offset from a bug that silently re-derives the trace direction from whatever the previous
     * widen appended (see the task-6 fix report). Triggering a SECOND widen is the smallest
     * repro - under that bug the second widen's direction vector collapses back onto the
     * original lane's own forward direction, so it extends the original single-file line instead
     * of adding a new parallel lane. Asserting on build-order positions directly (rather than on
     * placed blocks via tick()) sidesteps a separate wrinkle: the widened lane's real terrain may
     * classify as WALK rather than BUILD_STAIR, in which case nothing would ever be built there
     * for a block-state assertion to see, even with fully correct geometry. */
    @GameTest(template = "pathing_test", timeoutTicks = 100, skyAccess = true)
    public static void testSuccessiveWidensLandOnDistinctParallelLanes(GameTestHelper helper) {
        TerrainEvaluator evaluator = new TerrainEvaluator();
        BlockPos relativeAnchor = new BlockPos(4, 2, 4);
        BlockPos relativeTarget = relativeAnchor.relative(Direction.EAST);
        BlockPos anchor = helper.absolutePos(relativeAnchor);
        BlockPos target = helper.absolutePos(relativeTarget);

        // Same flat, open floor on both sides (Z-1 and Z+1) as testWidensWhenRegistrationRejectedAtCap
        // - two widens alternate onto exactly those two sides, so both stay within the floor the
        // default template already guarantees.
        List<SiegeNode> orderedSteps = List.of(new SiegeNode(target, SiegeNode.SiegeAction.BUILD_STAIR));
        Map<BlockPos, SiegeNode> instructions = Map.of(target, new SiegeNode(anchor, SiegeNode.SiegeAction.BUILD_STAIR));
        SiegeProject project = new SiegeProject(instructions, orderedSteps, anchor, target, 500);

        // width=1's cap is workersPerWidenStep; registering (workersPerWidenStep*2 + 1) rats
        // walks straight through both widen thresholds: the (workersPerWidenStep+1)th registration
        // fills width=2's cap and triggers the first widen, and the (workersPerWidenStep*2+1)th
        // registration triggers the second.
        registerNRatsNear(helper, project, target, evaluator, 2 * org.ratden.skavenblight.Config.workersPerWidenStep + 1);

        check(project.getWidth() == 3, "two widens should have occurred, taking width from 1 to 3");

        List<BlockPos> positions = project.getBuildOrderPositions();
        check(positions.size() == 3, "original step plus two widened lanes should be exactly 3 build-order entries");

        Set<Integer> distinctZ = positions.stream().map(BlockPos::getZ).collect(Collectors.toSet());
        check(distinctZ.size() == 3,
                "the original lane and both widened lanes must sit at 3 genuinely different Z offsets - "
                        + "under the bug this fix addresses, the second widen re-derives its direction from "
                        + "the first widen's own lane and ends up extending the original lane forward instead, "
                        + "collapsing this to only 2 distinct Z values");

        helper.succeed();
    }
}
