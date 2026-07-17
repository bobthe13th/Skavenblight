package org.ratden.skavenblight.event.skavenIncursion.planning.front;

import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Stores the calculated distribution of threat and complexity between the
 * fronts of one wave or planning step.
 *
 * Baseline allocations created through {@link #fromPattern} enforce the
 * normal minimum allocation of 10% per front.
 *
 * Tailored Stratagems may use {@link #custom} to deliberately replace that
 * minimum with another value, including 0%.
 */
public final class FrontAllocation {

    public static final double DEFAULT_MINIMUM_FRONT_SHARE = 0.10D;

    private static final double SHARE_TOLERANCE = 0.0001D;
    private static final double DOMINANT_WEIGHT = 3.0D;
    private static final double NORMAL_WEIGHT = 1.0D;

    private final Map<UUID, Double> threatShares;
    private final Map<UUID, Double> complexityShares;
    private final Set<UUID> dominantFrontIds;

    private final FrontAllocationPattern allocationPattern;
    private final double minimumFrontShare;

    private FrontAllocation(
            Map<UUID, Double> threatShares,
            Map<UUID, Double> complexityShares,
            Set<UUID> dominantFrontIds,
            FrontAllocationPattern allocationPattern,
            double minimumFrontShare
    ) {
        validateMinimumShare(minimumFrontShare);

        validateInputs(
                threatShares,
                complexityShares,
                dominantFrontIds,
                minimumFrontShare
        );

        this.threatShares = Collections.unmodifiableMap(
                new LinkedHashMap<>(threatShares)
        );

        this.complexityShares = Collections.unmodifiableMap(
                new LinkedHashMap<>(complexityShares)
        );

        this.dominantFrontIds = Collections.unmodifiableSet(
                new HashSet<>(dominantFrontIds)
        );

        this.allocationPattern = allocationPattern;
        this.minimumFrontShare = minimumFrontShare;
    }

    /**
     * Creates an ordinary baseline allocation.
     *
     * The supplied placement pattern must create exactly the same number of
     * fronts as the supplied front ID list.
     *
     * Dominant allocation patterns require at least one non-dominant front:
     *
     * ONE_DOMINANT requires at least 2 fronts.
     * TWO_DOMINANT requires at least 3 fronts.
     * THREE_DOMINANT requires at least 4 fronts.
     *
     * Threat and complexity use the same baseline allocation. A tailored
     * Stratagem may instead use {@link #custom} to provide different shares.
     */
    public static FrontAllocation fromPattern(
            List<UUID> frontIds,
            FrontPlacementPattern placementPattern,
            FrontAllocationPattern allocationPattern,
            RandomSource random
    ) {
        validatePatternInputs(
                frontIds,
                placementPattern,
                allocationPattern,
                random
        );

        int frontCount = frontIds.size();
        int dominantFrontCount = allocationPattern.getDominantFrontCount();

        if (dominantFrontCount > 0
                && dominantFrontCount >= frontCount) {
            throw new IllegalArgumentException(
                    allocationPattern
                            + " requires at least "
                            + (dominantFrontCount + 1)
                            + " fronts, but "
                            + placementPattern
                            + " creates "
                            + frontCount
                            + "."
            );
        }

        Set<UUID> dominantFrontIds = selectDominantFronts(
                frontIds,
                dominantFrontCount,
                random
        );

        Map<UUID, Double> shares = calculateBaselineShares(
                frontIds,
                allocationPattern,
                dominantFrontIds,
                random
        );

        return new FrontAllocation(
                shares,
                shares,
                dominantFrontIds,
                allocationPattern,
                DEFAULT_MINIMUM_FRONT_SHARE
        );
    }

    /**
     * Creates a tailored allocation, usually for a more specifically authored
     * Stratagem.
     *
     * The Stratagem must state the minimum share it wishes to permit.
     *
     * Examples:
     *
     * 0.10 preserves the normal baseline.
     * 0.00 allows empty, dormant or decoy fronts.
     */
    public static FrontAllocation custom(
            Map<UUID, Double> threatShares,
            Map<UUID, Double> complexityShares,
            Set<UUID> dominantFrontIds,
            double minimumFrontShare
    ) {
        return new FrontAllocation(
                threatShares,
                complexityShares,
                dominantFrontIds,
                null,
                minimumFrontShare
        );
    }

    public double getThreatShare(UUID frontId) {
        return threatShares.getOrDefault(frontId, 0.0D);
    }

    public double getComplexityShare(UUID frontId) {
        return complexityShares.getOrDefault(frontId, 0.0D);
    }

    public boolean isDominant(UUID frontId) {
        return dominantFrontIds.contains(frontId);
    }

    public Map<UUID, Double> getThreatShares() {
        return threatShares;
    }

    public Map<UUID, Double> getComplexityShares() {
        return complexityShares;
    }

    public Set<UUID> getDominantFrontIds() {
        return dominantFrontIds;
    }

    public Set<UUID> getFrontIds() {
        return threatShares.keySet();
    }

    public int getFrontCount() {
        return threatShares.size();
    }

    /**
     * Returns the baseline pattern that generated this allocation.
     *
     * Returns null for a custom Stratagem allocation.
     */
    public FrontAllocationPattern getAllocationPattern() {
        return allocationPattern;
    }

    public double getMinimumFrontShare() {
        return minimumFrontShare;
    }

    public boolean isCustom() {
        return allocationPattern == null;
    }

    private static Map<UUID, Double> calculateBaselineShares(
            List<UUID> frontIds,
            FrontAllocationPattern allocationPattern,
            Set<UUID> dominantFrontIds,
            RandomSource random
    ) {
        return switch (allocationPattern) {
            case BALANCED -> createBalancedShares(frontIds);
            case ONE_DOMINANT, TWO_DOMINANT, THREE_DOMINANT ->
                    createDominantShares(frontIds, dominantFrontIds);
            case RANDOM -> createRandomShares(frontIds, random);
        };
    }

    private static Map<UUID, Double> createBalancedShares(
            List<UUID> frontIds
    ) {
        Map<UUID, Double> shares = new LinkedHashMap<>();

        double share = 1.0D / frontIds.size();

        for (UUID frontId : frontIds) {
            shares.put(frontId, share);
        }

        return shares;
    }

    /**
     * Gives every front the normal minimum, then distributes the remaining
     * budget using a higher weight for deliberately dominant fronts.
     */
    private static Map<UUID, Double> createDominantShares(
            List<UUID> frontIds,
            Set<UUID> dominantFrontIds
    ) {
        Map<UUID, Double> shares = createMinimumShares(frontIds);

        double remainingShare =
                1.0D - DEFAULT_MINIMUM_FRONT_SHARE * frontIds.size();

        double totalWeight = 0.0D;

        for (UUID frontId : frontIds) {
            totalWeight += dominantFrontIds.contains(frontId)
                    ? DOMINANT_WEIGHT
                    : NORMAL_WEIGHT;
        }

        for (UUID frontId : frontIds) {
            double weight = dominantFrontIds.contains(frontId)
                    ? DOMINANT_WEIGHT
                    : NORMAL_WEIGHT;

            double additionalShare =
                    remainingShare * (weight / totalWeight);

            shares.put(
                    frontId,
                    shares.get(frontId) + additionalShare
            );
        }

        return shares;
    }

    /**
     * Gives every front the normal minimum, then distributes the remaining
     * budget randomly.
     *
     * RANDOM does not formally mark any front as dominant.
     */
    private static Map<UUID, Double> createRandomShares(
            List<UUID> frontIds,
            RandomSource random
    ) {
        Map<UUID, Double> shares = createMinimumShares(frontIds);

        double remainingShare =
                1.0D - DEFAULT_MINIMUM_FRONT_SHARE * frontIds.size();

        if (remainingShare <= 0.0D) {
            return shares;
        }

        Map<UUID, Double> randomWeights = new LinkedHashMap<>();
        double totalWeight = 0.0D;

        for (UUID frontId : frontIds) {
            double weight = 0.1D + random.nextDouble();

            randomWeights.put(frontId, weight);
            totalWeight += weight;
        }

        for (UUID frontId : frontIds) {
            double additionalShare =
                    remainingShare
                            * (randomWeights.get(frontId) / totalWeight);

            shares.put(
                    frontId,
                    shares.get(frontId) + additionalShare
            );
        }

        return shares;
    }

    private static Map<UUID, Double> createMinimumShares(
            List<UUID> frontIds
    ) {
        Map<UUID, Double> shares = new LinkedHashMap<>();

        for (UUID frontId : frontIds) {
            shares.put(frontId, DEFAULT_MINIMUM_FRONT_SHARE);
        }

        return shares;
    }

    private static Set<UUID> selectDominantFronts(
            List<UUID> frontIds,
            int dominantFrontCount,
            RandomSource random
    ) {
        if (dominantFrontCount <= 0) {
            return Collections.emptySet();
        }

        List<UUID> shuffledFrontIds = new ArrayList<>(frontIds);

        for (int i = shuffledFrontIds.size() - 1; i > 0; i--) {
            int swapIndex = random.nextInt(i + 1);

            UUID current = shuffledFrontIds.get(i);
            shuffledFrontIds.set(i, shuffledFrontIds.get(swapIndex));
            shuffledFrontIds.set(swapIndex, current);
        }

        return new HashSet<>(
                shuffledFrontIds.subList(0, dominantFrontCount)
        );
    }

    private static void validatePatternInputs(
            List<UUID> frontIds,
            FrontPlacementPattern placementPattern,
            FrontAllocationPattern allocationPattern,
            RandomSource random
    ) {
        if (frontIds == null
                || placementPattern == null
                || allocationPattern == null
                || random == null) {
            throw new IllegalArgumentException(
                    "Front allocation pattern inputs cannot be null."
            );
        }

        if (frontIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one front ID is required."
            );
        }

        if (frontIds.size() != placementPattern.getFrontCount()) {
            throw new IllegalArgumentException(
                    placementPattern
                            + " requires "
                            + placementPattern.getFrontCount()
                            + " front IDs, but received "
                            + frontIds.size()
                            + "."
            );
        }

        Set<UUID> uniqueFrontIds = new HashSet<>(frontIds);

        if (uniqueFrontIds.size() != frontIds.size()) {
            throw new IllegalArgumentException(
                    "Front IDs must be unique."
            );
        }

        if (uniqueFrontIds.contains(null)) {
            throw new IllegalArgumentException(
                    "Front IDs cannot contain null."
            );
        }
    }

    private static void validateInputs(
            Map<UUID, Double> threatShares,
            Map<UUID, Double> complexityShares,
            Set<UUID> dominantFrontIds,
            double minimumFrontShare
    ) {
        if (threatShares == null
                || complexityShares == null
                || dominantFrontIds == null) {
            throw new IllegalArgumentException(
                    "Front allocation values cannot be null."
            );
        }

        if (threatShares.isEmpty()) {
            throw new IllegalArgumentException(
                    "A front allocation must contain at least one front."
            );
        }

        if (!threatShares.keySet().equals(complexityShares.keySet())) {
            throw new IllegalArgumentException(
                    "Threat and complexity allocations must contain the same front IDs."
            );
        }

        validateShares(
                "Threat",
                threatShares,
                minimumFrontShare
        );

        validateShares(
                "Complexity",
                complexityShares,
                minimumFrontShare
        );

        if (!threatShares.keySet().containsAll(dominantFrontIds)) {
            throw new IllegalArgumentException(
                    "Every dominant front ID must exist in the allocation."
            );
        }
    }

    private static void validateShares(
            String budgetName,
            Map<UUID, Double> shares,
            double minimumFrontShare
    ) {
        double total = 0.0D;

        for (Map.Entry<UUID, Double> entry : shares.entrySet()) {
            UUID frontId = entry.getKey();
            Double share = entry.getValue();

            if (frontId == null) {
                throw new IllegalArgumentException(
                        budgetName + " allocation contains a null front ID."
                );
            }

            if (share == null
                    || !Double.isFinite(share)
                    || share < minimumFrontShare
                    || share > 1.0D) {
                throw new IllegalArgumentException(
                        budgetName
                                + " share for front "
                                + frontId
                                + " must be between "
                                + minimumFrontShare
                                + " and 1.0."
                );
            }

            total += share;
        }

        if (Math.abs(total - 1.0D) > SHARE_TOLERANCE) {
            throw new IllegalArgumentException(
                    budgetName
                            + " shares must total 1.0, but totalled "
                            + total
                            + "."
            );
        }
    }

    private static void validateMinimumShare(
            double minimumFrontShare
    ) {
        if (!Double.isFinite(minimumFrontShare)
                || minimumFrontShare < 0.0D
                || minimumFrontShare > 1.0D) {
            throw new IllegalArgumentException(
                    "Minimum front share must be between 0.0 and 1.0."
            );
        }
    }
}