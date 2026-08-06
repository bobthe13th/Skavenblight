package org.ratden.skavenblight.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.ai.goal.clanrat.AwaitFormationGoal;
import org.ratden.skavenblight.ai.pathing.*;
import org.ratden.skavenblight.ai.pathing.region.Region;
import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;
import org.ratden.skavenblight.ai.pathing.region.TerritoryRegionMap;
import org.ratden.skavenblight.entity.ModEntities;
import org.ratden.skavenblight.entity.custom.ClanratEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.ratden.skavenblight.gametest.PathingRegionGameTests.check;

/**
 * Coverage for the formation-waiting feature (see
 * docs/superpowers/plans/2026-07-30-formation-waiting-goal.md): when a rat's nearest
 * construction work is already claimed by someone else, it should redirect to any other
 * unclaimed construction work in its region, or - if nothing else is available - wait at a
 * real, terrain-validated slot in a dynamic row/column formation grid instead of piling into
 * a crowd (see the {@code testFormationGrid*} methods for that grid's own coverage).
 */
@GameTestHolder(Skavenblight.MODID)
@PrefixGameTestTemplate(false)
public class AwaitFormationGoalGameTests {

    /** Hand-built flow field with no owning TerritoryRegionMap - matches the pattern already established in PathingGoalRecalculationGameTests. */
    private static RegionFlowField buildFlowField(BlockPos anchorPos) {
        FlowFieldState state = new FlowFieldState(anchorPos, Set.of(new ChunkPos(anchorPos)));
        state.updateInstructions(Map.of());
        PathStepEvaluator evaluator = new PathStepEvaluator();
        SiegeProjectManager projectManager = new SiegeProjectManager(evaluator);
        CalculationThrottler throttler = new CalculationThrottler();
        FlowFieldCalculator calculator = new FlowFieldCalculator(evaluator, projectManager, throttler);
        return new RegionFlowField(null, 0, state, projectManager, calculator, throttler);
    }

    @GameTest(template = "pathing_test", timeoutTicks = 100, skyAccess = true)
    public static void testFormationSlotClaimIsIndependentOfTargetClaim(GameTestHelper helper) {
        BlockPos relativePos = new BlockPos(4, 2, 4);
        BlockPos pos = helper.absolutePos(relativePos);
        RegionFlowField flowField = buildFlowField(pos);

        ClanratEntity mobA = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
        mobA.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        helper.getLevel().addFreshEntity(mobA);

        ClanratEntity mobB = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
        mobB.setPos(pos.getX() + 3.5, pos.getY(), pos.getZ() + 3.5);
        helper.getLevel().addFreshEntity(mobB);

        check(!flowField.isFormationSlotClaimed(pos), "an unclaimed formation slot must report unclaimed");

        check(flowField.tryClaimFormationSlot(pos, mobA), "first claim on an unclaimed slot should succeed");
        check(flowField.isFormationSlotClaimed(pos), "slot should now report claimed");
        check(!flowField.isTargetClaimed(pos), "a formation-slot claim must NOT be visible as a construction-target claim");

        check(!flowField.tryClaimFormationSlot(pos, mobB), "a second mob must not be able to claim an already-held slot");

        flowField.releaseFormationSlot(pos);
        check(!flowField.isFormationSlotClaimed(pos), "slot should be unclaimed again after release");
        check(flowField.tryClaimFormationSlot(pos, mobB), "a released slot should be claimable by a different mob");

        helper.succeed();
    }

    /**
     * Two identical stair-widening opportunities exist near the mob. The first (closer) one is
     * already claimed by a different mob; the second (a bit further) is free. AwaitFormationGoal
     * must redirect to the free one instead of doing nothing (which would leave the mob to fall
     * through to FollowFlowFieldGoal and walk toward the contested one, joining a crowd).
     */
    @GameTest(template = "pathing_test", timeoutTicks = 100, skyAccess = true)
    public static void testAwaitFormationGoalRedirectsToUnclaimedAlternative(GameTestHelper helper) {
        BlockPos relativeMobPos = new BlockPos(4, 2, 4);
        BlockPos relativeContestedTarget = relativeMobPos.offset(1, 1, 0);
        BlockPos relativeFreeTarget = relativeMobPos.offset(2, 2, 0);

        for (BlockPos step : new BlockPos[]{relativeContestedTarget, relativeFreeTarget}) {
            helper.setBlock(step, Blocks.AIR.defaultBlockState());
            helper.setBlock(step.below(), Blocks.AIR.defaultBlockState());
            helper.setBlock(step.above(), Blocks.AIR.defaultBlockState());
            helper.setBlock(step.above(2), Blocks.AIR.defaultBlockState());
        }

        BlockPos mobPos = helper.absolutePos(relativeMobPos);
        BlockPos contestedTarget = helper.absolutePos(relativeContestedTarget);
        BlockPos freeTarget = helper.absolutePos(relativeFreeTarget);

        FlowFieldState state = new FlowFieldState(mobPos, Set.of(new ChunkPos(mobPos)));
        // Genuine Shape-A entries (see RegionFlowField.getNextStep's own doc: a map value's pos()
        // always equals its own key). mobPos is real WALK ground pointing at contestedTarget;
        // contestedTarget and freeTarget are each their OWN unbuilt AIR_STAIR construction site
        // (self-referencing predecessorPos - the established terminal convention, see
        // FlowFieldCalculator.startCalculation's target seed) - findUnclaimedAlternative's raw
        // getInstructionMap() scan needs freeTarget to be its own key, not merely another entry's
        // pos(), or the redirect target it's supposed to find is unrepresentable.
        state.updateInstructions(Map.of(
                mobPos, new FlowStep(mobPos, PathAction.WALK, contestedTarget),
                contestedTarget, new FlowStep(contestedTarget, PathAction.AIR_STAIR, contestedTarget),
                freeTarget, new FlowStep(freeTarget, PathAction.AIR_STAIR, freeTarget)
        ));
        PathStepEvaluator evaluator = new PathStepEvaluator();
        SiegeProjectManager projectManager = new SiegeProjectManager(evaluator);
        CalculationThrottler throttler = new CalculationThrottler();
        FlowFieldCalculator calculator = new FlowFieldCalculator(evaluator, projectManager, throttler);
        RegionFlowField flowField = new RegionFlowField(null, 0, state, projectManager, calculator, throttler);

        ClanratEntity mob = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
        mob.setPos(mobPos.getX() + 0.5, mobPos.getY(), mobPos.getZ() + 0.5);
        helper.getLevel().addFreshEntity(mob);
        // AwaitFormationGoal.canUse() asks the MOB'S OWN registered BuildFlowFieldGoal/
        // WidenStairsGoal (via peekAnyClaimedConstructionTarget()) whether their nearest target
        // is claimed - those internal instances need this flow field too, not just the
        // standalone AwaitFormationGoal built below. assignFlowField() is the real production
        // method that wires a field into every registered SiegeGoal at once.
        mob.assignFlowField(flowField);

        ClanratEntity claimant = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
        claimant.setPos(contestedTarget.getX() + 0.5, contestedTarget.getY(), contestedTarget.getZ() + 0.5);
        helper.getLevel().addFreshEntity(claimant);
        flowField.tryClaimTarget(contestedTarget, claimant);

        // AwaitFormationGoal.canUse() now (post project-scoped overhaul) asks whether the mob's
        // own nearest BUILD_STAIR target's owning SiegeProject is AT CAPACITY, not whether the
        // old per-block claim table has it claimed - that table no longer reflects BUILD_STAIR/
        // BUILD_PILLAR at all (see AwaitFormationGoal.isPositionAvailable / Task 9). The
        // tryClaimTarget call above is kept for the OLD table's own sake (still exercised by the
        // freeTarget assertion below, via AwaitFormationGoal's own redirect-claim logic, which is
        // unrelated to Task 9's fix) but no longer drives canUse() by itself - register a real,
        // single-worker-capacity SiegeProject at contestedTarget and fill it so the capacity
        // check this goal actually depends on trips.
        SiegeProject contestedProject = new SiegeProject(
                Map.of(contestedTarget, new FlowStep(mobPos, PathAction.AIR_STAIR, mobPos)),
                List.of(new FlowStep(contestedTarget, PathAction.AIR_STAIR, contestedTarget)),
                mobPos, contestedTarget, 500, UUID.randomUUID());
        projectManager.addSharedConnectorProject(contestedProject);
        contestedProject.tryRegisterWorker(claimant, new LiveTerrainAccess(helper.getLevel()), evaluator,
                org.ratden.skavenblight.Config.projectWorkRadius, 1, 1);
        check(contestedProject.isAtCapacity(),
                "setup sanity: the contested project must actually be at capacity, or canUse()'s "
                        + "at-capacity check below is testing nothing");

        AwaitFormationGoal goal = new AwaitFormationGoal(mob);
        goal.setFlowField(flowField);

        check(goal.canUse(), "goal should trigger: the mob's nearest work is claimed by a different mob");
        goal.start();

        check(flowField.isTargetClaimed(freeTarget), "the free alternative should now be claimed by the redirecting mob");
        // canContinueToUse() reflects this goal's OWN tracked redirectTarget state directly - more
        // reliable here than asserting on vanilla PathNavigation's internals, which may not
        // populate isInProgress()/getTargetPos() synchronously within the same tick moveTo() was
        // called (pathfinding can resolve on a later tick even for a short distance).
        check(goal.canContinueToUse(), "the goal should still consider itself active - it has a redirect target to walk to");

        helper.succeed();
    }

    /**
     * Only ONE stair-widening opportunity exists, and it's already claimed. With no alternative
     * anywhere in the region, AwaitFormationGoal must find a real, walkable waiting slot near the
     * contested target (never air/void, never inside solid rock) and navigate there instead of
     * doing nothing.
     */
    @GameTest(template = "pathing_test", timeoutTicks = 400, skyAccess = true)
    public static void testAwaitFormationGoalWaitsInFormationWhenNoAlternativeExists(GameTestHelper helper) {
        BlockPos relativeMobPos = new BlockPos(4, 2, 4);
        BlockPos relativeContestedTarget = relativeMobPos.offset(1, 1, 0);

        helper.setBlock(relativeContestedTarget, Blocks.AIR.defaultBlockState());
        helper.setBlock(relativeContestedTarget.below(), Blocks.AIR.defaultBlockState());
        helper.setBlock(relativeContestedTarget.above(), Blocks.AIR.defaultBlockState());
        helper.setBlock(relativeContestedTarget.above(2), Blocks.AIR.defaultBlockState());

        BlockPos mobPos = helper.absolutePos(relativeMobPos);
        BlockPos contestedTarget = helper.absolutePos(relativeContestedTarget);

        // Real region/territory map, used ONLY to get a genuine, terrain-validated Region -
        // rebuild() is asynchronous, so wait for it via succeedWhen (same pattern
        // PathingRegionGameTests.testSingleConnectedRegion already uses).
        TerritoryRegionMap regionMap = new TerritoryRegionMap();
        Set<ChunkPos> territory = Set.of(new ChunkPos(mobPos));
        regionMap.rebuild(helper.getLevel(), territory, mobPos);

        helper.succeedWhen(() -> {
            check(!regionMap.isCalculating(), "region map still calculating");
            List<Region> regions = regionMap.getRegionGraph().getRegions();
            check(!regions.isEmpty(), "test structure should scan into at least one region");
            Region region = regions.get(0);

            FlowFieldState state = new FlowFieldState(mobPos, territory);
            // Genuine Shape-A entries - see testAwaitFormationGoalRedirectsToUnclaimedAlternative's
            // identical comment. No freeTarget here: this test is specifically about the
            // no-alternative-exists fallback to a formation slot.
            state.updateInstructions(Map.of(
                    mobPos, new FlowStep(mobPos, PathAction.WALK, contestedTarget),
                    contestedTarget, new FlowStep(contestedTarget, PathAction.AIR_STAIR, contestedTarget)
            ));
            PathStepEvaluator evaluator = new PathStepEvaluator();
            SiegeProjectManager projectManager = new SiegeProjectManager(evaluator);
            CalculationThrottler throttler = new CalculationThrottler();
            FlowFieldCalculator calculator = new FlowFieldCalculator(evaluator, projectManager, throttler);
            RegionFlowField flowField = new RegionFlowField(regionMap, region.getId(), state, projectManager, calculator, throttler);

            ClanratEntity mob = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
            mob.setPos(mobPos.getX() + 0.5, mobPos.getY(), mobPos.getZ() + 0.5);
            helper.getLevel().addFreshEntity(mob);
            mob.assignFlowField(flowField);

            ClanratEntity claimant = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
            claimant.setPos(contestedTarget.getX() + 0.5, contestedTarget.getY(), contestedTarget.getZ() + 0.5);
            helper.getLevel().addFreshEntity(claimant);
            flowField.tryClaimTarget(contestedTarget, claimant);

            // See testAwaitFormationGoalRedirectsToUnclaimedAlternative's identical comment:
            // canUse() now depends on a real, at-capacity SiegeProject, not the old claim table.
            SiegeProject contestedProject = new SiegeProject(
                    Map.of(contestedTarget, new FlowStep(mobPos, PathAction.AIR_STAIR, mobPos)),
                    List.of(new FlowStep(contestedTarget, PathAction.AIR_STAIR, contestedTarget)),
                    mobPos, contestedTarget, 500, UUID.randomUUID());
            projectManager.addSharedConnectorProject(contestedProject);
            contestedProject.tryRegisterWorker(claimant, new LiveTerrainAccess(helper.getLevel()), evaluator,
                    org.ratden.skavenblight.Config.projectWorkRadius, 1, 1);
            check(contestedProject.isAtCapacity(),
                    "setup sanity: the contested project must actually be at capacity, or canUse()'s "
                            + "at-capacity check below is testing nothing");

            AwaitFormationGoal goal = new AwaitFormationGoal(mob);
            goal.setFlowField(flowField);

            check(goal.canUse(), "goal should trigger: the mob's only nearby work is claimed by a different mob");
            goal.start();

            // canContinueToUse() reflects this goal's OWN tracked formationSlot state directly -
            // more reliable than asserting on vanilla PathNavigation's internals synchronously.
            check(goal.canContinueToUse(),
                    "with no alternative work available, the mob should be navigating to a formation slot");

            // Same cleanup PathingRegionGameTests.testSingleConnectedRegion performs - releases
            // this map's forced chunk tickets so this test doesn't leak them into the shared
            // per-level forced-chunk set for the rest of the GameTestServer run.
            regionMap.cleanup(helper.getLevel());
        });
    }

    /**
     * region.contains() returns true for a provisionally-claimed-but-unbuilt connector cell too
     * (same bitset as a genuinely walkable cell - see
     * docs/superpowers/specs/2026-07-30-region-merge-detection-design.md's background
     * invariants). This proves findFormationSlot() doesn't hand out such a cell as a "safe"
     * waiting spot just because region.contains() says yes - it must also have real solid ground
     * beneath it right now.
     */
    @GameTest(template = "pathing_test", timeoutTicks = 400, skyAccess = true)
    public static void testFindFormationSlotRejectsUnbuiltConnectorCell(GameTestHelper helper) {
        BlockPos relativeMobPos = new BlockPos(4, 2, 4);
        BlockPos relativeContestedTarget = relativeMobPos.offset(1, 1, 0);
        // findFormationSlot()'s ring search starts at FORMATION_MIN_RADIUS (4), not 0 - a cell
        // right next to the mob is never even considered. This cell must sit at exactly that
        // radius, at the very first position the ring scan visits (dx=-4, dz=-4 for r=4), so it's
        // guaranteed to be the candidate under test rather than one of the many ordinary walkable
        // floor cells the ring search would otherwise accept first: region-member (added below
        // via addCell, exactly like registerConnector would), but genuinely open air underneath.
        BlockPos relativeUnbuiltConnectorCell = relativeMobPos.offset(-4, 0, -4);
        helper.setBlock(relativeUnbuiltConnectorCell, Blocks.AIR.defaultBlockState());
        helper.setBlock(relativeUnbuiltConnectorCell.below(), Blocks.AIR.defaultBlockState());

        helper.setBlock(relativeContestedTarget, Blocks.AIR.defaultBlockState());
        helper.setBlock(relativeContestedTarget.below(), Blocks.AIR.defaultBlockState());
        helper.setBlock(relativeContestedTarget.above(), Blocks.AIR.defaultBlockState());
        helper.setBlock(relativeContestedTarget.above(2), Blocks.AIR.defaultBlockState());

        BlockPos mobPos = helper.absolutePos(relativeMobPos);
        BlockPos contestedTarget = helper.absolutePos(relativeContestedTarget);
        BlockPos unbuiltConnectorCell = helper.absolutePos(relativeUnbuiltConnectorCell);

        TerritoryRegionMap regionMap = new TerritoryRegionMap();
        Set<ChunkPos> territory = Set.of(new ChunkPos(mobPos));
        regionMap.rebuild(helper.getLevel(), territory, mobPos);

        helper.succeedWhen(() -> {
            check(!regionMap.isCalculating(), "region map still calculating");
            List<Region> regions = regionMap.getRegionGraph().getRegions();
            check(!regions.isEmpty(), "test structure should scan into at least one region");
            Region region = regions.get(0);
            // Provisionally claim the unbuilt connector cell into the region - exactly what
            // RegionGraph.registerConnector does at connector-discovery time, before anything is
            // actually built.
            region.addCell(unbuiltConnectorCell);
            check(region.contains(unbuiltConnectorCell),
                    "sanity check - the provisionally-claimed cell must report contains()=true, "
                            + "matching production's registerConnector behavior");

            FlowFieldState state = new FlowFieldState(mobPos, territory);
            // Genuine Shape-A entries - see testAwaitFormationGoalRedirectsToUnclaimedAlternative's
            // identical comment. No freeTarget here: this test is specifically about the
            // no-alternative-exists fallback to a formation slot.
            state.updateInstructions(Map.of(
                    mobPos, new FlowStep(mobPos, PathAction.WALK, contestedTarget),
                    contestedTarget, new FlowStep(contestedTarget, PathAction.AIR_STAIR, contestedTarget)
            ));
            PathStepEvaluator evaluator = new PathStepEvaluator();
            SiegeProjectManager projectManager = new SiegeProjectManager(evaluator);
            CalculationThrottler throttler = new CalculationThrottler();
            FlowFieldCalculator calculator = new FlowFieldCalculator(evaluator, projectManager, throttler);
            RegionFlowField flowField = new RegionFlowField(regionMap, region.getId(), state, projectManager, calculator, throttler);

            ClanratEntity mob = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
            mob.setPos(mobPos.getX() + 0.5, mobPos.getY(), mobPos.getZ() + 0.5);
            helper.getLevel().addFreshEntity(mob);
            mob.assignFlowField(flowField);

            ClanratEntity claimant = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
            claimant.setPos(contestedTarget.getX() + 0.5, contestedTarget.getY(), contestedTarget.getZ() + 0.5);
            helper.getLevel().addFreshEntity(claimant);
            flowField.tryClaimTarget(contestedTarget, claimant);

            // See testAwaitFormationGoalRedirectsToUnclaimedAlternative's identical comment:
            // canUse() now depends on a real, at-capacity SiegeProject, not the old claim table.
            SiegeProject contestedProject = new SiegeProject(
                    Map.of(contestedTarget, new FlowStep(mobPos, PathAction.AIR_STAIR, mobPos)),
                    List.of(new FlowStep(contestedTarget, PathAction.AIR_STAIR, contestedTarget)),
                    mobPos, contestedTarget, 500, UUID.randomUUID());
            projectManager.addSharedConnectorProject(contestedProject);
            contestedProject.tryRegisterWorker(claimant, new LiveTerrainAccess(helper.getLevel()), evaluator,
                    org.ratden.skavenblight.Config.projectWorkRadius, 1, 1);
            check(contestedProject.isAtCapacity(),
                    "setup sanity: the contested project must actually be at capacity, or canUse()'s "
                            + "at-capacity check below is testing nothing");

            AwaitFormationGoal goal = new AwaitFormationGoal(mob);
            goal.setFlowField(flowField);

            check(goal.canUse(), "goal should trigger: the mob's only nearby work is claimed by a different mob");
            goal.start();

            check(goal.canContinueToUse(), "the mob should still be seeking/holding a formation slot");
            check(!flowField.isFormationSlotClaimed(unbuiltConnectorCell),
                    "the unbuilt connector cell must NEVER be selected as a formation slot, even "
                            + "though region.contains() reports it as a member");

            regionMap.cleanup(helper.getLevel());
        });
    }

    /**
     * Confirms AwaitFormationGoal is actually registered on ClanratEntity (integration, not unit,
     * coverage - the priority ordering itself isn't assertable from outside the entity, since
     * WrappedGoal/GoalSelector don't expose enough to check that directly). If registerGoals()
     * failed to compile with AwaitFormationGoal wired in, this whole test file wouldn't compile
     * at all - which is the real signal this task's registration step succeeded.
     */
    @GameTest(template = "pathing_test", timeoutTicks = 100, skyAccess = true)
    public static void testAwaitFormationGoalIsRegisteredOnClanratEntity(GameTestHelper helper) {
        ClanratEntity mob = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
        helper.getLevel().addFreshEntity(mob);

        String canUseState = mob.describeSiegeGoalCanUseState();
        check(canUseState != null, "sanity check - describeSiegeGoalCanUseState should always return a non-null string");

        helper.succeed();
    }

    /**
     * Real dynamic row/column formation grid coverage (Task 19 - "AwaitFormationGoal needs real
     * dynamic row/column slot computation, never observed working" per the design doc). Anchored
     * comfortably inside {@code pathing_test_giant} (96x64x96, plenty of open floor) well away
     * from the structure's own edges, so every candidate the 3x3 grid generates lands on genuine
     * open floor, not encasement padding - same anchoring caution
     * PathingRegionGameTests' class javadoc documents for this reason.
     */
    @GameTest(template = "pathing_test_giant", timeoutTicks = 400, skyAccess = true)
    public static void testFormationGridScalesRowsAndColumnsToRatCount(GameTestHelper helper) {
        BlockPos relativeAnchor = new BlockPos(48, 2, 48);
        BlockPos anchor = helper.absolutePos(relativeAnchor);

        TerritoryRegionMap regionMap = new TerritoryRegionMap();
        Set<ChunkPos> territory = Set.of(new ChunkPos(anchor));
        regionMap.rebuild(helper.getLevel(), territory, anchor);

        helper.succeedWhen(() -> {
            check(!regionMap.isCalculating(), "region map still calculating");
            List<Region> regions = regionMap.getRegionGraph().getRegions();
            check(!regions.isEmpty(), "test structure should scan into at least one region");
            Region region = regions.get(0);

            RegionFlowField field = buildFlowField(anchor);
            ClanratEntity mob = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
            mob.setPos(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5);
            helper.getLevel().addFreshEntity(mob);
            AwaitFormationGoal goal = new AwaitFormationGoal(mob);
            goal.setFlowField(field);

            // 9 rats should produce a 3x3 grid (columns = ceil(sqrt(9)) = 3, rows = ceil(9/3) = 3),
            // 9 DISTINCT slot positions, no duplicates, every slot within the region and unclaimed.
            List<BlockPos> slots = goal.computeFormationGrid(9, anchor, region, field);

            check(slots.size() == 9, "expected 9 formation slots for 9 rats, got " + slots.size());
            check(Set.copyOf(slots).size() == slots.size(), "formation slots must be distinct positions - no duplicates");

            regionMap.cleanup(helper.getLevel());
        });
    }

    @GameTest(template = "pathing_test_giant", timeoutTicks = 400, skyAccess = true)
    public static void testFormationGridDegeneratesToOneSlotForOneRat(GameTestHelper helper) {
        BlockPos relativeAnchor = new BlockPos(48, 2, 48);
        BlockPos anchor = helper.absolutePos(relativeAnchor);

        TerritoryRegionMap regionMap = new TerritoryRegionMap();
        Set<ChunkPos> territory = Set.of(new ChunkPos(anchor));
        regionMap.rebuild(helper.getLevel(), territory, anchor);

        helper.succeedWhen(() -> {
            check(!regionMap.isCalculating(), "region map still calculating");
            List<Region> regions = regionMap.getRegionGraph().getRegions();
            check(!regions.isEmpty(), "test structure should scan into at least one region");
            Region region = regions.get(0);

            RegionFlowField field = buildFlowField(anchor);
            ClanratEntity mob = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
            mob.setPos(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5);
            helper.getLevel().addFreshEntity(mob);
            AwaitFormationGoal goal = new AwaitFormationGoal(mob);
            goal.setFlowField(field);

            List<BlockPos> slots = goal.computeFormationGrid(1, anchor, region, field);

            check(slots.size() == 1, "one rat must get exactly one slot, not a full grid search radius");
            check(slots.get(0).equals(anchor), "a 1x1 grid's only slot must be the anchor itself, not an offset position");

            regionMap.cleanup(helper.getLevel());
        });
    }
}
