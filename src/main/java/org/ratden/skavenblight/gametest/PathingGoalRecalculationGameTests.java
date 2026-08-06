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
import java.util.UUID;

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
        // BRIDGE, not AIR_STAIR: this target is purely horizontal from anchor (dy=0), matching
        // candidateSteps' own dy==0/open-air classification - see PathStepEvaluator's step-
        // generation spec. The hand-fed action here never goes through real classification (this
        // fixture bypasses it entirely), but keeping it consistent with what the real flood would
        // compute for this exact geometry avoids a misleading fixture.
        state.updateInstructions(Map.of(anchor, new FlowStep(target, PathAction.BRIDGE, anchor)));

        PathStepEvaluator evaluator = new PathStepEvaluator();
        SiegeProjectManager projectManager = new SiegeProjectManager(evaluator);
        CalculationThrottler throttler = new CalculationThrottler();
        FlowFieldCalculator calculator = new FlowFieldCalculator(evaluator, projectManager, throttler);
        RegionFlowField flowField = new RegionFlowField(owner, 0, state, projectManager, calculator, throttler);

        // Single-step, default-solid-floor BRIDGE project (same shape as
        // SiegeProjectAutoWidenGameTests/PathingGoalRecalculationGameTests' own widen tests) -
        // this test is about region-dirty-marking, not the no-prior-support scenario, so real
        // ground support keeps the setup minimal.
        List<FlowStep> orderedSteps = List.of(new FlowStep(target, PathAction.BRIDGE, anchor));
        Map<BlockPos, FlowStep> instructions = Map.of(target, new FlowStep(anchor, PathAction.BRIDGE, anchor));
        SiegeProject project = new SiegeProject(instructions, orderedSteps, anchor, target, 500, UUID.randomUUID());
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
        // worker count), so N calls inside one game tick bank exactly one tick's work. A BRIDGE
        // costs bridgeBaseCost = 600 by default, so one worker at workPerRatPerTick = 100 needs
        // ~6 game ticks - well inside this test's 200-tick budget.
        helper.succeedWhen(() -> {
            goal.tick();

            helper.assertBlockState(relativeTarget, s -> s.is(Blocks.COBBLESTONE),
                    () -> "BRIDGE should have placed cobblestone at " + relativeTarget);

            check(owner.changes.contains(target),
                    "region map was never told about the bridge placement at " + target
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
        // instruction map (consumed by goal-facing code via RegionFlowField#getNextStep) is keyed
        // by STANDING position with a forward-pointing FlowStep ("from here, go build at pos()")
        // - see AwaitFormationGoal's own inline comment on this exact convention. SiegeProject's
        // own `instructions` field (consumed by SiegeProjectManager.findProjectContaining via
        // getInstructions().containsKey(pos)) is keyed the OPPOSITE way in real production usage
        // (see SiegeProjectManager.evaluateSingleLine, whose output populates this field for every
        // real project): keyed by the BUILD/target position itself, with a backward-pointing
        // (predecessor) FlowStep - see SiegeProject#isCompleted's own doc on this convention.
        // Reusing the standing-position-keyed map for both would leave the project's own
        // instructions missing a key for chain[4] (only chain[0..3] are ever "from" positions), so
        // AbstractSiegeProjectGoal.canUse()'s own flowField.findProjectFor(node.pos()) call -
        // node.pos() is always the BUILD target - would fail to find this project for the final step.
        Map<BlockPos, FlowStep> stateInstructions = new HashMap<>();
        Map<BlockPos, FlowStep> projectInstructions = new HashMap<>();
        List<FlowStep> orderedSteps = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            BlockPos from = helper.absolutePos(relativeChain[i]);
            BlockPos to = helper.absolutePos(relativeChain[i + 1]);
            // dx=1,dy=1,dz=0 per hop - a straight one-axis rise into open air (no support), matching
            // candidateSteps' AIR_STAIR classification (see PathStepEvaluatorStepGenerationTest's
            // identical regression pin for this exact shape).
            stateInstructions.put(from, new FlowStep(to, PathAction.AIR_STAIR, from));
            projectInstructions.put(to, new FlowStep(from, PathAction.AIR_STAIR, from));
            orderedSteps.add(new FlowStep(to, PathAction.AIR_STAIR, from));
        }
        state.updateInstructions(stateInstructions);

        PathStepEvaluator evaluator = new PathStepEvaluator();
        SiegeProjectManager projectManager = new SiegeProjectManager(evaluator);
        CalculationThrottler throttler = new CalculationThrottler();
        FlowFieldCalculator calculator = new FlowFieldCalculator(evaluator, projectManager, throttler);
        RegionFlowField flowField = new RegionFlowField(owner, 0, state, projectManager, calculator, throttler);

        BlockPos chainEndPos = helper.absolutePos(relativeChain[4]);
        SiegeProject project = new SiegeProject(projectInstructions, orderedSteps, startPos, chainEndPos, 500, UUID.randomUUID());
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

        // One goal.tick() per REAL game tick, four steps back to back: an AIR_STAIR costs
        // airStairBaseCost = 1000 by default, so a single worker at workPerRatPerTick = 100
        // needs ~10 game ticks per step, ~40 for the whole chain - inside this test's 400-tick
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
     * this file already established elsewhere: a real
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

        PathStepEvaluator evaluator = new PathStepEvaluator();
        LiveTerrainAccess terrain = new LiveTerrainAccess(helper.getLevel());
        // Unconstrained FlowFieldState (2-arg constructor: no cellFilter, empty territoryChunks =
        // "Global scope") - see method javadoc for why this is deliberate.
        FlowFieldState state = new FlowFieldState(anchorPos, Set.of());

        Map<BlockPos, Integer> uncappedCostMap = new HashMap<>();
        Map<BlockPos, FlowStep> uncappedInstructionMap = new HashMap<>();
        PriorityQueue<FlowFieldCalculator.QueueNode> uncappedQueue = new PriorityQueue<>();

        SiegeProjectManager uncapped = new SiegeProjectManager(evaluator);
        uncapped.evaluateMacroProjects(terrain, anchorPos, state, 0, uncappedQueue, uncappedCostMap, uncappedInstructionMap);

        check(uncappedInstructionMap.containsKey(realLanding),
                "uncapped (default 32) macro-project search should have reached the real landing 11 blocks east at "
                        + realLanding + " - found instructions at: " + uncappedInstructionMap.keySet());
        check(uncappedInstructionMap.get(realLanding).action() == PathAction.WALK,
                "the far landing should be the REAL walkable ground the trace found (action WALK), not a synthetic "
                        + "one - found: " + uncappedInstructionMap.get(realLanding));

        Map<BlockPos, Integer> cappedCostMap = new HashMap<>();
        Map<BlockPos, FlowStep> cappedInstructionMap = new HashMap<>();
        PriorityQueue<FlowFieldCalculator.QueueNode> cappedQueue = new PriorityQueue<>();

        // A fresh SiegeProjectManager - setMaxCandidateProjectLength is the one thing under test.
        SiegeProjectManager capped = new SiegeProjectManager(evaluator);
        capped.setMaxCandidateProjectLength(6);
        capped.evaluateMacroProjects(terrain, anchorPos, state, 0, cappedQueue, cappedCostMap, cappedInstructionMap);

        check(!cappedInstructionMap.containsKey(realLanding),
                "setMaxCandidateProjectLength(6) should have stopped the east-bound trace before reaching the real "
                        + "landing 11 blocks east - found instructions at: " + cappedInstructionMap.keySet());
        // No synthetic BUILD_LANDING anymore (that action type is gone - see
        // SiegeProjectManager.evaluateSingleLine's own doc): hops run 1..maxCandidateProjectLength,
        // so the cap-exhaustion terminus is a REAL BRIDGE step, one cell short of where the old
        // synthetic landing would have sat.
        FlowStep cappedNode = cappedInstructionMap.get(cappedLanding);
        check(cappedNode != null && cappedNode.action() == PathAction.BRIDGE,
                "setMaxCandidateProjectLength(6) should have terminated the east-bound trace with a real BRIDGE "
                        + "step exactly 6 blocks out at " + cappedLanding + " (found: " + cappedNode + ")");
        check(!cappedInstructionMap.containsKey(helper.absolutePos(relativeAnchor.offset(7, 0, 0))),
                "with no synthetic marker to key on, confirming hop 7 is entirely absent is what actually pins "
                        + "the 6-hop cap now");

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

        PathStepEvaluator evaluator = new PathStepEvaluator();
        LiveTerrainAccess terrain = new LiveTerrainAccess(helper.getLevel());
        FlowFieldState state = new FlowFieldState(anchorPos, Set.of());

        Map<BlockPos, Integer> costMap = new HashMap<>();
        Map<BlockPos, FlowStep> instructionMap = new HashMap<>();
        PriorityQueue<FlowFieldCalculator.QueueNode> queue = new PriorityQueue<>();

        // One shared manager across BOTH calls - mirrors how FlowFieldCalculator's own Dijkstra
        // loop reuses a single SiegeProjectManager instance across every evaluateMacroProjects
        // call within one region-scoped pass (see FlowFieldCalculator.calculate's hitObstacle
        // branch), which is exactly the scenario this test exercises.
        SiegeProjectManager manager = new SiegeProjectManager(evaluator);
        manager.setMaxCandidateProjectLength(6);

        manager.evaluateMacroProjects(terrain, anchorPos, state, 0, queue, costMap, instructionMap);

        // No synthetic BUILD_LANDING anymore - see testMaxCandidateProjectLengthCapsMacroProjectReach's
        // own comment. The cap-exhaustion terminus is a real BRIDGE step.
        FlowStep firstLandingNode = instructionMap.get(firstCappedLanding);
        check(firstLandingNode != null && firstLandingNode.action() == PathAction.BRIDGE,
                "first call should cap at 6 blocks east with a real BRIDGE step at " + firstCappedLanding
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
        state.updateInstructions(Map.of(anchor, new FlowStep(target, PathAction.BRIDGE, anchor)));

        PathStepEvaluator evaluator = new PathStepEvaluator();
        SiegeProjectManager projectManager = new SiegeProjectManager(evaluator);
        CalculationThrottler throttler = new CalculationThrottler();
        FlowFieldCalculator calculator = new FlowFieldCalculator(evaluator, projectManager, throttler);
        RegionFlowField flowField = new RegionFlowField(owner, 0, state, projectManager, calculator, throttler);

        List<FlowStep> orderedSteps = List.of(new FlowStep(target, PathAction.BRIDGE, anchor));
        Map<BlockPos, FlowStep> instructions = Map.of(target, new FlowStep(anchor, PathAction.BRIDGE, anchor));
        SiegeProject project = new SiegeProject(instructions, orderedSteps, anchor, target, 500, UUID.randomUUID());
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
     * Counterpart to {@link #testBuildFlowFieldGoalRegistersOnAWidenedLane}, driven through a real
     * goal instead of a direct {@code tryRegisterWorker} call - mirrors {@code
     * SiegeProjectAutoWidenGameTests#testDoesNotWidenWhenPerpendicularLaneIsAlreadyWalkable}.
     *
     * <p><b>Re-anchored (2026-08-05, per advisor review): the original premise ("BUILD_PILLAR is
     * not laterally widenable") no longer exists.</b> {@code SiegeProject#effectiveCapFor}'s new
     * rule is {@code action != PathAction.WALK} - EVERY construction action widens now (see that
     * method's own javadoc: "every action is parallel-lane-capable now, not just two of the old
     * five"), and pure-vertical steps like the old BUILD_PILLAR no longer exist as a PathAction at
     * all. The real, still-live "can't widen" path {@code isWidenEligible} still guards is the
     * perpendicular trace itself finding nothing to build (see {@code SiegeProject#tryWiden} /
     * {@code #traceChainedHops}'s "returns empty when the first hop is already walkable" case,
     * unit-tested directly in {@code SiegeProjectTest
     * #traceChainedHopsReturnsEmptyWhenTheFirstHopIsAlreadyWalkable}) - forced here via explicit
     * terrain control rather than guessed from the template's ambient geometry.
     *
     * <p><b>Preserved from the whole-branch review's Fix 3.</b> This test previously asserted
     * {@code canUse() == true} here, on the grounds that "canUse() only checks a project exists, not
     * capacity" - it was documenting, and locking in, the very bug Fix 3 removed. Holding
     * {MOVE, LOOK} at priority 6 while {@code start()}'s registration silently failed left
     * {@code tick()} a permanent no-op ({@code registeredProject == null}) AND starved
     * {@code AwaitFormationGoal} (8) / {@code FollowFlowFieldGoal} (9) of the MOVE flag - so the rat
     * did nothing at all instead of falling through to a goal that could act. {@code canUse()} now
     * mirrors {@code start()}'s real preconditions via {@code SiegeProject#canAcceptWorker} and must
     * decline outright, in the same tick, freeing the rat to fall through.
     *
     * <p><b>Correction (2026-08-06, per advisor review): {@code canUse()} does NOT decline here.</b>
     * The previous version of this test asserted {@code !canUse()}, but {@code
     * SiegeProject#canAcceptWorker} deliberately answers TRUE for any over-cap-but-widen-eligible
     * project without running the real trace (see that method's own javadoc: "attempting it here
     * would turn a demand-bounded retry into a per-rat-per-tick trace... a widen-eligible project
     * whose trace actually fails still reports true here and then fails in start()"). This fixture is
     * exactly that one documented exception - {@code isWidenEligible} only checks
     * {@code action != WALK} and remaining width headroom, not whether the perpendicular lane has
     * anything left to build. So {@code canUse()} is asserted TRUE, and the real rejection is proven
     * where it actually happens: {@code start()} runs {@link SiegeProject#tryRegisterWorker}'s real
     * chained-hop trace, which fails, leaving no project registered - {@code canContinueToUse() ==
     * false} and {@code width == 1} are what prove that.
     */
    @GameTest(template = "pathing_test", timeoutTicks = 200, skyAccess = true)
    public static void testBuildFlowFieldGoalDoesNotRegisterWhenWidenLaneIsAlreadyWalkable(GameTestHelper helper) {
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

        RecordingRegionMap owner = new RecordingRegionMap();
        FlowFieldState state = new FlowFieldState(anchor, Set.of(new ChunkPos(anchor)));
        state.updateInstructions(Map.of(anchor, new FlowStep(target, PathAction.BRIDGE, anchor)));

        PathStepEvaluator evaluator = new PathStepEvaluator();
        SiegeProjectManager projectManager = new SiegeProjectManager(evaluator);
        CalculationThrottler throttler = new CalculationThrottler();
        FlowFieldCalculator calculator = new FlowFieldCalculator(evaluator, projectManager, throttler);
        RegionFlowField flowField = new RegionFlowField(owner, 0, state, projectManager, calculator, throttler);

        List<FlowStep> orderedSteps = List.of(new FlowStep(target, PathAction.BRIDGE, anchor));
        Map<BlockPos, FlowStep> instructions = Map.of(target, new FlowStep(anchor, PathAction.BRIDGE, anchor));
        SiegeProject project = new SiegeProject(instructions, orderedSteps, anchor, target, 500, UUID.randomUUID());
        projectManager.addSharedConnectorProject(project);

        LiveTerrainAccess live = new LiveTerrainAccess(helper.getLevel());
        // Setup sanity: confirm the widen target really is walkable before asserting on anything
        // downstream, or a wrong offset computation above would make this test pass for the wrong
        // reason.
        check(evaluator.isWalkableTerrain(live, helper.absolutePos(relativeWidenHop)),
                "setup sanity: the widen lane's first hop must be genuinely walkable, or this test proves nothing");

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

        // Setup sanity: confirm the project is genuinely full and findable, so the assertions below
        // are known to be exercising the over-cap path rather than "no work found".
        check(flowField.findProjectFor(target).isPresent(),
                "setup sanity: the target's owning project must be findable");
        check(project.isAtCapacity(), "setup sanity: the project must actually be full");

        // canUse() is TRUE here - see this method's own javadoc correction. canAcceptWorker
        // deliberately doesn't run the real trace, so an over-cap-but-nominally-widen-eligible
        // project always reports true, even in this exact scenario where the trace will fail.
        check(goal.canUse(),
                "canUse() is documented to answer true for a widen-eligible project without running "
                        + "the real trace (see SiegeProject#canAcceptWorker) - this fixture's rejection "
                        + "happens in start(), not canUse()");

        // start() runs the REAL trace via tryRegisterWorker/tryWiden, which fails because the
        // perpendicular lane's first hop is already walkable ground - nothing gets registered.
        goal.start();

        check(!goal.canContinueToUse(),
                "registration should have been rejected (nothing to widen into) - the goal should have no "
                        + "registered project to continue with");
        check(project.getWidth() == 1, "width must not have changed when there was nothing to widen into");

        helper.succeed();
    }
}
