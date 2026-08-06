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
import java.util.UUID;
import java.util.stream.Collectors;

import static org.ratden.skavenblight.gametest.PathingRegionGameTests.check;

@GameTestHolder(Skavenblight.MODID)
@PrefixGameTestTemplate(false)
public class SiegeProjectAutoWidenGameTests {

    /** Registers `count` distinct mobs, one at a time, at ever-so-slightly different but all
     * within-radius positions near `target`, to simulate several rats converging simultaneously. */
    private static List<ClanratEntity> registerNRatsNear(GameTestHelper helper, SiegeProject project, BlockPos target,
                                                          PathStepEvaluator evaluator, int count) {
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

    /**
     * <b>Corrected (2026-08-06, per advisor review): flat, walkable floor at the widen offset makes
     * the widen FAIL, not succeed.</b> This fixture originally relied on "default template floor
     * already provides somewhere valid to go" at the perpendicular offset - but {@code
     * SiegeProject#traceChainedHops} returns an EMPTY list the moment its first hop is already
     * walkable, and {@code tryWiden} treats an empty trace as nothing to widen into and refuses
     * (unit-tested directly in {@code SiegeProjectTest
     * #traceChainedHopsReturnsEmptyWhenTheFirstHopIsAlreadyWalkable}). A widen this test expects to
     * SUCCEED needs a genuine gap at the offset instead - open air with no floor support, which
     * {@code PathStepEvaluator#candidateSteps} classifies as a real BRIDGE candidate.
     */
    @GameTest(template = "pathing_test", timeoutTicks = 100, skyAccess = true)
    public static void testWidensWhenRegistrationRejectedAtCap(GameTestHelper helper) {
        PathStepEvaluator evaluator = new PathStepEvaluator();
        BlockPos relativeAnchor = new BlockPos(4, 2, 4);
        BlockPos relativeTarget = relativeAnchor.relative(Direction.EAST);
        BlockPos anchor = helper.absolutePos(relativeAnchor);
        BlockPos target = helper.absolutePos(relativeTarget);

        // Carve a genuine gap (no floor support, open air) at the first widen's perpendicular
        // offset - see this method's own javadoc. Same perpX/perpZ math as
        // SiegeProjectAutoWidenGameTests#testDoesNotWidenWhenPerpendicularLaneIsAlreadyWalkable:
        // trace dir (dx=1,dz=0) -> perp (0,1) -> side=-1 at width=1 (odd) -> first hop =
        // anchor.offset(1,0,-1).
        BlockPos relativeWidenHop = relativeAnchor.offset(1, 0, -1);
        helper.setBlock(relativeWidenHop.below(), Blocks.AIR.defaultBlockState());
        helper.setBlock(relativeWidenHop, Blocks.AIR.defaultBlockState());
        helper.setBlock(relativeWidenHop.above(), Blocks.AIR.defaultBlockState());

        List<FlowStep> orderedSteps = List.of(new FlowStep(target, PathAction.BRIDGE, anchor));
        Map<BlockPos, FlowStep> instructions = Map.of(target, new FlowStep(anchor, PathAction.BRIDGE, anchor));
        SiegeProject project = new SiegeProject(instructions, orderedSteps, anchor, target, 500, UUID.randomUUID());

        LiveTerrainAccess live = new LiveTerrainAccess(helper.getLevel());
        check(!evaluator.isWalkableTerrain(live, helper.absolutePos(relativeWidenHop)),
                "setup sanity: the widen lane's first hop must NOT be walkable, or this test proves nothing");

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

    /**
     * <b>Re-anchored (2026-08-05, per advisor review): the original premise ("BUILD_PILLAR is not
     * laterally widenable") no longer exists.</b> {@code SiegeProject#effectiveCapFor}'s new rule
     * is {@code action != PathAction.WALK} - EVERY construction action widens now (see that
     * method's own javadoc: "every action is parallel-lane-capable now, not just two of the old
     * five"), and pure-vertical steps like the old BUILD_PILLAR no longer exist as a PathAction at
     * all. The real, still-live "can't widen" path {@code isWidenEligible} still guards is the
     * perpendicular trace itself finding nothing to build: {@code SiegeProject#traceChainedHops}
     * returns an EMPTY list the moment its very first hop is already walkable (unit-tested
     * directly in {@code SiegeProjectTest
     * #traceChainedHopsReturnsEmptyWhenTheFirstHopIsAlreadyWalkable}), and {@code tryWiden} treats
     * an empty trace as "nothing to widen into" and refuses - regardless of action type, cap, or
     * width. Forced here via explicit terrain control (not guessed from the template's ambient
     * geometry) so the test is deterministic.
     */
    @GameTest(template = "pathing_test", timeoutTicks = 100, skyAccess = true)
    public static void testDoesNotWidenWhenPerpendicularLaneIsAlreadyWalkable(GameTestHelper helper) {
        PathStepEvaluator evaluator = new PathStepEvaluator();
        BlockPos relativeAnchor = new BlockPos(4, 2, 4);
        BlockPos relativeTarget = relativeAnchor.relative(Direction.EAST);
        BlockPos anchor = helper.absolutePos(relativeAnchor);
        BlockPos target = helper.absolutePos(relativeTarget);

        // Force the widen lane's very first hop to be genuinely walkable (real solid floor + clear
        // headroom) - see this method's own javadoc. Computed from tryWiden's own perpX/perpZ math
        // for this exact anchor/target: trace dir (dx=1,dz=0) -> perp (0,1) -> side=-1 at width=1
        // (odd) -> newAnchor = anchor.offset(0,0,-1) -> first hop = newAnchor.offset(1,0,0).
        BlockPos relativeWidenHop = relativeAnchor.offset(1, 0, -1);
        helper.setBlock(relativeWidenHop.below(), Blocks.STONE.defaultBlockState());
        helper.setBlock(relativeWidenHop, Blocks.AIR.defaultBlockState());
        helper.setBlock(relativeWidenHop.above(), Blocks.AIR.defaultBlockState());

        List<FlowStep> orderedSteps = List.of(new FlowStep(target, PathAction.BRIDGE, anchor));
        Map<BlockPos, FlowStep> instructions = Map.of(target, new FlowStep(anchor, PathAction.BRIDGE, anchor));
        SiegeProject project = new SiegeProject(instructions, orderedSteps, anchor, target, 500, UUID.randomUUID());

        LiveTerrainAccess live = new LiveTerrainAccess(helper.getLevel());
        // Setup sanity: confirm the widen target really is walkable before asserting on tryWiden's
        // behavior, or a wrong offset computation above would make this test pass for the wrong
        // reason.
        check(evaluator.isWalkableTerrain(live, helper.absolutePos(relativeWidenHop)),
                "setup sanity: the widen lane's first hop must be genuinely walkable, or this test proves nothing");

        registerNRatsNear(helper, project, target, evaluator, org.ratden.skavenblight.Config.workersPerWidenStep);

        ClanratEntity extraRat = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
        extraRat.setPos(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        helper.getLevel().addFreshEntity(extraRat);

        boolean registered = project.tryRegisterWorker(extraRat, live, evaluator,
                org.ratden.skavenblight.Config.projectWorkRadius,
                org.ratden.skavenblight.Config.maxProjectWorkers, org.ratden.skavenblight.Config.workersPerWidenStep);

        check(!registered, "the widen lane is already real, walkable ground - there's nothing to build there, "
                + "so tryWiden must refuse and the over-cap registration must stay rejected");
        check(project.getWidth() == 1, "width must not have changed when there was nothing to widen into");

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
        PathStepEvaluator evaluator = new PathStepEvaluator();
        BlockPos relativeAnchor = new BlockPos(4, 2, 4);
        BlockPos relativeTarget = relativeAnchor.relative(Direction.EAST);
        BlockPos anchor = helper.absolutePos(relativeAnchor);
        BlockPos target = helper.absolutePos(relativeTarget);

        // Corrected (2026-08-06, per advisor review): same rationale as
        // testWidensWhenRegistrationRejectedAtCap - flat walkable floor makes tryWiden's trace
        // return empty and refuse. Two widens alternate sides (width=1 odd -> side=-1 -> z-1;
        // width=2 even -> side=1 -> z+1), so both perpendicular offsets need a genuine gap carved.
        BlockPos relativeFirstWidenHop = relativeAnchor.offset(1, 0, -1);
        BlockPos relativeSecondWidenHop = relativeAnchor.offset(1, 0, 1);
        for (BlockPos hop : List.of(relativeFirstWidenHop, relativeSecondWidenHop)) {
            helper.setBlock(hop.below(), Blocks.AIR.defaultBlockState());
            helper.setBlock(hop, Blocks.AIR.defaultBlockState());
            helper.setBlock(hop.above(), Blocks.AIR.defaultBlockState());
        }

        List<FlowStep> orderedSteps = List.of(new FlowStep(target, PathAction.BRIDGE, anchor));
        Map<BlockPos, FlowStep> instructions = Map.of(target, new FlowStep(anchor, PathAction.BRIDGE, anchor));
        SiegeProject project = new SiegeProject(instructions, orderedSteps, anchor, target, 500, UUID.randomUUID());

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
