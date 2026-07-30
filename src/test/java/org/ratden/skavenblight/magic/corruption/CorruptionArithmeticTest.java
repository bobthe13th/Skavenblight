package org.ratden.skavenblight.magic.corruption;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Corruption.grant/reduce are thin ServerPlayer-attachment wrappers around this arithmetic —
 * exercise the arithmetic directly rather than standing up a live player, mirroring how
 * CastingResolverTest tests pure formulas without a live cast.
 */
class CorruptionArithmeticTest {

    @Test
    void grantNeverGoesNegative() {
        assertEquals(0, Corruption.applyDelta(5, -20));
    }

    @Test
    void grantAccumulates() {
        assertEquals(15, Corruption.applyDelta(10, 5));
    }

    @Test
    void reduceIsGrantWithNegatedAbsoluteAmount() {
        assertEquals(5, Corruption.applyDelta(10, -Math.abs(5)));
    }
}
