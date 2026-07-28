package org.ratden.skavenblight.magic.player;

import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;
import org.ratden.skavenblight.magic.Wind;

import static org.junit.jupiter.api.Assertions.*;

class PlayerMagicDataTest {

    @Test
    void emptyHasZeroForEverything() {
        assertEquals(0, PlayerMagicData.EMPTY.getAptitude(Wind.HYSH));
        assertEquals(0, PlayerMagicData.EMPTY.getTier(Wind.HYSH));
        assertTrue(PlayerMagicData.EMPTY.knownSpells().isEmpty());
    }

    @Test
    void withAptitudeReturnsUpdatedCopy() {
        PlayerMagicData updated = PlayerMagicData.EMPTY.withAptitude(Wind.AQSHY, 35);

        assertEquals(35, updated.getAptitude(Wind.AQSHY));
        assertEquals(0, updated.getAptitude(Wind.HYSH));
        assertEquals(0, PlayerMagicData.EMPTY.getAptitude(Wind.AQSHY)); // original untouched
    }

    @Test
    void codecRoundTrips() {
        PlayerMagicData data = PlayerMagicData.EMPTY
                .withAptitude(Wind.AQSHY, 35)
                .withTier(Wind.AQSHY, 1);

        var encoded = PlayerMagicData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
        PlayerMagicData decoded = PlayerMagicData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();

        assertEquals(35, decoded.getAptitude(Wind.AQSHY));
        assertEquals(1, decoded.getTier(Wind.AQSHY));
    }
}
