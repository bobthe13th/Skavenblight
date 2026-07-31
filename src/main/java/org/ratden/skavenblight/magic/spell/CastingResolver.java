package org.ratden.skavenblight.magic.spell;

/**
 * The one place casting-number math lives. success = aptitude + windLevelBonus - castingNumber >= roll,
 * roll is a d100 rolled by the caller (kept out of this class so the formula stays a pure function).
 */
public final class CastingResolver {

    private static final float WIND_LEVEL_SCALE = 20f;
    private static final int WIND_LEVEL_BONUS_CAP = 50;
    private static final int DEGREE_MARGIN_STEP = 10;

    private CastingResolver() {}

    public static int windLevelBonus(float windLevel) {
        int bonus = (int) (windLevel / WIND_LEVEL_SCALE);
        return Math.min(WIND_LEVEL_BONUS_CAP, Math.max(0, bonus));
    }

    public static CastResult resolve(int aptitude, int windLevelBonus, int castingNumber, int roll) {
        int total = aptitude + windLevelBonus - castingNumber;
        boolean success = roll <= total;
        int degrees = success ? Math.max(1, (total - roll) / DEGREE_MARGIN_STEP + 1) : 0;
        return new CastResult(success, degrees, roll);
    }

    public record CastResult(boolean success, int degreesOfSuccess, int roll) {}
}
