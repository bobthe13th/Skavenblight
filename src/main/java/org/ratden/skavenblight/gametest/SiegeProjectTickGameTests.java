package org.ratden.skavenblight.gametest;

import net.minecraft.core.BlockPos;
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

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.ratden.skavenblight.gametest.PathingRegionGameTests.check;

/**
 * Proves SiegeProject.tick() itself - the new centralized, worker-count-scaled placement
 * mechanism - independent of any goal, since Task 8's goal migration hasn't landed yet when this
 * test is written (TDD: this test exercises tick()/findProjectContaining directly).
 *
 * <p><b>Why every tick()-driving test here advances REAL game ticks</b> (via {@code succeedWhen} /
 * {@code startSequence}) instead of calling {@code tick()} N times in a tight loop inside one
 * method body, which is how these tests were originally written: {@code SiegeProject.tick()} is now
 * idempotent per game tick (see its own javadoc - it has to be, because every registered worker's
 * own goal instance calls it once per server tick, which made accumulation quadratic in worker
 * count). A synchronous loop within a single game tick therefore accumulates exactly one tick's
 * worth of work no matter how many times it runs. That those loops used to "work" was itself a
 * symptom of the bug: they were exercising a per-CALL accumulation that a real server can never
 * produce.
 */
@GameTestHolder(Skavenblight.MODID)
@PrefixGameTestTemplate(false)
public class SiegeProjectTickGameTests {

    @GameTest(template = "pathing_test", timeoutTicks = 100, skyAccess = true)
    public static void testTickPlacesInstructionOnceWorkAccumulates(GameTestHelper helper) {
        TerrainEvaluator evaluator = new TerrainEvaluator();
        SiegeProjectManager projectManager = new SiegeProjectManager(evaluator);
        CalculationThrottler throttler = new CalculationThrottler();
        FlowFieldCalculator calculator = new FlowFieldCalculator(evaluator, projectManager, throttler);
        TerritoryRegionMap owner = new TerritoryRegionMap();

        BlockPos relativeAnchor = new BlockPos(4, 2, 4);
        BlockPos relativeTarget = relativeAnchor.relative(net.minecraft.core.Direction.EAST);
        BlockPos anchor = helper.absolutePos(relativeAnchor);
        BlockPos target = helper.absolutePos(relativeTarget);

        FlowFieldState state = new FlowFieldState(anchor, Set.of());
        RegionFlowField flowField = new RegionFlowField(owner, 0, state, projectManager, calculator, throttler);

        List<SiegeNode> orderedSteps = List.of(new SiegeNode(target, SiegeNode.SiegeAction.BUILD_BRIDGE));
        Map<BlockPos, SiegeNode> instructions = Map.of(target, new SiegeNode(anchor, SiegeNode.SiegeAction.BUILD_BRIDGE));
        SiegeProject project = new SiegeProject(instructions, orderedSteps, anchor, target, 500);
        projectManager.addSharedConnectorProject(project);

        ClanratEntity mob = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
        mob.setPos(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5);
        // This test now spans real game ticks (see the class javadoc) and drives project.tick()
        // itself - the worker must not also run its own goal list and drive it a second time.
        mob.setNoAi(true);
        helper.getLevel().addFreshEntity(mob);

        check(project.tryRegisterWorker(mob, new LiveTerrainAccess(helper.getLevel()), evaluator, 3.5, 4, 10),
                "worker should register: mob is within radius of the only unbuilt step");

        // buildingBasePenalty * 10 = cost (default Config.buildingBasePenalty=150 -> cost 1500), so
        // one worker at the default workPerRatPerTick=100 covers it in ~15 GAME ticks. Driven one
        // tick() per real game tick (see this class's javadoc for why a synchronous loop can't work
        // any more); this test only needs "eventually placed", not an exact tick count.
        helper.succeedWhen(() -> {
            project.tick(helper.getLevel(), flowField, evaluator);
            helper.assertBlockState(relativeTarget, s -> s.is(Blocks.COBBLESTONE),
                    () -> "tick() should have placed cobblestone at " + relativeTarget + " once enough work accumulated");
        });
    }

    /**
     * Regression coverage for the whole-branch review's Fix 1: {@code SiegeProject.tick()} must add
     * {@code min(workers, cap) * workPerRatPerTick} per GAME tick, however many times it is called
     * within that tick.
     *
     * <p>{@code AbstractSiegeProjectGoal.tick()} is tick()'s only production call site, and EVERY
     * registered worker runs its own instance of that goal - vanilla's {@code GoalSelector} ticks
     * every running goal once per server tick. So a project with W workers really does get W
     * separate tick() calls inside one game tick, and before the fix each of them added
     * {@code min(W, cap) * workPerRatPerTick}: total {@code W * min(W, cap) * workPerRatPerTick},
     * quadratic in worker count, with the cap (the design's only bound on build rate) defeated
     * entirely past a handful of rats.
     *
     * <p>Shape chosen to be impossible to pass under the old bug: 2 workers, tick() called TWICE
     * back-to-back within one game tick (exactly what two goal instances do), asserting the banked
     * work is {@code 2 * workPerRatPerTick} - the bug produces {@code 4 * workPerRatPerTick}, and a
     * hypothetical "only ever count one worker" bug would produce {@code 1 *}. The second phase then
     * advances a real game tick and ticks once more, so the guard is proven to be per-game-tick and
     * not "once ever" (which would stall every project after its first tick).
     *
     * <p>Reads {@code getAccumulatedWork()} rather than counting placed blocks: a BUILD_BRIDGE step
     * costs {@code buildingBasePenalty * 10} = 1500 by default, far above the ~200-400 this test
     * banks, so no placement can run and spend the work before it is measured - which makes the
     * assertion a direct read of the scaling arithmetic instead of an inference from block counts.
     */
    @GameTest(template = "pathing_test", timeoutTicks = 100, skyAccess = true)
    public static void testTickAccumulatesOncePerGameTickNotOncePerCall(GameTestHelper helper) {
        TerrainEvaluator evaluator = new TerrainEvaluator();
        SiegeProjectManager projectManager = new SiegeProjectManager(evaluator);
        CalculationThrottler throttler = new CalculationThrottler();
        FlowFieldCalculator calculator = new FlowFieldCalculator(evaluator, projectManager, throttler);
        TerritoryRegionMap owner = new TerritoryRegionMap();

        BlockPos relativeAnchor = new BlockPos(4, 2, 4);
        BlockPos relativeTarget = relativeAnchor.relative(net.minecraft.core.Direction.EAST);
        BlockPos anchor = helper.absolutePos(relativeAnchor);
        BlockPos target = helper.absolutePos(relativeTarget);

        FlowFieldState state = new FlowFieldState(anchor, Set.of());
        RegionFlowField flowField = new RegionFlowField(owner, 0, state, projectManager, calculator, throttler);

        List<SiegeNode> orderedSteps = List.of(new SiegeNode(target, SiegeNode.SiegeAction.BUILD_BRIDGE));
        Map<BlockPos, SiegeNode> instructions = Map.of(target, new SiegeNode(anchor, SiegeNode.SiegeAction.BUILD_BRIDGE));
        SiegeProject project = new SiegeProject(instructions, orderedSteps, anchor, target, 500);
        projectManager.addSharedConnectorProject(project);

        LiveTerrainAccess live = new LiveTerrainAccess(helper.getLevel());
        for (int i = 0; i < 2; i++) {
            ClanratEntity rat = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
            rat.setPos(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5);
            // This test spans real game ticks and measures tick()'s accumulation exactly, so these
            // two workers must not run their OWN goal lists: a rat that somehow acquired a flow
            // field mid-test would start calling project.tick() itself and corrupt the measurement.
            // They only need to exist and be alive to count as registered workers.
            rat.setNoAi(true);
            helper.getLevel().addFreshEntity(rat);
            check(project.tryRegisterWorker(rat, live, evaluator, org.ratden.skavenblight.Config.projectWorkRadius,
                            org.ratden.skavenblight.Config.maxProjectWorkers, org.ratden.skavenblight.Config.workersPerWidenStep),
                    "setup: worker " + i + " should register - it's within radius of the only unbuilt step");
        }

        double perRat = org.ratden.skavenblight.Config.workPerRatPerTick;

        helper.startSequence()
                .thenExecute(() -> {
                    // Two calls, one game tick - exactly what two registered workers' own goal
                    // instances produce on a real server.
                    project.tick(helper.getLevel(), flowField, evaluator);
                    project.tick(helper.getLevel(), flowField, evaluator);

                    check(nearlyEquals(project.getAccumulatedWork(), 2 * perRat),
                            "2 workers, ONE game tick, tick() called twice: accumulated work should be "
                                    + (2 * perRat) + " (2 workers x workPerRatPerTick), but was "
                                    + project.getAccumulatedWork() + " - " + (4 * perRat)
                                    + " means tick() accumulated once per CALL instead of once per game tick "
                                    + "(the O(W^2) bug), " + perRat + " means only one worker was counted");
                })
                .thenExecuteAfter(1, () -> {
                    project.tick(helper.getLevel(), flowField, evaluator);

                    check(nearlyEquals(project.getAccumulatedWork(), 4 * perRat),
                            "on the NEXT game tick the same project must accumulate again (the guard is "
                                    + "per-game-tick, not once-ever - otherwise every project stalls after its "
                                    + "first tick): expected " + (4 * perRat) + ", was " + project.getAccumulatedWork());
                })
                .thenSucceed();
    }

    private static boolean nearlyEquals(double a, double b) {
        return Math.abs(a - b) < 1.0E-6;
    }

    @GameTest(template = "pathing_test", timeoutTicks = 100, skyAccess = true)
    public static void testFindProjectContainingLocatesRegisteredProject(GameTestHelper helper) {
        TerrainEvaluator evaluator = new TerrainEvaluator();
        SiegeProjectManager projectManager = new SiegeProjectManager(evaluator);

        BlockPos relativeAnchor = new BlockPos(4, 2, 4);
        BlockPos relativeTarget = relativeAnchor.relative(net.minecraft.core.Direction.EAST);
        BlockPos anchor = helper.absolutePos(relativeAnchor);
        BlockPos target = helper.absolutePos(relativeTarget);

        List<SiegeNode> orderedSteps = List.of(new SiegeNode(target, SiegeNode.SiegeAction.BUILD_BRIDGE));
        Map<BlockPos, SiegeNode> instructions = Map.of(target, new SiegeNode(anchor, SiegeNode.SiegeAction.BUILD_BRIDGE));
        SiegeProject project = new SiegeProject(instructions, orderedSteps, anchor, target, 500);
        projectManager.addSharedConnectorProject(project);

        check(projectManager.findProjectContaining(target).isPresent(), "should find the project owning target");
        check(projectManager.findProjectContaining(anchor).isEmpty(), "anchor itself is not one of the project's build positions");

        helper.succeed();
    }
}
