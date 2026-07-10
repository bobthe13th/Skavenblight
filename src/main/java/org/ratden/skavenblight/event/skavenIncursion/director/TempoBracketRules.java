package org.ratden.skavenblight.event.skavenIncursion.director;

public class TempoBracketRules {
    private final int minTempo;
    private final int maxTempo;

    private final long globalLockoutTicks;

    private final int maxActiveMajorIncursions;
    private final int maxActiveMinorIncursions;
    private final int maxActiveBackgroundEffects;

    private final boolean allowMinorIncursionOverlap;
    private final boolean allowBackgroundEffectsDuringCombat;
    private final boolean allowSubtleEffectsDuringCombat;

    public TempoBracketRules(
            int minTempo,
            int maxTempo,
            long globalLockoutTicks,
            int maxActiveMajorIncursions,
            int maxActiveMinorIncursions,
            int maxActiveBackgroundEffects,
            boolean allowMinorIncursionOverlap,
            boolean allowBackgroundEffectsDuringCombat,
            boolean allowSubtleEffectsDuringCombat
    ) {
        this.minTempo = minTempo;
        this.maxTempo = maxTempo;
        this.globalLockoutTicks = globalLockoutTicks;
        this.maxActiveMajorIncursions = maxActiveMajorIncursions;
        this.maxActiveMinorIncursions = maxActiveMinorIncursions;
        this.maxActiveBackgroundEffects = maxActiveBackgroundEffects;
        this.allowMinorIncursionOverlap = allowMinorIncursionOverlap;
        this.allowBackgroundEffectsDuringCombat = allowBackgroundEffectsDuringCombat;
        this.allowSubtleEffectsDuringCombat = allowSubtleEffectsDuringCombat;
    }

    public static TempoBracketRules forTempo(int incursionTempo) {
        int tempo = Math.max(0, incursionTempo);

        if (tempo <= 3) {
            return new TempoBracketRules(
                    0,
                    3,
                    24000L,
                    1,
                    0,
                    1,
                    false,
                    false,
                    false
            );
        }

        if (tempo <= 6) {
            return new TempoBracketRules(
                    4,
                    6,
                    18000L,
                    1,
                    0,
                    1,
                    false,
                    true,
                    false
            );
        }

        if (tempo <= 9) {
            return new TempoBracketRules(
                    7,
                    9,
                    12000L,
                    1,
                    1,
                    2,
                    true,
                    true,
                    false
            );
        }

        return new TempoBracketRules(
                10,
                Integer.MAX_VALUE,
                6000L,
                2,
                2,
                3,
                true,
                true,
                false
        );
    }

    public int getMinTempo() {
        return minTempo;
    }

    public int getMaxTempo() {
        return maxTempo;
    }

    public long getGlobalLockoutTicks() {
        return globalLockoutTicks;
    }

    public int getMaxActiveMajorIncursions() {
        return maxActiveMajorIncursions;
    }

    public int getMaxActiveMinorIncursions() {
        return maxActiveMinorIncursions;
    }

    public int getMaxActiveBackgroundEffects() {
        return maxActiveBackgroundEffects;
    }

    public boolean allowsMinorIncursionOverlap() {
        return allowMinorIncursionOverlap;
    }

    public boolean allowsBackgroundEffectsDuringCombat() {
        return allowBackgroundEffectsDuringCombat;
    }

    public boolean allowsSubtleEffectsDuringCombat() {
        return allowSubtleEffectsDuringCombat;
    }

    public boolean isTempoInBracket(int incursionTempo) {
        return incursionTempo >= minTempo && incursionTempo <= maxTempo;
    }
}