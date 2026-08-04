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
import org.ratden.skavenblight.ai.goal.clanrat.BuildFlowFieldGoal;
import org.ratden.skavenblight.ai.goal.clanrat.DeployClimbableGoal;
import org.ratden.skavenblight.ai.pathing.*;
import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;
import org.ratden.skavenblight.ai.pathing.region.TerritoryRegionMap;
import org.ratden.skavenblight.entity.ModEntities;
import org.ratden.skavenblight.entity.custom.ClanratEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import static org.ratden.skavenblight.gametest.PathingRegionGameTests.check;

/**
 * Regression coverage for Task 3 of the region-pathing-hardening plan: {@code WidenStairsGoal},
 * {@code SmartBreachGoal} (both via {@code AbstractSiegeConstructionGoal}'s {@code onChainComplete}
 * default) and {@code DeployClimbableGoal} must call {@code RegionFlowField#forceRecalculation}
 * after mutating the world, or the region they just changed can never be told about its own
 * construction (the same bug the branch's history already fixed once for {@code BuildFlowFieldGoal}).
 *
 * <p><b>Deviation from the task-3 brief's proposed assertion (see
 * docs/pathing/region-pathing-hardening-findings.md's Finding C, "Task 3", for the full
 * writeup):</b> the brief's original {@code succeedWhen} checked
 * {@code owner.getRegionIndex().regionIdAt(...) != null || owner.getGeneration() > 0} after driving
 * {@code TerritoryRegionMap#tick}. That can never pass here: a freshly-constructed
 * {@code TerritoryRegionMap} (no {@code rebuild()} ever called - these tests build their
 * {@code RegionFlowField} by hand, the same way {@code PathingRegionGameTests} does) starts with an
 * EMPTY {@code RegionIndex}. {@code TerritoryRegionMap#tick}'s dirty-marking loop only adds a region
 * to {@code dirtyRegionIds} when {@code regionIndex.regionIdAt(candidate)} already resolves to a
 * real region - which can never happen against an empty index, regardless of whether
 * {@code forceRecalculation}/{@code onBlockChanged} was ever called. So {@code recomputeDirtyRegions}
 * (the only path to bumping {@code generation} outside an explicit {@code rebuild()}) is unreachable,
 * and the brief's {@code succeedWhen} times out in BOTH the broken and fixed states - it can't
 * discriminate the bug at all. (Calling {@code rebuild()} first doesn't fix this either: that bumps
 * {@code generation} on its own, independent of the goal ever running, making the assertion
 * trivially true instead of trivially false.)
 *
 * <p>Instead, these tests observe {@code onBlockChanged} directly via a recording subclass -
 * {@code TerritoryRegionMap} is a plain, non-final class with a public, non-final
 * {@code onBlockChanged(BlockPos)} - which sidesteps every one of {@code TerritoryRegionMap}'s
 * unrelated gating mechanisms (settle delay, recalculation cooldown, the topology-unchanged fast
 * path that doesn't bump {@code generation}) and asserts the one thing the bug is actually about:
 * did {@code forceRecalculation} get called, and with the right position.
 */
@GameTestHolder(Skavenblight.MODID)
@PrefixGameTestTemplate(false)
public class PathingGoalRecalculationGameTests {

    /** See the class javadoc: records every {@code onBlockChanged} call instead of relying on it visibly propagating through region/generation state. */
    static final class RecordingRegionMap extends TerritoryRegionMap {
        final List<BlockPos> changes = new ArrayList<>();

        @Override
        public void onBlockChanged(BlockPos pos) {
            this.changes.add(pos.immutable());
            super.onBlockChanged(pos);
        }
    }

    @GameTest(template = "pathing_test", timeoutTicks = 200, skyAccess = true)
    public static void testDeployClimbableGoalMarksRegionDirty(GameTestHelper helper) {
        // helper-Y=1 is the template's solid floor, helper-Y=2 is the first open/walkable layer
        // above it (see PathingRegionGameTests' class javadoc) - the mob must stand at Y=2, not
        // Y=1, or its feet land inside the solid floor block instead of on top of it.
        BlockPos relativeMobPos = new BlockPos(4, 2, 4);
        BlockPos relativeWallPos = relativeMobPos.relative(Direction.NORTH);
        helper.setBlock(relativeWallPos, Blocks.STONE.defaultBlockState());
        // 2 blocks of open headroom above the mob so checkOverhang() skips straight to
        // PLACING_LADDER instead of MINING_OVERHANG.
        helper.setBlock(relativeMobPos.above(), Blocks.AIR.defaultBlockState());
        helper.setBlock(relativeMobPos.above(2), Blocks.AIR.defaultBlockState());

        BlockPos mobPos = helper.absolutePos(relativeMobPos);
        BlockPos wallPos = helper.absolutePos(relativeWallPos);
        // DeployClimbableGoal places the ladder at targetWallPos.relative(wallFacing), and
        // wallFacing is the direction FROM the mob TO the wall, reversed - i.e. back at the mob's
        // own standing position. isSpaceClear() deliberately excludes the builder (see this mod's
        // CLAUDE.md), so placing into the mob's own cell is expected to succeed.
        BlockPos placePos = mobPos;

        RecordingRegionMap owner = new RecordingRegionMap();
        FlowFieldState state = new FlowFieldState(mobPos, Set.of(new ChunkPos(mobPos)));
        state.updateInstructions(Map.of(mobPos, new SiegeNode(mobPos, SiegeNode.SiegeAction.BUILD_LADDER)));
        TerrainEvaluator evaluator = new TerrainEvaluator();
        SiegeProjectManager projectManager = new SiegeProjectManager(evaluator);
        CalculationThrottler throttler = new CalculationThrottler();
        FlowFieldCalculator calculator = new FlowFieldCalculator(evaluator, projectManager, throttler);
        RegionFlowField flowField = new RegionFlowField(owner, 0, state, projectManager, calculator, throttler);

        ClanratEntity mob = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
        mob.setPos(mobPos.getX() + 0.5, mobPos.getY(), mobPos.getZ() + 0.5);
        helper.getLevel().addFreshEntity(mob);

        DeployClimbableGoal goal = new DeployClimbableGoal(mob);
        goal.setFlowField(flowField);

        check(goal.canUse(), "goal should trigger: BUILD_LADDER node + solid wall to the north");
        goal.start();
        for (int i = 0; i < 12; i++) goal.tick();

        helper.assertBlockState(relativeWallPos.relative(Direction.SOUTH),
                s -> s.is(Blocks.LADDER), () -> "ladder should have been placed");

        check(owner.changes.contains(placePos),
                "region map was never told about the ladder placement at " + placePos
                        + " - forceRecalculation was never called (recorded changes: " + owner.changes + ")");

        helper.succeed();
    }

    /**
     * Regression coverage for fix round 1 (Task 8-11 review): the old {@code BuildFlowFieldGoal}
     * (an {@code AbstractSiegeConstructionGoal}) called {@code onChainComplete}'s default
     * {@code flowField.forceRecalculation(completedPos)} after every completed action - the ONLY
     * thing that ever tells {@code TerritoryRegionMap} "a rat just built something here" (see
     * {@code testDeployClimbableGoalMarksRegionDirty}'s own class javadoc for why direct
     * {@code level.setBlockAndUpdate}/{@code destroyBlock} calls don't fire this on their own).
     * Task 8 migrated {@code BuildFlowFieldGoal} onto {@code AbstractSiegeProjectGoal} /
     * {@code SiegeProject.tick()}, which had no equivalent call at all - a project-driven
     * placement would never mark its region dirty, leaving it permanently stale exactly like the
     * bug {@code RegionFlowField#forceRecalculation}'s own javadoc describes. This proves the
     * call {@code SiegeProject.tick()} now makes after each successful placement actually reaches
     * the region map, through the real {@link BuildFlowFieldGoal} + {@link SiegeProject} path
     * (not a direct {@code SiegeProject.tick()} call), mirroring
     * {@code testDeployClimbableGoalMarksRegionDirty}'s own goal-driven shape.
     */
    @GameTest(template = "pathing_test", timeoutTicks = 200, skyAccess = true)
    public static void testBuildFlowFieldGoalMarksRegionDirty(GameTestHelper helper) {
        BlockPos relativeAnchor = new BlockPos(4, 2, 4);
        BlockPos relativeTarget = relativeAnchor.relative(Direction.EAST);
        BlockPos anchor = helper.absolutePos(relativeAnchor);
        BlockPos target = helper.absolutePos(relativeTarget);

        RecordingRegionMap owner = new RecordingRegionMap();
        FlowFieldState state = new FlowFieldState(anchor, Set.of(new ChunkPos(anchor)));
        state.updateInstructions(Map.of(anchor, new SiegeNode(target, SiegeNode.SiegeAction.BUILD_STAIR)));

        TerrainEvaluator evaluator = new TerrainEvaluator();
        SiegeProjectManager projectManager = new SiegeProjectManager(evaluator);
        CalculationThrottler throttler = new CalculationThrottler();
        FlowFieldCalculator calculator = new FlowFieldCalculator(evaluator, projectManager, throttler);
        RegionFlowField flowField = new RegionFlowField(owner, 0, state, projectManager, calculator, throttler);

        // Single-step, default-solid-floor BUILD_STAIR project (same shape as
        // SiegeProjectAutoWidenGameTests/PathingGoalRecalculationGameTests' own widen tests) -
        // this test is about region-dirty-marking, not the no-prior-support scenario, so real
        // ground support keeps the setup minimal.
        List<SiegeNode> orderedSteps = List.of(new SiegeNode(target, SiegeNode.SiegeAction.BUILD_STAIR));
        Map<BlockPos, SiegeNode> instructions = Map.of(target, new SiegeNode(anchor, SiegeNode.SiegeAction.BUILD_STAIR));
        SiegeProject project = new SiegeProject(instructions, orderedSteps, anchor, target, 500);
        projectManager.addSharedConnectorProject(project);

        ClanratEntity mob = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
        mob.setPos(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5);
        // This test drives ONE hand-built goal instance across real game ticks (see the
        // succeedWhen below). Suppressing the mob's own goal list keeps that the only driver, and
        // keeps the mob from strolling onto the build target and blocking isSpaceClear - neither
        // was possible back when the whole test ran inside a single game tick.
        mob.setNoAi(true);
        helper.getLevel().addFreshEntity(mob);

        BuildFlowFieldGoal goal = new BuildFlowFieldGoal(mob);
        goal.setFlowField(flowField);

        check(goal.canUse(), "goal should trigger for target " + target.toShortString());
        goal.start();

        // One goal.tick() per REAL game tick, not a synchronous loop: SiegeProject.tick() is now
        // idempotent per game tick (see its own javadoc - it has to be, since every registered
        // worker's goal instance calls it once per server tick, which made accumulation quadratic in
        // worker count), so N calls inside one game tick bank exactly one tick's work. A
        // BUILD_STAIR costs buildingBasePenalty * 10 = 1500 by default, so one worker at
        // workPerRatPerTick = 100 needs ~15 game ticks - well inside this test's 200-tick budget.
        helper.succeedWhen(() -> {
            goal.tick();

            helper.assertBlockState(relativeTarget, s -> s.is(Blocks.COBBLESTONE_STAIRS),
                    () -> "BUILD_STAIR should have placed a stair at " + relativeTarget);

            check(owner.changes.contains(target),
                    "region map was never told about the stair placement at " + target
                            + " - SiegeProject.tick() never called forceRecalculation (recorded changes: "
                            + owner.changes + ")");
        });
    }

    /**
     * Regression coverage for the narrowing correction described in
     * docs/superpowers/plans/2026-07-29-siege-project-floating-stair-fix.md's "Correction"
     * section: the original support guard required solid ground below EVERY BUILD_STAIR target,
     * which wrongly blocked a macro SiegeProject's legitimate next-unbuilt chain step (step N's
     * support is step N-1, built moments earlier - it never has support at the instant it's
     * selected). That over-broad guard invalidated every such step on tick 1 (before 15 ticks of
     * animation could complete), producing a silent start/stop loop with no log output at all -
     * exactly what a real user reported as clanrats "still stuck" after the first fix shipped.
     *
     * <p>This test drives {@link BuildFlowFieldGoal} through FOUR consecutive diagonal
     * BUILD_STAIR steps of a hand-built macro chain (mirroring the real bug's 15-step staircase,
     * just shorter for test speed), where every single step's support (target.below()) is air
     * throughout - never solid, matching the real diagonal-climb geometry where a step's target
     * cell and the previous step's own position are different cells entirely. If the guard were
     * still over-broad, {@code canUse()} would return true (a target IS proposed) but the build
     * would never actually complete within the per-step tick budget - proving the WHOLE macro
     * chain can progress end-to-end, not just that one isolated placement isn't wrongly blocked.
     */
    @GameTest(template = "pathing_test", timeoutTicks = 400, skyAccess = true)
    public static void testBuildFlowFieldGoalCompletesMultiStepMacroChainWithNoPriorSupport(GameTestHelper helper) {
        BlockPos relativeStart = new BlockPos(4, 2, 4);

        BlockPos[] relativeChain = new BlockPos[5];
        relativeChain[0] = relativeStart;
        for (int i = 1; i <= 4; i++) {
            relativeChain[i] = relativeStart.offset(i, i, 0);
        }

        for (int i = 1; i <= 4; i++) {
            BlockPos step = relativeChain[i];
            helper.setBlock(step, Blocks.AIR.defaultBlockState());
            helper.setBlock(step.below(), Blocks.AIR.defaultBlockState());
            helper.setBlock(step.above(), Blocks.AIR.defaultBlockState());
            helper.setBlock(step.above(2), Blocks.AIR.defaultBlockState());
        }

        BlockPos startPos = helper.absolutePos(relativeStart);

        RecordingRegionMap owner = new RecordingRegionMap();
        FlowFieldState state = new FlowFieldState(startPos, Set.of(new ChunkPos(startPos)));

        // Two DIFFERENT keyings are needed here, not one reused map: FlowFieldState's own
        // instruction map (consumed by goal-facing code via getNextSiegeNode/getInstruction) is
        // keyed by STANDING position with a forward-pointing value ("from here, go build at
        // pos()") - see AwaitFormationGoal's own inline comment on this exact convention.
        // SiegeProject's own `instructions` field (consumed by SiegeProjectManager.
        // findProjectContaining via getInstructions().containsKey(pos)) is keyed the OPPOSITE
        // way in real production usage (see SiegeLineTracer.trace, whose output populates this
        // field for every real project): keyed by the BUILD/target position itself, with a
        // backward-pointing value. Reusing the standing-position-keyed map for both would leave
        // the project's own instructions missing a key for chain[4] (only chain[0..3] are ever
        // "from" positions), so AbstractSiegeProjectGoal.canUse()'s own
        // flowField.findProjectFor(node.pos()) call - node.pos() is always the BUILD target -
        // would fail to find this project for the final step.
        Map<BlockPos, SiegeNode> stateInstructions = new HashMap<>();
        Map<BlockPos, SiegeNode> projectInstructions = new HashMap<>();
        List<SiegeNode> orderedSteps = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            BlockPos from = helper.absolutePos(relativeChain[i]);
            BlockPos to = helper.absolutePos(relativeChain[i + 1]);
            stateInstructions.put(from, new SiegeNode(to, SiegeNode.SiegeAction.BUILD_STAIR));
            projectInstructions.put(to, new SiegeNode(from, SiegeNode.SiegeAction.BUILD_STAIR));
            orderedSteps.add(new SiegeNode(to, SiegeNode.SiegeAction.BUILD_STAIR));
        }
        state.updateInstructions(stateInstructions);

        TerrainEvaluator evaluator = new TerrainEvaluator();
        SiegeProjectManager projectManager = new SiegeProjectManager(evaluator);
        CalculationThrottler throttler = new CalculationThrottler();
        FlowFieldCalculator calculator = new FlowFieldCalculator(evaluator, projectManager, throttler);
        RegionFlowField flowField = new RegionFlowField(owner, 0, state, projectManager, calculator, throttler);

        BlockPos chainEndPos = helper.absolutePos(relativeChain[4]);
        SiegeProject project = new SiegeProject(projectInstructions, orderedSteps, startPos, chainEndPos, 500);
        projectManager.addSharedConnectorProject(project);

        ClanratEntity mob = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
        mob.setPos(startPos.getX() + 0.5, startPos.getY(), startPos.getZ() + 0.5);
        // This test used to run all four steps inside a single game tick, which meant the mob was
        // never subject to its own AI or to gravity while the test repositioned it onto mid-air
        // chain cells. Now that each step spans real game ticks (see the sequence below - it has
        // to, because SiegeProject.tick() is idempotent per game tick), both have to be suppressed
        // explicitly or the mob would fall off the chain and stroll around mid-test.
        mob.setNoAi(true);
        mob.setNoGravity(true);
        helper.getLevel().addFreshEntity(mob);

        // One goal.tick() per REAL game tick, four steps back to back: a BUILD_STAIR costs
        // buildingBasePenalty * 10 = 1500 by default, so a single worker at workPerRatPerTick = 100
        // needs ~15 game ticks per step, ~60 for the whole chain - inside this test's 400-tick
        // budget with room to spare.
        BuildFlowFieldGoal[] currentGoal = new BuildFlowFieldGoal[1];
        net.minecraft.gametest.framework.GameTestSequence sequence = helper.startSequence();
        for (int i = 0; i < 4; i++) {
            final int stepIndex = i;
            BlockPos fromPos = helper.absolutePos(relativeChain[i]);
            BlockPos toPos = helper.absolutePos(relativeChain[i + 1]);

            sequence = sequence
                    .thenExecute(() -> {
                        mob.setPos(fromPos.getX() + 0.5, fromPos.getY(), fromPos.getZ() + 0.5);

                        check(!helper.getLevel().getBlockState(toPos.below()).blocksMotion(),
                                "step " + stepIndex + "'s support at " + toPos.below() + " must be air BEFORE building - "
                                        + "this is the whole point of the test (no support ever appears)");

                        // A fresh goal instance per step - mirrors the mob's real per-tick
                        // canUse()/start() re-evaluation as it moves; the underlying SiegeProject is
                        // the SAME instance across every step, matching how a real rat crossing a
                        // macro chain would.
                        BuildFlowFieldGoal goal = new BuildFlowFieldGoal(mob);
                        goal.setFlowField(flowField);
                        check(goal.canUse(), "step " + stepIndex + ": goal should trigger for the next unbuilt chain step");
                        goal.start();
                        currentGoal[0] = goal;
                    })
                    .thenWaitUntil(() -> {
                        // Can't query project.nextUnbuiltInstruction()'s own PlannedStep result
                        // directly here (it's package-private to org.ratden.skavenblight.ai.pathing,
                        // not accessible from this gametest package) - tick until the real world
                        // shows the step done instead.
                        currentGoal[0].tick();
                        helper.assertBlockState(relativeChain[stepIndex + 1], s -> s.is(Blocks.COBBLESTONE_STAIRS),
                                () -> "step " + stepIndex + " should have placed a stair at " + relativeChain[stepIndex + 1]);
                    })
                    .thenExecute(() -> currentGoal[0].stop());
        }
        sequence.thenSucceed();
    }

    /**
     * Regression coverage for today's clear-space fix (see
     * docs/superpowers/plans/2026-07-29-siege-project-floating-stair-fix.md, or the commit
     * message on SiegeInteractionHandler.isSpaceClear): clanrats are 1.8 blocks tall
     * (see ModEntities#CLANRAT), so a normal-height neighbor simply standing on the ground
     * directly below a BUILD target already had its hitbox poking into that target's airspace
     * under the old full-AABB-overlap check - permanently failing isSpaceClear in any crowded
     * bottleneck even when the target cell itself was genuinely empty. The fix scopes the check
     * to each entity's own blockPosition() (feet) instead. This proves both directions: a tall
     * neighbor below the target no longer blocks it, but a mob whose feet are genuinely AT the
     * target position still does.
     */
    @GameTest(template = "pathing_test", timeoutTicks = 100, skyAccess = true)
    public static void testIsSpaceClearIgnoresTallNeighborBelowTarget(GameTestHelper helper) {
        BlockPos relativeTarget = new BlockPos(4, 3, 4);
        BlockPos relativeNeighborPos = relativeTarget.below();

        helper.setBlock(relativeTarget, Blocks.AIR.defaultBlockState());
        helper.setBlock(relativeNeighborPos, Blocks.AIR.defaultBlockState());
        helper.setBlock(relativeNeighborPos.below(), Blocks.STONE.defaultBlockState());

        BlockPos targetPos = helper.absolutePos(relativeTarget);
        BlockPos neighborPos = helper.absolutePos(relativeNeighborPos);

        ClanratEntity builder = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
        builder.setPos(targetPos.getX() + 10.5, targetPos.getY(), targetPos.getZ() + 10.5);
        helper.getLevel().addFreshEntity(builder);

        ClanratEntity neighbor = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
        neighbor.setPos(neighborPos.getX() + 0.5, neighborPos.getY(), neighborPos.getZ() + 0.5);
        helper.getLevel().addFreshEntity(neighbor);

        check(SiegeInteractionHandler.isSpaceClear(helper.getLevel(), targetPos, builder),
                "a normal-height (1.8-tall) neighbor standing directly below the target has its hitbox poking "
                        + "into the target's airspace, but its FEET are at a different cell - isSpaceClear must "
                        + "not treat that as occupying the target");

        neighbor.setPos(targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5);
        check(!SiegeInteractionHandler.isSpaceClear(helper.getLevel(), targetPos, builder),
                "a mob actually standing (feet) at the target position must still block isSpaceClear");

        helper.succeed();
    }

    /**
     * Task 7 (region-pathing-hardening): mechanical proof that
     * {@code SiegeProjectManager.setMaxCandidateProjectLength} actually bounds how far a candidate
     * line traced by {@code evaluateMacroProjects}/{@code SiegeLineTracer.trace} can reach - see
     * docs/pathing/region-pathing-hardening-findings.md's Finding C ("Task 7") for why this can't
     * be shown through any {@code TerritoryRegionMap}/region-graph
     * scenario: every region-scoped {@code FlowFieldState} is built with a {@code cellFilter}
     * ({@code region::contains}), and {@code SiegeLineTracer.trace} checks that filter on EVERY step,
     * not just the endpoint, so a candidate line can never reach further than whatever
     * {@code RegionScanner} already put in the SAME region - which is itself capped at ~6 by the
     * pre-existing {@code MAX_CONSECUTIVE_MINE_DEPTH}/{@code MAX_CONSECUTIVE_MINE} = 5. That makes
     * the new cap of 6 a no-op against an already-lower ceiling in every region-graph GameTest, even
     * though the parameter itself works correctly.
     *
     * <p>This test proves the parameter itself, using the SAME hand-built-pathing-objects pattern
     * this file already established for {@link #testDeployClimbableGoalMarksRegionDirty}: a real
     * {@code FlowFieldState}, but constructed via the 2-arg constructor so {@code cellFilter} is
     * {@code null} and {@code territoryChunks} is empty ("Global scope" per
     * {@code FlowFieldState#isOutOfBounds}) - deliberately bypassing the region-scoping machinery
     * that makes the cap unobservable elsewhere. What's under test here is purely "does
     * {@code SiegeLineTracer.trace}'s line length actually respect the {@code maxLength} argument
     * {@code SiegeProjectManager} now forwards to it," independent of whether any current
     * region-graph scenario can trigger the difference.
     *
     * <p>Geometry: the anchor sits on ordinary open floor (helper-Y=2, template-Y=1 - see
     * {@code PathingRegionGameTests}' class javadoc for the +1 helper-Y offset), untouched. Directly
     * east, the floor (helper-Y=1) is removed for 10 consecutive columns (x+1..x+10) - open air with
     * no support, so {@code determineMacroAction} returns {@code BUILD_BRIDGE} for every one of
     * those positions (see that method's "Horizontal Bridge" branch: {@code dy==0}, some horizontal
     * offset, support not solid). The floor is left intact (untouched) starting at x+11, a REAL
     * walkable landing - far enough out that only an uncapped (default 32) trace can reach it, but
     * well within reach if the cap weren't working at all.
     *
     * <p>Two independent {@code SiegeProjectManager} instances (avoiding any
     * {@code isNearExistingProject} cross-talk) fire {@code evaluateMacroProjects} from the same
     * anchor against the same terrain: one left at the default cap, one set to
     * {@code setMaxCandidateProjectLength(6)}. The default-cap run must reach the real landing at
     * x+11 with a {@code WALK} action (the real ground {@code determineMacroAction} computes once
     * support is solid again). The capped run must NOT reach x+11 at all, and must instead terminate
     * exactly at x+6 with a synthetic {@code BUILD_LANDING} - the same substitution
     * {@code SiegeLineTracer.trace} makes at any {@code maxLength} boundary it hits before finding
     * real ground (mirroring {@code RegionGraph}'s own {@code MAX_CHAIN_HOPS} cap on its own, longer
     * chains). Only the east-bound, same-Y (dy=0) line reaches these exact positions - the other 13
     * directions {@code evaluateMacroProjects} also fires (2 pure-vertical, plus the same east
     * direction's own dy=-1/+1 variants, plus north/south/west/up/down) all move off this Y or X
     * axis and can never collide with the two checked keys.
     */
    @GameTest(template = "pathing_test", timeoutTicks = 200, skyAccess = true)
    public static void testMaxCandidateProjectLengthCapsMacroProjectReach(GameTestHelper helper) {
        BlockPos relativeAnchor = new BlockPos(4, 2, 4);
        BlockPos anchorPos = helper.absolutePos(relativeAnchor);

        // Remove the floor (helper-Y=1) for the 10 columns directly east of the anchor, forcing
        // BUILD_BRIDGE the whole way - see method javadoc. Floor at x+11 onward is left untouched
        // (the default solid stone floor), the real landing.
        for (int i = 1; i <= 10; i++) {
            helper.setBlock(relativeAnchor.offset(i, -1, 0), Blocks.AIR.defaultBlockState());
        }
        BlockPos realLanding = helper.absolutePos(relativeAnchor.offset(11, 0, 0));
        BlockPos cappedLanding = helper.absolutePos(relativeAnchor.offset(6, 0, 0));

        TerrainEvaluator evaluator = new TerrainEvaluator();
        LiveTerrainAccess terrain = new LiveTerrainAccess(helper.getLevel());
        // Unconstrained FlowFieldState (2-arg constructor: no cellFilter, empty territoryChunks =
        // "Global scope") - see method javadoc for why this is deliberate.
        FlowFieldState state = new FlowFieldState(anchorPos, Set.of());

        Map<BlockPos, Integer> uncappedCostMap = new HashMap<>();
        Map<BlockPos, SiegeNode> uncappedInstructionMap = new HashMap<>();
        PriorityQueue<FlowFieldCalculator.QueueNode> uncappedQueue = new PriorityQueue<>();

        SiegeProjectManager uncapped = new SiegeProjectManager(evaluator);
        uncapped.evaluateMacroProjects(terrain, anchorPos, state, 0, uncappedQueue, uncappedCostMap, uncappedInstructionMap);

        check(uncappedInstructionMap.containsKey(realLanding),
                "uncapped (default 32) macro-project search should have reached the real landing 11 blocks east at "
                        + realLanding + " - found instructions at: " + uncappedInstructionMap.keySet());
        check(uncappedInstructionMap.get(realLanding).action() == SiegeNode.SiegeAction.WALK,
                "the far landing should be the REAL walkable ground the trace found (action WALK), not a synthetic "
                        + "one - found: " + uncappedInstructionMap.get(realLanding));

        Map<BlockPos, Integer> cappedCostMap = new HashMap<>();
        Map<BlockPos, SiegeNode> cappedInstructionMap = new HashMap<>();
        PriorityQueue<FlowFieldCalculator.QueueNode> cappedQueue = new PriorityQueue<>();

        // A fresh SiegeProjectManager - setMaxCandidateProjectLength is the one thing under test.
        SiegeProjectManager capped = new SiegeProjectManager(evaluator);
        capped.setMaxCandidateProjectLength(6);
        capped.evaluateMacroProjects(terrain, anchorPos, state, 0, cappedQueue, cappedCostMap, cappedInstructionMap);

        check(!cappedInstructionMap.containsKey(realLanding),
                "setMaxCandidateProjectLength(6) should have stopped the east-bound trace before reaching the real "
                        + "landing 11 blocks east - found instructions at: " + cappedInstructionMap.keySet());
        SiegeNode cappedNode = cappedInstructionMap.get(cappedLanding);
        check(cappedNode != null && cappedNode.action() == SiegeNode.SiegeAction.BUILD_LANDING,
                "setMaxCandidateProjectLength(6) should have terminated the east-bound trace with a synthetic "
                        + "BUILD_LANDING exactly 6 blocks out at " + cappedLanding + " (found: " + cappedNode + ")");

        helper.succeed();
    }

    /**
     * Proves setMaxCandidateProjectLength's cap applies to the WHOLE region-scoped reactive
     * search across a pass, not just to a single macro-project line. FlowFieldCalculator's own
     * Dijkstra loop fires evaluateMacroProjects again on any frontier node that still "hits an
     * obstacle" - including a synthetic BUILD_LANDING a previous capped line just terminated on
     * (see evaluateSingleLine, which pushes a successful line's own endPos back onto the SAME
     * pass's calcQueue). Before the fix, each such chained call got a completely fresh
     * maxCandidateProjectLength budget, letting a region capped to 6 blocks discover a path far
     * longer than 6 blocks by chaining several 6-block-capped lines end to end - confirmed via a
     * live GameTest capture reaching 14 blocks across 3 chained hops, well past a 6-block cap,
     * reaching all the way into a different region's own territory (see
     * docs/superpowers/plans/2026-08-01-flow-field-planning-gap-fix.md for the full trace).
     *
     * <p>Reuses testMaxCandidateProjectLengthCapsMacroProjectReach's own geometry technique
     * (remove the floor east of the anchor to force BUILD_BRIDGE), but doubles the open span to
     * 20 blocks so BOTH a first 6-block-capped line AND a hypothetical (bugged) second chained
     * 6-block line reaching 12 blocks out would still land in open air, not real ground - the
     * only way to prove the SECOND call's budget is actually constrained by the first call's own
     * usage, rather than simply being re-granted a fresh 6 blocks.
     */
    @GameTest(template = "pathing_test", timeoutTicks = 100, skyAccess = true)
    public static void testMaxCandidateProjectLengthCapsWholeSearchNotJustOneLine(GameTestHelper helper) {
        BlockPos relativeAnchor = new BlockPos(4, 2, 4);
        BlockPos anchorPos = helper.absolutePos(relativeAnchor);

        for (int i = 1; i <= 20; i++) {
            helper.setBlock(relativeAnchor.offset(i, -1, 0), Blocks.AIR.defaultBlockState());
        }

        BlockPos firstCappedLanding = helper.absolutePos(relativeAnchor.offset(6, 0, 0));
        BlockPos secondChainedLanding = helper.absolutePos(relativeAnchor.offset(12, 0, 0));

        TerrainEvaluator evaluator = new TerrainEvaluator();
        LiveTerrainAccess terrain = new LiveTerrainAccess(helper.getLevel());
        FlowFieldState state = new FlowFieldState(anchorPos, Set.of());

        Map<BlockPos, Integer> costMap = new HashMap<>();
        Map<BlockPos, SiegeNode> instructionMap = new HashMap<>();
        PriorityQueue<FlowFieldCalculator.QueueNode> queue = new PriorityQueue<>();

        // One shared manager across BOTH calls - mirrors how FlowFieldCalculator's own Dijkstra
        // loop reuses a single SiegeProjectManager instance across every evaluateMacroProjects
        // call within one region-scoped pass (see FlowFieldCalculator.calculate's hitObstacle
        // branch), which is exactly the scenario this test exercises.
        SiegeProjectManager manager = new SiegeProjectManager(evaluator);
        manager.setMaxCandidateProjectLength(6);

        manager.evaluateMacroProjects(terrain, anchorPos, state, 0, queue, costMap, instructionMap);

        SiegeNode firstLandingNode = instructionMap.get(firstCappedLanding);
        check(firstLandingNode != null && firstLandingNode.action() == SiegeNode.SiegeAction.BUILD_LANDING,
                "first call should cap at 6 blocks east with a synthetic BUILD_LANDING at " + firstCappedLanding
                        + " (found: " + firstLandingNode + ")");

        // Simulate the Dijkstra flood reaching that landing next and firing another macro
        // evaluation from there - exactly what FlowFieldCalculator's own loop does for any
        // frontier node that still hits an obstacle (a synthetic BUILD_LANDING always does,
        // since there's nothing real to walk onto yet).
        int firstLandingCost = costMap.getOrDefault(firstCappedLanding, 0);
        manager.evaluateMacroProjects(terrain, firstCappedLanding, state, firstLandingCost, queue, costMap, instructionMap);

        check(!instructionMap.containsKey(secondChainedLanding),
                "setMaxCandidateProjectLength(6) should cap the region's WHOLE reactive search to 6 blocks total "
                        + "per pass, not 6 blocks PER macro-project call - a second chained call from the first "
                        + "call's own landing should not extend the search any further, but found instructions "
                        + "reaching a second landing 12 blocks out at " + secondChainedLanding + ": "
                        + instructionMap.keySet());

        helper.succeed();
    }

    /**
     * Goal-driven regression coverage for the auto-widening mechanism this file's own deletions
     * leave uncovered at this level (the two deleted {@code WidenStairsGoal} tests exercised
     * region-dirty-marking, not auto-widen - that property is already covered at the
     * {@code SiegeProject} level by {@code SiegeProjectAutoWidenGameTests
     * #testWidensWhenRegistrationRejectedAtCap}). This proves the SAME mechanism actually engages
     * through a real {@link BuildFlowFieldGoal}, not just via direct
     * {@code SiegeProject#tryRegisterWorker} calls: fill a BUILD_STAIR project's width-1 capacity
     * with {@code Config.workersPerWidenStep} filler workers, then start the goal on one more rat
     * and confirm the project widens.
     *
     * <p>The extra rat is positioned at {@code anchor}, not {@code target}: {@code
     * SiegeNodeLookahead.findEffectiveNode}'s own self-reference guard rejects any resolved node
     * whose {@code pos()} equals the mob's current position (see that class's javadoc), and a rat
     * standing AT {@code target} would resolve (via the neighbor-fallback lookahead) to exactly
     * that self-referential node, since the only instruction in this field's state is keyed at
     * {@code anchor} pointing at {@code target}.
     */
    @GameTest(template = "pathing_test", timeoutTicks = 200, skyAccess = true)
    public static void testBuildFlowFieldGoalRegistersOnAWidenedLane(GameTestHelper helper) {
        BlockPos relativeAnchor = new BlockPos(4, 2, 4);
        BlockPos relativeTarget = relativeAnchor.relative(Direction.EAST);
        BlockPos anchor = helper.absolutePos(relativeAnchor);
        BlockPos target = helper.absolutePos(relativeTarget);

        RecordingRegionMap owner = new RecordingRegionMap();
        FlowFieldState state = new FlowFieldState(anchor, Set.of(new ChunkPos(anchor)));
        state.updateInstructions(Map.of(anchor, new SiegeNode(target, SiegeNode.SiegeAction.BUILD_STAIR)));

        TerrainEvaluator evaluator = new TerrainEvaluator();
        SiegeProjectManager projectManager = new SiegeProjectManager(evaluator);
        CalculationThrottler throttler = new CalculationThrottler();
        FlowFieldCalculator calculator = new FlowFieldCalculator(evaluator, projectManager, throttler);
        RegionFlowField flowField = new RegionFlowField(owner, 0, state, projectManager, calculator, throttler);

        List<SiegeNode> orderedSteps = List.of(new SiegeNode(target, SiegeNode.SiegeAction.BUILD_STAIR));
        Map<BlockPos, SiegeNode> instructions = Map.of(target, new SiegeNode(anchor, SiegeNode.SiegeAction.BUILD_STAIR));
        SiegeProject project = new SiegeProject(instructions, orderedSteps, anchor, target, 500);
        projectManager.addSharedConnectorProject(project);

        LiveTerrainAccess live = new LiveTerrainAccess(helper.getLevel());
        List<ClanratEntity> filler = new ArrayList<>();
        for (int i = 0; i < org.ratden.skavenblight.Config.workersPerWidenStep; i++) {
            ClanratEntity rat = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
            rat.setPos(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
            helper.getLevel().addFreshEntity(rat);
            filler.add(rat);
            project.tryRegisterWorker(rat, live, evaluator, org.ratden.skavenblight.Config.projectWorkRadius,
                    org.ratden.skavenblight.Config.maxProjectWorkers, org.ratden.skavenblight.Config.workersPerWidenStep);
        }

        ClanratEntity extraRat = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
        extraRat.setPos(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5);
        helper.getLevel().addFreshEntity(extraRat);

        BuildFlowFieldGoal goal = new BuildFlowFieldGoal(extraRat);
        goal.setFlowField(flowField);

        check(goal.canUse(), "goal should trigger even though the project is nominally at its width-1 cap");
        goal.start();

        check(project.getWidth() > 1, "starting the goal on a full project should have triggered a widen");

        helper.succeed();
    }

    /**
     * Counterpart to {@link #testBuildFlowFieldGoalRegistersOnAWidenedLane}: BUILD_PILLAR is not
     * laterally widenable (see {@code SiegeProject#effectiveCapFor} - only BUILD_STAIR/
     * BUILD_BRIDGE scale with width), mirroring {@code SiegeProjectAutoWidenGameTests
     * #testDoesNotWidenWhenActionIsNotLaterallyWidenable} but driven through a real goal instead
     * of a direct {@code tryRegisterWorker} call.
     *
     * <p><b>Updated for the whole-branch review's Fix 3.</b> This test previously asserted
     * {@code canUse() == true} here, on the grounds that "canUse() only checks a project exists, not
     * capacity" - it was documenting, and locking in, the very bug Fix 3 removed. Holding
     * {MOVE, LOOK} at priority 6 while {@code start()}'s registration silently failed left
     * {@code tick()} a permanent no-op ({@code registeredProject == null}) AND starved
     * {@code AwaitFormationGoal} (8) / {@code FollowFlowFieldGoal} (9) of the MOVE flag - so the rat
     * did nothing at all instead of falling through to a goal that could act. {@code canUse()} now
     * mirrors {@code start()}'s real preconditions via {@code SiegeProject#canAcceptWorker} and must
     * decline outright, in the same tick, freeing the rat to fall through.
     *
     * <p>{@code canContinueToUse() == false} and {@code width == 1} are still asserted: they prove
     * the declining is because registration genuinely couldn't happen (no project registered, no
     * widen attempted), not because the goal simply failed to find a target.
     */
    @GameTest(template = "pathing_test", timeoutTicks = 200, skyAccess = true)
    public static void testBuildFlowFieldGoalDoesNotRegisterOnAFullNonWidenableProject(GameTestHelper helper) {
        BlockPos relativeAnchor = new BlockPos(4, 2, 4);
        BlockPos relativeTarget = relativeAnchor.above();
        BlockPos anchor = helper.absolutePos(relativeAnchor);
        BlockPos target = helper.absolutePos(relativeTarget);

        RecordingRegionMap owner = new RecordingRegionMap();
        FlowFieldState state = new FlowFieldState(anchor, Set.of(new ChunkPos(anchor)));
        state.updateInstructions(Map.of(anchor, new SiegeNode(target, SiegeNode.SiegeAction.BUILD_PILLAR)));

        TerrainEvaluator evaluator = new TerrainEvaluator();
        SiegeProjectManager projectManager = new SiegeProjectManager(evaluator);
        CalculationThrottler throttler = new CalculationThrottler();
        FlowFieldCalculator calculator = new FlowFieldCalculator(evaluator, projectManager, throttler);
        RegionFlowField flowField = new RegionFlowField(owner, 0, state, projectManager, calculator, throttler);

        List<SiegeNode> orderedSteps = List.of(new SiegeNode(target, SiegeNode.SiegeAction.BUILD_PILLAR));
        Map<BlockPos, SiegeNode> instructions = Map.of(target, new SiegeNode(anchor, SiegeNode.SiegeAction.BUILD_PILLAR));
        SiegeProject project = new SiegeProject(instructions, orderedSteps, anchor, target, 500);
        projectManager.addSharedConnectorProject(project);

        LiveTerrainAccess live = new LiveTerrainAccess(helper.getLevel());
        List<ClanratEntity> filler = new ArrayList<>();
        for (int i = 0; i < org.ratden.skavenblight.Config.maxProjectWorkers; i++) {
            ClanratEntity rat = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
            rat.setPos(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
            helper.getLevel().addFreshEntity(rat);
            filler.add(rat);
            project.tryRegisterWorker(rat, live, evaluator, org.ratden.skavenblight.Config.projectWorkRadius,
                    org.ratden.skavenblight.Config.maxProjectWorkers, org.ratden.skavenblight.Config.workersPerWidenStep);
        }

        ClanratEntity extraRat = new ClanratEntity(ModEntities.CLANRAT.get(), helper.getLevel());
        extraRat.setPos(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5);
        helper.getLevel().addFreshEntity(extraRat);

        BuildFlowFieldGoal goal = new BuildFlowFieldGoal(extraRat);
        goal.setFlowField(flowField);

        // Sanity: the ONLY reason canUse() may decline below is capacity. Confirm a target is
        // genuinely proposed here, so a false from canUse() can't be mistaken for "no work found".
        check(flowField.findProjectFor(target).isPresent(),
                "setup sanity: the BUILD_PILLAR target's owning project must be findable, or canUse()'s "
                        + "capacity check below isn't what's being observed");
        check(project.isAtCapacity(),
                "setup sanity: the project must actually be full, or there'd be nothing for canUse() to decline");

        check(!goal.canUse(),
                "canUse() must now decline a full, non-widenable project instead of taking the mob's MOVE "
                        + "flag and then silently failing to register in start() - see this method's javadoc");

        // start() is still driven, to prove the rejection is real rather than merely predicted: no
        // project gets registered and no widen is attempted, even when start() is called anyway.
        goal.start();

        check(!goal.canContinueToUse(),
                "registration should have been rejected (BUILD_PILLAR can't widen) - the goal should have no "
                        + "registered project to continue with");
        check(project.getWidth() == 1, "width must not have changed for a non-widenable action");

        helper.succeed();
    }
}
