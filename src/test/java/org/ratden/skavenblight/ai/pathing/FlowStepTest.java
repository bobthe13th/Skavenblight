package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlowStepTest {

    @Test
    void flowStepCarriesItsOwnPositionIndependentOfAnyMapKey() {
        BlockPos pos = new BlockPos(5, 10, 5);
        BlockPos predecessor = new BlockPos(4, 10, 5);
        FlowStep step = new FlowStep(pos, PathAction.WALK, predecessor);

        assertEquals(pos, step.pos());
        assertEquals(predecessor, step.predecessorPos());
        assertEquals(PathAction.WALK, step.action());
    }

    @Test
    void plannedStepHasNoPredecessorField() {
        PlannedStep step = new PlannedStep(new BlockPos(1, 2, 3), PathAction.TUNNEL, Direction.NORTH);

        assertEquals(PathAction.TUNNEL, step.action());
        assertEquals(Direction.NORTH, step.facing());
        // No predecessorPos accessor exists on PlannedStep - order comes from list position.
    }

    @Test
    void pathActionHasExactlyTheFiveNewValues() {
        assertEquals(5, PathAction.values().length);
        assertArrayEquals(
                new PathAction[]{PathAction.WALK, PathAction.TUNNEL, PathAction.BRIDGE,
                        PathAction.CARVED_STAIR, PathAction.AIR_STAIR},
                PathAction.values());
    }
}
