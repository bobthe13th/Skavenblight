package org.ratden.skavenblight.magic.corruption;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * How badly corrupted a player is. Point thresholds are structural game-design constants
 * (like CastingResolver's WIND_LEVEL_SCALE), not Config — they define what the tier names mean.
 */
public enum CorruptionTier implements StringRepresentable {
    NONE("none", 0),
    MINOR("minor", 10),
    MODERATE("moderate", 25),
    SEVERE("severe", 50),
    CATASTROPHIC("catastrophic", 100);

    public static final Codec<CorruptionTier> CODEC = StringRepresentable.fromEnum(CorruptionTier::values);

    private final String id;
    private final int threshold;

    CorruptionTier(String id, int threshold) {
        this.id = id;
        this.threshold = threshold;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    public int threshold() {
        return threshold;
    }

    /** Highest tier whose threshold is at or below the given points. Enum declaration order is ascending. */
    public static CorruptionTier forPoints(int points) {
        CorruptionTier result = NONE;
        for (CorruptionTier tier : values()) {
            if (points >= tier.threshold) {
                result = tier;
            }
        }
        return result;
    }
}
