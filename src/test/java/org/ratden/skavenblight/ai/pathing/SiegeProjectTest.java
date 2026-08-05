package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SiegeProjectTest {

    @Test
    void planStepsComputesFacingFromAnchorForFirstStep() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        List<FlowStep> orderedSteps = List.of(
                new FlowStep(new BlockPos(1, 65, 0), PathAction.AIR_STAIR, anchor)
        );

        List<PlannedStep> planned = SiegeProject.planSteps(orderedSteps, anchor);

        assertEquals(1, planned.size());
        assertEquals(new BlockPos(1, 65, 0), planned.get(0).pos());
        assertEquals(PathAction.AIR_STAIR, planned.get(0).action());
        assertEquals(Direction.EAST, planned.get(0).facing());
    }

    @Test
    void planStepsComputesFacingFromPredecessorForLaterSteps() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        List<FlowStep> orderedSteps = List.of(
                new FlowStep(new BlockPos(1, 65, 0), PathAction.AIR_STAIR, anchor),
                new FlowStep(new BlockPos(3, 66, -2), PathAction.AIR_STAIR, new BlockPos(1, 65, 0))
        );

        List<PlannedStep> planned = SiegeProject.planSteps(orderedSteps, anchor);

        assertEquals(Direction.EAST, planned.get(0).facing());
        // step 1's coordinates are deliberately chosen so the anchor and the true predecessor
        // (step 0) disagree on the dominant axis, not just the sign: from anchor (0,64,0) ->
        // (3,66,-2), dx=3/dz=-2, |dx|>|dz| -> EAST. From the real predecessor (1,65,0) ->
        // (3,66,-2), dx=2/dz=-2, a TIE falls through to the dz branch -> NORTH. A mutation that
        // computed facing from the anchor for every step (instead of updating `previous` per
        // step) would produce EAST here and get caught; the previous coordinates happened to
        // agree on NORTH from either reference point, so that mutation went undetected.
        assertEquals(Direction.NORTH, planned.get(1).facing());
    }

    @Test
    void planStepsPreservesPosAndActionUnchanged() {
        BlockPos anchor = new BlockPos(5, 5, 5);
        FlowStep step = new FlowStep(new BlockPos(5, 4, 5), PathAction.TUNNEL, anchor);

        List<PlannedStep> planned = SiegeProject.planSteps(List.of(step), anchor);

        assertEquals(step.pos(), planned.get(0).pos());
        assertEquals(step.action(), planned.get(0).action());
    }

    @Test
    void effectiveCapForConstructionActionsScalesWithWidth() {
        assertEquals(10, SiegeProject.effectiveCapFor(PathAction.AIR_STAIR, 1, 4, 10));
        assertEquals(30, SiegeProject.effectiveCapFor(PathAction.AIR_STAIR, 3, 4, 10));
        assertEquals(20, SiegeProject.effectiveCapFor(PathAction.BRIDGE, 2, 4, 10));
        assertEquals(10, SiegeProject.effectiveCapFor(PathAction.TUNNEL, 1, 4, 10));
        assertEquals(10, SiegeProject.effectiveCapFor(PathAction.CARVED_STAIR, 1, 4, 10));
    }

    @Test
    void effectiveCapForWalkIgnoresWidth() {
        // WALK is the only PathAction that never reaches effectiveCapFor in practice
        // (nextUnbuiltInstruction skips WALK steps before any caller passes an action in), but the
        // method itself must still ignore width for it, matching every other flat-cap case.
        assertEquals(4, SiegeProject.effectiveCapFor(PathAction.WALK, 3, 4, 10));
    }

    @Test
    void constructorAcceptsOrderedStepsAndAnchorWithoutError() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        BlockPos entryPos = new BlockPos(1, 65, 0);
        List<FlowStep> orderedSteps = List.of(new FlowStep(entryPos, PathAction.AIR_STAIR, anchor));
        Map<BlockPos, FlowStep> instructions = Map.of(entryPos, new FlowStep(anchor, PathAction.AIR_STAIR, anchor));

        SiegeProject project = new SiegeProject(instructions, orderedSteps, anchor, entryPos, 500, UUID.randomUUID());

        assertEquals(entryPos, project.getEntryPos());
        assertEquals(instructions, project.getInstructions());
    }

    @Test
    void everyProjectGetsAStableIdOnConstruction() {
        SiegeProject project = new SiegeProject(Map.of(), List.of(), BlockPos.ZERO, BlockPos.ZERO, 0, UUID.randomUUID());

        assertNotNull(project.getId());
    }

    @Test
    void constructorStoresPlatformPositionsFromTheBuildOrder() {
        BlockPos anchor = new BlockPos(0, 10, 0);
        BlockPos tunnelStep = new BlockPos(1, 10, 0);
        BlockPos bridgeStep = new BlockPos(2, 10, 0);
        List<FlowStep> orderedSteps = List.of(
                new FlowStep(tunnelStep, PathAction.TUNNEL, anchor),
                new FlowStep(bridgeStep, PathAction.BRIDGE, tunnelStep));
        Map<BlockPos, FlowStep> instructions = Map.of(
                tunnelStep, new FlowStep(tunnelStep, PathAction.TUNNEL, anchor),
                bridgeStep, new FlowStep(bridgeStep, PathAction.BRIDGE, tunnelStep));

        SiegeProject project = new SiegeProject(instructions, orderedSteps, anchor, bridgeStep, 100, UUID.randomUUID());

        assertEquals(Set.of(bridgeStep), project.getPlatformPositions());
    }

    /** Minimal fake so pure registration/cap logic can run with no real ServerLevel. Every
     * position not explicitly set reports as open air, i.e. every action is "not yet built". */
    private static class FakeTerrain implements TerrainAccess {
        private final Map<BlockPos, BlockState> states = new java.util.HashMap<>();

        void set(BlockPos pos, BlockState state) { states.put(pos, state); }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }

        @Override
        public boolean isLoaded(BlockPos pos) { return true; }

        @Override
        public boolean isOutsideBuildHeight(BlockPos pos) { return false; }

        @Override
        public boolean isSolidRender(BlockPos pos) { return getBlockState(pos).blocksMotion(); }

        @Override
        public float getDestroySpeed(BlockPos pos) { return 1.0F; }
    }

    private static SiegeProject freshSingleStepProject(BlockPos anchor, BlockPos target, PathAction action) {
        List<FlowStep> orderedSteps = List.of(new FlowStep(target, action, anchor));
        Map<BlockPos, FlowStep> instructions = Map.of(target, new FlowStep(anchor, action, anchor));
        return new SiegeProject(instructions, orderedSteps, anchor, target, 500, UUID.randomUUID());
    }

    @Test
    void nextUnbuiltInstructionReturnsFirstIncompleteStep() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        BlockPos target = new BlockPos(1, 64, 0);
        SiegeProject project = freshSingleStepProject(anchor, target, PathAction.BRIDGE);
        PathStepEvaluator evaluator = new PathStepEvaluator();
        FakeTerrain terrain = new FakeTerrain();

        assertTrue(project.nextUnbuiltInstruction(terrain, evaluator).isPresent());
        assertEquals(target, project.nextUnbuiltInstruction(terrain, evaluator).get().pos());
    }

    @Test
    void nextUnbuiltInstructionEmptyOnceBuilt() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        BlockPos target = new BlockPos(1, 64, 0);
        SiegeProject project = freshSingleStepProject(anchor, target, PathAction.BRIDGE);
        PathStepEvaluator evaluator = new PathStepEvaluator();
        FakeTerrain terrain = new FakeTerrain();
        terrain.set(target, Blocks.COBBLESTONE.defaultBlockState());

        assertTrue(project.nextUnbuiltInstruction(terrain, evaluator).isEmpty());
    }

    @Test
    void nextUnbuiltInstructionStopsAtAnIncompleteTunnelStep() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        BlockPos tunnelPos = new BlockPos(1, 64, 0);
        BlockPos buildPos = new BlockPos(2, 64, 0);
        List<FlowStep> orderedSteps = List.of(
                new FlowStep(tunnelPos, PathAction.TUNNEL, anchor),
                new FlowStep(buildPos, PathAction.BRIDGE, tunnelPos)
        );
        Map<BlockPos, FlowStep> instructions = Map.of(
                tunnelPos, new FlowStep(anchor, PathAction.TUNNEL, anchor),
                buildPos, new FlowStep(tunnelPos, PathAction.BRIDGE, tunnelPos)
        );
        SiegeProject project = new SiegeProject(instructions, orderedSteps, anchor, buildPos, 500, UUID.randomUUID());
        PathStepEvaluator evaluator = new PathStepEvaluator();
        FakeTerrain terrain = new FakeTerrain();
        terrain.set(tunnelPos, Blocks.STONE.defaultBlockState()); // not yet mined through

        // Blocked on the still-solid TUNNEL step (handled by the old per-rat SmartBreachGoal path,
        // untouched by this overhaul) - must not skip ahead to the BRIDGE step past it.
        assertTrue(project.nextUnbuiltInstruction(terrain, evaluator).isEmpty());
    }

    /**
     * Mirror case to {@link #nextUnbuiltInstructionStopsAtAnIncompleteTunnelStep}: once
     * SmartBreachGoal has actually cleared the TUNNEL step in the real world, the project must
     * CONTINUE past it to the next real build step - not keep reporting empty forever.
     */
    @Test
    void nextUnbuiltInstructionContinuesPastAnAlreadyClearedTunnelStep() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        BlockPos tunnelPos = new BlockPos(1, 64, 0);
        BlockPos buildPos = new BlockPos(2, 64, 0);
        List<FlowStep> orderedSteps = List.of(
                new FlowStep(tunnelPos, PathAction.TUNNEL, anchor),
                new FlowStep(buildPos, PathAction.BRIDGE, tunnelPos)
        );
        Map<BlockPos, FlowStep> instructions = Map.of(
                tunnelPos, new FlowStep(anchor, PathAction.TUNNEL, anchor),
                buildPos, new FlowStep(tunnelPos, PathAction.BRIDGE, tunnelPos)
        );
        SiegeProject project = new SiegeProject(instructions, orderedSteps, anchor, buildPos, 500, UUID.randomUUID());
        PathStepEvaluator evaluator = new PathStepEvaluator();
        // tunnelPos deliberately left unset: FakeTerrain reports air for it AND for its head/ceiling
        // cells, which is exactly what PathStepEvaluator.isActionCompleted requires of a completed
        // TUNNEL (see its own TUNNEL/CARVED_STAIR case - all three cells must be open).
        FakeTerrain terrain = new FakeTerrain();

        assertTrue(evaluator.isActionCompleted(terrain, tunnelPos, PathAction.TUNNEL),
                "setup sanity: the TUNNEL step must read as already cleared, or this test proves nothing");

        java.util.Optional<PlannedStep> next = project.nextUnbuiltInstruction(terrain, evaluator);

        assertTrue(next.isPresent(), "a cleared TUNNEL step must not block the rest of the build order");
        assertEquals(buildPos, next.get().pos());
        assertEquals(PathAction.BRIDGE, next.get().action());
    }

    @Test
    void isAtCapacityFalseBeforeAnyRegistration() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        BlockPos target = new BlockPos(1, 64, 0);
        SiegeProject project = freshSingleStepProject(anchor, target, PathAction.AIR_STAIR);

        assertFalse(project.isAtCapacity());
    }

    // The three tests below cover isCompleted()/getRemainingInstructions() checking the wrong
    // cell. `instructions` is keyed by the REAL position (target) but its stored FlowStep value
    // carries the PREDECESSOR position (anchor) in this fixture, deliberately mismatched from the
    // key - the same "map-key vs stored-value position" bug FlowStep's own design fixes at the
    // type level (see FlowStep's own doc), but isCompleted/getRemainingInstructions must still
    // check entry.getKey(), never entry.getValue().pos(), since nothing enforces that every real
    // caller constructs FlowStep with pos()==key. Each test sets terrain so the two cells
    // disagree, proving the fix changes which cell gets checked rather than coincidentally
    // agreeing.

    @Test
    void isCompletedTrueOnceAirStairIsBuiltAtTheRealPositionDespiteAnUnbuiltPredecessor() {
        BlockPos anchor = new BlockPos(0, 64, 0); // predecessor - left open air, unbuilt
        BlockPos target = new BlockPos(1, 65, 0); // real position - the instructions map's KEY
        SiegeProject project = freshSingleStepProject(anchor, target, PathAction.AIR_STAIR);
        PathStepEvaluator evaluator = new PathStepEvaluator();
        FakeTerrain terrain = new FakeTerrain();
        terrain.set(target, Blocks.COBBLESTONE_STAIRS.defaultBlockState());

        assertTrue(project.isCompleted(terrain, evaluator),
                "the built stair sits at target (the map key) - checking anchor (the predecessor-"
                        + "position bug) would wrongly see unbuilt open air and report incomplete");
        assertTrue(project.getRemainingInstructions(terrain, evaluator).isEmpty());
    }

    @Test
    void isCompletedFalseWhenTunnelStepsRealPositionIsStillSolidDespiteAnOpenPredecessor() {
        BlockPos anchor = new BlockPos(0, 64, 0); // predecessor - left open air (already "cleared")
        BlockPos target = new BlockPos(1, 64, 0); // real position - the obstacle that still needs mining
        SiegeProject project = freshSingleStepProject(anchor, target, PathAction.TUNNEL);
        PathStepEvaluator evaluator = new PathStepEvaluator();
        FakeTerrain terrain = new FakeTerrain();
        terrain.set(target, Blocks.STONE.defaultBlockState()); // not yet mined through

        assertFalse(project.isCompleted(terrain, evaluator),
                "target is still solid rock - checking anchor instead (the predecessor-position "
                        + "bug) would see it as already-open air and wrongly report this TUNNEL step done");
        assertEquals(Set.of(target), project.getRemainingInstructions(terrain, evaluator).keySet());
    }

    @Test
    void isCompletedTrueOnceWalkTargetIsWalkableDespiteASolidPredecessor() {
        BlockPos anchor = new BlockPos(0, 64, 0); // predecessor - solid, not itself walkable
        BlockPos target = new BlockPos(1, 64, 0); // real position - open ground, genuinely walkable
        SiegeProject project = freshSingleStepProject(anchor, target, PathAction.WALK);
        PathStepEvaluator evaluator = new PathStepEvaluator();
        FakeTerrain terrain = new FakeTerrain();
        terrain.set(anchor, Blocks.STONE.defaultBlockState());
        terrain.set(target.below(), Blocks.STONE.defaultBlockState()); // support for target itself

        assertTrue(evaluator.isWalkableTerrain(terrain, target),
                "setup sanity: target must actually be walkable, or this test proves nothing");

        assertTrue(project.isCompleted(terrain, evaluator),
                "target is genuinely walkable - checking anchor instead (the predecessor-position "
                        + "bug) would see solid rock and wrongly report this WALK step incomplete forever");
        assertTrue(project.getRemainingInstructions(terrain, evaluator).isEmpty());
    }

    // traceChainedHops replaces SiegeLineTracer.trace for tryWiden - package-private/static
    // specifically so this coverage doesn't need a real Mob (tryWiden is only reachable through
    // tryRegisterWorker(Mob, ...), and constructing a real Mob needs a bootstrapped level).

    @Test
    void traceChainedHopsCollectsConstructionStepsUntilItReachesWalkableGround() {
        BlockPos start = new BlockPos(0, 64, 0);
        PathStepEvaluator evaluator = new PathStepEvaluator();
        FakeTerrain terrain = new FakeTerrain();
        BlockPos walkableAt3 = new BlockPos(3, 64, 0);
        terrain.set(walkableAt3.below(), Blocks.STONE.defaultBlockState()); // real support - genuinely walkable

        // Setup sanity: this test's whole premise is that (1,64,0)/(2,64,0) evaluate as BRIDGE and
        // (3,64,0) evaluates as WALK on this exact fixture - confirm both directly, or a change to
        // candidateSteps' BRIDGE/WALK classification could silently invalidate this test's assertions
        // below without ever failing them for the reason intended.
        assertTrue(evaluator.isWalkableTerrain(terrain, walkableAt3),
                "setup sanity: (3,64,0) must evaluate as walkable, or this test proves nothing");
        List<PathStepEvaluator.EvaluatedStep> stepsFromStart = evaluator.candidateSteps(
                terrain, start, Set.of(), pos -> false);
        assertEquals(PathAction.BRIDGE, stepsFromStart.stream()
                        .filter(s -> s.pos().equals(new BlockPos(1, 64, 0))).findFirst()
                        .orElseThrow(() -> new AssertionError("setup sanity: expected a candidate step at (1,64,0)"))
                        .action(),
                "setup sanity: (1,64,0) must evaluate as BRIDGE, or this test proves nothing");

        List<FlowStep> traced = SiegeProject.traceChainedHops(terrain, evaluator, start, 1, 0, 0, 10);

        // (1,64,0) and (2,64,0) are open air with no support - BRIDGE candidates; (3,64,0) is
        // genuinely walkable and terminates the trace, so it must NOT appear in the result.
        assertEquals(List.of(new BlockPos(1, 64, 0), new BlockPos(2, 64, 0)),
                traced.stream().map(FlowStep::pos).toList());
        assertTrue(traced.stream().allMatch(s -> s.action() == PathAction.BRIDGE));
    }

    @Test
    void traceChainedHopsSucceedsEvenWhenMaxHopsIsExhaustedWithoutReachingWalkableGround() {
        // Matches old SiegeLineTracer.trace's own contract: hitting its length cap returned
        // completed=true (a synthetic BUILD_LANDING appended) rather than failing - cap exhaustion
        // was never a trace failure, only genuine aborts (out-of-bounds/invalid) were. This proves
        // the replacement preserves that success/failure split even with the landing gone.
        BlockPos start = new BlockPos(0, 64, 0);
        PathStepEvaluator evaluator = new PathStepEvaluator();
        FakeTerrain terrain = new FakeTerrain(); // nothing walkable anywhere within reach

        // Setup sanity: this test relies on every hop from (1,64,0) through (3,64,0) evaluating as a
        // real (non-WALK) candidate, or exhausting maxHops would coincidentally look identical to
        // hitting walkable ground early - confirm the fixture is genuinely all-construction first.
        for (int x = 1; x <= 3; x++) {
            BlockPos hop = new BlockPos(x, 64, 0);
            assertFalse(evaluator.isWalkableTerrain(terrain, hop),
                    "setup sanity: (" + x + ",64,0) must NOT be walkable, or this test proves nothing");
        }

        List<FlowStep> traced = SiegeProject.traceChainedHops(terrain, evaluator, start, 1, 0, 0, 3);

        assertEquals(3, traced.size(), "exhausting maxHops without reaching walkable ground must "
                + "still return the steps collected so far, not null");
    }

    @Test
    void traceChainedHopsReturnsEmptyWhenTheFirstHopIsAlreadyWalkable() {
        BlockPos start = new BlockPos(0, 64, 0);
        PathStepEvaluator evaluator = new PathStepEvaluator();
        FakeTerrain terrain = new FakeTerrain();
        BlockPos firstHop = new BlockPos(1, 64, 0);
        terrain.set(firstHop.below(), Blocks.STONE.defaultBlockState());

        // Setup sanity: confirm the very first hop reads as walkable on this fixture, or an empty
        // result here wouldn't actually be exercising the "already walkable" early-return at all.
        assertTrue(evaluator.isWalkableTerrain(terrain, firstHop),
                "setup sanity: (1,64,0) must evaluate as walkable, or this test proves nothing");

        List<FlowStep> traced = SiegeProject.traceChainedHops(terrain, evaluator, start, 1, 0, 0, 10);

        assertTrue(traced.isEmpty(), "no widening is needed when the very first hop is already walkable");
    }
}
