package org.ratden.skavenblight.magic.spell;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpellCastingOutcomeTest {

    @Test
    void blockedOutcomeIsNotASuccess() {
        SpellCasting.Outcome blocked = new SpellCasting.Outcome(false, true, 0, 0, 0);
        assertTrue(blocked.blockedDarkMagic());
        assertFalse(blocked.success());
    }
}
