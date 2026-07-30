package org.ratden.skavenblight.magic.corruption;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CorruptionTierTest {

    @Test
    void forPointsReturnsNoneBelowFirstThreshold() {
        assertEquals(CorruptionTier.NONE, CorruptionTier.forPoints(0));
        assertEquals(CorruptionTier.NONE, CorruptionTier.forPoints(9));
    }

    @Test
    void forPointsReturnsHighestTierAtOrBelowPoints() {
        assertEquals(CorruptionTier.MINOR, CorruptionTier.forPoints(10));
        assertEquals(CorruptionTier.MINOR, CorruptionTier.forPoints(24));
        assertEquals(CorruptionTier.MODERATE, CorruptionTier.forPoints(25));
        assertEquals(CorruptionTier.SEVERE, CorruptionTier.forPoints(50));
        assertEquals(CorruptionTier.CATASTROPHIC, CorruptionTier.forPoints(100));
        assertEquals(CorruptionTier.CATASTROPHIC, CorruptionTier.forPoints(9999));
    }

    @Test
    void serializedNamesAreLowercaseIds() {
        assertEquals("none", CorruptionTier.NONE.getSerializedName());
        assertEquals("catastrophic", CorruptionTier.CATASTROPHIC.getSerializedName());
    }
}
