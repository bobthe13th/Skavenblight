package org.ratden.skavenblight.block.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResearchFormulasTest {

    @Test
    void requiredWindLevelScalesWithTier() {
        assertEquals(200f, ResearchFormulas.requiredWindLevel(0, 200));
        assertEquals(400f, ResearchFormulas.requiredWindLevel(1, 200));
        assertEquals(600f, ResearchFormulas.requiredWindLevel(2, 200));
        assertEquals(800f, ResearchFormulas.requiredWindLevel(3, 200));
    }

    @Test
    void speedMultiplierIsOneAtExactlyTheRequirement() {
        assertEquals(1.0f, ResearchFormulas.speedMultiplier(200f, 200f, 300, 2.0), 0.001f);
    }

    @Test
    void speedMultiplierIsOneBelowTheRequirement() {
        // The function itself never goes below 1.0x - the caller is responsible for gating
        // "should this even be progressing" separately (current >= required) before applying this.
        assertEquals(1.0f, ResearchFormulas.speedMultiplier(50f, 200f, 300, 2.0), 0.001f);
    }

    @Test
    void speedMultiplierScalesWithExcessWind() {
        // 150 excess / 300 reference = 0.5 bonus -> 1.5x total
        assertEquals(1.5f, ResearchFormulas.speedMultiplier(350f, 200f, 300, 2.0), 0.001f);
        // 300 excess / 300 reference = 1.0 bonus -> 2.0x total
        assertEquals(2.0f, ResearchFormulas.speedMultiplier(500f, 200f, 300, 2.0), 0.001f);
    }

    @Test
    void speedMultiplierCapsAtMaxBonus() {
        // huge excess wind, but the bonus portion is capped at +2.0 -> 3.0x total, never more
        assertEquals(3.0f, ResearchFormulas.speedMultiplier(100000f, 200f, 300, 2.0), 0.001f);
    }

    @Test
    void progressIncrementScalesContinuously() {
        assertEquals(100, ResearchFormulas.progressIncrement(1.0f));
        assertEquals(113, ResearchFormulas.progressIncrement(1.13f));
        assertEquals(150, ResearchFormulas.progressIncrement(1.5f));
        assertEquals(300, ResearchFormulas.progressIncrement(3.0f));
    }
}
