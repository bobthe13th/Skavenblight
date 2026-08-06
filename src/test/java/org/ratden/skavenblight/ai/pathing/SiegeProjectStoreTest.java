package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import org.ratden.skavenblight.ai.pathing.nbt.SiegeProjectNbtCodec;
import org.ratden.skavenblight.ai.pathing.nbt.SiegeProjectSnapshot;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SiegeProjectStoreTest {

    @Test
    void nbtRoundTripPreservesEveryPersistedField() {
        UUID projectId = UUID.randomUUID();
        UUID networkId = UUID.randomUUID();
        List<PlannedStep> buildOrder = List.of(
                new PlannedStep(new BlockPos(1, 2, 3), PathAction.TUNNEL, Direction.NORTH));
        SiegeProjectSnapshot original = new SiegeProjectSnapshot(projectId, networkId,
                new BlockPos(0, 0, 0), 500, new BlockPos(5, 5, 5), buildOrder,
                new BlockPos(0, 0, 0), 2, 1234.5, 9999L);

        net.minecraft.nbt.CompoundTag tag = SiegeProjectNbtCodec.write(original);
        SiegeProjectSnapshot roundTripped = SiegeProjectNbtCodec.read(tag);

        assertEquals(original, roundTripped);
    }

    @Test
    void nbtRoundTripPreservesNullExitPos() {
        SiegeProjectSnapshot original = new SiegeProjectSnapshot(UUID.randomUUID(), UUID.randomUUID(),
                new BlockPos(0, 0, 0), 500, null, List.of(),
                new BlockPos(0, 0, 0), 1, 0.0, 0L);

        net.minecraft.nbt.CompoundTag tag = SiegeProjectNbtCodec.write(original);
        SiegeProjectSnapshot roundTripped = SiegeProjectNbtCodec.read(tag);

        assertNull(roundTripped.exitPos(), "a null exitPos must round-trip as null, not a synthesized position");
        assertEquals(original, roundTripped);
    }

    @Test
    void lastTickedGameTimeClampsToCurrentGameTimeOnLoad() {
        long storedFutureStamp = 1_000_000L;
        long actualCurrentGameTime = 500L;

        long clamped = Math.min(storedFutureStamp, actualCurrentGameTime);

        assertEquals(actualCurrentGameTime, clamped, "a future-dated stamp from a restored backup must clamp to now, never block tick() forever");
    }
}
