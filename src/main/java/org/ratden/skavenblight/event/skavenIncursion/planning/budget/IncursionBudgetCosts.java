package org.ratden.skavenblight.event.skavenIncursion.planning.budget;

/**
 * Central definitions for content purchased with incursion budgets.
 *
 * Mob entries contain both:
 * - threatCost: how much threat is spent to select one mob;
 * - capacityCost: how much source capacity that mob consumes.
 *
 * Capacity values are provisional and should be adjusted through testing.
 */
public final class IncursionBudgetCosts {

    // Mob threat/capacity costs
    public static final MobCost WOLF_RAT = new MobCost(
            1,
            1
    );

    public static final MobCost CLANRAT = new MobCost(
            2,
            1
    );

    public static final MobCost WOLF_CAT = new MobCost(
            1,
            1
    );

    public static final MobCost SLINGER = new MobCost(
            3,
            1
    );

    public static final MobCost STORMVERMIN = new MobCost(
            5,
            2
    );

    public static final MobCost RAT_OGRE = new MobCost(
            10,
            8
    );

    // Complexity/effect costs
    public static final int POISON_WOLF_RAT_ATTACKS = 5;
    public static final int LEADER_AURA = 6;
    public static final int POISON_WIND_STRIKE = 8;
    public static final int WARP_LIGHTNING_STRIKE = 10;

    /**
     * Planning costs for one mob.
     *
     * @param threatCost   threat required to purchase one mob
     * @param capacityCost source capacity consumed by one mob
     */
    public record MobCost(
            int threatCost,
            int capacityCost
    ) {
        public MobCost {
            if (threatCost <= 0) {
                throw new IllegalArgumentException(
                        "Mob threat cost must be greater than zero."
                );
            }

            if (capacityCost <= 0) {
                throw new IllegalArgumentException(
                        "Mob capacity cost must be greater than zero."
                );
            }
        }

        /**
         * Threat cost per unit of source capacity.
         *
         * Used when estimating the expected cost of filling sources.
         */
        public double threatPerCapacity() {
            return (double) threatCost / capacityCost;
        }
    }

    private IncursionBudgetCosts() {
    }
}