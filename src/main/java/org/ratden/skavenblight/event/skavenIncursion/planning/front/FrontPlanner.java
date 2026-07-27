package org.ratden.skavenblight.event.skavenIncursion.planning.front;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import org.ratden.skavenblight.event.skavenIncursion.planning.FrontPlacementGeometry;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlanningContext;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlanningResult;
import org.ratden.skavenblight.event.skavenIncursion.planning.PlanningStepResult;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.ActiveIncursionSourceReservationRegistry;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceGroupEnvelope;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceGroupSpatialRules;
import org.ratden.skavenblight.event.skavenIncursion.planning.stratagem.StratagemDefinition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Creates front anchors, allocations and resolved wave budgets.
 *
 * Front placement preserves the Scenario's distance identity, the
 * Stratagem's complete angular pattern and active-incursion reservations.
 * A failed front never moves independently: the whole pattern is rejected,
 * rotated or regenerated, then tried again.
 *
 * Search order:
 *
 * 1. choose one preferred distance inside the authored band;
 * 2. exhaust bounded whole-pattern rotations at that distance;
 * 3. try nearby distances inside the same band;
 * 4. expand the whole pattern outwards only after the band is exhausted;
 * 5. never move inward below the preferred minimum as occupancy fallback.
 *
 * Every front in one attempted pattern requests the same value from
 * FrontPlacementGeometry. For Nexus targets this is clearance from the
 * protected Warp Flux network. For player targets it is distance from the
 * target centre.
 *
 * Exact adaptive envelopes are not known until composition and lifetime
 * source demand have been planned. This class therefore uses a provisional
 * first-group envelope. SourcePlacementPlanner remains authoritative for final
 * envelopes, source reservations, terrain and hard network clearance.
 */
public class FrontPlanner {

    private static final int MAX_IRREGULAR_ANGLE_ATTEMPTS = 100;
    private static final int IRREGULAR_LAYOUT_VARIANTS = 6;
    private static final int ROTATION_SAMPLES_PER_LAYOUT = 36;
    private static final int OUTWARD_SEARCH_DISTANCE = 96;
    private static final int OUTWARD_DISTANCE_STEP = 2;

    private static final int PROVISIONAL_FRONT_ENVELOPE_RADIUS = Math.max(
            SourceGroupSpatialRules.STANDARD.minimumInitialRadius(),
            SourceGroupSpatialRules.STANDARD.minimumAnchorSeparation()
    );

    public PlanningStepResult planFronts(
            IncursionPlanningContext context,
            IncursionPlan incursionPlan
    ) {
        if (context == null) {
            throw new IllegalArgumentException(
                    "Planning context cannot be null."
            );
        }

        if (incursionPlan == null) {
            throw new IllegalArgumentException(
                    "Incursion plan cannot be null."
            );
        }

        if (incursionPlan.hasFrontPlans()) {
            return failure(
                    IncursionPlanningResult.PlanningFailureReason
                            .INVALID_FRONT_ALLOCATION,
                    "Front planning cannot begin because incursion plan "
                            + incursionPlan.getIncursionId()
                            + " already contains fronts."
            );
        }

        StratagemDefinition stratagem =
                context.stratagemDefinition();

        FrontPlacementPattern placementPattern =
                stratagem.getFrontPlacementPattern();

        FrontAllocationPattern allocationPattern =
                stratagem.getFrontAllocationPattern();

        RandomSource random =
                context.level().getRandom();

        FrontPlacementGeometry placementGeometry =
                FrontPlacementGeometry.from(
                        context
                );

        List<UUID> frontIds =
                createFrontIds(
                        placementPattern.getFrontCount()
                );

        FrontAllocation frontAllocation;

        try {
            frontAllocation =
                    FrontAllocation.fromPattern(
                            frontIds,
                            placementPattern,
                            allocationPattern,
                            random
                    );
        } catch (IllegalArgumentException exception) {
            return failure(
                    IncursionPlanningResult.PlanningFailureReason
                            .INVALID_FRONT_ALLOCATION,
                    "Could not create front allocation for Stratagem "
                            + stratagem.getId()
                            + ": "
                            + exception.getMessage()
            );
        }

        List<ActiveIncursionSourceReservationRegistry
                .SourceGroupReservationSnapshot>
                activeGroupReservations =
                captureActiveGroupReservations(
                        context,
                        incursionPlan.getIncursionId()
                );

        List<BlockPos> frontAnchors =
                createFrontAnchors(
                        placementGeometry,
                        placementPattern,
                        random,
                        activeGroupReservations
                );

        if (frontAnchors.size()
                != frontIds.size()) {

            return failure(
                    IncursionPlanningResult.PlanningFailureReason
                            .NO_VIABLE_FRONT_PATTERN,
                    "Could not find a reservation-aware front layout for "
                            + "Stratagem "
                            + stratagem.getId()
                            + "."
                            + "\nRequired fronts: "
                            + frontIds.size()
                            + "."
                            + "\nDistance metric: "
                            + placementGeometry.getDistanceDescription()
                            + "."
                            + "\nPreferred distance band: "
                            + placementGeometry
                            .getPreferredMinimumDistance()
                            + " to "
                            + placementGeometry
                            .getPreferredMaximumDistance()
                            + "."
                            + "\nMaximum outward distance considered: "
                            + (
                            placementGeometry
                                    .getPreferredMaximumDistance()
                                    + OUTWARD_SEARCH_DISTANCE
                    )
                            + "."
                            + "\nRotation samples per layout: "
                            + ROTATION_SAMPLES_PER_LAYOUT
                            + "."
                            + "\nIrregular layout variants: "
                            + getLayoutVariantCount(
                            placementPattern
                    )
                            + "."
                            + "\nActive reserved groups considered: "
                            + activeGroupReservations.size()
                            + "."
                            + "\nProvisional front-envelope radius: "
                            + PROVISIONAL_FRONT_ENVELOPE_RADIUS
                            + "."
            );
        }

        int waveCount =
                stratagem.getWaveCount();

        double[][] threatWeights =
                new double[
                        frontIds.size()
                        ][
                        waveCount
                        ];

        double[][] complexityWeights =
                new double[
                        frontIds.size()
                        ][
                        waveCount
                        ];

        for (int frontIndex = 0;
             frontIndex < frontIds.size();
             frontIndex++) {

            UUID frontId =
                    frontIds.get(
                            frontIndex
                    );

            for (int waveIndex = 0;
                 waveIndex < waveCount;
                 waveIndex++) {

                StratagemDefinition.WaveProfile waveProfile =
                        stratagem.getWaveProfile(
                                waveIndex
                        );

                threatWeights[
                        frontIndex
                        ][
                        waveIndex
                        ] =
                        frontAllocation
                                .getThreatShare(
                                        frontId
                                )
                                * waveProfile
                                .threatShare();

                complexityWeights[
                        frontIndex
                        ][
                        waveIndex
                        ] =
                        frontAllocation
                                .getComplexityShare(
                                        frontId
                                )
                                * waveProfile
                                .complexityShare();
            }
        }

        int[][] threatBudgets =
                allocateIntegerBudget(
                        context.totalThreatBudget(),
                        threatWeights
                );

        int[][] complexityBudgets =
                allocateIntegerBudget(
                        context.totalComplexityBudget(),
                        complexityWeights
                );

        for (int frontIndex = 0;
             frontIndex < frontIds.size();
             frontIndex++) {

            UUID frontId =
                    frontIds.get(
                            frontIndex
                    );

            FrontPlan frontPlan =
                    new FrontPlan(
                            frontId,
                            frontIndex,
                            frontAnchors.get(
                                    frontIndex
                            ),
                            placementPattern,
                            frontAllocation
                                    .getThreatShare(
                                            frontId
                                    ),
                            frontAllocation
                                    .getComplexityShare(
                                            frontId
                                    ),
                            frontAllocation
                                    .isDominant(
                                            frontId
                                    )
                    );

            for (int waveIndex = 0;
                 waveIndex < waveCount;
                 waveIndex++) {

                StratagemDefinition.WaveProfile waveProfile =
                        stratagem.getWaveProfile(
                                waveIndex
                        );

                frontPlan.addWavePlan(
                        new FrontPlan.WavePlan(
                                waveIndex,
                                waveProfile
                                        .threatShare(),
                                waveProfile
                                        .complexityShare(),
                                threatBudgets[
                                        frontIndex
                                        ][
                                        waveIndex
                                        ],
                                complexityBudgets[
                                        frontIndex
                                        ][
                                        waveIndex
                                        ]
                        )
                );
            }

            incursionPlan.addFrontPlan(
                    frontPlan
            );
        }

        return PlanningStepResult.success();
    }

    private PlanningStepResult failure(
            IncursionPlanningResult.PlanningFailureReason reason,
            String message
    ) {
        return PlanningStepResult.failure(
                IncursionPlanningResult
                        .PlanningStage
                        .FRONT_PLANNING,
                reason,
                message
        );
    }

    private List<ActiveIncursionSourceReservationRegistry
            .SourceGroupReservationSnapshot>
    captureActiveGroupReservations(
            IncursionPlanningContext context,
            UUID currentIncursionId
    ) {
        List<ActiveIncursionSourceReservationRegistry
                .SourceGroupReservationSnapshot>
                captured =
                new ArrayList<>();

        for (ActiveIncursionSourceReservationRegistry
                .SourceGroupReservationSnapshot snapshot
                : ActiveIncursionSourceReservationRegistry
                .getSourceGroupReservations(
                        context.level()
                )) {

            /*
             * The current plan normally has not entered the registry yet.
             * Excluding its ID also protects deterministic debug retries that
             * deliberately reuse an incursion ID.
             */
            if (!currentIncursionId.equals(
                    snapshot.incursionId()
            )) {
                captured.add(
                        snapshot
                );
            }
        }

        return List.copyOf(
                captured
        );
    }

    private List<UUID> createFrontIds(
            int frontCount
    ) {
        List<UUID> frontIds =
                new ArrayList<>();

        for (int index = 0;
             index < frontCount;
             index++) {

            frontIds.add(
                    UUID.randomUUID()
            );
        }

        return frontIds;
    }

    // =========================================================
    // Complete pattern placement
    // =========================================================

    /**
     * Tries one complete tactical pattern at one requested distance at a
     * time.
     *
     * No anchor from a failed pattern is retained.
     */
    private List<BlockPos> createFrontAnchors(
            FrontPlacementGeometry placementGeometry,
            FrontPlacementPattern placementPattern,
            RandomSource random,
            List<ActiveIncursionSourceReservationRegistry
                    .SourceGroupReservationSnapshot>
                    activeGroupReservations
    ) {
        List<Integer> distanceAttempts =
                createDistanceAttempts(
                        placementGeometry,
                        random
                );

        List<List<Double>> relativeLayouts =
                createRelativeAngleLayouts(
                        placementPattern,
                        random
                );

        double rotationStep =
                360.0D
                        / ROTATION_SAMPLES_PER_LAYOUT;

        for (int requestedDistance
                : distanceAttempts) {

            for (List<Double> relativeAngles
                    : relativeLayouts) {

                double startingRotation =
                        random.nextDouble()
                                * 360.0D;

                for (int rotationIndex = 0;
                     rotationIndex
                             < ROTATION_SAMPLES_PER_LAYOUT;
                     rotationIndex++) {

                    double baseRotation =
                            normaliseAngle(
                                    startingRotation
                                            + rotationStep
                                            * rotationIndex
                            );

                    List<BlockPos> completePattern =
                            tryCreatePatternAtDistance(
                                    placementGeometry,
                                    requestedDistance,
                                    baseRotation,
                                    relativeAngles,
                                    activeGroupReservations
                            );

                    if (completePattern.size()
                            == placementPattern
                            .getFrontCount()) {

                        return completePattern;
                    }
                }
            }
        }

        return List.of();
    }

    /**
     * Selects one preferred distance first, followed by nearby values inside
     * the same band. Outward fallback is appended only after every preferred
     * distance.
     */
    private List<Integer> createDistanceAttempts(
            FrontPlacementGeometry placementGeometry,
            RandomSource random
    ) {
        int minimum =
                placementGeometry
                        .getPreferredMinimumDistance();

        int maximum =
                placementGeometry
                        .getPreferredMaximumDistance();

        if (minimum < 0
                || maximum < minimum) {

            throw new IllegalArgumentException(
                    "Invalid preferred front-distance band: "
                            + minimum
                            + " to "
                            + maximum
                            + "."
            );
        }

        int preferredCount =
                maximum
                        - minimum
                        + 1;

        int selected =
                minimum
                        + (
                        preferredCount <= 1
                                ? 0
                                : random.nextInt(
                                preferredCount
                        )
                );

        Set<Integer> ordered =
                new LinkedHashSet<>();

        ordered.add(
                selected
        );

        boolean lowerFirst =
                random.nextBoolean();

        int maximumOffset =
                Math.max(
                        selected - minimum,
                        maximum - selected
                );

        for (int offset = 1;
             offset <= maximumOffset;
             offset++) {

            int lower =
                    selected
                            - offset;

            int upper =
                    selected
                            + offset;

            if (lowerFirst) {
                addPreferredDistanceIfValid(
                        ordered,
                        lower,
                        minimum,
                        maximum
                );

                addPreferredDistanceIfValid(
                        ordered,
                        upper,
                        minimum,
                        maximum
                );
            } else {
                addPreferredDistanceIfValid(
                        ordered,
                        upper,
                        minimum,
                        maximum
                );

                addPreferredDistanceIfValid(
                        ordered,
                        lower,
                        minimum,
                        maximum
                );
            }
        }

        int maximumOutwardDistance =
                maximum
                        + OUTWARD_SEARCH_DISTANCE;

        for (int distance =
             maximum
                     + OUTWARD_DISTANCE_STEP;
             distance <= maximumOutwardDistance;
             distance += OUTWARD_DISTANCE_STEP) {

            ordered.add(
                    distance
            );
        }

        return List.copyOf(
                ordered
        );
    }

    private void addPreferredDistanceIfValid(
            Set<Integer> distances,
            int candidate,
            int minimum,
            int maximum
    ) {
        if (candidate >= minimum
                && candidate <= maximum) {

            distances.add(
                    candidate
            );
        }
    }

    /**
     * Attempts one complete angular layout at one shared requested distance.
     *
     * Every relative angle remains fixed. If any front fails, the complete
     * layout is discarded.
     */
    private List<BlockPos> tryCreatePatternAtDistance(
            FrontPlacementGeometry placementGeometry,
            int requestedDistance,
            double baseRotation,
            List<Double> relativeAngles,
            List<ActiveIncursionSourceReservationRegistry
                    .SourceGroupReservationSnapshot>
                    activeGroupReservations
    ) {
        List<BlockPos> frontAnchors =
                new ArrayList<>();

        List<SourceGroupEnvelope> provisionalEnvelopes =
                new ArrayList<>();

        for (double relativeAngle
                : relativeAngles) {

            double absoluteAngle =
                    normaliseAngle(
                            baseRotation
                                    + relativeAngle
                    );

            BlockPos candidate =
                    placementGeometry
                            .findPositionAtDistance(
                                    absoluteAngle,
                                    requestedDistance
                            );

            if (candidate == null
                    || !isFrontAnchorViable(
                    candidate,
                    activeGroupReservations,
                    provisionalEnvelopes
            )) {
                return List.of();
            }

            frontAnchors.add(
                    candidate
            );

            provisionalEnvelopes.add(
                    new SourceGroupEnvelope(
                            candidate,
                            PROVISIONAL_FRONT_ENVELOPE_RADIUS
                    )
            );
        }

        return List.copyOf(
                frontAnchors
        );
    }

    // =========================================================
    // Relative angular layouts
    // =========================================================

    /**
     * Evenly spaced patterns have one fixed relative shape.
     *
     * Irregular patterns may generate several distinct shapes, but each shape
     * is subsequently rotated and validated as a complete unit.
     */
    private List<List<Double>> createRelativeAngleLayouts(
            FrontPlacementPattern placementPattern,
            RandomSource random
    ) {
        if (placementPattern.isEvenlySpaced()) {
            return List.of(
                    createEvenlySpacedRelativeAngles(
                            placementPattern
                    )
            );
        }

        if (placementPattern.getFrontCount()
                == 1) {

            return List.of(
                    List.of(
                            0.0D
                    )
            );
        }

        List<List<Double>> layouts =
                new ArrayList<>();

        for (int layoutIndex = 0;
             layoutIndex
                     < IRREGULAR_LAYOUT_VARIANTS;
             layoutIndex++) {

            List<Double> layout =
                    createIrregularRelativeAngles(
                            placementPattern,
                            random
                    );

            if (!layout.isEmpty()) {
                layouts.add(
                        layout
                );
            }
        }

        if (layouts.isEmpty()) {
            layouts.add(
                    createFallbackRelativeAngles(
                            placementPattern.getFrontCount()
                    )
            );
        }

        return List.copyOf(
                layouts
        );
    }

    private List<Double> createEvenlySpacedRelativeAngles(
            FrontPlacementPattern placementPattern
    ) {
        List<Double> angles =
                new ArrayList<>();

        for (int frontIndex = 0;
             frontIndex
                     < placementPattern.getFrontCount();
             frontIndex++) {

            angles.add(
                    normaliseAngle(
                            placementPattern
                                    .getSeparationDegrees()
                                    * frontIndex
                    )
            );
        }

        return List.copyOf(
                angles
        );
    }

    private List<Double> createIrregularRelativeAngles(
            FrontPlacementPattern placementPattern,
            RandomSource random
    ) {
        List<Double> angles =
                new ArrayList<>();

        /*
         * The first front defines zero degrees inside this relative layout.
         * A later base rotation moves the complete shape around the target.
         */
        angles.add(
                0.0D
        );

        for (int frontIndex = 1;
             frontIndex
                     < placementPattern.getFrontCount();
             frontIndex++) {

            boolean angleFound =
                    false;

            for (int attempt = 0;
                 attempt
                         < MAX_IRREGULAR_ANGLE_ATTEMPTS;
                 attempt++) {

                double candidate =
                        random.nextDouble()
                                * 360.0D;

                if (isSeparatedFromExistingAngles(
                        candidate,
                        angles,
                        placementPattern
                                .getSeparationDegrees()
                )) {
                    angles.add(
                            candidate
                    );

                    angleFound =
                            true;

                    break;
                }
            }

            if (!angleFound) {
                return List.of();
            }
        }

        return List.copyOf(
                angles
        );
    }

    private boolean isSeparatedFromExistingAngles(
            double candidateAngle,
            List<Double> existingAngles,
            double minimumSeparation
    ) {
        for (double existingAngle
                : existingAngles) {

            double difference =
                    Math.abs(
                            normaliseAngle(
                                    candidateAngle
                            )
                                    - normaliseAngle(
                                    existingAngle
                            )
                    );

            difference =
                    Math.min(
                            difference,
                            360.0D
                                    - difference
                    );

            if (difference
                    < minimumSeparation) {

                return false;
            }
        }

        return true;
    }

    private List<Double> createFallbackRelativeAngles(
            int frontCount
    ) {
        List<Double> angles =
                new ArrayList<>();

        double separation =
                360.0D
                        / frontCount;

        for (int frontIndex = 0;
             frontIndex < frontCount;
             frontIndex++) {

            angles.add(
                    normaliseAngle(
                            separation
                                    * frontIndex
                    )
            );
        }

        return List.copyOf(
                angles
        );
    }

    private int getLayoutVariantCount(
            FrontPlacementPattern placementPattern
    ) {
        return placementPattern.isEvenlySpaced()
                || placementPattern.getFrontCount() == 1
                ? 1
                : IRREGULAR_LAYOUT_VARIANTS;
    }

    // =========================================================
    // Provisional reservation checks
    // =========================================================

    private boolean isFrontAnchorViable(
            BlockPos candidateAnchor,
            List<ActiveIncursionSourceReservationRegistry
                    .SourceGroupReservationSnapshot>
                    activeGroupReservations,
            List<SourceGroupEnvelope> provisionalEnvelopes
    ) {
        SourceGroupEnvelope candidateEnvelope =
                new SourceGroupEnvelope(
                        candidateAnchor,
                        PROVISIONAL_FRONT_ENVELOPE_RADIUS
                );

        SourceGroupSpatialRules candidateRules =
                SourceGroupSpatialRules.STANDARD;

        /*
         * Preserve separation between every front in this same provisional
         * pattern.
         */
        for (SourceGroupEnvelope provisionalEnvelope
                : provisionalEnvelopes) {

            int requiredSeparation =
                    calculateSymmetricEnvelopeSeparation(
                            candidateRules,
                            candidateEnvelope,
                            SourceGroupSpatialRules.STANDARD,
                            provisionalEnvelope
                    );

            if (!hasRequiredEnvelopeSeparation(
                    candidateEnvelope,
                    provisionalEnvelope,
                    requiredSeparation
            )) {
                return false;
            }
        }

        /*
         * Preserve separation from every source group belonging to an
         * already-active incursion.
         */
        for (ActiveIncursionSourceReservationRegistry
                .SourceGroupReservationSnapshot activeGroup
                : activeGroupReservations) {

            int requiredSeparation =
                    calculateSymmetricEnvelopeSeparation(
                            candidateRules,
                            candidateEnvelope,
                            activeGroup.sourceGroupSpatialRules(),
                            activeGroup.sourceGroupEnvelope()
                    );

            if (!hasRequiredEnvelopeSeparation(
                    candidateEnvelope,
                    activeGroup.sourceGroupEnvelope(),
                    requiredSeparation
            )) {
                return false;
            }
        }

        return true;
    }

    private int calculateSymmetricEnvelopeSeparation(
            SourceGroupSpatialRules firstRules,
            SourceGroupEnvelope firstEnvelope,
            SourceGroupSpatialRules secondRules,
            SourceGroupEnvelope secondEnvelope
    ) {
        int combinedRadius =
                firstEnvelope.radius()
                        + secondEnvelope.radius();

        int firstRequirement =
                Math.max(
                        firstRules.minimumAnchorSeparation(),
                        combinedRadius
                                - firstRules.maximumEnvelopeOverlap()
                );

        int secondRequirement =
                Math.max(
                        secondRules.minimumAnchorSeparation(),
                        combinedRadius
                                - secondRules.maximumEnvelopeOverlap()
                );

        return Math.max(
                firstRequirement,
                secondRequirement
        );
    }

    private boolean hasRequiredEnvelopeSeparation(
            SourceGroupEnvelope firstEnvelope,
            SourceGroupEnvelope secondEnvelope,
            int requiredSeparation
    ) {
        double actualSquared =
                firstEnvelope.horizontalDistanceSquaredTo(
                        secondEnvelope
                );

        double requiredSquared =
                (double) requiredSeparation
                        * requiredSeparation;

        return actualSquared
                >= requiredSquared;
    }

    private double normaliseAngle(
            double angle
    ) {
        double normalised =
                angle
                        % 360.0D;

        return normalised < 0.0D
                ? normalised + 360.0D
                : normalised;
    }

    // =========================================================
    // Budget allocation
    // =========================================================

    private int[][] allocateIntegerBudget(
            int totalBudget,
            double[][] weights
    ) {
        int frontCount =
                weights.length;

        int waveCount =
                frontCount == 0
                        ? 0
                        : weights[0].length;

        int[][] allocations =
                new int[
                        frontCount
                        ][
                        waveCount
                        ];

        List<BudgetRemainder> remainders =
                new ArrayList<>();

        int allocatedBudget =
                0;

        for (int frontIndex = 0;
             frontIndex < frontCount;
             frontIndex++) {

            for (int waveIndex = 0;
                 waveIndex < waveCount;
                 waveIndex++) {

                double exactAllocation =
                        totalBudget
                                * weights[
                                frontIndex
                                ][
                                waveIndex
                                ];

                int floorAllocation =
                        (int) Math.floor(
                                exactAllocation
                        );

                allocations[
                        frontIndex
                        ][
                        waveIndex
                        ] =
                        floorAllocation;

                allocatedBudget +=
                        floorAllocation;

                remainders.add(
                        new BudgetRemainder(
                                frontIndex,
                                waveIndex,
                                exactAllocation
                                        - floorAllocation
                        )
                );
            }
        }

        remainders.sort(
                Comparator.comparingDouble(
                        BudgetRemainder
                                ::fractionalRemainder
                ).reversed()
        );

        int remainingBudget =
                totalBudget
                        - allocatedBudget;

        for (int index = 0;
             index < remainingBudget;
             index++) {

            BudgetRemainder remainder =
                    remainders.get(
                            index
                                    % remainders.size()
                    );

            allocations[
                    remainder.frontIndex()
                    ][
                    remainder.waveIndex()
                    ]++;
        }

        return allocations;
    }

    private record BudgetRemainder(
            int frontIndex,
            int waveIndex,
            double fractionalRemainder
    ) {
    }
}