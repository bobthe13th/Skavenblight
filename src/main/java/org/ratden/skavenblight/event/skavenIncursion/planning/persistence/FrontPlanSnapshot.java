package org.ratden.skavenblight.event.skavenIncursion.planning.persistence;

import net.minecraft.core.BlockPos;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.SourceGroupComposition;
import org.ratden.skavenblight.event.skavenIncursion.planning.front.FrontPlacementPattern;
import org.ratden.skavenblight.event.skavenIncursion.planning.front.FrontPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceGroupPlacementPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceReservationArea;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceRole;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable persistence-facing snapshot of one complete tactical front.
 *
 * This snapshot connects:
 *
 * - front identity and tactical placement;
 * - ordered wave plans;
 * - immutable source-group compositions;
 * - persistent physical source groups;
 * - physical source placements;
 * - all composition-to-placement bindings.
 *
 * Restoration happens in dependency order:
 *
 * 1. restore every source-group composition belonging to every wave;
 * 2. rebuild the ordered FrontPlan.WavePlan objects;
 * 3. restore physical source groups against those composition objects;
 * 4. attach the physical groups to the restored FrontPlan.
 *
 * Every structural UUID survives restoration. No replacement planning
 * identity is generated.
 *
 * This class contains immutable planning data only. Its NBT representation
 * is handled by FrontPlanSnapshotNbtCodec as part of the complete persisted
 * incursion-plan tree.
 */
public record FrontPlanSnapshot(
        UUID frontId,
        int frontIndex,
        BlockPos anchorPos,
        FrontPlacementPattern placementPattern,
        double threatShare,
        double complexityShare,
        boolean dominant,
        List<WavePlanSnapshot> wavePlanSnapshots,
        List<SourceGroupPlacementSnapshot>
        sourceGroupPlacementSnapshots
) {

    public FrontPlanSnapshot {
        if (frontId == null) {
            throw new IllegalArgumentException(
                    "Front snapshot ID cannot be null."
            );
        }

        if (frontIndex < 0) {
            throw new IllegalArgumentException(
                    "Front snapshot index cannot be negative."
            );
        }

        if (anchorPos == null) {
            throw new IllegalArgumentException(
                    "Front snapshot anchor position cannot be null."
            );
        }

        if (placementPattern == null) {
            throw new IllegalArgumentException(
                    "Front snapshot placement pattern cannot be null."
            );
        }

        if (frontIndex >= placementPattern.getFrontCount()) {
            throw new IllegalArgumentException(
                    "Front snapshot index "
                            + frontIndex
                            + " lies outside placement pattern "
                            + placementPattern
                            + " with "
                            + placementPattern.getFrontCount()
                            + " fronts."
            );
        }

        validateShare(
                threatShare,
                "Front threat"
        );

        validateShare(
                complexityShare,
                "Front complexity"
        );

        if (wavePlanSnapshots == null
                || wavePlanSnapshots.isEmpty()) {

            throw new IllegalArgumentException(
                    "Front snapshot requires at least one wave plan."
            );
        }

        if (sourceGroupPlacementSnapshots == null
                || sourceGroupPlacementSnapshots.isEmpty()) {

            throw new IllegalArgumentException(
                    "Front snapshot requires at least one physical "
                            + "source-group placement."
            );
        }

        anchorPos =
                anchorPos.immutable();

        wavePlanSnapshots =
                List.copyOf(
                        wavePlanSnapshots
                );

        sourceGroupPlacementSnapshots =
                List.copyOf(
                        sourceGroupPlacementSnapshots
                );

        validateCompleteFrontStructure(
                frontId,
                wavePlanSnapshots,
                sourceGroupPlacementSnapshots
        );
    }

    /**
     * Captures one complete planned front.
     */
    public static FrontPlanSnapshot capture(
            FrontPlan frontPlan
    ) {
        if (frontPlan == null) {
            throw new IllegalArgumentException(
                    "Front plan cannot be null."
            );
        }

        if (!frontPlan.hasWavePlans()) {
            throw new IllegalArgumentException(
                    "Front "
                            + frontPlan.getFrontId()
                            + " contains no wave plans."
            );
        }

        if (!frontPlan.hasSourceGroupPlacementPlans()) {
            throw new IllegalArgumentException(
                    "Front "
                            + frontPlan.getFrontId()
                            + " contains no physical source-group "
                            + "placements."
            );
        }

        List<WavePlanSnapshot> capturedWaves =
                new ArrayList<>();

        for (FrontPlan.WavePlan wavePlan
                : frontPlan.getWavePlans()) {

            capturedWaves.add(
                    WavePlanSnapshot.capture(
                            wavePlan
                    )
            );
        }

        List<SourceGroupPlacementSnapshot> capturedGroups =
                new ArrayList<>();

        for (SourceGroupPlacementPlan sourceGroupPlacementPlan
                : frontPlan.getSourceGroupPlacementPlans()) {

            capturedGroups.add(
                    SourceGroupPlacementSnapshot.capture(
                            sourceGroupPlacementPlan
                    )
            );
        }

        return new FrontPlanSnapshot(
                frontPlan.getFrontId(),
                frontPlan.getFrontIndex(),
                frontPlan.getAnchorPos(),
                frontPlan.getPlacementPattern(),
                frontPlan.getThreatShare(),
                frontPlan.getComplexityShare(),
                frontPlan.isDominant(),
                capturedWaves,
                capturedGroups
        );
    }

    /**
     * Restores one complete front while preserving all planning identities.
     */
    public FrontPlan restore() {
        LinkedHashMap<UUID, SourceGroupComposition>
                sourceGroupCompositionsById =
                new LinkedHashMap<>();

        List<FrontPlan.WavePlan> restoredWavePlans =
                new ArrayList<>();

        /*
         * Restore the composition branch before physical placement because
         * SourceGroupPlacementPlan inherits and validates its role and load
         * rules from the compositions bound to it.
         */
        for (WavePlanSnapshot wavePlanSnapshot
                : wavePlanSnapshots) {

            restoredWavePlans.add(
                    wavePlanSnapshot.restore(
                            sourceGroupCompositionsById
                    )
            );
        }

        FrontPlan restoredFront =
                new FrontPlan(
                        frontId,
                        frontIndex,
                        anchorPos,
                        placementPattern,
                        threatShare,
                        complexityShare,
                        dominant
                );

        for (FrontPlan.WavePlan restoredWavePlan
                : restoredWavePlans) {

            restoredFront.addWavePlan(
                    restoredWavePlan
            );
        }

        for (SourceGroupPlacementSnapshot
                sourceGroupPlacementSnapshot
                : sourceGroupPlacementSnapshots) {

            SourceGroupPlacementPlan restoredPlacement =
                    sourceGroupPlacementSnapshot.restore(
                            sourceGroupCompositionsById
                    );

            restoredFront.addSourceGroupPlacementPlan(
                    restoredPlacement
            );
        }

        FrontPlanSnapshot reconstructedSnapshot =
                capture(
                        restoredFront
                );

        if (!equals(
                reconstructedSnapshot
        )) {
            throw new IllegalArgumentException(
                    "Restored front "
                            + frontId
                            + " does not exactly match its saved snapshot."
            );
        }

        return restoredFront;
    }

    public int getWaveCount() {
        return wavePlanSnapshots.size();
    }

    public int getSourceGroupCompositionCount() {
        int compositionCount =
                0;

        for (WavePlanSnapshot wavePlanSnapshot
                : wavePlanSnapshots) {

            compositionCount +=
                    wavePlanSnapshot
                            .sourceGroupCompositionSnapshots()
                            .size();
        }

        return compositionCount;
    }

    public int getPhysicalSourceGroupCount() {
        return sourceGroupPlacementSnapshots.size();
    }

    public int getPhysicalSourceCount() {
        int sourceCount =
                0;

        for (SourceGroupPlacementSnapshot placementSnapshot
                : sourceGroupPlacementSnapshots) {

            sourceCount +=
                    placementSnapshot.getPhysicalSourceCount();
        }

        return sourceCount;
    }

    public int getTotalThreatBudget() {
        int totalThreatBudget =
                0;

        for (WavePlanSnapshot wavePlanSnapshot
                : wavePlanSnapshots) {

            totalThreatBudget +=
                    wavePlanSnapshot.threatBudget();
        }

        return totalThreatBudget;
    }

    public int getTotalComplexityBudget() {
        int totalComplexityBudget =
                0;

        for (WavePlanSnapshot wavePlanSnapshot
                : wavePlanSnapshots) {

            totalComplexityBudget +=
                    wavePlanSnapshot.complexityBudget();
        }

        return totalComplexityBudget;
    }

    public int getTotalThreatSpent() {
        int totalThreatSpent =
                0;

        for (WavePlanSnapshot wavePlanSnapshot
                : wavePlanSnapshots) {

            totalThreatSpent +=
                    wavePlanSnapshot.getThreatSpent();
        }

        return totalThreatSpent;
    }

    public int getTotalComplexitySpent() {
        int totalComplexitySpent =
                0;

        for (WavePlanSnapshot wavePlanSnapshot
                : wavePlanSnapshots) {

            totalComplexitySpent +=
                    wavePlanSnapshot.getComplexitySpent();
        }

        return totalComplexitySpent;
    }

    public WavePlanSnapshot getWavePlanSnapshot(
            int waveIndex
    ) {
        for (WavePlanSnapshot wavePlanSnapshot
                : wavePlanSnapshots) {

            if (wavePlanSnapshot.waveIndex()
                    == waveIndex) {

                return wavePlanSnapshot;
            }
        }

        return null;
    }

    public SourceGroupPlacementSnapshot
    getSourceGroupPlacementSnapshot(
            UUID sourceGroupPlacementId
    ) {
        if (sourceGroupPlacementId == null) {
            return null;
        }

        for (SourceGroupPlacementSnapshot placementSnapshot
                : sourceGroupPlacementSnapshots) {

            if (sourceGroupPlacementId.equals(
                    placementSnapshot.sourceGroupPlacementId()
            )) {
                return placementSnapshot;
            }
        }

        return null;
    }

    /**
     * Validates that the composition branch and placement branch form one
     * complete front rather than two unrelated collections.
     */
    private static void validateCompleteFrontStructure(
            UUID frontId,
            List<WavePlanSnapshot> wavePlanSnapshots,
            List<SourceGroupPlacementSnapshot>
                    sourceGroupPlacementSnapshots
    ) {
        LinkedHashMap<UUID, SourceGroupCompositionSnapshot>
                compositionSnapshotsById =
                indexWaveCompositions(
                        wavePlanSnapshots
                );

        Set<Integer> waveIndexes =
                new HashSet<>();

        for (WavePlanSnapshot wavePlanSnapshot
                : wavePlanSnapshots) {

            if (wavePlanSnapshot == null) {
                throw new IllegalArgumentException(
                        "Front snapshot cannot contain a null wave plan."
                );
            }

            if (!waveIndexes.add(
                    wavePlanSnapshot.waveIndex()
            )) {
                throw new IllegalArgumentException(
                        "Front snapshot contains duplicate wave index "
                                + wavePlanSnapshot.waveIndex()
                                + "."
                );
            }
        }

        Set<UUID> sourceGroupPlacementIds =
                new HashSet<>();

        Set<UUID> boundSourceGroupCompositionIds =
                new LinkedHashSet<>();

        Set<UUID> physicalSourcePlacementIds =
                new HashSet<>();

        List<SourceGroupPlacementSnapshot.SourcePlacementSnapshot>
                allPhysicalSources =
                new ArrayList<>();

        for (SourceGroupPlacementSnapshot placementSnapshot
                : sourceGroupPlacementSnapshots) {

            if (placementSnapshot == null) {
                throw new IllegalArgumentException(
                        "Front snapshot cannot contain a null physical "
                                + "source-group placement."
                );
            }

            if (!frontId.equals(
                    placementSnapshot.frontId()
            )) {
                throw new IllegalArgumentException(
                        "Source-group placement "
                                + placementSnapshot
                                .sourceGroupPlacementId()
                                + " belongs to front "
                                + placementSnapshot.frontId()
                                + " rather than containing front "
                                + frontId
                                + "."
                );
            }

            if (!sourceGroupPlacementIds.add(
                    placementSnapshot.sourceGroupPlacementId()
            )) {
                throw new IllegalArgumentException(
                        "Front snapshot contains duplicate source-group "
                                + "placement ID "
                                + placementSnapshot
                                .sourceGroupPlacementId()
                                + "."
                );
            }

            validatePlacementCompositionBindings(
                    placementSnapshot,
                    compositionSnapshotsById,
                    boundSourceGroupCompositionIds
            );

            for (SourceGroupPlacementSnapshot.SourcePlacementSnapshot
                    sourcePlacementSnapshot
                    : placementSnapshot.sourcePlacementSnapshots()) {

                if (!physicalSourcePlacementIds.add(
                        sourcePlacementSnapshot.sourcePlacementId()
                )) {
                    throw new IllegalArgumentException(
                            "Front snapshot contains duplicate physical "
                                    + "source-placement ID "
                                    + sourcePlacementSnapshot
                                    .sourcePlacementId()
                                    + "."
                    );
                }

                allPhysicalSources.add(
                        sourcePlacementSnapshot
                );
            }
        }

        Set<UUID> expectedCompositionIds =
                new LinkedHashSet<>(
                        compositionSnapshotsById.keySet()
                );

        if (!boundSourceGroupCompositionIds.equals(
                expectedCompositionIds
        )) {
            Set<UUID> missingCompositionIds =
                    new LinkedHashSet<>(
                            expectedCompositionIds
                    );

            missingCompositionIds.removeAll(
                    boundSourceGroupCompositionIds
            );

            Set<UUID> unexpectedCompositionIds =
                    new LinkedHashSet<>(
                            boundSourceGroupCompositionIds
                    );

            unexpectedCompositionIds.removeAll(
                    expectedCompositionIds
            );

            throw new IllegalArgumentException(
                    "Physical source groups in front "
                            + frontId
                            + " do not exactly cover its source-group "
                            + "compositions. Missing: "
                            + missingCompositionIds
                            + ". Unexpected: "
                            + unexpectedCompositionIds
                            + "."
            );
        }

        validatePhysicalSourceReservations(
                allPhysicalSources
        );
    }

    private static LinkedHashMap<
            UUID,
            SourceGroupCompositionSnapshot
            > indexWaveCompositions(
            List<WavePlanSnapshot> wavePlanSnapshots
    ) {
        LinkedHashMap<UUID, SourceGroupCompositionSnapshot>
                compositionSnapshotsById =
                new LinkedHashMap<>();

        for (WavePlanSnapshot wavePlanSnapshot
                : wavePlanSnapshots) {

            if (wavePlanSnapshot == null) {
                throw new IllegalArgumentException(
                        "Front snapshot cannot contain a null wave plan."
                );
            }

            for (SourceGroupCompositionSnapshot compositionSnapshot
                    : wavePlanSnapshot
                    .sourceGroupCompositionSnapshots()) {

                UUID compositionId =
                        compositionSnapshot
                                .sourceGroupCompositionId();

                SourceGroupCompositionSnapshot previousSnapshot =
                        compositionSnapshotsById.putIfAbsent(
                                compositionId,
                                compositionSnapshot
                        );

                if (previousSnapshot != null) {
                    throw new IllegalArgumentException(
                            "Front snapshot contains duplicate source-group "
                                    + "composition ID "
                                    + compositionId
                                    + "."
                    );
                }
            }
        }

        if (compositionSnapshotsById.isEmpty()) {
            throw new IllegalArgumentException(
                    "Front snapshot contains no source-group compositions."
            );
        }

        return compositionSnapshotsById;
    }

    private static void validatePlacementCompositionBindings(
            SourceGroupPlacementSnapshot placementSnapshot,
            Map<UUID, SourceGroupCompositionSnapshot>
                    compositionSnapshotsById,
            Set<UUID> boundSourceGroupCompositionIds
    ) {
        for (UUID sourceGroupCompositionId
                : placementSnapshot.sourceGroupCompositionIds()) {

            SourceGroupCompositionSnapshot compositionSnapshot =
                    compositionSnapshotsById.get(
                            sourceGroupCompositionId
                    );

            if (compositionSnapshot == null) {
                throw new IllegalArgumentException(
                        "Physical source group "
                                + placementSnapshot
                                .sourceGroupPlacementId()
                                + " refers to unknown source-group "
                                + "composition "
                                + sourceGroupCompositionId
                                + "."
                );
            }

            if (!boundSourceGroupCompositionIds.add(
                    sourceGroupCompositionId
            )) {
                throw new IllegalArgumentException(
                        "Source-group composition "
                                + sourceGroupCompositionId
                                + " is bound to more than one physical "
                                + "source group in the same front."
                );
            }

            if (placementSnapshot.maximumLoad()
                    != compositionSnapshot.maximumLoad()) {

                throw new IllegalArgumentException(
                        "Physical source group "
                                + placementSnapshot
                                .sourceGroupPlacementId()
                                + " has maximum load "
                                + placementSnapshot.maximumLoad()
                                + " but composition "
                                + sourceGroupCompositionId
                                + " has maximum load "
                                + compositionSnapshot.maximumLoad()
                                + "."
                );
            }

            SourceRole compositionRole =
                    compositionSnapshot.getSourceRole();

            if (placementSnapshot.sourceRole()
                    != compositionRole) {

                throw new IllegalArgumentException(
                        "Physical source group "
                                + placementSnapshot
                                .sourceGroupPlacementId()
                                + " has role "
                                + placementSnapshot.sourceRole()
                                + " but composition "
                                + sourceGroupCompositionId
                                + " has role "
                                + compositionRole
                                + "."
                );
            }
        }
    }

    /**
     * SourceGroupPlacementSnapshot validates non-overlap inside each physical
     * group. This additional pass validates non-overlap between groups in the
     * same front.
     */
    private static void validatePhysicalSourceReservations(
            List<SourceGroupPlacementSnapshot.SourcePlacementSnapshot>
                    allPhysicalSources
    ) {
        for (int firstIndex = 0;
             firstIndex < allPhysicalSources.size();
             firstIndex++) {

            SourceGroupPlacementSnapshot.SourcePlacementSnapshot
                    firstSource =
                    allPhysicalSources.get(
                            firstIndex
                    );

            SourceReservationArea.WorldBounds firstBounds =
                    firstSource.getReservationBounds();

            for (int secondIndex = firstIndex + 1;
                 secondIndex < allPhysicalSources.size();
                 secondIndex++) {

                SourceGroupPlacementSnapshot.SourcePlacementSnapshot
                        secondSource =
                        allPhysicalSources.get(
                                secondIndex
                        );

                SourceReservationArea.WorldBounds secondBounds =
                        secondSource.getReservationBounds();

                if (firstBounds.overlaps(
                        secondBounds
                )) {
                    throw new IllegalArgumentException(
                            "Physical source placements "
                                    + firstSource.sourcePlacementId()
                                    + " and "
                                    + secondSource.sourcePlacementId()
                                    + " overlap inside front "
                                    + firstSource.sourceGroupPlacementId()
                                    + "/"
                                    + secondSource
                                    .sourceGroupPlacementId()
                                    + "."
                    );
                }
            }
        }
    }

    private static void validateShare(
            double share,
            String description
    ) {
        if (!Double.isFinite(
                share
        )) {
            throw new IllegalArgumentException(
                    description
                            + " share must be finite."
            );
        }

        if (share < 0.0D
                || share > 1.0D) {

            throw new IllegalArgumentException(
                    description
                            + " share must be between 0.0 and 1.0."
            );
        }
    }

    /**
     * Immutable persistence-facing snapshot of one wave within one front.
     */
    public record WavePlanSnapshot(
            int waveIndex,
            double threatShare,
            double complexityShare,
            int threatBudget,
            int complexityBudget,
            List<SourceGroupCompositionSnapshot>
            sourceGroupCompositionSnapshots
    ) {

        public WavePlanSnapshot {
            if (waveIndex < 0) {
                throw new IllegalArgumentException(
                        "Wave snapshot index cannot be negative."
                );
            }

            validateShare(
                    threatShare,
                    "Wave threat"
            );

            validateShare(
                    complexityShare,
                    "Wave complexity"
            );

            if (threatBudget < 0) {
                throw new IllegalArgumentException(
                        "Wave snapshot threat budget cannot be negative."
                );
            }

            if (complexityBudget < 0) {
                throw new IllegalArgumentException(
                        "Wave snapshot complexity budget cannot be negative."
                );
            }

            if (sourceGroupCompositionSnapshots == null
                    || sourceGroupCompositionSnapshots.isEmpty()) {

                throw new IllegalArgumentException(
                        "Runtime-ready wave snapshot requires at least one "
                                + "source-group composition."
                );
            }

            sourceGroupCompositionSnapshots =
                    List.copyOf(
                            sourceGroupCompositionSnapshots
                    );

            validateCompositions(
                    threatBudget,
                    complexityBudget,
                    sourceGroupCompositionSnapshots
            );
        }

        private static WavePlanSnapshot capture(
                FrontPlan.WavePlan wavePlan
        ) {
            if (wavePlan == null) {
                throw new IllegalArgumentException(
                        "Wave plan cannot be null."
                );
            }

            if (!wavePlan.hasSourceGroupCompositions()) {
                throw new IllegalArgumentException(
                        "Runtime-ready wave "
                                + wavePlan.getWaveIndex()
                                + " contains no source-group compositions."
                );
            }

            List<SourceGroupCompositionSnapshot>
                    capturedCompositions =
                    new ArrayList<>();

            for (SourceGroupComposition sourceGroupComposition
                    : wavePlan.getSourceGroupCompositions()) {

                capturedCompositions.add(
                        SourceGroupCompositionSnapshot.capture(
                                sourceGroupComposition
                        )
                );
            }

            return new WavePlanSnapshot(
                    wavePlan.getWaveIndex(),
                    wavePlan.getThreatShare(),
                    wavePlan.getComplexityShare(),
                    wavePlan.getThreatBudget(),
                    wavePlan.getComplexityBudget(),
                    capturedCompositions
            );
        }

        /**
         * Restores this wave and adds its source-group compositions to the
         * front-wide identity index.
         */
        private FrontPlan.WavePlan restore(
                Map<UUID, SourceGroupComposition>
                        sourceGroupCompositionsById
        ) {
            if (sourceGroupCompositionsById == null) {
                throw new IllegalArgumentException(
                        "Source-group composition restoration map cannot be "
                                + "null."
                );
            }

            FrontPlan.WavePlan restoredWave =
                    new FrontPlan.WavePlan(
                            waveIndex,
                            threatShare,
                            complexityShare,
                            threatBudget,
                            complexityBudget
                    );

            for (SourceGroupCompositionSnapshot compositionSnapshot
                    : sourceGroupCompositionSnapshots) {

                SourceGroupComposition restoredComposition =
                        compositionSnapshot.restore();

                UUID compositionId =
                        restoredComposition
                                .getSourceGroupCompositionId();

                SourceGroupComposition previousComposition =
                        sourceGroupCompositionsById.putIfAbsent(
                                compositionId,
                                restoredComposition
                        );

                if (previousComposition != null) {
                    throw new IllegalArgumentException(
                            "Restoration encountered duplicate source-group "
                                    + "composition ID "
                                    + compositionId
                                    + "."
                    );
                }

                restoredWave.addSourceGroupComposition(
                        restoredComposition
                );
            }

            WavePlanSnapshot reconstructedSnapshot =
                    capture(
                            restoredWave
                    );

            if (!equals(
                    reconstructedSnapshot
            )) {
                throw new IllegalArgumentException(
                        "Restored wave "
                                + waveIndex
                                + " does not exactly match its saved "
                                + "snapshot."
                );
            }

            return restoredWave;
        }

        public int getThreatSpent() {
            int threatSpent =
                    0;

            for (SourceGroupCompositionSnapshot compositionSnapshot
                    : sourceGroupCompositionSnapshots) {

                threatSpent +=
                        compositionSnapshot.getTotalThreatSpent();
            }

            return threatSpent;
        }

        public int getComplexitySpent() {
            int complexitySpent =
                    0;

            for (SourceGroupCompositionSnapshot compositionSnapshot
                    : sourceGroupCompositionSnapshots) {

                complexitySpent +=
                        compositionSnapshot.getTotalComplexitySpent();
            }

            return complexitySpent;
        }

        public int getUnspentThreat() {
            return threatBudget
                    - getThreatSpent();
        }

        public int getUnspentComplexity() {
            return complexityBudget
                    - getComplexitySpent();
        }

        public SourceGroupCompositionSnapshot
        getSourceGroupCompositionSnapshot(
                UUID sourceGroupCompositionId
        ) {
            if (sourceGroupCompositionId == null) {
                return null;
            }

            for (SourceGroupCompositionSnapshot compositionSnapshot
                    : sourceGroupCompositionSnapshots) {

                if (sourceGroupCompositionId.equals(
                        compositionSnapshot
                                .sourceGroupCompositionId()
                )) {
                    return compositionSnapshot;
                }
            }

            return null;
        }

        private static void validateCompositions(
                int threatBudget,
                int complexityBudget,
                List<SourceGroupCompositionSnapshot>
                        sourceGroupCompositionSnapshots
        ) {
            Set<UUID> sourceGroupCompositionIds =
                    new HashSet<>();

            int threatSpent =
                    0;

            int complexitySpent =
                    0;

            for (SourceGroupCompositionSnapshot compositionSnapshot
                    : sourceGroupCompositionSnapshots) {

                if (compositionSnapshot == null) {
                    throw new IllegalArgumentException(
                            "Wave snapshot cannot contain a null "
                                    + "source-group composition."
                    );
                }

                if (!sourceGroupCompositionIds.add(
                        compositionSnapshot
                                .sourceGroupCompositionId()
                )) {
                    throw new IllegalArgumentException(
                            "Wave snapshot contains duplicate source-group "
                                    + "composition ID "
                                    + compositionSnapshot
                                    .sourceGroupCompositionId()
                                    + "."
                    );
                }

                threatSpent +=
                        compositionSnapshot.getTotalThreatSpent();

                complexitySpent +=
                        compositionSnapshot.getTotalComplexitySpent();
            }

            if (threatSpent > threatBudget) {
                throw new IllegalArgumentException(
                        "Wave snapshot spends "
                                + threatSpent
                                + " threat from a budget of "
                                + threatBudget
                                + "."
                );
            }

            if (complexitySpent > complexityBudget) {
                throw new IllegalArgumentException(
                        "Wave snapshot spends "
                                + complexitySpent
                                + " complexity from a budget of "
                                + complexityBudget
                                + "."
                );
            }
        }
    }
}