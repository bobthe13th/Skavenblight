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
