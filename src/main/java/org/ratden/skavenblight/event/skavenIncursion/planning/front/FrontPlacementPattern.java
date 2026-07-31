package org.ratden.skavenblight.event.skavenIncursion.planning.front;

/**
 * Defines the high-level tactical arrangement of fronts around an incursion
 * target.
 *
 * A front is a broad region from which one or more source placement groups
 * may operate. This enum does not determine source count, source type,
 * source role, or the exact positions of individual sources.
 *
 * Front anchors are positioned by angle around the target and by a distance
 * selected from the scenario's permitted placement range.
 */
public enum FrontPlacementPattern {

    /**
     * One front at a random angle around the target.
     */
    ONE(
            1,
            0.0D,
            false
    ),

    /**
     * Two independently positioned fronts.
     *
     * They must be separated enough to read as distinct fronts, but they are
     * not required to oppose one another.
     */
    TWO(
            2,
            60.0D,
            false
    ),

    /**
     * Three independently positioned fronts.
     *
     * They must maintain minimum separation, but are not required to form an
     * equilateral arrangement.
     */
    THREE(
            3,
            60.0D,
            false
    ),

    /**
     * Four independently positioned fronts.
     *
     * They must maintain minimum separation, but are not required to form a
     * cross.
     */
    FOUR(
            4,
            45.0D,
            false
    ),

    /**
     * Two fronts positioned on opposite sides of the target.
     */
    PINCER(
            2,
            180.0D,
            true
    ),

    /**
     * Three fronts spaced evenly around the target.
     */
    TRIAD(
            3,
            120.0D,
            true
    ),

    /**
     * Four fronts spaced evenly around the target.
     */
    CROSS(
            4,
            90.0D,
            true
    ),

    /**
     * Six fronts spaced evenly around the target.
     */
    SURROUND(
            6,
            60.0D,
            true
    );

    private final int frontCount;
    private final double separationDegrees;
    private final boolean evenlySpaced;

    FrontPlacementPattern(
            int frontCount,
            double separationDegrees,
            boolean evenlySpaced
    ) {
        this.frontCount = frontCount;
        this.separationDegrees = separationDegrees;
        this.evenlySpaced = evenlySpaced;
    }

    /**
     * Returns the number of fronts created by this pattern.
     */
    public int getFrontCount() {
        return frontCount;
    }

    /**
     * For irregular patterns, this is the minimum permitted angular
     * separation between fronts.
     *
     * For evenly spaced patterns, this is the intended angular separation
     * between consecutive fronts.
     */
    public double getSeparationDegrees() {
        return separationDegrees;
    }

    /**
     * Returns whether the fronts should be generated at regular angular
     * intervals from a randomly selected starting rotation.
     */
    public boolean isEvenlySpaced() {
        return evenlySpaced;
    }
}