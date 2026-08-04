package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.Mob;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SiegeProjectTest {

    @Test
    void planStepsComputesFacingFromAnchorForFirstStep() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        List<SiegeNode> orderedSteps = List.of(
                new SiegeNode(new BlockPos(1, 65, 0), SiegeNode.SiegeAction.BUILD_STAIR)
        );

        List<SiegeProject.PlannedStep> planned = SiegeProject.planSteps(orderedSteps, anchor);

        assertEquals(1, planned.size());
        assertEquals(new BlockPos(1, 65, 0), planned.get(0).pos());
        assertEquals(SiegeNode.SiegeAction.BUILD_STAIR, planned.get(0).action());
        assertEquals(Direction.EAST, planned.get(0).facing());
    }

    @Test
    void planStepsComputesFacingFromPredecessorForLaterSteps() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        List<SiegeNode> orderedSteps = List.of(
                new SiegeNode(new BlockPos(1, 65, 0), SiegeNode.SiegeAction.BUILD_STAIR),
                new SiegeNode(new BlockPos(3, 66, -2), SiegeNode.SiegeAction.BUILD_STAIR)
        );

        List<SiegeProject.PlannedStep> planned = SiegeProject.planSteps(orderedSteps, anchor);

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
        SiegeNode step = new SiegeNode(new BlockPos(5, 4, 5), SiegeNode.SiegeAction.BUILD_PILLAR);

        List<SiegeProject.PlannedStep> planned = SiegeProject.planSteps(List.of(step), anchor);

        assertEquals(step.pos(), planned.get(0).pos());
        assertEquals(step.action(), planned.get(0).action());
    }

    // The three countCompletable tests that used to sit here were deleted alongside the method
    // itself: Task 4 reimplemented its stop-at-the-first-uncoverable-cost semantics inline in
    // SiegeProject.tick()'s own placement loop and never called countCompletable, so it had zero
    // production callers and these tests only ever exercised dead code. tick()'s loop is what
    // covers that behavior now (see SiegeProjectTickGameTests).

    @Test
    void effectiveCapForBuildStairScalesWithWidth() {
        assertEquals(10, SiegeProject.effectiveCapFor(SiegeNode.SiegeAction.BUILD_STAIR, 1, 4, 10));
        assertEquals(30, SiegeProject.effectiveCapFor(SiegeNode.SiegeAction.BUILD_STAIR, 3, 4, 10));
    }

    @Test
    void effectiveCapForBuildBridgeScalesWithWidth() {
        assertEquals(20, SiegeProject.effectiveCapFor(SiegeNode.SiegeAction.BUILD_BRIDGE, 2, 4, 10));
    }

    @Test
    void effectiveCapForNonWidenableActionIgnoresWidth() {
        assertEquals(4, SiegeProject.effectiveCapFor(SiegeNode.SiegeAction.BUILD_PILLAR, 3, 4, 10));
        assertEquals(4, SiegeProject.effectiveCapFor(SiegeNode.SiegeAction.BUILD_LANDING, 1, 4, 10));
    }

    @Test
    void constructorAcceptsOrderedStepsAndAnchorWithoutError() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        BlockPos entryPos = new BlockPos(1, 65, 0);
        List<SiegeNode> orderedSteps = List.of(new SiegeNode(entryPos, SiegeNode.SiegeAction.BUILD_STAIR));
        Map<BlockPos, SiegeNode> instructions = Map.of(entryPos, new SiegeNode(anchor, SiegeNode.SiegeAction.BUILD_STAIR));

        SiegeProject project = new SiegeProject(instructions, orderedSteps, anchor, entryPos, 500);

        assertEquals(entryPos, project.getEntryPos());
        assertEquals(instructions, project.getInstructions());
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

    private static SiegeProject freshSingleStepProject(BlockPos anchor, BlockPos target, SiegeNode.SiegeAction action) {
        List<SiegeNode> orderedSteps = List.of(new SiegeNode(target, action));
        Map<BlockPos, SiegeNode> instructions = Map.of(target, new SiegeNode(anchor, action));
        return new SiegeProject(instructions, orderedSteps, anchor, target, 500);
    }

    @Test
    void nextUnbuiltInstructionReturnsFirstIncompleteStep() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        BlockPos target = new BlockPos(1, 64, 0);
        SiegeProject project = freshSingleStepProject(anchor, target, SiegeNode.SiegeAction.BUILD_BRIDGE);
        TerrainEvaluator evaluator = new TerrainEvaluator();
        FakeTerrain terrain = new FakeTerrain();

        assertTrue(project.nextUnbuiltInstruction(terrain, evaluator).isPresent());
        assertEquals(target, project.nextUnbuiltInstruction(terrain, evaluator).get().pos());
    }

    @Test
    void nextUnbuiltInstructionEmptyOnceBuilt() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        BlockPos target = new BlockPos(1, 64, 0);
        SiegeProject project = freshSingleStepProject(anchor, target, SiegeNode.SiegeAction.BUILD_BRIDGE);
        TerrainEvaluator evaluator = new TerrainEvaluator();
        FakeTerrain terrain = new FakeTerrain();
        terrain.set(target, Blocks.COBBLESTONE.defaultBlockState());

        assertTrue(project.nextUnbuiltInstruction(terrain, evaluator).isEmpty());
    }

    @Test
    void nextUnbuiltInstructionStopsAtAnIncompleteMineStep() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        BlockPos minePos = new BlockPos(1, 64, 0);
        BlockPos buildPos = new BlockPos(2, 64, 0);
        List<SiegeNode> orderedSteps = List.of(
                new SiegeNode(minePos, SiegeNode.SiegeAction.MINE),
                new SiegeNode(buildPos, SiegeNode.SiegeAction.BUILD_BRIDGE)
        );
        Map<BlockPos, SiegeNode> instructions = Map.of(
                minePos, new SiegeNode(anchor, SiegeNode.SiegeAction.MINE),
                buildPos, new SiegeNode(minePos, SiegeNode.SiegeAction.BUILD_BRIDGE)
        );
        SiegeProject project = new SiegeProject(instructions, orderedSteps, anchor, buildPos, 500);
        TerrainEvaluator evaluator = new TerrainEvaluator();
        FakeTerrain terrain = new FakeTerrain();
        terrain.set(minePos, Blocks.STONE.defaultBlockState()); // not yet mined

        // Blocked on the still-solid MINE step (handled by the old per-rat SmartBreachGoal path,
        // untouched by this overhaul) - must not skip ahead to the BUILD_BRIDGE step past it.
        assertTrue(project.nextUnbuiltInstruction(terrain, evaluator).isEmpty());
    }

    /**
     * Mirror case to {@link #nextUnbuiltInstructionStopsAtAnIncompleteMineStep}: once
     * SmartBreachGoal has actually cleared the MINE step in the real world, the project must
     * CONTINUE past it to the next real build step - not keep reporting empty forever. Before the
     * whole-branch review's Fix 2, the MINE branch had no completion check at all, so any project
     * whose build order contained a MINE step stalled permanently, even after the obstacle was
     * gone. Asserts the specific step that comes after the mine, not merely "non-empty".
     */
    @Test
    void nextUnbuiltInstructionContinuesPastAnAlreadyClearedMineStep() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        BlockPos minePos = new BlockPos(1, 64, 0);
        BlockPos buildPos = new BlockPos(2, 64, 0);
        List<SiegeNode> orderedSteps = List.of(
                new SiegeNode(minePos, SiegeNode.SiegeAction.MINE),
                new SiegeNode(buildPos, SiegeNode.SiegeAction.BUILD_BRIDGE)
        );
        Map<BlockPos, SiegeNode> instructions = Map.of(
                minePos, new SiegeNode(anchor, SiegeNode.SiegeAction.MINE),
                buildPos, new SiegeNode(minePos, SiegeNode.SiegeAction.BUILD_BRIDGE)
        );
        SiegeProject project = new SiegeProject(instructions, orderedSteps, anchor, buildPos, 500);
        TerrainEvaluator evaluator = new TerrainEvaluator();
        // minePos deliberately left unset: FakeTerrain reports air for it AND for its head/ceiling
        // cells, which is exactly what TerrainEvaluator.isActionCompleted requires of a completed
        // MINE (see its own MINE case - all three cells must be open).
        FakeTerrain terrain = new FakeTerrain();

        assertTrue(evaluator.isActionCompleted(terrain, new SiegeNode(minePos, SiegeNode.SiegeAction.MINE)),
                "setup sanity: the MINE step must read as already cleared, or this test proves nothing");

        java.util.Optional<SiegeProject.PlannedStep> next = project.nextUnbuiltInstruction(terrain, evaluator);

        assertTrue(next.isPresent(), "a cleared MINE step must not block the rest of the build order");
        assertEquals(buildPos, next.get().pos());
        assertEquals(SiegeNode.SiegeAction.BUILD_BRIDGE, next.get().action());
    }

    @Test
    void isAtCapacityFalseBeforeAnyRegistration() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        BlockPos target = new BlockPos(1, 64, 0);
        SiegeProject project = freshSingleStepProject(anchor, target, SiegeNode.SiegeAction.BUILD_PILLAR);

        assertFalse(project.isAtCapacity());
    }

    // The three tests below cover isCompleted()/getRemainingInstructions() checking the wrong
    // cell. `instructions` is keyed by the REAL position (target) but its stored SiegeNode value
    // carries the PREDECESSOR position (anchor) - see this class's own Javadoc on that anchor-ward
    // convention (SiegeLineTracer.trace's own doc explains why). Calling isActionCompleted with the
    // raw stored value evaluates completion at anchor, never at target - the cell the action
    // actually applies to. Each test sets terrain so the two cells disagree, proving the fix
    // changes which cell gets checked rather than coincidentally agreeing.

    @Test
    void isCompletedTrueOnceBuildStairIsBuiltAtTheRealPositionDespiteAnUnbuiltPredecessor() {
        BlockPos anchor = new BlockPos(0, 64, 0); // predecessor - left open air, unbuilt
        BlockPos target = new BlockPos(1, 65, 0); // real position - the instructions map's KEY
        SiegeProject project = freshSingleStepProject(anchor, target, SiegeNode.SiegeAction.BUILD_STAIR);
        TerrainEvaluator evaluator = new TerrainEvaluator();
        FakeTerrain terrain = new FakeTerrain();
        terrain.set(target, Blocks.COBBLESTONE_STAIRS.defaultBlockState());

        assertTrue(project.isCompleted(terrain, evaluator),
                "the built stair sits at target (the map key) - checking anchor (the predecessor-"
                        + "position bug) would wrongly see unbuilt open air and report incomplete");
        assertTrue(project.getRemainingInstructions(terrain, evaluator).isEmpty());
    }

    @Test
    void isCompletedFalseWhenMineStepsRealPositionIsStillSolidDespiteAnOpenPredecessor() {
        BlockPos anchor = new BlockPos(0, 64, 0); // predecessor - left open air (already "cleared")
        BlockPos target = new BlockPos(1, 64, 0); // real position - the obstacle that still needs mining
        SiegeProject project = freshSingleStepProject(anchor, target, SiegeNode.SiegeAction.MINE);
        TerrainEvaluator evaluator = new TerrainEvaluator();
        FakeTerrain terrain = new FakeTerrain();
        terrain.set(target, Blocks.STONE.defaultBlockState()); // not yet mined

        assertFalse(project.isCompleted(terrain, evaluator),
                "target is still solid rock - checking anchor instead (the predecessor-position "
                        + "bug) would see it as already-open air and wrongly report this MINE step done");
        assertEquals(java.util.Set.of(target), project.getRemainingInstructions(terrain, evaluator).keySet());
    }

    @Test
    void isCompletedTrueOnceWalkTargetIsWalkableDespiteASolidPredecessor() {
        BlockPos anchor = new BlockPos(0, 64, 0); // predecessor - solid, not itself walkable
        BlockPos target = new BlockPos(1, 64, 0); // real position - open ground, genuinely walkable
        SiegeProject project = freshSingleStepProject(anchor, target, SiegeNode.SiegeAction.WALK);
        TerrainEvaluator evaluator = new TerrainEvaluator();
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
}
