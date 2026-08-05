package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PlatformInserterTest {

    private static PlannedStep step(int x, int y, int z, PathAction action) {
        return new PlannedStep(new BlockPos(x, y, z), action, Direction.NORTH);
    }

    @Test
    void insertsAPlatformWhereTunnelMeetsBridge() {
        List<PlannedStep> buildOrder = List.of(
                step(0, 10, 0, PathAction.TUNNEL),
                step(1, 10, 0, PathAction.TUNNEL),
                step(2, 10, 0, PathAction.BRIDGE),
                step(3, 10, 0, PathAction.BRIDGE));

        PlatformInserter.Result result = PlatformInserter.insertPlatforms(buildOrder);

        assertEquals(Set.of(new BlockPos(2, 10, 0)), result.platformPositions(),
                "seam is at the FIRST step of the new action, not the last step of the old one");
        assertEquals(buildOrder, result.buildOrder(), "positions/actions/facings are unchanged - only the seam set is new");
    }

    @Test
    void insertsAPlatformWhereCarvedStairMeetsAirStair() {
        List<PlannedStep> buildOrder = List.of(
                step(0, 10, 0, PathAction.CARVED_STAIR),
                step(1, 11, 1, PathAction.AIR_STAIR));

        PlatformInserter.Result result = PlatformInserter.insertPlatforms(buildOrder);

        assertEquals(Set.of(new BlockPos(1, 11, 1)), result.platformPositions());
    }

    @Test
    void noSeamBetweenTwoStepsOfTheSameAction() {
        List<PlannedStep> buildOrder = List.of(
                step(0, 10, 0, PathAction.TUNNEL),
                step(1, 10, 0, PathAction.TUNNEL));

        PlatformInserter.Result result = PlatformInserter.insertPlatforms(buildOrder);

        assertTrue(result.platformPositions().isEmpty());
    }

    @Test
    void noSeamWhenEitherSideIsWalk() {
        List<PlannedStep> buildOrder = List.of(
                step(0, 10, 0, PathAction.WALK),
                step(1, 10, 0, PathAction.TUNNEL));

        PlatformInserter.Result result = PlatformInserter.insertPlatforms(buildOrder);

        assertTrue(result.platformPositions().isEmpty(),
                "WALK cells are real, already-safe terrain - never a platform seam");
    }
}
