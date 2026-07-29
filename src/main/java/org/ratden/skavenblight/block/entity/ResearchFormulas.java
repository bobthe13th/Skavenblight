package org.ratden.skavenblight.block.entity;

/**
 * Pure Research Table math, kept dependency-free (no Minecraft/BlockEntity types, no Config
 * reads) so it's trivially unit-testable - same reasoning as
 * org.ratden.skavenblight.magic.spell.CastingResolver. Callers pass Config.* values in explicitly.
 */
public final class ResearchFormulas {

    private ResearchFormulas() {}

    /**
     * Minimum local Wind level required to research a spell of the given tier: each tier above 0
     * requires that much more Wind, so higher-tier spells demand a stronger local presence of
     * their Wind, not just the same flat bar every tier used before.
     */
    public static float requiredWindLevel(int tier, int baseThreshold) {
        return baseThreshold * (1 + tier);
    }

    /**
     * Research speed multiplier once the local Wind meets the requirement: every bonusReference
     * points of *excess* wind above the requirement adds another 1.0x, capped at
     * maxBonusMultiplier extra. Never returns less than 1.0x - callers are responsible for
     * deciding separately whether research should be progressing at all (current >= required).
     */
    public static float speedMultiplier(float current, float required, int bonusReference, double maxBonusMultiplier) {
        float excess = Math.max(0f, current - required);
        double bonus = Math.min(maxBonusMultiplier, excess / bonusReference);
        return 1f + (float) bonus;
    }
}
