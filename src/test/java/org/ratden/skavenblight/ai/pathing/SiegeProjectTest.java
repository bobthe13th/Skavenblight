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
                new SiegeNode(new BlockPos(1, 66, -1), SiegeNode.SiegeAction.BUILD_STAIR)
        );

        List<SiegeProject.PlannedStep> planned = SiegeProject.planSteps(orderedSteps, anchor);

        assertEquals(Direction.EAST, planned.get(0).facing());
        // step 1 moves from (1,65,0) to (1,66,-1): dz=-1, dx=0 -> NORTH
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

    @Test
    void countCompletableCountsWhileWorkCoversCost() {
        assertEquals(0, SiegeProject.countCompletable(List.of(150, 150, 150), 100.0));
        assertEquals(1, SiegeProject.countCompletable(List.of(150, 150, 150), 150.0));
        assertEquals(2, SiegeProject.countCompletable(List.of(150, 150, 150), 300.0));
        assertEquals(3, SiegeProject.countCompletable(List.of(150, 150, 150), 999.0));
    }

    @Test
    void countCompletableStopsAtFirstUncoverableCost() {
        // A large second cost blocks the third even though total work would otherwise cover it.
        assertEquals(1, SiegeProject.countCompletable(List.of(100, 5000, 100), 250.0));
    }

    @Test
    void countCompletableHandlesEmptyList() {
        assertEquals(0, SiegeProject.countCompletable(List.of(), 999.0));
    }

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

    @Test
    void isAtCapacityFalseBeforeAnyRegistration() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        BlockPos target = new BlockPos(1, 64, 0);
        SiegeProject project = freshSingleStepProject(anchor, target, SiegeNode.SiegeAction.BUILD_PILLAR);

        assertFalse(project.isAtCapacity());
    }
}
