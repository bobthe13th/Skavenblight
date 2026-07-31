package org.ratden.skavenblight.event.skavenIncursion.planning.source;

import org.ratden.skavenblight.event.skavenIncursion.planning.composition.SourceGroupComposition;
import org.ratden.skavenblight.event.skavenIncursion.planning.front.FrontPlan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Plans the complete persistent source-group demand for one front before any
 * physical anchors, envelopes or source positions are selected.
 *
 * The planner reads every wave in the front and determines:
 *
 * - which source-group compositions can share one persistent physical group;
 * - which physical sources can be reused across later waves;
 * - when another persistent physical group is required;
 * - the complete physical-source demand over each group's full lifecycle.
 *
 * One persistent source group may execute no more than one
 * SourceGroupComposition in any particular wave. It may be reused by any
 * number of later waves.
 *
 * This planner performs no terrain checks and creates no placement plans. Its
 * output is consumed later by SourcePlacementPlanner when selecting group
 * anchors, adaptive envelopes and permanent physical source positions.
 */
public final class SourceGroupDemandPlanner {

    /**
     * Produces all persistent source-group demands required by one front.
     *
     * Wave plans are processed in ascending wave-index order, regardless of
     * their storage order inside FrontPlan.
     */
    public DemandPlanningResult planFrontDemands(
            FrontPlan frontPlan
    ) {
        if (frontPlan == null) {
            return DemandPlanningResult.failure(
                    null,
                    "Front plan cannot be null."
            );
        }

        UUID frontId =
                frontPlan.getFrontId();

        if (frontId == null) {
            return DemandPlanningResult.failure(
                    null,
                    "Front plan has no front ID."
            );
        }

        if (!frontPlan.hasWavePlans()) {
            return DemandPlanningResult.failure(
                    frontId,
                    "Front "
                            + frontId
                            + " contains no wave plans."
            );
        }

        List<FrontPlan.WavePlan> orderedWavePlans =
                new ArrayList<>(
                        frontPlan.getWavePlans()
                );

        orderedWavePlans.sort(
                Comparator.comparingInt(
                        FrontPlan.WavePlan::getWaveIndex
                )
        );

        Set<Integer> processedWaveIndexes =
                new HashSet<>();

        Set<UUID> processedSourceGroupCompositionIds =
                new HashSet<>();

        List<SourceGroupPlacementDemand> sourceGroupDemands =
                new ArrayList<>();

        for (FrontPlan.WavePlan wavePlan
                : orderedWavePlans) {

            DemandPlanningResult waveValidationResult =
                    validateWave(
                            frontId,
                            wavePlan,
                            processedWaveIndexes
                    );

            if (waveValidationResult != null) {
                return waveValidationResult;
            }

            int waveIndex =
                    wavePlan.getWaveIndex();

            for (SourceGroupComposition sourceGroupComposition
                    : wavePlan.getSourceGroupCompositions()) {

                DemandPlanningResult compositionValidationResult =
                        validateComposition(
                                frontId,
                                waveIndex,
                                sourceGroupComposition,
                                processedSourceGroupCompositionIds
                        );

                if (compositionValidationResult != null) {
                    return compositionValidationResult;
                }

                DemandSelection demandSelection =
                        findBestDemand(
                                waveIndex,
                                sourceGroupComposition,
                                sourceGroupDemands
                        );

                if (demandSelection != null) {
                    try {
                        demandSelection
                                .sourceGroupDemand()
                                .applyComposition(
                                        sourceGroupComposition,
                                        demandSelection.fitAssessment()
                                );
                    } catch (IllegalArgumentException
                             | IllegalStateException exception) {
                        return DemandPlanningResult.failure(
                                frontId,
                                "Could not bind source-group composition "
                                        + sourceGroupComposition
                                        .getSourceGroupCompositionId()
                                        + " from wave "
                                        + waveIndex
                                        + " to persistent source-group "
                                        + "demand "
                                        + demandSelection
                                        .sourceGroupDemand()
                                        .getSourceGroupDemandId()
                                        + ": "
                                        + exception.getMessage()
                        );
                    }

                    continue;
                }

                SourceGroupPlacementDemand newSourceGroupDemand;

                try {
                    newSourceGroupDemand =
                            new SourceGroupPlacementDemand(
                                    frontId,
                                    sourceGroupDemands.size(),
                                    waveIndex,
                                    sourceGroupComposition,
                                    SourceGroupSpatialRules.STANDARD
                            );
                } catch (IllegalArgumentException
                         | IllegalStateException exception) {
                    return DemandPlanningResult.failure(
                            frontId,
                            "Could not create a persistent source-group "
                                    + "demand for composition "
                                    + sourceGroupComposition
                                    .getSourceGroupCompositionId()
                                    + " from wave "
                                    + waveIndex
                                    + ": "
                                    + exception.getMessage()
                    );
                }

                sourceGroupDemands.add(
                        newSourceGroupDemand
                );
            }
        }

        DemandPlanningResult completedDemandValidation =
                validateCompletedDemands(
                        frontId,
                        sourceGroupDemands,
                        processedSourceGroupCompositionIds.size()
                );

        if (completedDemandValidation != null) {
            return completedDemandValidation;
        }

        return DemandPlanningResult.success(
                new FrontDemandPlan(
                        frontId,
                        sourceGroupDemands
                )
        );
    }

    /**
     * Finds the compatible existing persistent group that can receive the
     * composition with the smallest infrastructure increase.
     *
     * Selection priorities are:
     *
     * 1. least additional source-group load;
     * 2. fewest newly required physical sources;
     * 3. least remaining load after binding, favouring compact best-fit
     *    packing;
     * 4. greatest number of reused physical sources;
     * 5. lowest stable source-group index.
     */
    private DemandSelection findBestDemand(
            int waveIndex,
            SourceGroupComposition sourceGroupComposition,
            List<SourceGroupPlacementDemand> sourceGroupDemands
    ) {
        DemandSelection bestSelection =
                null;

        for (SourceGroupPlacementDemand sourceGroupDemand
                : sourceGroupDemands) {

            SourceGroupPlacementDemand.CompositionFitAssessment
                    fitAssessment =
                    sourceGroupDemand.assessComposition(
                            waveIndex,
                            sourceGroupComposition
                    );

            if (!fitAssessment.successful()) {
                continue;
            }

            DemandSelection candidateSelection =
                    new DemandSelection(
                            sourceGroupDemand,
                            fitAssessment
                    );

            if (bestSelection == null
                    || isBetterSelection(
                    candidateSelection,
                    bestSelection
            )) {
                bestSelection =
                        candidateSelection;
            }
        }

        return bestSelection;
    }

    private boolean isBetterSelection(
            DemandSelection candidate,
            DemandSelection currentBest
    ) {
        SourceGroupPlacementDemand.CompositionFitAssessment
                candidateAssessment =
                candidate.fitAssessment();

        SourceGroupPlacementDemand.CompositionFitAssessment
                currentAssessment =
                currentBest.fitAssessment();

        int additionalLoadComparison =
                Integer.compare(
                        candidateAssessment.additionalLoadRequired(),
                        currentAssessment.additionalLoadRequired()
                );

        if (additionalLoadComparison != 0) {
            return additionalLoadComparison < 0;
        }

        int newPhysicalSourceComparison =
                Integer.compare(
                        candidateAssessment.getNewPhysicalSourceCount(),
                        currentAssessment.getNewPhysicalSourceCount()
                );

        if (newPhysicalSourceComparison != 0) {
            return newPhysicalSourceComparison < 0;
        }

        int candidateRemainingLoad =
                candidate
                        .sourceGroupDemand()
                        .getMaximumSourceGroupLoad()
                        - candidateAssessment.resultingLoad();

        int currentRemainingLoad =
                currentBest
                        .sourceGroupDemand()
                        .getMaximumSourceGroupLoad()
                        - currentAssessment.resultingLoad();

        int remainingLoadComparison =
                Integer.compare(
                        candidateRemainingLoad,
                        currentRemainingLoad
                );

        if (remainingLoadComparison != 0) {
            return remainingLoadComparison < 0;
        }

        int reusedSourceComparison =
                Integer.compare(
                        candidateAssessment
                                .getReusedPhysicalSourceCount(),
                        currentAssessment
                                .getReusedPhysicalSourceCount()
                );

        if (reusedSourceComparison != 0) {
            return reusedSourceComparison > 0;
        }

        return candidate
                .sourceGroupDemand()
                .getSourceGroupIndex()
                < currentBest
                .sourceGroupDemand()
                .getSourceGroupIndex();
    }

    /**
     * Returns a failure result when the wave is invalid, otherwise null.
     */
    private DemandPlanningResult validateWave(
            UUID frontId,
            FrontPlan.WavePlan wavePlan,
            Set<Integer> processedWaveIndexes
    ) {
        if (wavePlan == null) {
            return DemandPlanningResult.failure(
                    frontId,
                    "Front "
                            + frontId
                            + " contains a null wave plan."
            );
        }

        int waveIndex =
                wavePlan.getWaveIndex();

        if (waveIndex < 0) {
            return DemandPlanningResult.failure(
                    frontId,
                    "Front "
                            + frontId
                            + " contains negative wave index "
                            + waveIndex
                            + "."
            );
        }

        if (!processedWaveIndexes.add(
                waveIndex
        )) {
            return DemandPlanningResult.failure(
                    frontId,
                    "Front "
                            + frontId
                            + " contains duplicate wave index "
                            + waveIndex
                            + "."
            );
        }

        if (wavePlan.getSourceGroupCompositions() == null) {
            return DemandPlanningResult.failure(
                    frontId,
                    "Front "
                            + frontId
                            + ", wave "
                            + waveIndex
                            + " has a null source-group composition list."
            );
        }

        return null;
    }

    /**
     * Returns a failure result when the composition is invalid, otherwise
     * null.
     */
    private DemandPlanningResult validateComposition(
            UUID frontId,
            int waveIndex,
            SourceGroupComposition sourceGroupComposition,
            Set<UUID> processedSourceGroupCompositionIds
    ) {
        if (sourceGroupComposition == null) {
            return DemandPlanningResult.failure(
                    frontId,
                    "Front "
                            + frontId
                            + ", wave "
                            + waveIndex
                            + " contains a null source-group composition."
            );
        }

        UUID sourceGroupCompositionId =
                sourceGroupComposition
                        .getSourceGroupCompositionId();

        if (sourceGroupCompositionId == null) {
            return DemandPlanningResult.failure(
                    frontId,
                    "Front "
                            + frontId
                            + ", wave "
                            + waveIndex
                            + " contains a source-group composition with no "
                            + "ID."
            );
        }

        if (!processedSourceGroupCompositionIds.add(
                sourceGroupCompositionId
        )) {
            return DemandPlanningResult.failure(
                    frontId,
                    "Front "
                            + frontId
                            + " contains duplicate source-group composition "
                            + "ID "
                            + sourceGroupCompositionId
                            + "."
            );
        }

        if (sourceGroupComposition.isEmpty()) {
            return DemandPlanningResult.failure(
                    frontId,
                    "Source-group composition "
                            + sourceGroupCompositionId
                            + " in front "
                            + frontId
                            + ", wave "
                            + waveIndex
                            + " is empty."
            );
        }

        if (sourceGroupComposition.getSourceRole() == null) {
            return DemandPlanningResult.failure(
                    frontId,
                    "Source-group composition "
                            + sourceGroupCompositionId
                            + " has no source role."
            );
        }

        if (sourceGroupComposition.getSourceGroupRules() == null) {
            return DemandPlanningResult.failure(
                    frontId,
                    "Source-group composition "
                            + sourceGroupCompositionId
                            + " has no source-group rules."
            );
        }

        return null;
    }

    /**
     * Performs consistency checks after every wave composition has been
     * assigned.
     *
     * Returns a failure result when invalid, otherwise null.
     */
    private DemandPlanningResult validateCompletedDemands(
            UUID frontId,
            List<SourceGroupPlacementDemand> sourceGroupDemands,
            int expectedCompositionCount
    ) {
        int boundCompositionCount =
                0;

        Set<UUID> sourceGroupDemandIds =
                new HashSet<>();

        Set<Integer> sourceGroupIndexes =
                new HashSet<>();

        Set<UUID> boundSourceGroupCompositionIds =
                new HashSet<>();

        for (int expectedIndex = 0;
             expectedIndex < sourceGroupDemands.size();
             expectedIndex++) {

            SourceGroupPlacementDemand sourceGroupDemand =
                    sourceGroupDemands.get(
                            expectedIndex
                    );

            if (sourceGroupDemand == null) {
                return DemandPlanningResult.failure(
                        frontId,
                        "Front "
                                + frontId
                                + " contains a null source-group demand."
                );
            }

            if (!frontId.equals(
                    sourceGroupDemand.getFrontId()
            )) {
                return DemandPlanningResult.failure(
                        frontId,
                        "Source-group demand "
                                + sourceGroupDemand.getSourceGroupDemandId()
                                + " belongs to a different front."
                );
            }

            if (!sourceGroupDemandIds.add(
                    sourceGroupDemand.getSourceGroupDemandId()
            )) {
                return DemandPlanningResult.failure(
                        frontId,
                        "Front "
                                + frontId
                                + " contains duplicate source-group demand "
                                + "ID "
                                + sourceGroupDemand
                                .getSourceGroupDemandId()
                                + "."
                );
            }

            if (!sourceGroupIndexes.add(
                    sourceGroupDemand.getSourceGroupIndex()
            )) {
                return DemandPlanningResult.failure(
                        frontId,
                        "Front "
                                + frontId
                                + " contains duplicate source-group demand "
                                + "index "
                                + sourceGroupDemand
                                .getSourceGroupIndex()
                                + "."
                );
            }

            if (sourceGroupDemand.getSourceGroupIndex()
                    != expectedIndex) {
                return DemandPlanningResult.failure(
                        frontId,
                        "Source-group demand "
                                + sourceGroupDemand.getSourceGroupDemandId()
                                + " has index "
                                + sourceGroupDemand.getSourceGroupIndex()
                                + " but appears at ordered position "
                                + expectedIndex
                                + "."
                );
            }

            if (!sourceGroupDemand
                    .isWithinSourceGroupLoadLimit()) {
                return DemandPlanningResult.failure(
                        frontId,
                        "Source-group demand "
                                + sourceGroupDemand.getSourceGroupDemandId()
                                + " uses load "
                                + sourceGroupDemand
                                .getTotalSourceGroupLoad()
                                + " from a maximum of "
                                + sourceGroupDemand
                                .getMaximumSourceGroupLoad()
                                + "."
                );
            }

            if (sourceGroupDemand.getPhysicalSourceCount()
                    <= 0) {
                return DemandPlanningResult.failure(
                        frontId,
                        "Source-group demand "
                                + sourceGroupDemand.getSourceGroupDemandId()
                                + " contains no physical source demand."
                );
            }

            if (sourceGroupDemand
                    .getRequiredPlacementProfiles()
                    .size()
                    != sourceGroupDemand
                    .getPhysicalSourceCount()) {
                return DemandPlanningResult.failure(
                        frontId,
                        "Source-group demand "
                                + sourceGroupDemand.getSourceGroupDemandId()
                                + " has inconsistent physical-source profile "
                                + "demand."
                );
            }

            if (sourceGroupDemand
                    .createEnvelopeRadiusAttempts()
                    .isEmpty()) {
                return DemandPlanningResult.failure(
                        frontId,
                        "Source-group demand "
                                + sourceGroupDemand.getSourceGroupDemandId()
                                + " produced no adaptive envelope radius "
                                + "attempts."
                );
            }

            for (SourceGroupPlacementDemand
                    .SourceGroupCompositionBinding compositionBinding
                    : sourceGroupDemand
                    .getSourceGroupCompositionBindings()) {

                if (!boundSourceGroupCompositionIds.add(
                        compositionBinding
                                .sourceGroupCompositionId()
                )) {
                    return DemandPlanningResult.failure(
                            frontId,
                            "Source-group composition "
                                    + compositionBinding
                                    .sourceGroupCompositionId()
                                    + " was assigned to more than one "
                                    + "persistent source-group demand."
                    );
                }

                boundCompositionCount++;
            }
        }

        if (boundCompositionCount
                != expectedCompositionCount) {
            return DemandPlanningResult.failure(
                    frontId,
                    "Persistent source-group demand planning bound "
                            + boundCompositionCount
                            + " source-group compositions, but "
                            + expectedCompositionCount
                            + " were supplied by the front."
            );
        }

        return null;
    }

    /**
     * Completed all-lifecycle infrastructure demand for one front.
     */
    public record FrontDemandPlan(
            UUID frontId,
            List<SourceGroupPlacementDemand> sourceGroupDemands
    ) {

        public FrontDemandPlan {
            if (frontId == null) {
                throw new IllegalArgumentException(
                        "Front demand-plan ID cannot be null."
                );
            }

            if (sourceGroupDemands == null) {
                throw new IllegalArgumentException(
                        "Front source-group demand list cannot be null."
                );
            }

            sourceGroupDemands =
                    List.copyOf(
                            sourceGroupDemands
                    );
        }

        public int getSourceGroupDemandCount() {
            return sourceGroupDemands.size();
        }

        public int getPhysicalSourceDemandCount() {
            int totalPhysicalSources =
                    0;

            for (SourceGroupPlacementDemand sourceGroupDemand
                    : sourceGroupDemands) {

                totalPhysicalSources +=
                        sourceGroupDemand
                                .getPhysicalSourceCount();
            }

            return totalPhysicalSources;
        }

        public int getBoundCompositionCount() {
            int totalCompositions =
                    0;

            for (SourceGroupPlacementDemand sourceGroupDemand
                    : sourceGroupDemands) {

                totalCompositions +=
                        sourceGroupDemand
                                .getSourceGroupCompositionBindings()
                                .size();
            }

            return totalCompositions;
        }

        public boolean isEmpty() {
            return sourceGroupDemands.isEmpty();
        }
    }

    /**
     * Success or failure result for one front's demand-planning pass.
     */
    public record DemandPlanningResult(
            UUID frontId,
            boolean successful,
            FrontDemandPlan frontDemandPlan,
            String failureMessage
    ) {

        public DemandPlanningResult {
            boolean hasFailureMessage =
                    failureMessage != null
                            && !failureMessage.isBlank();

            if (successful) {
                if (frontId == null) {
                    throw new IllegalArgumentException(
                            "Successful demand planning requires a front ID."
                    );
                }

                if (frontDemandPlan == null) {
                    throw new IllegalArgumentException(
                            "Successful demand planning requires a completed "
                                    + "front demand plan."
                    );
                }

                if (!frontId.equals(
                        frontDemandPlan.frontId()
                )) {
                    throw new IllegalArgumentException(
                            "Demand-planning result and front demand plan use "
                                    + "different front IDs."
                    );
                }

                if (hasFailureMessage) {
                    throw new IllegalArgumentException(
                            "Successful demand planning cannot contain a "
                                    + "failure message."
                    );
                }
            } else {
                if (frontDemandPlan != null) {
                    throw new IllegalArgumentException(
                            "Failed demand planning cannot contain a "
                                    + "completed front demand plan."
                    );
                }

                if (!hasFailureMessage) {
                    throw new IllegalArgumentException(
                            "Failed demand planning requires a failure "
                                    + "message."
                    );
                }
            }
        }

        public static DemandPlanningResult success(
                FrontDemandPlan frontDemandPlan
        ) {
            if (frontDemandPlan == null) {
                throw new IllegalArgumentException(
                        "Successful front demand plan cannot be null."
                );
            }

            return new DemandPlanningResult(
                    frontDemandPlan.frontId(),
                    true,
                    frontDemandPlan,
                    null
            );
        }

        public static DemandPlanningResult failure(
                UUID frontId,
                String failureMessage
        ) {
            return new DemandPlanningResult(
                    frontId,
                    false,
                    null,
                    failureMessage
            );
        }

        public boolean hasFailed() {
            return !successful;
        }

        public List<SourceGroupPlacementDemand>
        getSourceGroupDemands() {
            if (!successful
                    || frontDemandPlan == null) {
                return List.of();
            }

            return frontDemandPlan
                    .sourceGroupDemands();
        }
    }

    private record DemandSelection(
            SourceGroupPlacementDemand sourceGroupDemand,
            SourceGroupPlacementDemand.CompositionFitAssessment
            fitAssessment
    ) {

        private DemandSelection {
            if (sourceGroupDemand == null) {
                throw new IllegalArgumentException(
                        "Selected source-group demand cannot be null."
                );
            }

            if (fitAssessment == null
                    || !fitAssessment.successful()) {
                throw new IllegalArgumentException(
                        "Demand selection requires a successful fit "
                                + "assessment."
                );
            }

            if (!sourceGroupDemand
                    .getSourceGroupDemandId()
                    .equals(
                            fitAssessment.sourceGroupDemandId()
                    )) {
                throw new IllegalArgumentException(
                        "Demand selection assessment belongs to a different "
                                + "source-group demand."
                );
            }
        }
    }

    public SourceGroupDemandPlanner() {
    }
}