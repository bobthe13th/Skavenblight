package org.ratden.skavenblight.event.skavenIncursion.planning.validation;

import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlanningContext;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.SourceGroupComposition;
import org.ratden.skavenblight.event.skavenIncursion.planning.front.FrontPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceGroupPlacementPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourcePlacementPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourcePlacementProfile;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourcePlacementProfileCatalogue;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.WarpFluxNetworkGeometry;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Validates a completed IncursionPlan before runtime execution.
 *
 * The validator confirms that:
 *
 * - front, wave, composition and placement identities are unique;
 * - wave threat spending remains within budget;
 * - source compositions fit their required source capacities;
 * - every source-group composition has one physical group binding;
 * - every source composition has one physical source binding;
 * - reused physical sources remain compatible with all bound compositions;
 * - stored placement profiles match the authored profile catalogue;
 * - physical source reservation areas do not overlap;
 * - ordinary source reservations respect the hard Warp Flux network
 *   clearance selected by the planning context.
 *
 * The validator reports invalid plans but does not repair or redesign them.
 */
public class IncursionPlanValidator {

    public PlanValidationResult validate(
            IncursionPlanningContext context,
            IncursionPlan incursionPlan
    ) {
        if (context == null) {
            return failure(
                    "Planning context cannot be null."
            );
        }

        if (incursionPlan == null) {
            return failure(
                    "Incursion plan cannot be null."
            );
        }

        if (!incursionPlan.hasFrontPlans()) {
            return failure(
                    "Incursion plan "
                            + incursionPlan.getIncursionId()
                            + " contains no fronts."
            );
        }

        ValidationIndex validationIndex =
                new ValidationIndex();

        for (FrontPlan frontPlan
                : incursionPlan.getFrontPlans()) {

            PlanValidationResult structureResult =
                    validateFrontStructure(
                            frontPlan,
                            validationIndex
                    );

            if (!structureResult.valid()) {
                return structureResult;
            }
        }

        List<SourcePlacementPlan> allSourcePlacements =
                new ArrayList<>();

        for (FrontPlan frontPlan
                : incursionPlan.getFrontPlans()) {

            PlanValidationResult placementResult =
                    validateFrontPlacements(
                            frontPlan,
                            validationIndex,
                            allSourcePlacements
                    );

            if (!placementResult.valid()) {
                return placementResult;
            }
        }

        PlanValidationResult bindingResult =
                validateCompleteBindings(
                        validationIndex
                );

        if (!bindingResult.valid()) {
            return bindingResult;
        }

        PlanValidationResult overlapResult =
                validateReservationOverlap(
                        allSourcePlacements
                );

        if (!overlapResult.valid()) {
            return overlapResult;
        }

        PlanValidationResult networkClearanceResult =
                validateNetworkClearance(
                        context,
                        allSourcePlacements
                );

        if (!networkClearanceResult.valid()) {
            return networkClearanceResult;
        }

        return PlanValidationResult.success();
    }

    // =========================================================
    // Front, wave and composition structure
    // =========================================================

    private PlanValidationResult validateFrontStructure(
            FrontPlan frontPlan,
            ValidationIndex validationIndex
    ) {
        if (frontPlan == null) {
            return failure(
                    "Incursion plan contains a null front."
            );
        }

        if (!validationIndex.frontIds.add(
                frontPlan.getFrontId()
        )) {
            return failure(
                    "Duplicate front ID "
                            + frontPlan.getFrontId()
                            + "."
            );
        }

        if (!validationIndex.frontIndexes.add(
                frontPlan.getFrontIndex()
        )) {
            return failure(
                    "Duplicate front index "
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

        Set<Integer> waveIndexes =
                new HashSet<>();

        for (FrontPlan.WavePlan wavePlan
                : frontPlan.getWavePlans()) {

            PlanValidationResult waveResult =
                    validateWave(
                            frontPlan,
                            wavePlan,
                            waveIndexes,
                            validationIndex
                    );

            if (!waveResult.valid()) {
                return waveResult;
            }
        }

        return PlanValidationResult.success();
    }

    private PlanValidationResult validateWave(
            FrontPlan frontPlan,
            FrontPlan.WavePlan wavePlan,
            Set<Integer> waveIndexes,
            ValidationIndex validationIndex
    ) {
        if (wavePlan == null) {
            return failure(
                    "Front "
                            + frontPlan.getFrontId()
                            + " contains a null wave plan."
            );
        }

        if (!waveIndexes.add(
                wavePlan.getWaveIndex()
        )) {
            return failure(
                    "Front "
                            + frontPlan.getFrontId()
                            + " contains duplicate wave index "
                            + wavePlan.getWaveIndex()
                            + "."
            );
        }

        if (wavePlan.getThreatSpent()
                > wavePlan.getThreatBudget()) {
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

        if (wavePlan.getThreatBudget() > 0
                && !wavePlan.hasSourceGroupCompositions()) {
            return failure(
                    "Front "
                            + frontPlan.getFrontId()
                            + ", wave "
                            + wavePlan.getWaveIndex()
                            + " has a positive threat budget but no "
                            + "source-group compositions."
            );
        }

        for (SourceGroupComposition sourceGroupComposition
                : wavePlan.getSourceGroupCompositions()) {

            PlanValidationResult compositionResult =
                    validateSourceGroupComposition(
                            frontPlan,
                            wavePlan,
                            sourceGroupComposition,
                            validationIndex
                    );

            if (!compositionResult.valid()) {
                return compositionResult;
            }
        }

        return PlanValidationResult.success();
    }

    private PlanValidationResult
    validateSourceGroupComposition(
            FrontPlan frontPlan,
            FrontPlan.WavePlan wavePlan,
            SourceGroupComposition sourceGroupComposition,
            ValidationIndex validationIndex
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

        UUID sourceGroupCompositionId =
                sourceGroupComposition
                        .getSourceGroupCompositionId();

        if (validationIndex.sourceGroupCompositions
                .putIfAbsent(
                        sourceGroupCompositionId,
                        sourceGroupComposition
                ) != null) {
            return failure(
                    "Duplicate source-group composition ID "
                            + sourceGroupCompositionId
                            + "."
            );
        }

        validationIndex.sourceGroupCompositionFrontIds.put(
                sourceGroupCompositionId,
                frontPlan.getFrontId()
        );

        if (sourceGroupComposition.isEmpty()) {
            return failure(
                    "Source-group composition "
                            + sourceGroupCompositionId
                            + " contains no source compositions."
            );
        }

        for (SourceGroupComposition.SourceComposition
                sourceComposition
                : sourceGroupComposition
                .getSourceCompositions()) {

            PlanValidationResult sourceResult =
                    validateSourceComposition(
                            frontPlan,
                            sourceGroupComposition,
                            sourceComposition,
                            validationIndex
                    );

            if (!sourceResult.valid()) {
                return sourceResult;
            }
        }

        return PlanValidationResult.success();
    }

    private PlanValidationResult validateSourceComposition(
            FrontPlan frontPlan,
            SourceGroupComposition sourceGroupComposition,
            SourceGroupComposition.SourceComposition
                    sourceComposition,
            ValidationIndex validationIndex
    ) {
        if (sourceComposition == null) {
            return failure(
                    "Source-group composition "
                            + sourceGroupComposition
                            .getSourceGroupCompositionId()
                            + " contains a null source composition."
            );
        }

        UUID sourceCompositionId =
                sourceComposition
                        .getSourceCompositionId();

        if (validationIndex.sourceCompositions
                .putIfAbsent(
                        sourceCompositionId,
                        sourceComposition
                ) != null) {
            return failure(
                    "Duplicate source composition ID "
                            + sourceCompositionId
                            + "."
            );
        }

        validationIndex.sourceCompositionGroupIds.put(
                sourceCompositionId,
                sourceGroupComposition
                        .getSourceGroupCompositionId()
        );

        validationIndex.sourceCompositionFrontIds.put(
                sourceCompositionId,
                frontPlan.getFrontId()
        );

        if (sourceComposition.isEmpty()) {
            return failure(
                    "Source composition "
                            + sourceCompositionId
                            + " contains no mobs."
            );
        }

        if (sourceComposition.getUsedCapacityUnits()
                > sourceComposition
                .getRequiredSourceSize()
                .getCapacityUnits()) {
            return failure(
                    "Source composition "
                            + sourceCompositionId
                            + " exceeds its required source capacity."
            );
        }

        return PlanValidationResult.success();
    }

    // =========================================================
    // Physical placement structure and bindings
    // =========================================================

    private PlanValidationResult validateFrontPlacements(
            FrontPlan frontPlan,
            ValidationIndex validationIndex,
            List<SourcePlacementPlan> allSourcePlacements
    ) {
        for (SourceGroupPlacementPlan
                sourceGroupPlacementPlan
                : frontPlan
                .getSourceGroupPlacementPlans()) {

            PlanValidationResult groupResult =
                    validateSourceGroupPlacement(
                            frontPlan,
                            sourceGroupPlacementPlan,
                            validationIndex,
                            allSourcePlacements
                    );

            if (!groupResult.valid()) {
                return groupResult;
            }
        }

        return PlanValidationResult.success();
    }

    private PlanValidationResult validateSourceGroupPlacement(
            FrontPlan frontPlan,
            SourceGroupPlacementPlan sourceGroupPlacementPlan,
            ValidationIndex validationIndex,
            List<SourcePlacementPlan> allSourcePlacements
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

        if (!validationIndex.sourceGroupPlacementIds.add(
                sourceGroupPlacementPlan
                        .getSourceGroupPlacementId()
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

        if (sourceGroupPlacementPlan
                .getSourceGroupCompositionIds()
                .isEmpty()) {
            return failure(
                    "Source-group placement "
                            + sourceGroupPlacementPlan
                            .getSourceGroupPlacementId()
                            + " is not bound to any source-group "
                            + "composition."
            );
        }

        Set<UUID> groupCompositionIds =
                new HashSet<>();

        for (UUID sourceGroupCompositionId
                : sourceGroupPlacementPlan
                .getSourceGroupCompositionIds()) {

            PlanValidationResult bindingResult =
                    validateSourceGroupBinding(
                            frontPlan,
                            sourceGroupPlacementPlan,
                            sourceGroupCompositionId,
                            groupCompositionIds,
                            validationIndex
                    );

            if (!bindingResult.valid()) {
                return bindingResult;
            }
        }

        for (SourcePlacementPlan sourcePlacementPlan
                : sourceGroupPlacementPlan
                .getSourcePlacementPlans()) {

            PlanValidationResult sourceResult =
                    validateSourcePlacement(
                            frontPlan,
                            sourceGroupPlacementPlan,
                            sourcePlacementPlan,
                            groupCompositionIds,
                            validationIndex
                    );

            if (!sourceResult.valid()) {
                return sourceResult;
            }

            allSourcePlacements.add(
                    sourcePlacementPlan
            );
        }

        return PlanValidationResult.success();
    }

    private PlanValidationResult validateSourceGroupBinding(
            FrontPlan frontPlan,
            SourceGroupPlacementPlan sourceGroupPlacementPlan,
            UUID sourceGroupCompositionId,
            Set<UUID> localGroupCompositionIds,
            ValidationIndex validationIndex
    ) {
        if (sourceGroupCompositionId == null) {
            return failure(
                    "Source-group placement "
                            + sourceGroupPlacementPlan
                            .getSourceGroupPlacementId()
                            + " contains a null composition binding."
            );
        }

        if (!localGroupCompositionIds.add(
                sourceGroupCompositionId
        )) {
            return failure(
                    "Source-group placement "
                            + sourceGroupPlacementPlan
                            .getSourceGroupPlacementId()
                            + " contains duplicate binding "
                            + sourceGroupCompositionId
                            + "."
            );
        }

        SourceGroupComposition sourceGroupComposition =
                validationIndex.sourceGroupCompositions.get(
                        sourceGroupCompositionId
                );

        if (sourceGroupComposition == null) {
            return failure(
                    "Source-group placement "
                            + sourceGroupPlacementPlan
                            .getSourceGroupPlacementId()
                            + " refers to unknown source-group composition "
                            + sourceGroupCompositionId
                            + "."
            );
        }

        UUID compositionFrontId =
                validationIndex
                        .sourceGroupCompositionFrontIds
                        .get(sourceGroupCompositionId);

        if (!frontPlan.getFrontId().equals(
                compositionFrontId
        )) {
            return failure(
                    "Source-group composition "
                            + sourceGroupCompositionId
                            + " is bound to a placement in the wrong front."
            );
        }

        if (!validationIndex
                .boundSourceGroupCompositionIds
                .add(sourceGroupCompositionId)) {
            return failure(
                    "Source-group composition "
                            + sourceGroupCompositionId
                            + " is bound to more than one physical "
                            + "source group."
            );
        }

        for (SourceGroupComposition.SourceComposition
                sourceComposition
                : sourceGroupComposition
                .getSourceCompositions()) {

            if (sourceComposition.getSourceRole()
                    != sourceGroupPlacementPlan
                    .getSourceRole()) {
                return failure(
                        "Source-group placement "
                                + sourceGroupPlacementPlan
                                .getSourceGroupPlacementId()
                                + " has role "
                                + sourceGroupPlacementPlan
                                .getSourceRole()
                                + " but bound composition "
                                + sourceGroupCompositionId
                                + " contains source role "
                                + sourceComposition
                                .getSourceRole()
                                + "."
                );
            }
        }

        return PlanValidationResult.success();
    }

    private PlanValidationResult validateSourcePlacement(
            FrontPlan frontPlan,
            SourceGroupPlacementPlan sourceGroupPlacementPlan,
            SourcePlacementPlan sourcePlacementPlan,
            Set<UUID> groupCompositionIds,
            ValidationIndex validationIndex
    ) {
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

        if (!validationIndex.sourcePlacementIds.add(
                sourcePlacementPlan
                        .getSourcePlacementId()
        )) {
            return failure(
                    "Duplicate source placement ID "
                            + sourcePlacementPlan
                            .getSourcePlacementId()
                            + "."
            );
        }

        if (!sourcePlacementPlan.hasPlacedPos()) {
            return failure(
                    "Source placement "
                            + sourcePlacementPlan
                            .getSourcePlacementId()
                            + " does not have a final placed position."
            );
        }

        if (sourcePlacementPlan.getSourceRole()
                != sourceGroupPlacementPlan.getSourceRole()) {
            return failure(
                    "Source placement "
                            + sourcePlacementPlan
                            .getSourcePlacementId()
                            + " has role "
                            + sourcePlacementPlan.getSourceRole()
                            + " but its source group has role "
                            + sourceGroupPlacementPlan.getSourceRole()
                            + "."
            );
        }

        SourcePlacementProfile expectedProfile =
                SourcePlacementProfileCatalogue.get(
                        sourcePlacementPlan.getSourceType(),
                        sourcePlacementPlan.getSourceSize()
                );

        if (expectedProfile == null) {
            return failure(
                    "Source placement "
                            + sourcePlacementPlan
                            .getSourcePlacementId()
                            + " has no authored placement profile for "
                            + sourcePlacementPlan.getSourceType()
                            + " "
                            + sourcePlacementPlan.getSourceSize()
                            + "."
            );
        }

        if (!expectedProfile.equals(
                sourcePlacementPlan.getPlacementProfile()
        )) {
            return failure(
                    "Source placement "
                            + sourcePlacementPlan
                            .getSourcePlacementId()
                            + " does not use the authored placement profile "
                            + "for "
                            + sourcePlacementPlan.getSourceType()
                            + " "
                            + sourcePlacementPlan.getSourceSize()
                            + "."
            );
        }

        if (sourcePlacementPlan
                .getSourceCompositionIds()
                .isEmpty()) {
            return failure(
                    "Source placement "
                            + sourcePlacementPlan
                            .getSourcePlacementId()
                            + " is not bound to any source composition."
            );
        }

        Set<UUID> localCompositionIds =
                new HashSet<>();

        Set<UUID> localGroupCompositionIds =
                new HashSet<>();

        for (UUID sourceCompositionId
                : sourcePlacementPlan
                .getSourceCompositionIds()) {

            PlanValidationResult bindingResult =
                    validateSourceBinding(
                            frontPlan,
                            sourceGroupPlacementPlan,
                            sourcePlacementPlan,
                            sourceCompositionId,
                            groupCompositionIds,
                            localCompositionIds,
                            localGroupCompositionIds,
                            validationIndex
                    );

            if (!bindingResult.valid()) {
                return bindingResult;
            }
        }

        return PlanValidationResult.success();
    }

    private PlanValidationResult validateSourceBinding(
            FrontPlan frontPlan,
            SourceGroupPlacementPlan sourceGroupPlacementPlan,
            SourcePlacementPlan sourcePlacementPlan,
            UUID sourceCompositionId,
            Set<UUID> groupCompositionIds,
            Set<UUID> localCompositionIds,
            Set<UUID> localGroupCompositionIds,
            ValidationIndex validationIndex
    ) {
        if (sourceCompositionId == null) {
            return failure(
                    "Source placement "
                            + sourcePlacementPlan
                            .getSourcePlacementId()
                            + " contains a null composition binding."
            );
        }

        if (!localCompositionIds.add(
                sourceCompositionId
        )) {
            return failure(
                    "Source placement "
                            + sourcePlacementPlan
                            .getSourcePlacementId()
                            + " contains duplicate composition binding "
                            + sourceCompositionId
                            + "."
            );
        }

        SourceGroupComposition.SourceComposition
                sourceComposition =
                validationIndex.sourceCompositions.get(
                        sourceCompositionId
                );

        if (sourceComposition == null) {
            return failure(
                    "Source placement "
                            + sourcePlacementPlan
                            .getSourcePlacementId()
                            + " refers to unknown source composition "
                            + sourceCompositionId
                            + "."
            );
        }

        UUID compositionFrontId =
                validationIndex
                        .sourceCompositionFrontIds
                        .get(sourceCompositionId);

        if (!frontPlan.getFrontId().equals(
                compositionFrontId
        )) {
            return failure(
                    "Source composition "
                            + sourceCompositionId
                            + " is bound to a placement in the wrong front."
            );
        }

        UUID parentGroupCompositionId =
                validationIndex
                        .sourceCompositionGroupIds
                        .get(sourceCompositionId);

        if (!groupCompositionIds.contains(
                parentGroupCompositionId
        )) {
            return failure(
                    "Source composition "
                            + sourceCompositionId
                            + " does not belong to a source-group "
                            + "composition bound to physical group "
                            + sourceGroupPlacementPlan
                            .getSourceGroupPlacementId()
                            + "."
            );
        }

        if (!localGroupCompositionIds.add(
                parentGroupCompositionId
        )) {
            return failure(
                    "Source placement "
                            + sourcePlacementPlan
                            .getSourcePlacementId()
                            + " is bound to more than one source composition "
                            + "from source-group composition "
                            + parentGroupCompositionId
                            + "."
            );
        }

        if (!validationIndex
                .boundSourceCompositionIds
                .add(sourceCompositionId)) {
            return failure(
                    "Source composition "
                            + sourceCompositionId
                            + " is bound to more than one physical source."
            );
        }

        if (sourcePlacementPlan.getSourceType()
                != sourceComposition
                .getRequiredSourceType()) {
            return failure(
                    "Source placement "
                            + sourcePlacementPlan
                            .getSourcePlacementId()
                            + " has type "
                            + sourcePlacementPlan.getSourceType()
                            + " but composition "
                            + sourceCompositionId
                            + " requires "
                            + sourceComposition
                            .getRequiredSourceType()
                            + "."
            );
        }

        if (sourcePlacementPlan.getSourceRole()
                != sourceComposition.getSourceRole()) {
            return failure(
                    "Source placement "
                            + sourcePlacementPlan
                            .getSourcePlacementId()
                            + " has role "
                            + sourcePlacementPlan.getSourceRole()
                            + " but composition "
                            + sourceCompositionId
                            + " requires "
                            + sourceComposition.getSourceRole()
                            + "."
            );
        }

        if (!sourcePlacementPlan
                .getSourceSize()
                .canFit(
                        sourceComposition
                                .getRequiredSourceSize()
                )) {
            return failure(
                    "Source placement "
                            + sourcePlacementPlan
                            .getSourcePlacementId()
                            + " has size "
                            + sourcePlacementPlan.getSourceSize()
                            + " but composition "
                            + sourceCompositionId
                            + " requires at least "
                            + sourceComposition
                            .getRequiredSourceSize()
                            + "."
            );
        }

        return PlanValidationResult.success();
    }

    // =========================================================
    // Completed binding and reservation checks
    // =========================================================

    private PlanValidationResult validateCompleteBindings(
            ValidationIndex validationIndex
    ) {
        for (UUID sourceGroupCompositionId
                : validationIndex
                .sourceGroupCompositions
                .keySet()) {

            if (!validationIndex
                    .boundSourceGroupCompositionIds
                    .contains(sourceGroupCompositionId)) {
                return failure(
                        "Source-group composition "
                                + sourceGroupCompositionId
                                + " has no physical source-group placement."
                );
            }
        }

        for (UUID sourceCompositionId
                : validationIndex
                .sourceCompositions
                .keySet()) {

            if (!validationIndex
                    .boundSourceCompositionIds
                    .contains(sourceCompositionId)) {
                return failure(
                        "Source composition "
                                + sourceCompositionId
                                + " has no physical source placement."
                );
            }
        }

        return PlanValidationResult.success();
    }

    private PlanValidationResult validateReservationOverlap(
            List<SourcePlacementPlan> sourcePlacements
    ) {
        for (int firstIndex = 0;
             firstIndex < sourcePlacements.size();
             firstIndex++) {

            SourcePlacementPlan firstPlacement =
                    sourcePlacements.get(firstIndex);

            for (int secondIndex = firstIndex + 1;
                 secondIndex < sourcePlacements.size();
                 secondIndex++) {

                SourcePlacementPlan secondPlacement =
                        sourcePlacements.get(secondIndex);

                if (firstPlacement.reservationOverlaps(
                        secondPlacement
                )) {
                    return failure(
                            "Source placements "
                                    + firstPlacement
                                    .getSourcePlacementId()
                                    + " and "
                                    + secondPlacement
                                    .getSourcePlacementId()
                                    + " have overlapping reservation areas."
                    );
                }
            }
        }

        return PlanValidationResult.success();
    }

    /**
     * Independently confirms the hard source-exclusion rule after placement.
     *
     * The complete source reservation footprint is checked against the immutable
     * Warp Flux network snapshot captured when the planning context was created.
     *
     * When no protected-network snapshot exists, there is no network boundary to
     * validate.
     */
    private PlanValidationResult validateNetworkClearance(
            IncursionPlanningContext context,
            List<SourcePlacementPlan> sourcePlacements
    ) {
        int minimumClearance =
                context
                        .frontDistanceProfile()
                        .getHardMinimumNetworkClearance();

        WarpFluxNetworkGeometry networkGeometry =
                context.protectedNetworkGeometrySnapshot();

        if (networkGeometry == null) {
            return PlanValidationResult.success();
        }

        for (SourcePlacementPlan sourcePlacementPlan
                : sourcePlacements) {

            if (!networkGeometry.hasReservationClearance(
                    sourcePlacementPlan.getReservationBounds(),
                    minimumClearance
            )) {
                return failure(
                        "Source placement "
                                + sourcePlacementPlan
                                .getSourcePlacementId()
                                + " violates the captured Warp Flux network "
                                + "clearance of "
                                + minimumClearance
                                + " blocks."
                );
            }
        }

        return PlanValidationResult.success();
    }

    private PlanValidationResult failure(
            String message
    ) {
        return PlanValidationResult.failure(
                message
        );
    }

    /**
     * Temporary lookup structure used only during one validation pass.
     */
    private static class ValidationIndex {

        private final Set<UUID> frontIds =
                new HashSet<>();

        private final Set<Integer> frontIndexes =
                new HashSet<>();

        private final Map<UUID, SourceGroupComposition>
                sourceGroupCompositions =
                new HashMap<>();

        private final Map<UUID, UUID>
                sourceGroupCompositionFrontIds =
                new HashMap<>();

        private final Map<
                UUID,
                SourceGroupComposition.SourceComposition
                > sourceCompositions =
                new HashMap<>();

        private final Map<UUID, UUID>
                sourceCompositionGroupIds =
                new HashMap<>();

        private final Map<UUID, UUID>
                sourceCompositionFrontIds =
                new HashMap<>();

        private final Set<UUID>
                sourceGroupPlacementIds =
                new HashSet<>();

        private final Set<UUID>
                sourcePlacementIds =
                new HashSet<>();

        private final Set<UUID>
                boundSourceGroupCompositionIds =
                new HashSet<>();

        private final Set<UUID>
                boundSourceCompositionIds =
                new HashSet<>();
    }
}