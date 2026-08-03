package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
}
