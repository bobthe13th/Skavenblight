package org.ratden.skavenblight.magic.spell;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CastingResolverTest {

    @Test
    void windLevelBonusScalesAndCaps() {
        assertEquals(0, CastingResolver.windLevelBonus(0f));
        assertEquals(10, CastingResolver.windLevelBonus(200f));
        assertEquals(50, CastingResolver.windLevelBonus(1000f));
        assertEquals(50, CastingResolver.windLevelBonus(5000f)); // capped
    }

    @Test
    void rollUnderOrEqualTotalSucceeds() {
        // total = 50 (aptitude) + 0 (windLevelBonus) - 30 (castingNumber) = 20
        CastingResolver.CastResult result = CastingResolver.resolve(50, 0, 30, 20);
        assertTrue(result.success());
        assertEquals(20, result.roll());
    }

    @Test
    void rollOverTotalFails() {
        CastingResolver.CastResult result = CastingResolver.resolve(50, 0, 30, 21);
        assertFalse(result.success());
        assertEquals(0, result.degreesOfSuccess());
    }

    @Test
    void degreesOfSuccessScaleWithMargin() {
        // total = 20, roll = 10 -> margin 10 -> 2 degrees
        CastingResolver.CastResult close = CastingResolver.resolve(50, 0, 30, 10);
        assertTrue(close.success());
        assertEquals(2, close.degreesOfSuccess());

        // total = 20, roll = 1 -> margin 19 -> 2 degrees (integer division floor)
        CastingResolver.CastResult wide = CastingResolver.resolve(50, 0, 30, 1);
        assertTrue(wide.success());
        assertEquals(2, wide.degreesOfSuccess());

        // total = 20, roll = 20 -> margin 0 -> 1 degree (barely made it)
        CastingResolver.CastResult barely = CastingResolver.resolve(50, 0, 30, 20);
        assertTrue(barely.success());
        assertEquals(1, barely.degreesOfSuccess());
    }
}
