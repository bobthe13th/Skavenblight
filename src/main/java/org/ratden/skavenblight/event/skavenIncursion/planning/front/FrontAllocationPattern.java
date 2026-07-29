package org.ratden.skavenblight.event.skavenIncursion.planning.front;

/**
 * Defines a reusable baseline method for dividing a wave's budget between
 * the fronts created by a {@link FrontPlacementPattern}.
 *
 * This enum does not store the calculated percentages for a particular
 * incursion or wave. Those should be stored in a FrontAllocation object.
 *
 * Tailored Stratagems may bypass these baseline patterns and create their
 * own FrontAllocation directly.
 */
public enum FrontAllocationPattern {

    /**
     * Divides the available budget as evenly as possible between every front.
     */
    BALANCED(0),

    /**
     * Selects one front to receive a larger share of the budget.
     *
     * Every remaining front should still receive the normal minimum share
     * unless a Stratagem explicitly overrides that rule.
     */
    ONE_DOMINANT(1),

    /**
     * Selects two fronts to receive larger shares of the budget.
     *
     * This pattern is only meaningfully different from BALANCED when more
     * than two fronts exist.
     */
    TWO_DOMINANT(2),

    /**
     * Selects three fronts to receive larger shares of the budget.
     *
     * This pattern is only meaningfully different from BALANCED when more
     * than three fronts exist.
     */
    THREE_DOMINANT(3),

    /**
     * Produces a varied allocation while preserving the normal minimum share
     * for each funded front.
     */
    RANDOM(-1);

    private final int dominantFrontCount;

    FrontAllocationPattern(int dominantFrontCount) {
        this.dominantFrontCount = dominantFrontCount;
    }

    /**
     * Returns the intended number of dominant fronts.
     *
     * BALANCED returns 0 because it has no dominant front.
     * RANDOM returns -1 because the number of dominant fronts is not fixed.
     */
    public int getDominantFrontCount() {
        return dominantFrontCount;
    }

    /**
     * Returns whether this pattern explicitly selects dominant fronts.
     */
    public boolean hasDominantFronts() {
        return dominantFrontCount > 0;
    }

    /**
     * Returns whether this pattern uses unrestricted baseline randomness
     * rather than a fixed dominant-front count.
     */
    public boolean isRandom() {
        return this == RANDOM;
    }
}