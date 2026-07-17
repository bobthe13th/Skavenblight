package org.ratden.skavenblight.event.skavenIncursion.planning.validation;

import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.SourceGroupComposition;
import org.ratden.skavenblight.event.skavenIncursion.planning.front.FrontPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceGroupPlacementPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourcePlacementPlan;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Validates a completed IncursionPlan before runtime execution.
 *
 * The validator reports problems but does not repair or redesign the plan.
 * Further checks can be added as leadership, scheduling, complexity spending,
 * and world placement are implemented.
 */
public class IncursionPlanValidator {

    public PlanValidationResult validate(IncursionPlan incursionPlan) {
        if (incursionPlan == null) {
            return failure("Incursion plan cannot be null.");
        }

        if (!incursionPlan.hasFrontPlans()) {
            return failure(
                    "Incursion plan "
                            + incursionPlan.getIncursionId()
                            + " contains no fronts."
            );
        }

        Set<UUID> frontIds = new HashSet<>();
        Set<Integer> frontIndexes = new HashSet<>();

        for (FrontPlan frontPlan : incursionPlan.getFrontPlans()) {
            PlanValidationResult frontResult = validateFront(
                    frontPlan,
                    frontIds,
                    frontIndexes
            );

            if (!frontResult.valid()) {
                return frontResult;
            }
        }

        return PlanValidationResult.success();
    }

    private PlanValidationResult validateFront(
            FrontPlan frontPlan,
            Set<UUID> frontIds,
            Set<Integer> frontIndexes
    ) {
        if (frontPlan == null) {
            return failure("Incursion plan contains a null front.");
        }

        if (!frontIds.add(frontPlan.getFrontId())) {
            return failure(
                    "Duplicate front ID: "
                            + frontPlan.getFrontId()
                            + "."
            );
        }

        if (!frontIndexes.add(frontPlan.getFrontIndex())) {
            return failure(
                    "Duplicate front index: "
                            + frontPlan.getFrontIndex()
                            + "."
            );
        }

        if (!frontPlan.hasWavePlans()) {
            return failure(
                    "Front "
                            + frontPlan.getFrontId()
                            + " contains no wave plans."
            );
        }

        Set<Integer> waveIndexes = new HashSet<>();
        Set<UUID> sourceGroupCompositionIds = new HashSet<>();
        Set<UUID> sourceCompositionIds = new HashSet<>();

        for (FrontPlan.WavePlan wavePlan : frontPlan.getWavePlans()) {
            PlanValidationResult waveResult = validateWave(
                    frontPlan,
                    wavePlan,
                    waveIndexes,
                    sourceGroupCompositionIds,
                    sourceCompositionIds
            );

            if (!waveResult.valid()) {
                return waveResult;
            }
        }

        Set<UUID> sourceGroupPlacementIds = new HashSet<>();
        Set<UUID> sourcePlacementIds = new HashSet<>();

        for (SourceGroupPlacementPlan sourceGroupPlacementPlan
                : frontPlan.getSourceGroupPlacementPlans()) {
            PlanValidationResult placementResult =
                    validateSourceGroupPlacement(
                            frontPlan,
                            sourceGroupPlacementPlan,
                            sourceGroupPlacementIds,
                            sourcePlacementIds
                    );

            if (!placementResult.valid()) {
                return placementResult;
            }
        }

        return PlanValidationResult.success();
    }

    private PlanValidationResult validateWave(
            FrontPlan frontPlan,
            FrontPlan.WavePlan wavePlan,
            Set<Integer> waveIndexes,
            Set<UUID> sourceGroupCompositionIds,
            Set<UUID> sourceCompositionIds
    ) {
        if (wavePlan == null) {
            return failure(
                    "Front "
                            + frontPlan.getFrontId()
                            + " contains a null wave plan."
            );
        }

        if (!waveIndexes.add(wavePlan.getWaveIndex())) {
            return failure(
                    "Front "
                            + frontPlan.getFrontId()
                            + " contains duplicate wave index "
                            + wavePlan.getWaveIndex()
                            + "."
            );
        }

        if (wavePlan.getThreatSpent() > wavePlan.getThreatBudget()) {
            return failure(
                    "Front "
                            + frontPlan.getFrontId()
                            + ", wave "
                            + wavePlan.getWaveIndex()
                            + " spent "
                            + wavePlan.getThreatSpent()
                            + " threat from a budget of "
                            + wavePlan.getThreatBudget()
                            + "."
            );
        }

        for (SourceGroupComposition sourceGroupComposition
                : wavePlan.getSourceGroupCompositions()) {
            PlanValidationResult compositionResult =
                    validateSourceGroupComposition(
                            frontPlan,
                            wavePlan,
                            sourceGroupComposition,
                            sourceGroupCompositionIds,
                            sourceCompositionIds
                    );

            if (!compositionResult.valid()) {
                return compositionResult;
            }
        }

        return PlanValidationResult.success();
    }

    private PlanValidationResult validateSourceGroupComposition(
            FrontPlan frontPlan,
            FrontPlan.WavePlan wavePlan,
            SourceGroupComposition sourceGroupComposition,
            Set<UUID> sourceGroupCompositionIds,
            Set<UUID> sourceCompositionIds
    ) {
        if (sourceGroupComposition == null) {
            return failure(
                    "Front "
                            + frontPlan.getFrontId()
                            + ", wave "
                            + wavePlan.getWaveIndex()
                            + " contains a null source-group composition."
            );
        }

        if (!sourceGroupCompositionIds.add(
                sourceGroupComposition.getSourceGroupCompositionId()
        )) {
            return failure(
                    "Duplicate source-group composition ID "
                            + sourceGroupComposition
                            .getSourceGroupCompositionId()
                            + "."
            );
        }

        if (sourceGroupComposition.isEmpty()) {
            return failure(
                    "Source-group composition "
                            + sourceGroupComposition
                            .getSourceGroupCompositionId()
                            + " contains no source compositions."
            );
        }

        for (SourceGroupComposition.SourceComposition sourceComposition
                : sourceGroupComposition.getSourceCompositions()) {
            if (sourceComposition == null) {
                return failure(
                        "Source-group composition "
                                + sourceGroupComposition
                                .getSourceGroupCompositionId()
                                + " contains a null source composition."
                );
            }

            if (!sourceCompositionIds.add(
                    sourceComposition.getSourceCompositionId()
            )) {
                return failure(
                        "Duplicate source composition ID "
                                + sourceComposition
                                .getSourceCompositionId()
                                + "."
                );
            }

            if (sourceComposition.isEmpty()) {
                return failure(
                        "Source composition "
                                + sourceComposition
                                .getSourceCompositionId()
                                + " contains no mobs."
                );
            }

            if (sourceComposition.getUsedCapacityUnits()
                    > sourceComposition
                    .getRequiredSourceSize()
                    .getCapacityUnits()) {
                return failure(
                        "Source composition "
                                + sourceComposition
                                .getSourceCompositionId()
                                + " exceeds its required source capacity."
                );
            }
        }

        return PlanValidationResult.success();
    }

    private PlanValidationResult validateSourceGroupPlacement(
            FrontPlan frontPlan,
            SourceGroupPlacementPlan sourceGroupPlacementPlan,
            Set<UUID> sourceGroupPlacementIds,
            Set<UUID> sourcePlacementIds
    ) {
        if (sourceGroupPlacementPlan == null) {
            return failure(
                    "Front "
                            + frontPlan.getFrontId()
                            + " contains a null source-group placement."
            );
        }

        if (!frontPlan.getFrontId().equals(
                sourceGroupPlacementPlan.getFrontId()
        )) {
            return failure(
                    "Source-group placement "
                            + sourceGroupPlacementPlan
                            .getSourceGroupPlacementId()
                            + " belongs to the wrong front."
            );
        }

        if (!sourceGroupPlacementIds.add(
                sourceGroupPlacementPlan.getSourceGroupPlacementId()
        )) {
            return failure(
                    "Duplicate source-group placement ID "
                            + sourceGroupPlacementPlan
                            .getSourceGroupPlacementId()
                            + "."
            );
        }

        if (sourceGroupPlacementPlan.isEmpty()) {
            return failure(
                    "Source-group placement "
                            + sourceGroupPlacementPlan
                            .getSourceGroupPlacementId()
                            + " contains no source placements."
            );
        }

        for (SourcePlacementPlan sourcePlacementPlan
                : sourceGroupPlacementPlan.getSourcePlacementPlans()) {
            if (sourcePlacementPlan == null) {
                return failure(
                        "Source-group placement "
                                + sourceGroupPlacementPlan
                                .getSourceGroupPlacementId()
                                + " contains a null source placement."
                );
            }

            if (!sourceGroupPlacementPlan
                    .getSourceGroupPlacementId()
                    .equals(
                            sourcePlacementPlan
                                    .getSourceGroupPlacementId()
                    )) {
                return failure(
                        "Source placement "
                                + sourcePlacementPlan
                                .getSourcePlacementId()
                                + " belongs to the wrong source group."
                );
            }

            if (!sourcePlacementIds.add(
                    sourcePlacementPlan.getSourcePlacementId()
            )) {
                return failure(
                        "Duplicate source placement ID "
                                + sourcePlacementPlan
                                .getSourcePlacementId()
                                + "."
                );
            }
        }

        return PlanValidationResult.success();
    }

    private PlanValidationResult failure(String message) {
        return PlanValidationResult.failure(message);
    }
}