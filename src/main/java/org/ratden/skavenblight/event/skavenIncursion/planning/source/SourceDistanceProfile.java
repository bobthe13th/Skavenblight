package org.ratden.skavenblight.event.skavenIncursion.planning.source;

/**
 * Defines how far incursion sources should be placed from the defended
 * Warp Flux network.
 *
 * Each profile contains:
 *
 * - a hard minimum clearance that source reservations may never enter;
 * - a preferred minimum and maximum offset beyond that hard boundary.
 *
 * The hard minimum is currently identical for every profile, but is stored
 * per profile so individual source-distance policies can be tuned later.
 */
public enum SourceDistanceProfile {

    CLOSE(
            16,
            6,
            12
    ),

    STANDARD(
            16,
            18,
            28
    ),

    FAR(
            16,
            36,
            50
    ),

    SIEGE(
            16,
            50,
            68
    );

    private final int hardMinimumNetworkClearance;
    private final int minPreferredOffset;
    private final int maxPreferredOffset;

    SourceDistanceProfile(
            int hardMinimumNetworkClearance,
            int minPreferredOffset,
            int maxPreferredOffset
    ) {
        if (hardMinimumNetworkClearance < 0) {
            throw new IllegalArgumentException(
                    "Hard network clearance cannot be negative."
            );
        }

        if (minPreferredOffset < 0) {
            throw new IllegalArgumentException(
                    "Minimum preferred offset cannot be negative."
            );
        }

        if (maxPreferredOffset < minPreferredOffset) {
            throw new IllegalArgumentException(
                    "Maximum preferred offset cannot be below the minimum."
            );
        }

        this.hardMinimumNetworkClearance =
                hardMinimumNetworkClearance;

        this.minPreferredOffset =
                minPreferredOffset;

        this.maxPreferredOffset =
                maxPreferredOffset;
    }

    /**
     * Absolute forbidden distance from the nearest protected Warp Flux
     * network block.
     */
    public int getHardMinimumNetworkClearance() {
        return hardMinimumNetworkClearance;
    }

    /**
     * Preferred minimum clearance from the network after including the hard
     * exclusion band.
     */
    public int getMinPreferredNetworkClearance() {
        return hardMinimumNetworkClearance
                + minPreferredOffset;
    }

    /**
     * Preferred maximum clearance from the network after including the hard
     * exclusion band.
     */
    public int getMaxPreferredNetworkClearance() {
        return hardMinimumNetworkClearance
                + maxPreferredOffset;
    }

    public int getMinPreferredOffset() {
        return minPreferredOffset;
    }

    public int getMaxPreferredOffset() {
        return maxPreferredOffset;
    }

    /**
     * Legacy target-radius calculation retained temporarily for older source
     * placement code and player-only debug placement.
     *
     * Nexus-targeted planning will stop using this once it is migrated to
     * exact Warp Flux network geometry.
     */
    public int getMinDistanceFromTarget(
            int baseRadius
    ) {
        return Math.max(
                0,
                baseRadius
        ) + minPreferredOffset;
    }

    /**
     * Legacy target-radius calculation retained temporarily for older source
     * placement code and player-only debug placement.
     */
    public int getMaxDistanceFromTarget(
            int baseRadius
    ) {
        return Math.max(
                0,
                baseRadius
        ) + maxPreferredOffset;
    }

    public SourceDistanceProfile oneStepFarther() {
        return switch (this) {
            case CLOSE -> STANDARD;
            case STANDARD -> FAR;
            case FAR, SIEGE -> SIEGE;
        };
    }

    public SourceDistanceProfile oneStepCloser() {
        return switch (this) {
            case CLOSE -> CLOSE;
            case STANDARD -> CLOSE;
            case FAR -> STANDARD;
            case SIEGE -> FAR;
        };
    }
}