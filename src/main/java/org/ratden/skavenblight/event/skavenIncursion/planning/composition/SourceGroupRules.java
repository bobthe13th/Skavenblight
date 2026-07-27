package org.ratden.skavenblight.event.skavenIncursion.planning.composition;

/**
 * Rules controlling how much source infrastructure may belong to one
 * source-group composition.
 *
 * Source group load is separate from mob capacity:
 *
 * - mob capacity controls how many mobs fit inside one source;
 * - source group load controls how much source infrastructure one group
 *   anchor and one group-level leadership scope may organise.
 *
 * Stratagems may later provide different rules. STANDARD is the baseline
 * used when no authored override exists.
 */
public record SourceGroupRules(
        int maximumLoad
) {

    public static final SourceGroupRules STANDARD =
            new SourceGroupRules(
                    6
            );

    public SourceGroupRules {
        if (maximumLoad <= 0) {
            throw new IllegalArgumentException(
                    "Maximum source-group load must be greater than zero."
            );
        }
    }

    public boolean canFit(
            int currentLoad,
            int addedLoad
    ) {
        validateLoad(
                currentLoad,
                "Current"
        );

        if (addedLoad <= 0) {
            throw new IllegalArgumentException(
                    "Added source-group load must be greater than zero."
            );
        }

        return currentLoad + addedLoad
                <= maximumLoad;
    }

    public int getRemainingLoad(
            int currentLoad
    ) {
        validateLoad(
                currentLoad,
                "Current"
        );

        return Math.max(
                0,
                maximumLoad - currentLoad
        );
    }

    private static void validateLoad(
            int load,
            String loadName
    ) {
        if (load < 0) {
            throw new IllegalArgumentException(
                    loadName
                            + " source-group load cannot be negative."
            );
        }
    }
}