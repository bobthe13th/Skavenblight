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
        helper.getLevel().addFreshEntity(mob);

        check(project.tryRegisterWorker(mob, new LiveTerrainAccess(helper.getLevel()), evaluator, 3.5, 4, 10),
                "worker should register: mob is within radius of the only unbuilt step");

        // buildingBasePenalty * 10 = cost (default Config.buildingBasePenalty=150 -> cost 1500);
        // tick() until it's covered. 200 ticks * workPerRatPerTick is plenty regardless of the
        // exact default chosen in Task 5 - this test only needs "eventually placed", not a tick count.
        for (int i = 0; i < 200 && !helper.getLevel().getBlockState(target).is(Blocks.COBBLESTONE); i++) {
            project.tick(helper.getLevel(), flowField, evaluator);
        }

        helper.assertBlockState(relativeTarget, s -> s.is(Blocks.COBBLESTONE),
                () -> "tick() should have placed cobblestone at " + relativeTarget + " once enough work accumulated");

        helper.succeed();
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
