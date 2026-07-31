package org.ratden.skavenblight.event.skavenIncursion.planning.composition;

import java.util.List;

/**
 * Calculates the threat-density values used to judge whether a proposed
 * source structure has enough threat available to produce a meaningful
 * composition.
 *
 * The calculator does not choose mobs or purchase structural options.
 * It only provides a feasibility estimate for other planning systems.
 */
public class ThreatDensityCalculator {

    /**
     * Working baseline used when no authored value is supplied.
     */
    public static final double DEFAULT_TARGET_CAPACITY_UTILISATION = 0.80D;

    /**
     * Working baseline safety margin.
     *
     * A value of 1.25 represents a 25% buffer.
     */
    public static final double DEFAULT_THREAT_DENSITY_BUFFER = 1.25D;

    /**
     * Calculates the weighted threat density of a legal mob roster.
     *
     * Each mob definition is paired with an effective planning weight.
     * Scenario and Stratagem weighting should be resolved before calling
     * this method.
     */
    public double calculateRosterThreatDensity(
            List<WeightedMobDefinition> weightedMobDefinitions
    ) {
        validateWeightedRoster(weightedMobDefinitions);

        double weightedThreat = 0.0D;
        double weightedCapacity = 0.0D;

        for (WeightedMobDefinition weightedMobDefinition
                : weightedMobDefinitions) {
            IncursionMobDefinition mobDefinition =
                    weightedMobDefinition.mobDefinition();

            double weight = weightedMobDefinition.weight();

            weightedThreat += mobDefinition.getThreatCost() * weight;
            weightedCapacity += mobDefinition.getCapacityCost() * weight;
        }

        if (weightedCapacity <= 0.0D) {
            throw new IllegalArgumentException(
                    "Weighted mob roster produced no usable capacity."
            );
        }

        return weightedThreat / weightedCapacity;
    }

    /**
     * Calculates the minimum threat allocation required to support a proposed
     * amount of source capacity.
     */
    public ThreatDensityResult calculateStructuralThreatTarget(
            List<WeightedMobDefinition> weightedMobDefinitions,
            int requiredCapacityUnits
    ) {
        return calculateStructuralThreatTarget(
                weightedMobDefinitions,
                requiredCapacityUnits,
                DEFAULT_TARGET_CAPACITY_UTILISATION,
                DEFAULT_THREAT_DENSITY_BUFFER
        );
    }

    /**
     * Calculates the minimum threat allocation required to support a proposed
     * amount of source capacity using authored utilisation and buffer values.
     */
    public ThreatDensityResult calculateStructuralThreatTarget(
            List<WeightedMobDefinition> weightedMobDefinitions,
            int requiredCapacityUnits,
            double targetCapacityUtilisation,
            double threatDensityBuffer
    ) {
        if (requiredCapacityUnits <= 0) {
            throw new IllegalArgumentException(
                    "Required capacity must be greater than zero."
            );
        }

        validateTargetCapacityUtilisation(targetCapacityUtilisation);
        validateThreatDensityBuffer(threatDensityBuffer);

        double rosterThreatDensity =
                calculateRosterThreatDensity(weightedMobDefinitions);

        double baseStructuralThreat =
                requiredCapacityUnits
                        * rosterThreatDensity
                        * targetCapacityUtilisation;

        int structuralThreatTarget = (int) Math.ceil(
                baseStructuralThreat * threatDensityBuffer
        );

        return new ThreatDensityResult(
                rosterThreatDensity,
                requiredCapacityUnits,
                targetCapacityUtilisation,
                threatDensityBuffer,
                baseStructuralThreat,
                structuralThreatTarget
        );
    }

    private void validateWeightedRoster(
            List<WeightedMobDefinition> weightedMobDefinitions
    ) {
        if (weightedMobDefinitions == null
                || weightedMobDefinitions.isEmpty()) {
            throw new IllegalArgumentException(
                    "Weighted mob roster cannot be null or empty."
            );
        }

        boolean hasPositiveWeight = false;

        for (int index = 0;
             index < weightedMobDefinitions.size();
             index++) {
            WeightedMobDefinition weightedMobDefinition =
                    weightedMobDefinitions.get(index);

            if (weightedMobDefinition == null) {
                throw new IllegalArgumentException(
                        "Weighted mob definition at index "
                                + index
                                + " cannot be null."
                );
            }

            if (weightedMobDefinition.weight() > 0.0D) {
                hasPositiveWeight = true;
            }
        }

        if (!hasPositiveWeight) {
            throw new IllegalArgumentException(
                    "Weighted mob roster must contain at least one "
                            + "positive weight."
            );
        }
    }

    private void validateTargetCapacityUtilisation(
            double targetCapacityUtilisation
    ) {
        if (!Double.isFinite(targetCapacityUtilisation)) {
            throw new IllegalArgumentException(
                    "Target capacity utilisation must be finite."
            );
        }

        if (targetCapacityUtilisation <= 0.0D
                || targetCapacityUtilisation > 1.0D) {
            throw new IllegalArgumentException(
                    "Target capacity utilisation must be greater than "
                            + "0.0 and no greater than 1.0."
            );
        }
    }

    private void validateThreatDensityBuffer(
            double threatDensityBuffer
    ) {
        if (!Double.isFinite(threatDensityBuffer)) {
            throw new IllegalArgumentException(
                    "Threat-density buffer must be finite."
            );
        }

        if (threatDensityBuffer < 1.0D) {
            throw new IllegalArgumentException(
                    "Threat-density buffer cannot be less than 1.0."
            );
        }
    }

    /**
     * One mob definition combined with its resolved planning weight.
     *
     * This may eventually be built from Scenario baseline weights plus
     * Stratagem characteristic adjustments.
     */
    public record WeightedMobDefinition(
            IncursionMobDefinition mobDefinition,
            double weight
    ) {
        public WeightedMobDefinition {
            if (mobDefinition == null) {
                throw new IllegalArgumentException(
                        "Mob definition cannot be null."
                );
            }

            if (!Double.isFinite(weight)) {
                throw new IllegalArgumentException(
                        "Mob planning weight must be finite."
                );
            }

            if (weight < 0.0D) {
                throw new IllegalArgumentException(
                        "Mob planning weight cannot be negative."
                );
            }
        }
    }

    /**
     * Detailed result retained for planning decisions and future debug output.
     */
    public record ThreatDensityResult(
            double rosterThreatDensity,
            int requiredCapacityUnits,
            double targetCapacityUtilisation,
            double threatDensityBuffer,
            double baseStructuralThreat,
            int structuralThreatTarget
    ) {
        public ThreatDensityResult {
            if (!Double.isFinite(rosterThreatDensity)
                    || rosterThreatDensity <= 0.0D) {
                throw new IllegalArgumentException(
                        "Roster threat density must be finite and positive."
                );
            }

            if (requiredCapacityUnits <= 0) {
                throw new IllegalArgumentException(
                        "Required capacity must be greater than zero."
                );
            }

            if (!Double.isFinite(baseStructuralThreat)
                    || baseStructuralThreat <= 0.0D) {
                throw new IllegalArgumentException(
                        "Base structural threat must be finite and positive."
                );
            }

            if (structuralThreatTarget <= 0) {
                throw new IllegalArgumentException(
                        "Structural threat target must be positive."
                );
            }
        }

        public boolean isSatisfiedBy(int availableThreat) {
            return availableThreat >= structuralThreatTarget;
        }
    }
}