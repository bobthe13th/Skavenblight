package org.ratden.skavenblight.magic.spell;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpellCastingOutcomeTest {

    @Test
    void blockedOutcomeIsNotASuccess() {
        SpellCasting.Outcome blocked = new SpellCasting.Outcome(false, true, 0, 0, 0);
        assertTrue(blocked.blockedDarkMagic());
        assertFalse(blocked.success());
    }

    @Test
    void monolithBonusIsZeroOutsideZone() {
        assertEquals(0, SpellCasting.monolithBonus(false, 7));
    }

    @Test
    void monolithBonusIsD10PlusOneInsideZone() {
        // A raw die roll of 0 (nextInt(10)) must yield a bonus of 1, and 9 must yield 10 — the
        // book's "+1d10" is a roll of 1-10, not 0-9.
        assertEquals(1, SpellCasting.monolithBonus(true, 0));
        assertEquals(10, SpellCasting.monolithBonus(true, 9));
    }
}
