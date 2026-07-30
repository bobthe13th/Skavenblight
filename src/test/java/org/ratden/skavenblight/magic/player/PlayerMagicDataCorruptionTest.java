package org.ratden.skavenblight.magic.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerMagicDataCorruptionTest {

    @Test
    void emptyDataHasZeroCorruptionAndLockedDarkMagic() {
        assertEquals(0, PlayerMagicData.EMPTY.corruptionPoints());
        assertFalse(PlayerMagicData.EMPTY.darkMagicUnlocked());
        assertEquals(0L, PlayerMagicData.EMPTY.lastCleanseGameTime());
    }

    @Test
    void withCorruptionPointsReplacesOnlyThatField() {
        PlayerMagicData updated = PlayerMagicData.EMPTY.withCorruptionPoints(42);
        assertEquals(42, updated.corruptionPoints());
        assertFalse(updated.darkMagicUnlocked());
    }

    @Test
    void withDarkMagicUnlockedPreservesCorruptionPoints() {
        PlayerMagicData updated = PlayerMagicData.EMPTY
                .withCorruptionPoints(15)
                .withDarkMagicUnlocked(true);
        assertEquals(15, updated.corruptionPoints());
        assertTrue(updated.darkMagicUnlocked());
    }

    @Test
    void withLastCleanseGameTimeRoundTrips() {
        PlayerMagicData updated = PlayerMagicData.EMPTY.withLastCleanseGameTime(123456L);
        assertEquals(123456L, updated.lastCleanseGameTime());
    }
}
