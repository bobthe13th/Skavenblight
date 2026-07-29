package org.ratden.skavenblight.event.skavenIncursion.planning.persistence;

import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.front.FrontPlacementPattern;
import org.ratden.skavenblight.event.skavenIncursion.planning.front.FrontPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceGroupEnvelope;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceGroupSpatialRules;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceReservationArea;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable persistence-facing snapshot of one complete IncursionPlan.
 *
 * This is the root of the immutable planning snapshot tree:
 *
 * IncursionPlanSnapshot
 * -> FrontPlanSnapshot
 * -> WavePlanSnapshot
 * -> SourceGroupCompositionSnapshot
 * -> SourceCompositionSnapshot
 * -> mob entries and attached assignments
 *
 * Each front also contains its persistent physical-placement branch:
 *
 * FrontPlanSnapshot
 * -> SourceGroupPlacementSnapshot
 * -> SourcePlacementSnapshot
 *
 * The snapshot preserves every structural UUID required by runtime
 * persistence:
 *
 * - incursion ID;
 * - front IDs;
 * - source-group composition IDs;
 * - source-composition IDs;
 * - attached-mob assignment IDs;
 * - Pack IDs;
 * - physical source-group IDs;
 * - physical source-placement IDs.
 *
 * Restoration is intentionally strict. It rejects malformed or internally
 * inconsistent persisted plans rather than silently generating replacement
 * identities or altering physical placement.
 *
 * This class contains immutable planning data only.
 * IncursionPlanSnapshotNbtCodec serialises the complete snapshot tree for
 * persistent-incursion SavedData.
 */
public record IncursionPlanSnapshot(
        UUID incursionId,
        List<FrontPlanSnapshot> frontPlanSnapshots
) {

    private static final double SHARE_TOLERANCE =
            0.0001D;

    public IncursionPlanSnapshot {
        if (incursionId == null) {
            throw new IllegalArgumentException(
                    "Incursion-plan snapshot ID cannot be null."
            );
        }

        if (frontPlanSnapshots == null
                || frontPlanSnapshots.isEmpty()) {

            throw new IllegalArgumentException(
                    "Incursion-plan snapshot requires at least one front."
            );
        }

        frontPlanSnapshots =
                List.copyOf(
                        frontPlanSnapshots
                );

        validateCompletePlan(
                frontPlanSnapshots
        );
    }

    /**
     * Captures one complete immutable incursion plan.
     */
    public static IncursionPlanSnapshot capture(
            IncursionPlan incursionPlan
    ) {
        if (incursionPlan == null) {
            throw new IllegalArgumentException(
                    "Incursion plan cannot be null."
            );
        }

        if (incursionPlan.getIncursionId() == null) {
            throw new IllegalArgumentException(
                    "Incursion plan has no incursion ID."
            );
        }

        if (!incursionPlan.hasFrontPlans()) {
            throw new IllegalArgumentException(
                    "Incursion plan "
                            + incursionPlan.getIncursionId()
                            + " contains no fronts."
            );
        }

        List<FrontPlanSnapshot> capturedFronts =
                new ArrayList<>();

        for (FrontPlan frontPlan
                : incursionPlan.getFrontPlans()) {

            capturedFronts.add(
                    FrontPlanSnapshot.capture(
                            frontPlan
                    )
            );
        }

        return new IncursionPlanSnapshot(
                incursionPlan.getIncursionId(),
                capturedFronts
        );
    }

    /**
     * Restores the complete immutable planning graph using every saved
     * structural ID.
     */
    public IncursionPlan restore() {
        IncursionPlan restoredPlan =
                new IncursionPlan(
                        incursionId
                );

        for (FrontPlanSnapshot frontPlanSnapshot
                : frontPlanSnapshots) {

            restoredPlan.addFrontPlan(
                    frontPlanSnapshot.restore()
            );
        }

        IncursionPlanSnapshot reconstructedSnapshot =
                capture(
                        restoredPlan
                );

        if (!equals(
                reconstructedSnapshot
        )) {
            throw new IllegalArgumentException(
                    "Restored incursion plan "
                            + incursionId
                            + " does not exactly match its saved snapshot."
            );
        }

        return restoredPlan;
    }

    public int getFrontCount() {
        return frontPlanSnapshots.size();
    }

    public FrontPlacementPattern getPlacementPattern() {
        return frontPlanSnapshots
                .getFirst()
                .placementPattern();
    }

    public int getWaveCount() {
        return frontPlanSnapshots
                .getFirst()
                .getWaveCount();
    }

    public int getSourceGroupCompositionCount() {
        int compositionCount =
                0;

        for (FrontPlanSnapshot frontPlanSnapshot
                : frontPlanSnapshots) {

            compositionCount +=
                    frontPlanSnapshot
                            .getSourceGroupCompositionCount();
        }

        return compositionCount;
    }

    public int getPhysicalSourceGroupCount() {
        int physicalGroupCount =
                0;

        for (FrontPlanSnapshot frontPlanSnapshot
                : frontPlanSnapshots) {

            physicalGroupCount +=
                    frontPlanSnapshot
                            .getPhysicalSourceGroupCount();
        }

        return physicalGroupCount;
    }

    public int getPhysicalSourceCount() {
        int sourceCount =
                0;

        for (FrontPlanSnapshot frontPlanSnapshot
                : frontPlanSnapshots) {

            sourceCount +=
                    frontPlanSnapshot
                            .getPhysicalSourceCount();
        }

        return sourceCount;
    }

    public int getTotalThreatBudget() {
        int threatBudget =
                0;

        for (FrontPlanSnapshot frontPlanSnapshot
                : frontPlanSnapshots) {

            threatBudget +=
                    frontPlanSnapshot.getTotalThreatBudget();
        }

        return threatBudget;
    }

    public int getTotalComplexityBudget() {
        int complexityBudget =
                0;

        for (FrontPlanSnapshot frontPlanSnapshot
                : frontPlanSnapshots) {

            complexityBudget +=
                    frontPlanSnapshot.getTotalComplexityBudget();
        }

        return complexityBudget;
    }

    public int getTotalThreatSpent() {
        int threatSpent =
                0;

        for (FrontPlanSnapshot frontPlanSnapshot
                : frontPlanSnapshots) {

            threatSpent +=
                    frontPlanSnapshot.getTotalThreatSpent();
        }

        return threatSpent;
    }

    public int getTotalComplexitySpent() {
        int complexitySpent =
                0;

        for (FrontPlanSnapshot frontPlanSnapshot
                : frontPlanSnapshots) {

            complexitySpent +=
                    frontPlanSnapshot.getTotalComplexitySpent();
        }

        return complexitySpent;
    }

    public FrontPlanSnapshot getFrontPlanSnapshot(
            UUID frontId
    ) {
        if (frontId == null) {
            return null;
        }

        for (FrontPlanSnapshot frontPlanSnapshot
                : frontPlanSnapshots) {

            if (frontId.equals(
                    frontPlanSnapshot.frontId()
            )) {
                return frontPlanSnapshot;
            }
        }

        return null;
    }

    public FrontPlanSnapshot getFrontPlanSnapshot(
            int frontIndex
    ) {
        for (FrontPlanSnapshot frontPlanSnapshot
                : frontPlanSnapshots) {

            if (frontPlanSnapshot.frontIndex()
                    == frontIndex) {

                return frontPlanSnapshot;
            }
        }

        return null;
    }

    /**
     * Validates relationships that extend beyond one individual front.
     */
    private static void validateCompletePlan(
            List<FrontPlanSnapshot> frontPlanSnapshots
    ) {
        validateFrontStructure(
                frontPlanSnapshots
        );

        validateGlobalStructuralIdentities(
                frontPlanSnapshots
        );

        validateGlobalSourceGroupSeparation(
                frontPlanSnapshots
        );
    }

    /**
     * Confirms that all fronts form one coherent tactical pattern and share
     * one common ordered wave schedule.
     */
    private static void validateFrontStructure(
            List<FrontPlanSnapshot> frontPlanSnapshots
    ) {
        Set<UUID> frontIds =
                new HashSet<>();

        Set<Integer> frontIndexes =
                new HashSet<>();

        FrontPlacementPattern commonPlacementPattern =
                null;

        List<WaveIdentity> commonWaveStructure =
                null;

        double totalThreatShare =
                0.0D;

        double totalComplexityShare =
                0.0D;

        for (int listPosition = 0;
             listPosition < frontPlanSnapshots.size();
             listPosition++) {

            FrontPlanSnapshot frontPlanSnapshot =
                    frontPlanSnapshots.get(
                            listPosition
                    );

            if (frontPlanSnapshot == null) {
                throw new IllegalArgumentException(
                        "Incursion-plan snapshot cannot contain a null front."
                );
            }

            if (!frontIds.add(
                    frontPlanSnapshot.frontId()
            )) {
                throw new IllegalArgumentException(
                        "Incursion-plan snapshot contains duplicate front ID "
                                + frontPlanSnapshot.frontId()
                                + "."
                );
            }

            if (!frontIndexes.add(
                    frontPlanSnapshot.frontIndex()
            )) {
                throw new IllegalArgumentException(
                        "Incursion-plan snapshot contains duplicate front "
                                + "index "
                                + frontPlanSnapshot.frontIndex()
                                + "."
                );
            }

            /*
             * FrontPlanner creates fronts in front-index order. Preserving
             * that canonical order keeps debug labels, runtime traversal and
             * snapshot equality deterministic.
             */
            if (frontPlanSnapshot.frontIndex()
                    != listPosition) {

                throw new IllegalArgumentException(
                        "Front snapshot at list position "
                                + listPosition
                                + " has front index "
                                + frontPlanSnapshot.frontIndex()
                                + ". Front snapshots must remain in canonical "
                                + "front-index order."
                );
            }

            if (commonPlacementPattern == null) {
                commonPlacementPattern =
                        frontPlanSnapshot.placementPattern();
            } else if (commonPlacementPattern
                    != frontPlanSnapshot.placementPattern()) {

                throw new IllegalArgumentException(
                        "One incursion plan cannot contain mixed front "
                                + "placement patterns "
                                + commonPlacementPattern
                                + " and "
                                + frontPlanSnapshot.placementPattern()
                                + "."
                );
            }

            List<WaveIdentity> frontWaveStructure =
                    captureWaveStructure(
                            frontPlanSnapshot
                    );

            if (commonWaveStructure == null) {
                commonWaveStructure =
                        frontWaveStructure;
            } else {
                validateMatchingWaveStructure(
                        commonWaveStructure,
                        frontWaveStructure,
                        frontPlanSnapshot.frontId()
                );
            }

            totalThreatShare +=
                    frontPlanSnapshot.threatShare();

            totalComplexityShare +=
                    frontPlanSnapshot.complexityShare();
        }

        if (commonPlacementPattern == null) {
            throw new IllegalArgumentException(
                    "Incursion-plan snapshot has no placement pattern."
            );
        }

        if (frontPlanSnapshots.size()
                != commonPlacementPattern.getFrontCount()) {

            throw new IllegalArgumentException(
                    "Placement pattern "
                            + commonPlacementPattern
                            + " requires "
                            + commonPlacementPattern.getFrontCount()
                            + " fronts, but the snapshot contains "
                            + frontPlanSnapshots.size()
                            + "."
            );
        }

        validateTotalShare(
                totalThreatShare,
                "Incursion front threat"
        );

        validateTotalShare(
                totalComplexityShare,
                "Incursion front complexity"
        );
    }

    private static List<WaveIdentity> captureWaveStructure(
            FrontPlanSnapshot frontPlanSnapshot
    ) {
        List<WaveIdentity> waveStructure =
                new ArrayList<>();

        int expectedWaveIndex =
                0;

        for (FrontPlanSnapshot.WavePlanSnapshot wavePlanSnapshot
                : frontPlanSnapshot.wavePlanSnapshots()) {

            if (wavePlanSnapshot.waveIndex()
                    != expectedWaveIndex) {

                throw new IllegalArgumentException(
                        "Front "
                                + frontPlanSnapshot.frontId()
                                + " contains wave index "
                                + wavePlanSnapshot.waveIndex()
                                + " at ordered position "
                                + expectedWaveIndex
                                + ". Wave indexes must begin at zero and be "
                                + "contiguous."
                );
            }

            waveStructure.add(
                    new WaveIdentity(
                            wavePlanSnapshot.waveIndex(),
                            wavePlanSnapshot.threatShare(),
                            wavePlanSnapshot.complexityShare()
                    )
            );

            expectedWaveIndex++;
        }

        return List.copyOf(
                waveStructure
        );
    }

    private static void validateMatchingWaveStructure(
            List<WaveIdentity> expectedWaveStructure,
            List<WaveIdentity> actualWaveStructure,
            UUID frontId
    ) {
        if (actualWaveStructure.size()
                != expectedWaveStructure.size()) {

            throw new IllegalArgumentException(
                    "Front "
                            + frontId
                            + " contains "
                            + actualWaveStructure.size()
                            + " waves, but the incursion's common wave "
                            + "schedule contains "
                            + expectedWaveStructure.size()
                            + "."
            );
        }

        for (int wavePosition = 0;
             wavePosition < expectedWaveStructure.size();
             wavePosition++) {

            WaveIdentity expectedWave =
                    expectedWaveStructure.get(
                            wavePosition
                    );

            WaveIdentity actualWave =
                    actualWaveStructure.get(
                            wavePosition
                    );

            if (expectedWave.waveIndex()
                    != actualWave.waveIndex()) {

                throw new IllegalArgumentException(
                        "Front "
                                + frontId
                                + " has wave index "
                                + actualWave.waveIndex()
                                + " at position "
                                + wavePosition
                                + ", but the common incursion schedule "
                                + "requires wave "
                                + expectedWave.waveIndex()
                                + "."
                );
            }

            if (!sharesEqual(
                    expectedWave.threatShare(),
                    actualWave.threatShare()
            )) {
                throw new IllegalArgumentException(
                        "Front "
                                + frontId
                                + ", wave "
                                + actualWave.waveIndex()
                                + " has threat share "
                                + actualWave.threatShare()
                                + " but the common incursion wave profile "
                                + "uses "
                                + expectedWave.threatShare()
                                + "."
                );
            }

            if (!sharesEqual(
                    expectedWave.complexityShare(),
                    actualWave.complexityShare()
            )) {
                throw new IllegalArgumentException(
                        "Front "
                                + frontId
                                + ", wave "
                                + actualWave.waveIndex()
                                + " has complexity share "
                                + actualWave.complexityShare()
                                + " but the common incursion wave profile "
                                + "uses "
                                + expectedWave.complexityShare()
                                + "."
                );
            }
        }
    }

    /**
     * Confirms that every structural UUID is unique across the complete
     * incursion rather than merely unique inside its local parent.
     */
    private static void validateGlobalStructuralIdentities(
            List<FrontPlanSnapshot> frontPlanSnapshots
    ) {
        Set<UUID> sourceGroupCompositionIds =
                new HashSet<>();

        Set<UUID> sourceCompositionIds =
                new HashSet<>();

        Set<UUID> attachedMobAssignmentIds =
                new HashSet<>();

        Set<UUID> packIds =
                new HashSet<>();

        Set<UUID> sourceGroupPlacementIds =
                new HashSet<>();

        Set<UUID> sourcePlacementIds =
                new HashSet<>();

        for (FrontPlanSnapshot frontPlanSnapshot
                : frontPlanSnapshots) {

            for (FrontPlanSnapshot.WavePlanSnapshot wavePlanSnapshot
                    : frontPlanSnapshot.wavePlanSnapshots()) {

                for (SourceGroupCompositionSnapshot
                        sourceGroupCompositionSnapshot
                        : wavePlanSnapshot
                        .sourceGroupCompositionSnapshots()) {

                    requireUniqueStructuralId(
                            sourceGroupCompositionIds,
                            sourceGroupCompositionSnapshot
                                    .sourceGroupCompositionId(),
                            "source-group composition"
                    );

                    for (SourceGroupCompositionSnapshot
                            .SourceCompositionSnapshot
                            sourceCompositionSnapshot
                            : sourceGroupCompositionSnapshot
                            .sourceCompositionSnapshots()) {

                        requireUniqueStructuralId(
                                sourceCompositionIds,
                                sourceCompositionSnapshot
                                        .sourceCompositionId(),
                                "source composition"
                        );

                        for (SourceGroupCompositionSnapshot
                                .AttachedMobAssignmentSnapshot
                                attachedMobAssignmentSnapshot
                                : sourceCompositionSnapshot
                                .attachedMobAssignmentSnapshots()) {

                            requireUniqueStructuralId(
                                    attachedMobAssignmentIds,
                                    attachedMobAssignmentSnapshot
                                            .attachedMobAssignmentId(),
                                    "attached-mob assignment"
                            );
                        }
                    }

                    SourceGroupCompositionSnapshot
                            .PackAssignmentSnapshot
                            packAssignmentSnapshot =
                            sourceGroupCompositionSnapshot
                                    .packAssignmentSnapshot();

                    if (packAssignmentSnapshot != null) {
                        requireUniqueStructuralId(
                                packIds,
                                packAssignmentSnapshot.packId(),
                                "Pack"
                        );
                    }
                }
            }

            for (SourceGroupPlacementSnapshot
                    sourceGroupPlacementSnapshot
                    : frontPlanSnapshot
                    .sourceGroupPlacementSnapshots()) {

                requireUniqueStructuralId(
                        sourceGroupPlacementIds,
                        sourceGroupPlacementSnapshot
                                .sourceGroupPlacementId(),
                        "source-group placement"
                );

                for (SourceGroupPlacementSnapshot
                        .SourcePlacementSnapshot
                        sourcePlacementSnapshot
                        : sourceGroupPlacementSnapshot
                        .sourcePlacementSnapshots()) {

                    requireUniqueStructuralId(
                            sourcePlacementIds,
                            sourcePlacementSnapshot
                                    .sourcePlacementId(),
                            "source placement"
                    );
                }
            }
        }
    }

    /**
     * Confirms source-group envelope separation and cross-group source buffers
     * across the complete incursion.
     *
     * The lower-level snapshots already validate:
     *
     * - reservation non-overlap inside each group;
     * - reservation non-overlap between groups inside each front.
     *
     * This pass applies the complete authored cross-group rules regardless of
     * whether the two groups belong to the same front or different fronts.
     */
    private static void validateGlobalSourceGroupSeparation(
            List<FrontPlanSnapshot> frontPlanSnapshots
    ) {
        List<PhysicalGroupView> physicalGroups =
                capturePhysicalGroups(
                        frontPlanSnapshots
                );

        for (int firstGroupIndex = 0;
             firstGroupIndex < physicalGroups.size();
             firstGroupIndex++) {

            PhysicalGroupView firstGroup =
                    physicalGroups.get(
                            firstGroupIndex
                    );

            for (int secondGroupIndex = firstGroupIndex + 1;
                 secondGroupIndex < physicalGroups.size();
                 secondGroupIndex++) {

                PhysicalGroupView secondGroup =
                        physicalGroups.get(
                                secondGroupIndex
                        );

                validateEnvelopeSeparation(
                        firstGroup,
                        secondGroup
                );

                validateCrossGroupSourceBuffers(
                        firstGroup,
                        secondGroup
                );
            }
        }
    }

    private static List<PhysicalGroupView> capturePhysicalGroups(
            List<FrontPlanSnapshot> frontPlanSnapshots
    ) {
        List<PhysicalGroupView> physicalGroups =
                new ArrayList<>();

        for (FrontPlanSnapshot frontPlanSnapshot
                : frontPlanSnapshots) {

            for (SourceGroupPlacementSnapshot groupSnapshot
                    : frontPlanSnapshot
                    .sourceGroupPlacementSnapshots()) {

                SourceGroupPlacementSnapshot
                        .SourceGroupSpatialRulesSnapshot
                        rulesSnapshot =
                        groupSnapshot.spatialRulesSnapshot();

                SourceGroupPlacementSnapshot
                        .SourceGroupEnvelopeSnapshot
                        envelopeSnapshot =
                        groupSnapshot.envelopeSnapshot();

                SourceGroupSpatialRules spatialRules =
                        new SourceGroupSpatialRules(
                                rulesSnapshot.minimumInitialRadius(),
                                rulesSnapshot.initialRadiusMargin(),
                                rulesSnapshot.radiusExpansionStep(),
                                rulesSnapshot.maximumRadius(),
                                rulesSnapshot.minimumAnchorSeparation(),
                                rulesSnapshot.maximumEnvelopeOverlap(),
                                rulesSnapshot.crossGroupSourceBuffer()
                        );

                SourceGroupEnvelope envelope =
                        new SourceGroupEnvelope(
                                envelopeSnapshot.centre(),
                                envelopeSnapshot.radius()
                        );

                physicalGroups.add(
                        new PhysicalGroupView(
                                frontPlanSnapshot.frontId(),
                                groupSnapshot.sourceGroupPlacementId(),
                                spatialRules,
                                envelope,
                                groupSnapshot.sourcePlacementSnapshots()
                        )
                );
            }
        }

        return List.copyOf(
                physicalGroups
        );
    }

    private static void validateEnvelopeSeparation(
            PhysicalGroupView firstGroup,
            PhysicalGroupView secondGroup
    ) {
        int combinedRadius =
                firstGroup.envelope().radius()
                        + secondGroup.envelope().radius();

        int firstRuleSeparation =
                Math.max(
                        firstGroup
                                .spatialRules()
                                .minimumAnchorSeparation(),
                        combinedRadius
                                - firstGroup
                                .spatialRules()
                                .maximumEnvelopeOverlap()
                );

        int secondRuleSeparation =
                Math.max(
                        secondGroup
                                .spatialRules()
                                .minimumAnchorSeparation(),
                        combinedRadius
                                - secondGroup
                                .spatialRules()
                                .maximumEnvelopeOverlap()
                );

        int requiredSeparation =
                Math.max(
                        firstRuleSeparation,
                        secondRuleSeparation
                );

        double actualSeparationSquared =
                firstGroup
                        .envelope()
                        .horizontalDistanceSquaredTo(
                                secondGroup.envelope()
                        );

        double requiredSeparationSquared =
                (double) requiredSeparation
                        * requiredSeparation;

        if (actualSeparationSquared
                < requiredSeparationSquared) {

            throw new IllegalArgumentException(
                    "Physical source groups "
                            + firstGroup.sourceGroupPlacementId()
                            + " and "
                            + secondGroup.sourceGroupPlacementId()
                            + " are too close. Their envelopes require at "
                            + "least "
                            + requiredSeparation
                            + " blocks of centre separation."
            );
        }
    }

    private static void validateCrossGroupSourceBuffers(
            PhysicalGroupView firstGroup,
            PhysicalGroupView secondGroup
    ) {
        int requiredBuffer =
                Math.max(
                        firstGroup
                                .spatialRules()
                                .crossGroupSourceBuffer(),
                        secondGroup
                                .spatialRules()
                                .crossGroupSourceBuffer()
                );

        for (SourceGroupPlacementSnapshot
                .SourcePlacementSnapshot
                firstSource
                : firstGroup.sourcePlacementSnapshots()) {

            SourceReservationArea.WorldBounds firstBounds =
                    firstSource.getReservationBounds();

            for (SourceGroupPlacementSnapshot
                    .SourcePlacementSnapshot
                    secondSource
                    : secondGroup.sourcePlacementSnapshots()) {

                SourceReservationArea.WorldBounds secondBounds =
                        secondSource.getReservationBounds();

                if (overlapsWithBuffer(
                        firstBounds,
                        secondBounds,
                        requiredBuffer
                )) {
                    throw new IllegalArgumentException(
                            "Physical source placements "
                                    + firstSource.sourcePlacementId()
                                    + " and "
                                    + secondSource.sourcePlacementId()
                                    + " violate the cross-group source buffer "
                                    + "of "
                                    + requiredBuffer
                                    + " blocks between source groups "
                                    + firstGroup.sourceGroupPlacementId()
                                    + " and "
                                    + secondGroup.sourceGroupPlacementId()
                                    + "."
                    );
                }
            }
        }
    }

    /**
     * Uses the same inclusive buffered-boundary test as
     * SourcePlacementPlanner.
     */
    private static boolean overlapsWithBuffer(
            SourceReservationArea.WorldBounds firstBounds,
            SourceReservationArea.WorldBounds secondBounds,
            int buffer
    ) {
        if (firstBounds == null
                || secondBounds == null) {

            throw new IllegalArgumentException(
                    "Buffered reservation comparison requires two bounds."
            );
        }

        if (buffer < 0) {
            throw new IllegalArgumentException(
                    "Reservation separation buffer cannot be negative."
            );
        }

        return (long) firstBounds.minX()
                <= (long) secondBounds.maxX()
                + buffer
                && (long) firstBounds.maxX()
                >= (long) secondBounds.minX()
                - buffer
                && (long) firstBounds.minZ()
                <= (long) secondBounds.maxZ()
                + buffer
                && (long) firstBounds.maxZ()
                >= (long) secondBounds.minZ()
                - buffer;
    }

    private static void requireUniqueStructuralId(
            Set<UUID> existingIds,
            UUID candidateId,
            String description
    ) {
        if (candidateId == null) {
            throw new IllegalArgumentException(
                    description
                            + " ID cannot be null."
            );
        }

        if (!existingIds.add(
                candidateId
        )) {
            throw new IllegalArgumentException(
                    "Incursion-plan snapshot contains duplicate "
                            + description
                            + " ID "
                            + candidateId
                            + "."
            );
        }
    }

    private static void validateTotalShare(
            double totalShare,
            String description
    ) {
        if (!Double.isFinite(
                totalShare
        )) {
            throw new IllegalArgumentException(
                    description
                            + " share total must be finite."
            );
        }

        if (!sharesEqual(
                totalShare,
                1.0D
        )) {
            throw new IllegalArgumentException(
                    description
                            + " shares must total 1.0, but total "
                            + totalShare
                            + "."
            );
        }
    }

    private static boolean sharesEqual(
            double firstShare,
            double secondShare
    ) {
        return Math.abs(
                firstShare - secondShare
        ) <= SHARE_TOLERANCE;
    }

    /**
     * Minimal immutable description of one common global wave.
     */
    private record WaveIdentity(
            int waveIndex,
            double threatShare,
            double complexityShare
    ) {

        private WaveIdentity {
            if (waveIndex < 0) {
                throw new IllegalArgumentException(
                        "Wave identity index cannot be negative."
                );
            }

            if (!Double.isFinite(
                    threatShare
            )) {
                throw new IllegalArgumentException(
                        "Wave identity threat share must be finite."
                );
            }

            if (!Double.isFinite(
                    complexityShare
            )) {
                throw new IllegalArgumentException(
                        "Wave identity complexity share must be finite."
                );
            }
        }
    }

    /**
     * Reconstructed view of one physical group used only for complete-plan
     * spatial validation.
     */
    private record PhysicalGroupView(
            UUID frontId,
            UUID sourceGroupPlacementId,
            SourceGroupSpatialRules spatialRules,
            SourceGroupEnvelope envelope,
            List<SourceGroupPlacementSnapshot.SourcePlacementSnapshot>
            sourcePlacementSnapshots
    ) {

        private PhysicalGroupView {
            if (frontId == null) {
                throw new IllegalArgumentException(
                        "Physical-group front ID cannot be null."
                );
            }

            if (sourceGroupPlacementId == null) {
                throw new IllegalArgumentException(
                        "Physical-group placement ID cannot be null."
                );
            }

            if (spatialRules == null) {
                throw new IllegalArgumentException(
                        "Physical-group spatial rules cannot be null."
                );
            }

            if (envelope == null) {
                throw new IllegalArgumentException(
                        "Physical-group envelope cannot be null."
                );
            }

            if (sourcePlacementSnapshots == null
                    || sourcePlacementSnapshots.isEmpty()) {

                throw new IllegalArgumentException(
                        "Physical group requires at least one source "
                                + "placement."
                );
            }

            sourcePlacementSnapshots =
                    List.copyOf(
                            sourcePlacementSnapshots
                    );
        }
    }
}