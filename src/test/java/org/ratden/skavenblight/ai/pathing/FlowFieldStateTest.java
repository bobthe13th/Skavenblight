package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FlowFieldStateTest {

    @Test
    void updateInstructionsPublishesFlowStepsAtomically() {
        FlowFieldState state = new FlowFieldState(BlockPos.ZERO, Set.of());
        BlockPos pos = new BlockPos(1, 0, 0);
        FlowStep step = new FlowStep(pos, PathAction.WALK, BlockPos.ZERO);

        state.updateInstructions(Map.of(pos, step));

        assertEquals(step, state.getInstruction(pos));
        assertNull(state.getInstruction(new BlockPos(99, 0, 0)));
    }
}
