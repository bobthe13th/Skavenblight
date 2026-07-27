package org.ratden.skavenblight.event.skavenIncursion.planning.source;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.ratden.skavenblight.block.custom.SkavenTunnelSourceBlock;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlanningContext;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlanningResult;
import org.ratden.skavenblight.event.skavenIncursion.planning.PlanningStepResult;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.SourceGroupComposition;
import org.ratden.skavenblight.event.skavenIncursion.planning.front.FrontPlan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Converts completed all-wave source compositions into persistent physical
 * source-group and source-placement plans.
 *
 * Placement occurs in four stages:
 *
 * 1. SourceGroupDemandPlanner examines every wave in one front and determines
 *    each persistent source group's complete lifetime infrastructure demand.
 *
 * 2. A conceptual group anchor and adaptive circular source-centre envelope
 *    are selected.
 *
 * 3. Every persistent physical source required during the group's complete
 *    lifetime is assigned a permanent position inside that envelope.
 *
 * 4. The successful temporary draft is committed to final
 *    SourceGroupPlacementPlan and SourcePlacementPlan objects.
 *
 * The first persistent source group in a front uses the FrontPlan anchor as
 * its conceptual centre. Additional groups receive their own separately
 * planned anchors.
 *
 * Only the centre of each complete source reservation must lie inside its
 * group's envelope. The reservation itself may protrude beyond the envelope.
 *
 * Complete source reservations control:
 *
 * - overlap between physical sources;
 * - additional separation between different groups;
 * - hard Warp Flux network clearance;
 * - existing tunnel-source conflicts;
 * - active-incursion reservations, including unspawned future sources;
 * - terrain preparation viability.
 *
 * This planner does not place source blocks or modify terrain. Runtime
 * execution performs those actions after the completed IncursionPlan has
 * passed validation.
 */
public class SourcePlacementPlanner {

    /**
     * Number of alternative source layouts attempted for one anchor and one
     * envelope radius before the envelope is expanded.
     */
    private static final int
            GROUP_LAYOUT_ATTEMPTS_PER_ENVELOPE =
            8;

    /**
     * Random source-centre candidates examined during each preferred or
     * preparable placement pass.
     */
    private static final int
            RANDOM_SOURCE_CANDIDATES_PER_PASS =
            48;

    /**
     * Step used by the deterministic circular fallback search inside an
     * envelope.
     */
    private static final int
            SOURCE_FALLBACK_RING_STEP =
            2;

    /**
     * Additional-group anchor candidates are generated on rings around the
     * front anchor.
     */
    private static final int
            GROUP_ANCHOR_RING_STEP =
            4;

    private static final int
            GROUP_ANCHOR_ANGLE_SAMPLES =
            16;

    /**
     * Maximum distance from the front anchor considered for an additional
     * physical source-group centre.
     *
     * Candidate ordering searches the preferred battlefield band first and
     * then progressively farther from the target.
     */
    private static final int
            GROUP_ANCHOR_MAX_SEARCH_RADIUS =
            80;

    private static final int
            MAX_GROUP_ANCHOR_CANDIDATES =
            96;

    private static final int
            EXISTING_SOURCE_VERTICAL_SEARCH =
            6;

    /**
     * Existing tunnel blocks do not yet expose their complete authored
     * reservation profiles through their block entities.
     *
     * Managed active incursions are protected by the authoritative reservation
     * registry. Any other tunnel block discovered in the world is treated as
     * occupying the normal 5x5 tunnel reservation.
     */
    private static final SourceReservationArea
            LEGACY_TUNNEL_RESERVATION =
            SourceReservationArea.centered(
                    5,
                    5
            );

    public PlanningStepResult planSourcePlacements(
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

        if (!incursionPlan.hasFrontPlans()) {
            return failure(
                    "Source placement cannot begin because incursion plan "
                            + incursionPlan.getIncursionId()
                            + " contains no fronts."
            );
        }

        for (FrontPlan frontPlan
                : incursionPlan.getFrontPlans()) {

            if (frontPlan == null) {
                return failure(
                        "Incursion plan "
                                + incursionPlan.getIncursionId()
                                + " contains a null front."
                );
            }

            if (frontPlan.hasSourceGroupPlacementPlans()) {
                return failure(
                        "Front "
                                + frontPlan.getFrontId()
                                + " already contains source-placement "
                                + "results."
                );
            }
        }

        NetworkClearanceRule networkClearanceRule =
                resolveNetworkClearanceRule(
                        context
                );

        /*
         * Capture the active-incursion reservation state once.
         *
         * Every front and candidate in this planning pass therefore sees the
         * same immutable external battlefield reservations.
         */
        ActiveReservationView activeReservationView =
                ActiveReservationView.capture(
                        context.level(),
                        incursionPlan.getIncursionId()
                );

        /*
         * These drafts belong to source groups already accepted by earlier
         * fronts in this same incursion.
         *
         * They prevent later fronts from occupying or crowding the same
         * source reservations before this plan itself enters the active
         * reservation registry.
         */
        List<SourceGroupPlacementDraft>
                committedIncursionDrafts =
                new ArrayList<>();

        SourceGroupDemandPlanner demandPlanner =
                new SourceGroupDemandPlanner();

        for (FrontPlan frontPlan
                : incursionPlan.getFrontPlans()) {

            SourceGroupDemandPlanner.DemandPlanningResult
                    demandPlanningResult =
                    demandPlanner.planFrontDemands(
                            frontPlan
                    );

            if (demandPlanningResult.hasFailed()) {
                return failure(
                        "Could not determine complete source-group demand "
                                + "for front "
                                + frontPlan.getFrontId()
                                + ": "
                                + demandPlanningResult.failureMessage()
                );
            }

            PlanningStepResult frontResult =
                    planFrontPlacements(
                            context,
                            frontPlan,
                            demandPlanningResult.frontDemandPlan(),
                            committedIncursionDrafts,
                            activeReservationView,
                            networkClearanceRule
                    );

            if (frontResult.hasFailed()) {
                return frontResult;
            }
        }

        return PlanningStepResult.success();
    }

    // =========================================================
    // Front and group orchestration
    // =========================================================

    /**
     * Plans every source group in one front before mutating FrontPlan.
     *
     * This makes the front placement pass transactional: a failed later group
     * does not leave earlier groups from the same front partially committed.
     */
    private PlanningStepResult planFrontPlacements(
            IncursionPlanningContext context,
            FrontPlan frontPlan,
            SourceGroupDemandPlanner.FrontDemandPlan frontDemandPlan,
            List<SourceGroupPlacementDraft>
                    committedIncursionDrafts,
            ActiveReservationView activeReservationView,
            NetworkClearanceRule networkClearanceRule
    ) {
        if (frontDemandPlan == null) {
            return failure(
                    "Front "
                            + frontPlan.getFrontId()
                            + " has no completed source-group demand plan."
            );
        }

        if (!frontPlan.getFrontId().equals(
                frontDemandPlan.frontId()
        )) {
            return failure(
                    "Front demand plan belongs to a different front."
            );
        }

        if (frontDemandPlan.isEmpty()) {
            return failure(
                    "Front "
                            + frontPlan.getFrontId()
                            + " requires no persistent source groups."
            );
        }

        List<SourceGroupPlacementDemand> orderedDemands =
                new ArrayList<>(
                        frontDemandPlan.sourceGroupDemands()
                );

        orderedDemands.sort(
                Comparator.comparingInt(
                        SourceGroupPlacementDemand
                                ::getSourceGroupIndex
                )
        );

        List<SourceGroupPlacementDraft> frontDrafts =
                new ArrayList<>();

        for (int expectedGroupIndex = 0;
             expectedGroupIndex < orderedDemands.size();
             expectedGroupIndex++) {

            SourceGroupPlacementDemand sourceGroupDemand =
                    orderedDemands.get(
                            expectedGroupIndex
                    );

            if (sourceGroupDemand.getSourceGroupIndex()
                    != expectedGroupIndex) {
                return failure(
                        "Front "
                                + frontPlan.getFrontId()
                                + " contains source-group demand index "
                                + sourceGroupDemand.getSourceGroupIndex()
                                + " at ordered position "
                                + expectedGroupIndex
                                + "."
                );
            }

            SourceGroupPlacementDraft sourceGroupDraft =
                    planSourceGroupDraft(
                            context,
                            frontPlan,
                            sourceGroupDemand,
                            committedIncursionDrafts,
                            frontDrafts,
                            activeReservationView,
                            networkClearanceRule
                    );

            if (sourceGroupDraft == null) {
                return failure(
                        "Could not place persistent source group "
                                + (sourceGroupDemand
                                .getSourceGroupIndex() + 1)
                                + " in front "
                                + frontPlan.getFrontId()
                                + ". Required physical sources: "
                                + sourceGroupDemand
                                .getPhysicalSourceCount()
                                + ". Attempted envelope radii: "
                                + sourceGroupDemand
                                .createEnvelopeRadiusAttempts()
                                + ". Active reserved groups considered: "
                                + activeReservationView
                                .getSourceGroupReservationCount()
                                + "."
                );
            }

            frontDrafts.add(
                    sourceGroupDraft
            );
        }

        try {
            commitFrontDrafts(
                    frontPlan,
                    frontDrafts
            );
        } catch (IllegalArgumentException
                 | IllegalStateException exception) {

            return failure(
                    "Could not commit completed source-group placements for "
                            + "front "
                            + frontPlan.getFrontId()
                            + ": "
                            + exception.getMessage()
            );
        }

        committedIncursionDrafts.addAll(
                frontDrafts
        );

        return PlanningStepResult.success();
    }

    private SourceGroupPlacementDraft planSourceGroupDraft(
            IncursionPlanningContext context,
            FrontPlan frontPlan,
            SourceGroupPlacementDemand sourceGroupDemand,
            List<SourceGroupPlacementDraft>
                    committedIncursionDrafts,
            List<SourceGroupPlacementDraft> currentFrontDrafts,
            ActiveReservationView activeReservationView,
            NetworkClearanceRule networkClearanceRule
    ) {
        List<BlockPos> anchorCandidates;

        if (sourceGroupDemand.getSourceGroupIndex() == 0) {
            /*
             * The first source group is structurally represented by the front
             * anchor. Its conceptual group centre must therefore remain
             * exactly equal to the FrontPlan anchor.
             */
            anchorCandidates =
                    List.of(
                            frontPlan
                                    .getAnchorPos()
                                    .immutable()
                    );
        } else {
            anchorCandidates =
                    createAdditionalGroupAnchorCandidates(
                            context,
                            frontPlan
                    );
        }

        List<SourceGroupPlacementDraft>
                reservedGroupDrafts =
                new ArrayList<>(
                        committedIncursionDrafts
                );

        reservedGroupDrafts.addAll(
                currentFrontDrafts
        );

        for (BlockPos groupAnchor
                : anchorCandidates) {

            for (int envelopeRadius
                    : sourceGroupDemand
                    .createEnvelopeRadiusAttempts()) {

                SourceGroupEnvelope sourceGroupEnvelope =
                        new SourceGroupEnvelope(
                                groupAnchor,
                                envelopeRadius
                        );

                if (!canPlaceEnvelope(
                        sourceGroupDemand
                                .getSourceGroupSpatialRules(),
                        sourceGroupEnvelope,
                        reservedGroupDrafts,
                        activeReservationView
                )) {
                    continue;
                }

                SourceGroupPlacementDraft sourceGroupDraft =
                        attemptGroupLayouts(
                                context,
                                sourceGroupDemand,
                                sourceGroupEnvelope,
                                reservedGroupDrafts,
                                activeReservationView,
                                networkClearanceRule
                        );

                if (sourceGroupDraft != null) {
                    return sourceGroupDraft;
                }
            }
        }

        return null;
    }

    // =========================================================
    // Source-group anchor placement
    // =========================================================

    /**
     * Creates bounded candidate positions for an additional source-group
     * anchor.
     *
     * Candidate ordering follows the desired fallback policy:
     *
     * 1. positions no closer to the target than the front anchor;
     * 2. positions still broadly inside the preferred front-distance band;
     * 3. positions progressively farther outward.
     *
     * The hard protected-network boundary is separately enforced against
     * every complete source reservation and is never relaxed.
     */
    private List<BlockPos> createAdditionalGroupAnchorCandidates(
            IncursionPlanningContext context,
            FrontPlan frontPlan
    ) {
        BlockPos targetPos =
                context.targetPos();

        BlockPos frontAnchor =
                frontPlan.getAnchorPos();

        double frontDistance =
                horizontalDistance(
                        frontAnchor,
                        targetPos
                );

        double preferredMaximumDistance =
                Math.max(
                        context.maximumFrontDistance(),
                        frontDistance
                                + GROUP_ANCHOR_RING_STEP * 2.0D
                );

        double outwardAngle =
                calculateOutwardAngle(
                        targetPos,
                        frontAnchor
                );

        LinkedHashSet<BlockPos> preferredCandidates =
                new LinkedHashSet<>();

        LinkedHashSet<BlockPos> outwardCandidates =
                new LinkedHashSet<>();

        int[] sampleOrder = {
                4,
                12,
                2,
                14,
                0,
                6,
                10,
                8,
                1,
                15,
                3,
                13,
                5,
                11,
                7,
                9
        };

        for (int ringRadius =
             SourceGroupSpatialRules
                     .STANDARD
                     .minimumAnchorSeparation();
             ringRadius
                     <= GROUP_ANCHOR_MAX_SEARCH_RADIUS;
             ringRadius += GROUP_ANCHOR_RING_STEP) {

            for (int orderedSampleIndex = 0;
                 orderedSampleIndex
                         < GROUP_ANCHOR_ANGLE_SAMPLES;
                 orderedSampleIndex++) {

                int sampleIndex =
                        sampleOrder[
                                orderedSampleIndex
                                ];

                double angle =
                        outwardAngle
                                + (
                                Math.PI
                                        * 2.0D
                                        * sampleIndex
                                        / GROUP_ANCHOR_ANGLE_SAMPLES
                        );

                int offsetX =
                        (int) Math.round(
                                Math.cos(angle)
                                        * ringRadius
                        );

                int offsetZ =
                        (int) Math.round(
                                Math.sin(angle)
                                        * ringRadius
                        );

                if (offsetX == 0
                        && offsetZ == 0) {
                    continue;
                }

                BlockPos candidate =
                        frontAnchor.offset(
                                offsetX,
                                0,
                                offsetZ
                        ).immutable();

                double candidateDistance =
                        horizontalDistance(
                                candidate,
                                targetPos
                        );

                /*
                 * Additional group anchors may move laterally or farther
                 * outward, but never inward towards the protected base.
                 */
                if (candidateDistance
                        + 0.001D
                        < frontDistance) {
                    continue;
                }

                if (candidateDistance
                        <= preferredMaximumDistance) {
                    preferredCandidates.add(
                            candidate
                    );
                } else {
                    outwardCandidates.add(
                            candidate
                    );
                }
            }
        }

        List<BlockPos> orderedCandidates =
                new ArrayList<>();

        appendCandidates(
                orderedCandidates,
                preferredCandidates
        );

        appendCandidates(
                orderedCandidates,
                outwardCandidates
        );

        if (orderedCandidates.size()
                > MAX_GROUP_ANCHOR_CANDIDATES) {
            return List.copyOf(
                    orderedCandidates.subList(
                            0,
                            MAX_GROUP_ANCHOR_CANDIDATES
                    )
            );
        }

        return List.copyOf(
                orderedCandidates
        );
    }

    private void appendCandidates(
            List<BlockPos> destination,
            Set<BlockPos> candidates
    ) {
        for (BlockPos candidate
                : candidates) {

            if (destination.size()
                    >= MAX_GROUP_ANCHOR_CANDIDATES) {
                return;
            }

            destination.add(
                    candidate
            );
        }
    }

    private double calculateOutwardAngle(
            BlockPos targetPos,
            BlockPos frontAnchor
    ) {
        int differenceX =
                frontAnchor.getX()
                        - targetPos.getX();

        int differenceZ =
                frontAnchor.getZ()
                        - targetPos.getZ();

        if (differenceX == 0
                && differenceZ == 0) {
            return 0.0D;
        }

        return Math.atan2(
                differenceZ,
                differenceX
        );
    }

    /**
     * Checks circular envelope separation against:
     *
     * - every source group already accepted by this same incursion;
     * - every source group reserved by another active incursion.
     *
     * A small authored amount of envelope overlap is permitted. Actual source
     * reservations remain subject to the stricter cross-group source buffer.
     */
    private boolean canPlaceEnvelope(
            SourceGroupSpatialRules candidateRules,
            SourceGroupEnvelope candidateEnvelope,
            List<SourceGroupPlacementDraft> reservedGroupDrafts,
            ActiveReservationView activeReservationView
    ) {
        for (SourceGroupPlacementDraft reservedGroupDraft
                : reservedGroupDrafts) {

            int requiredSeparation =
                    calculateSymmetricEnvelopeSeparation(
                            candidateRules,
                            candidateEnvelope,
                            reservedGroupDraft
                                    .getSourceGroupSpatialRules(),
                            reservedGroupDraft
                                    .getSourceGroupEnvelope()
                    );

            if (!hasRequiredEnvelopeSeparation(
                    candidateEnvelope,
                    reservedGroupDraft
                            .getSourceGroupEnvelope(),
                    requiredSeparation
            )) {
                return false;
            }
        }

        for (ActiveIncursionSourceReservationRegistry
                .SourceGroupReservationSnapshot activeGroupReservation
                : activeReservationView
                .sourceGroupReservations()) {

            int requiredSeparation =
                    calculateSymmetricEnvelopeSeparation(
                            candidateRules,
                            candidateEnvelope,
                            activeGroupReservation
                                    .sourceGroupSpatialRules(),
                            activeGroupReservation
                                    .sourceGroupEnvelope()
                    );

            if (!hasRequiredEnvelopeSeparation(
                    candidateEnvelope,
                    activeGroupReservation
                            .sourceGroupEnvelope(),
                    requiredSeparation
            )) {
                return false;
            }
        }

        return true;
    }

    /**
     * Calculates separation symmetrically without asking either rule object to
     * validate the other group's radius against its own maximum-radius limit.
     *
     * This allows differently authored group-rule sets to coexist safely.
     */
    private int calculateSymmetricEnvelopeSeparation(
            SourceGroupSpatialRules firstRules,
            SourceGroupEnvelope firstEnvelope,
            SourceGroupSpatialRules secondRules,
            SourceGroupEnvelope secondEnvelope
    ) {
        int combinedRadius =
                firstEnvelope.radius()
                        + secondEnvelope.radius();

        int firstRuleSeparation =
                Math.max(
                        firstRules.minimumAnchorSeparation(),
                        combinedRadius
                                - firstRules.maximumEnvelopeOverlap()
                );

        int secondRuleSeparation =
                Math.max(
                        secondRules.minimumAnchorSeparation(),
                        combinedRadius
                                - secondRules.maximumEnvelopeOverlap()
                );

        return Math.max(
                firstRuleSeparation,
                secondRuleSeparation
        );
    }

    private boolean hasRequiredEnvelopeSeparation(
            SourceGroupEnvelope firstEnvelope,
            SourceGroupEnvelope secondEnvelope,
            int requiredSeparation
    ) {
        double actualSeparationSquared =
                firstEnvelope
                        .horizontalDistanceSquaredTo(
                                secondEnvelope
                        );

        double requiredSeparationSquared =
                (double) requiredSeparation
                        * requiredSeparation;

        return actualSeparationSquared
                >= requiredSeparationSquared;
    }

    // =========================================================
    // Complete group-layout attempts
    // =========================================================

    private SourceGroupPlacementDraft attemptGroupLayouts(
            IncursionPlanningContext context,
            SourceGroupPlacementDemand sourceGroupDemand,
            SourceGroupEnvelope sourceGroupEnvelope,
            List<SourceGroupPlacementDraft> reservedGroupDrafts,
            ActiveReservationView activeReservationView,
            NetworkClearanceRule networkClearanceRule
    ) {
        List<
                SourceGroupPlacementDemand.PhysicalSourceDemand
                > orderedPhysicalDemands =
                new ArrayList<>(
                        sourceGroupDemand
                                .getPhysicalSourceDemands()
                );

        orderMostRestrictiveSourcesFirst(
                orderedPhysicalDemands
        );

        for (int layoutAttempt = 0;
             layoutAttempt
                     < GROUP_LAYOUT_ATTEMPTS_PER_ENVELOPE;
             layoutAttempt++) {

            List<
                    SourceGroupPlacementDraft
                            .PhysicalSourcePlacementDraft
                    > physicalSourcePlacements =
                    new ArrayList<>();

            boolean layoutFailed =
                    false;

            for (SourceGroupPlacementDemand.PhysicalSourceDemand
                    physicalSourceDemand
                    : orderedPhysicalDemands) {

                SourceSite sourceSite =
                        findSourceSiteWithinEnvelope(
                                context.level(),
                                context.targetPos(),
                                sourceGroupDemand,
                                sourceGroupEnvelope,
                                physicalSourceDemand,
                                physicalSourcePlacements,
                                reservedGroupDrafts,
                                activeReservationView,
                                networkClearanceRule
                        );

                if (sourceSite == null) {
                    layoutFailed =
                            true;

                    break;
                }

                physicalSourcePlacements.add(
                        new SourceGroupPlacementDraft
                                .PhysicalSourcePlacementDraft(
                                sourceGroupDemand
                                        .getSourceGroupDemandId(),
                                physicalSourceDemand
                                        .getPhysicalSourceDemandId(),
                                physicalSourceDemand
                                        .getPlacementProfile(),
                                sourceSite.origin(),
                                sourceSite.facing(),
                                sourceSite.reservationBounds(),
                                physicalSourceDemand
                                        .getSourceCompositionIds(),
                                sourceSite.siteQuality()
                        )
                );
            }

            if (layoutFailed) {
                continue;
            }

            try {
                return new SourceGroupPlacementDraft(
                        sourceGroupDemand,
                        sourceGroupEnvelope,
                        physicalSourcePlacements
                );
            } catch (IllegalArgumentException
                     | IllegalStateException exception) {
                /*
                 * A failed complete draft is discarded. Another independent
                 * layout is attempted without mutating final planning
                 * objects.
                 */
            }
        }

        return null;
    }

    /**
     * Places larger and more restrictive source footprints first.
     *
     * This prevents small normal sources from consuming the few positions
     * capable of accommodating a later large or asymmetric source.
     */
    private void orderMostRestrictiveSourcesFirst(
            List<
                    SourceGroupPlacementDemand.PhysicalSourceDemand
                    > physicalSourceDemands
    ) {
        physicalSourceDemands.sort(
                (first, second) -> {
                    SourcePlacementProfile firstProfile =
                            first.getPlacementProfile();

                    SourcePlacementProfile secondProfile =
                            second.getPlacementProfile();

                    int areaComparison =
                            Integer.compare(
                                    secondProfile
                                            .reservationArea()
                                            .blockCount(),
                                    firstProfile
                                            .reservationArea()
                                            .blockCount()
                            );

                    if (areaComparison != 0) {
                        return areaComparison;
                    }

                    int capacityComparison =
                            Integer.compare(
                                    second
                                            .getRequiredSourceSize()
                                            .getCapacityUnits(),
                                    first
                                            .getRequiredSourceSize()
                                            .getCapacityUnits()
                            );

                    if (capacityComparison != 0) {
                        return capacityComparison;
                    }

                    int loadComparison =
                            Integer.compare(
                                    second.getSourceGroupLoadCost(),
                                    first.getSourceGroupLoadCost()
                            );

                    if (loadComparison != 0) {
                        return loadComparison;
                    }

                    return first
                            .getPhysicalSourceDemandId()
                            .compareTo(
                                    second
                                            .getPhysicalSourceDemandId()
                            );
                }
        );
    }

    // =========================================================
    // Source-site search inside one envelope
    // =========================================================

    /**
     * Searches preferred terrain first and preparable terrain second.
     *
     * Each pass first examines varied random positions and then uses a
     * deterministic circular fallback. The group anchor itself is considered
     * only as the final fallback, preventing a one-source group from
     * routinely placing its source directly underneath its marker.
     */
    private SourceSite findSourceSiteWithinEnvelope(
            ServerLevel level,
            BlockPos targetPos,
            SourceGroupPlacementDemand sourceGroupDemand,
            SourceGroupEnvelope sourceGroupEnvelope,
            SourceGroupPlacementDemand.PhysicalSourceDemand
                    physicalSourceDemand,
            List<
                    SourceGroupPlacementDraft
                            .PhysicalSourcePlacementDraft
                    > currentGroupPlacements,
            List<SourceGroupPlacementDraft> reservedGroupDrafts,
            ActiveReservationView activeReservationView,
            NetworkClearanceRule networkClearanceRule
    ) {
        SourceSite preferredSite =
                findSourceSitePass(
                        level,
                        targetPos,
                        sourceGroupDemand,
                        sourceGroupEnvelope,
                        physicalSourceDemand,
                        currentGroupPlacements,
                        reservedGroupDrafts,
                        activeReservationView,
                        networkClearanceRule,
                        true
                );

        if (preferredSite != null) {
            return preferredSite;
        }

        return findSourceSitePass(
                level,
                targetPos,
                sourceGroupDemand,
                sourceGroupEnvelope,
                physicalSourceDemand,
                currentGroupPlacements,
                reservedGroupDrafts,
                activeReservationView,
                networkClearanceRule,
                false
        );
    }

    private SourceSite findSourceSitePass(
            ServerLevel level,
            BlockPos targetPos,
            SourceGroupPlacementDemand sourceGroupDemand,
            SourceGroupEnvelope sourceGroupEnvelope,
            SourceGroupPlacementDemand.PhysicalSourceDemand
                    physicalSourceDemand,
            List<
                    SourceGroupPlacementDraft
                            .PhysicalSourcePlacementDraft
                    > currentGroupPlacements,
            List<SourceGroupPlacementDraft> reservedGroupDrafts,
            ActiveReservationView activeReservationView,
            NetworkClearanceRule networkClearanceRule,
            boolean preferredOnly
    ) {
        RandomSource random =
                level.getRandom();

        Set<BlockPos> testedHorizontalCandidates =
                new HashSet<>();

        /*
         * Random positions produce the loose, non-uniform layouts intended by
         * the adaptive envelope system.
         */
        for (int attempt = 0;
             attempt
                     < RANDOM_SOURCE_CANDIDATES_PER_PASS;
             attempt++) {

            BlockPos horizontalCandidate =
                    createRandomEnvelopeCandidate(
                            random,
                            sourceGroupEnvelope
                    );

            SourceSite sourceSite =
                    assessUniqueCandidate(
                            level,
                            horizontalCandidate,
                            targetPos,
                            sourceGroupDemand,
                            sourceGroupEnvelope,
                            physicalSourceDemand,
                            currentGroupPlacements,
                            reservedGroupDrafts,
                            activeReservationView,
                            networkClearanceRule,
                            preferredOnly,
                            testedHorizontalCandidates
                    );

            if (sourceSite != null) {
                return sourceSite;
            }
        }

        /*
         * Deterministic circular fallback ensures that an unlucky random
         * sequence does not incorrectly reject a viable envelope.
         */
        for (int radius = SOURCE_FALLBACK_RING_STEP;
             radius <= sourceGroupEnvelope.radius();
             radius += SOURCE_FALLBACK_RING_STEP) {

            for (int xOffset = -radius;
                 xOffset <= radius;
                 xOffset += SOURCE_FALLBACK_RING_STEP) {

                SourceSite northSite =
                        assessFallbackEnvelopeCandidate(
                                level,
                                targetPos,
                                sourceGroupDemand,
                                sourceGroupEnvelope,
                                physicalSourceDemand,
                                currentGroupPlacements,
                                reservedGroupDrafts,
                                activeReservationView,
                                networkClearanceRule,
                                preferredOnly,
                                testedHorizontalCandidates,
                                xOffset,
                                -radius
                        );

                if (northSite != null) {
                    return northSite;
                }

                SourceSite southSite =
                        assessFallbackEnvelopeCandidate(
                                level,
                                targetPos,
                                sourceGroupDemand,
                                sourceGroupEnvelope,
                                physicalSourceDemand,
                                currentGroupPlacements,
                                reservedGroupDrafts,
                                activeReservationView,
                                networkClearanceRule,
                                preferredOnly,
                                testedHorizontalCandidates,
                                xOffset,
                                radius
                        );

                if (southSite != null) {
                    return southSite;
                }
            }

            for (int zOffset =
                 -radius
                         + SOURCE_FALLBACK_RING_STEP;
                 zOffset
                         <= radius
                         - SOURCE_FALLBACK_RING_STEP;
                 zOffset += SOURCE_FALLBACK_RING_STEP) {

                SourceSite westSite =
                        assessFallbackEnvelopeCandidate(
                                level,
                                targetPos,
                                sourceGroupDemand,
                                sourceGroupEnvelope,
                                physicalSourceDemand,
                                currentGroupPlacements,
                                reservedGroupDrafts,
                                activeReservationView,
                                networkClearanceRule,
                                preferredOnly,
                                testedHorizontalCandidates,
                                -radius,
                                zOffset
                        );

                if (westSite != null) {
                    return westSite;
                }

                SourceSite eastSite =
                        assessFallbackEnvelopeCandidate(
                                level,
                                targetPos,
                                sourceGroupDemand,
                                sourceGroupEnvelope,
                                physicalSourceDemand,
                                currentGroupPlacements,
                                reservedGroupDrafts,
                                activeReservationView,
                                networkClearanceRule,
                                preferredOnly,
                                testedHorizontalCandidates,
                                radius,
                                zOffset
                        );

                if (eastSite != null) {
                    return eastSite;
                }
            }
        }

        /*
         * The conceptual centre remains legal, but it is deliberately the
         * final candidate rather than the normal first source position.
         */
        return assessUniqueCandidate(
                level,
                sourceGroupEnvelope.centre(),
                targetPos,
                sourceGroupDemand,
                sourceGroupEnvelope,
                physicalSourceDemand,
                currentGroupPlacements,
                reservedGroupDrafts,
                activeReservationView,
                networkClearanceRule,
                preferredOnly,
                testedHorizontalCandidates
        );
    }

    private BlockPos createRandomEnvelopeCandidate(
            RandomSource random,
            SourceGroupEnvelope sourceGroupEnvelope
    ) {
        double angle =
                random.nextDouble()
                        * Math.PI
                        * 2.0D;

        /*
         * Square-root distribution gives an approximately uniform spread over
         * the area of the envelope rather than clustering every candidate
         * near its centre.
         */
        double radialDistance =
                Math.sqrt(
                        random.nextDouble()
                ) * sourceGroupEnvelope.radius();

        radialDistance =
                Math.max(
                        1.0D,
                        radialDistance
                );

        int offsetX =
                (int) Math.round(
                        Math.cos(angle)
                                * radialDistance
                );

        int offsetZ =
                (int) Math.round(
                        Math.sin(angle)
                                * radialDistance
                );

        return sourceGroupEnvelope
                .centre()
                .offset(
                        offsetX,
                        0,
                        offsetZ
                )
                .immutable();
    }

    private SourceSite assessFallbackEnvelopeCandidate(
            ServerLevel level,
            BlockPos targetPos,
            SourceGroupPlacementDemand sourceGroupDemand,
            SourceGroupEnvelope sourceGroupEnvelope,
            SourceGroupPlacementDemand.PhysicalSourceDemand
                    physicalSourceDemand,
            List<
                    SourceGroupPlacementDraft
                            .PhysicalSourcePlacementDraft
                    > currentGroupPlacements,
            List<SourceGroupPlacementDraft> reservedGroupDrafts,
            ActiveReservationView activeReservationView,
            NetworkClearanceRule networkClearanceRule,
            boolean preferredOnly,
            Set<BlockPos> testedHorizontalCandidates,
            int offsetX,
            int offsetZ
    ) {
        BlockPos horizontalCandidate =
                sourceGroupEnvelope
                        .centre()
                        .offset(
                                offsetX,
                                0,
                                offsetZ
                        )
                        .immutable();

        /*
         * This preliminary point check keeps the deterministic search
         * circular. The later reservation-centre check remains authoritative,
         * particularly for asymmetric source profiles.
         */
        if (!sourceGroupEnvelope.containsSourceCentre(
                horizontalCandidate
        )) {
            return null;
        }

        return assessUniqueCandidate(
                level,
                horizontalCandidate,
                targetPos,
                sourceGroupDemand,
                sourceGroupEnvelope,
                physicalSourceDemand,
                currentGroupPlacements,
                reservedGroupDrafts,
                activeReservationView,
                networkClearanceRule,
                preferredOnly,
                testedHorizontalCandidates
        );
    }

    private SourceSite assessUniqueCandidate(
            ServerLevel level,
            BlockPos horizontalCandidate,
            BlockPos targetPos,
            SourceGroupPlacementDemand sourceGroupDemand,
            SourceGroupEnvelope sourceGroupEnvelope,
            SourceGroupPlacementDemand.PhysicalSourceDemand
                    physicalSourceDemand,
            List<
                    SourceGroupPlacementDraft
                            .PhysicalSourcePlacementDraft
                    > currentGroupPlacements,
            List<SourceGroupPlacementDraft> reservedGroupDrafts,
            ActiveReservationView activeReservationView,
            NetworkClearanceRule networkClearanceRule,
            boolean preferredOnly,
            Set<BlockPos> testedHorizontalCandidates
    ) {
        BlockPos immutableCandidate =
                horizontalCandidate.immutable();

        if (!testedHorizontalCandidates.add(
                immutableCandidate
        )) {
            return null;
        }

        return assessCandidate(
                level,
                immutableCandidate,
                targetPos,
                sourceGroupDemand,
                sourceGroupEnvelope,
                physicalSourceDemand,
                currentGroupPlacements,
                reservedGroupDrafts,
                activeReservationView,
                networkClearanceRule,
                preferredOnly
        );
    }

    // =========================================================
    // Candidate assessment
    // =========================================================

    private SourceSite assessCandidate(
            ServerLevel level,
            BlockPos horizontalCandidate,
            BlockPos targetPos,
            SourceGroupPlacementDemand sourceGroupDemand,
            SourceGroupEnvelope sourceGroupEnvelope,
            SourceGroupPlacementDemand.PhysicalSourceDemand
                    physicalSourceDemand,
            List<
                    SourceGroupPlacementDraft
                            .PhysicalSourcePlacementDraft
                    > currentGroupPlacements,
            List<SourceGroupPlacementDraft> reservedGroupDrafts,
            ActiveReservationView activeReservationView,
            NetworkClearanceRule networkClearanceRule,
            boolean preferredOnly
    ) {
        SourcePlacementProfile placementProfile =
                physicalSourceDemand
                        .getPlacementProfile();

        BlockPos origin =
                getSurfacePos(
                        level,
                        horizontalCandidate
                );

        Direction facing =
                chooseFacing(
                        origin,
                        targetPos
                );

        SourceReservationArea.WorldBounds reservationBounds =
                placementProfile
                        .reservationArea()
                        .resolve(
                                origin,
                                facing
                        );

        /*
         * Only the complete reservation's horizontal centre must remain
         * inside the circular envelope. Its outer blocks may protrude.
         */
        if (!sourceGroupEnvelope
                .containsReservationCentre(
                        reservationBounds
                )) {
            return null;
        }

        if (!isWithinBuildHeight(
                level,
                origin,
                placementProfile
        )) {
            return null;
        }

        if (!hasRequiredNetworkClearance(
                reservationBounds,
                sourceGroupDemand.getSourceRole(),
                networkClearanceRule
        )) {
            return null;
        }

        if (overlapsCurrentGroupPlacement(
                reservationBounds,
                currentGroupPlacements
        )) {
            return null;
        }

        if (violatesCrossGroupSourceBuffer(
                reservationBounds,
                sourceGroupDemand
                        .getSourceGroupSpatialRules(),
                reservedGroupDrafts,
                activeReservationView
        )) {
            return null;
        }

        int existingSourceBuffer =
                sourceGroupDemand
                        .getSourceGroupSpatialRules()
                        .crossGroupSourceBuffer();

        if (overlapsExistingTunnelSource(
                level,
                reservationBounds,
                origin.getY(),
                existingSourceBuffer
        )) {
            return null;
        }

        if (!canPrepareSite(
                level,
                origin,
                facing,
                placementProfile
        )) {
            return null;
        }

        SourceGroupPlacementDraft.SiteQuality siteQuality =
                isPreferredSite(
                        level,
                        origin,
                        facing,
                        placementProfile
                )
                        ? SourceGroupPlacementDraft
                          .SiteQuality
                          .PREFERRED
                        : SourceGroupPlacementDraft
                          .SiteQuality
                          .PREPARABLE;

        if (preferredOnly
                && siteQuality
                != SourceGroupPlacementDraft
                .SiteQuality
                .PREFERRED) {
            return null;
        }

        return new SourceSite(
                origin,
                facing,
                reservationBounds,
                siteQuality
        );
    }

    private boolean overlapsCurrentGroupPlacement(
            SourceReservationArea.WorldBounds candidateBounds,
            List<
                    SourceGroupPlacementDraft
                            .PhysicalSourcePlacementDraft
                    > currentGroupPlacements
    ) {
        for (SourceGroupPlacementDraft
                .PhysicalSourcePlacementDraft currentPlacement
                : currentGroupPlacements) {

            if (candidateBounds.overlaps(
                    currentPlacement
                            .reservationBounds()
            )) {
                return true;
            }
        }

        return false;
    }

    /**
     * Sources belonging to different groups require an additional authored
     * gap beyond normal non-overlap.
     *
     * This protects separation against:
     *
     * - other groups in the current planning attempt;
     * - groups belonging to already-active incursions;
     * - future sources reserved by those active incursions but not yet
     *   spawned.
     */
    private boolean violatesCrossGroupSourceBuffer(
            SourceReservationArea.WorldBounds candidateBounds,
            SourceGroupSpatialRules candidateRules,
            List<SourceGroupPlacementDraft> reservedGroupDrafts,
            ActiveReservationView activeReservationView
    ) {
        for (SourceGroupPlacementDraft reservedGroupDraft
                : reservedGroupDrafts) {

            int separationBuffer =
                    Math.max(
                            candidateRules
                                    .crossGroupSourceBuffer(),
                            reservedGroupDraft
                                    .getSourceGroupSpatialRules()
                                    .crossGroupSourceBuffer()
                    );

            for (SourceReservationArea.WorldBounds reservedBounds
                    : reservedGroupDraft
                    .getReservationBounds()) {

                if (overlapsWithBuffer(
                        candidateBounds,
                        reservedBounds,
                        separationBuffer
                )) {
                    return true;
                }
            }
        }

        for (ActiveIncursionSourceReservationRegistry
                .SourceGroupReservationSnapshot activeGroupReservation
                : activeReservationView
                .sourceGroupReservations()) {

            int separationBuffer =
                    Math.max(
                            candidateRules
                                    .crossGroupSourceBuffer(),
                            activeGroupReservation
                                    .getCrossGroupSourceBuffer()
                    );

            for (ActiveIncursionSourceReservationRegistry
                    .SourceReservationSnapshot activeSourceReservation
                    : activeGroupReservation
                    .sourceReservations()) {

                if (overlapsWithBuffer(
                        candidateBounds,
                        activeSourceReservation
                                .reservationBounds(),
                        separationBuffer
                )) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean overlapsWithBuffer(
            SourceReservationArea.WorldBounds firstBounds,
            SourceReservationArea.WorldBounds secondBounds,
            int buffer
    ) {
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

    private boolean isWithinBuildHeight(
            ServerLevel level,
            BlockPos origin,
            SourcePlacementProfile placementProfile
    ) {
        int minimumRequiredY =
                origin.getY()
                        - placementProfile.foundationDepth();

        int maximumRequiredYExclusive =
                origin.getY()
                        + placementProfile.clearanceHeight();

        return minimumRequiredY
                >= level.getMinBuildHeight()
                && maximumRequiredYExclusive
                <= level.getMaxBuildHeight();
    }

    /**
     * Ordinary incursion sources must remain outside the absolute exclusion
     * band surrounding the protected Warp Flux network.
     *
     * The complete reservation footprint is checked. Envelope adaptation,
     * additional groups and outward fallback may never weaken this rule.
     */
    private boolean hasRequiredNetworkClearance(
            SourceReservationArea.WorldBounds reservationBounds,
            SourceRole sourceRole,
            NetworkClearanceRule networkClearanceRule
    ) {
        if (!requiresHardNetworkClearance(
                sourceRole
        )) {
            return true;
        }

        return networkClearanceRule.allows(
                reservationBounds
        );
    }

    /**
     * Every currently implemented source role uses hard network clearance.
     *
     * A future explicitly authored internal-breach source must opt out here
     * deliberately.
     */
    private boolean requiresHardNetworkClearance(
            SourceRole sourceRole
    ) {
        if (sourceRole == null) {
            throw new IllegalArgumentException(
                    "Source role cannot be null."
            );
        }

        return true;
    }

    /**
     * Uses the immutable protected-network snapshot captured by
     * IncursionPlanningContext.
     *
     * Live network changes during planning therefore cannot move the
     * exclusion boundary between fronts or whole-plan retries.
     */
    private NetworkClearanceRule resolveNetworkClearanceRule(
            IncursionPlanningContext context
    ) {
        int minimumClearance =
                context
                        .frontDistanceProfile()
                        .getHardMinimumNetworkClearance();

        if (!context
                .hasProtectedNetworkGeometrySnapshot()) {
            return NetworkClearanceRule.unrestricted(
                    minimumClearance
            );
        }

        return NetworkClearanceRule.protectedNetwork(
                context.protectedNetworkGeometrySnapshot(),
                minimumClearance
        );
    }

    /**
     * Checks existing world tunnel blocks using the provisional normal tunnel
     * reservation and the cross-group separation buffer.
     *
     * This remains necessary for manually created or otherwise unmanaged
     * tunnel blocks that do not appear in the active-incursion registry.
     */
    private boolean overlapsExistingTunnelSource(
            ServerLevel level,
            SourceReservationArea.WorldBounds candidateBounds,
            int candidateY,
            int separationBuffer
    ) {
        int legacyHalfExtent =
                LEGACY_TUNNEL_RESERVATION.width()
                        / 2;

        int horizontalSearchExpansion =
                legacyHalfExtent
                        + separationBuffer;

        int minimumSearchY =
                Math.max(
                        level.getMinBuildHeight(),
                        candidateY
                                - EXISTING_SOURCE_VERTICAL_SEARCH
                );

        int maximumSearchY =
                Math.min(
                        level.getMaxBuildHeight() - 1,
                        candidateY
                                + EXISTING_SOURCE_VERTICAL_SEARCH
                );

        BlockPos.MutableBlockPos mutablePos =
                new BlockPos.MutableBlockPos();

        for (int x =
             candidateBounds.minX()
                     - horizontalSearchExpansion;
             x <= candidateBounds.maxX()
                     + horizontalSearchExpansion;
             x++) {

            for (int y = minimumSearchY;
                 y <= maximumSearchY;
                 y++) {

                for (int z =
                     candidateBounds.minZ()
                             - horizontalSearchExpansion;
                     z <= candidateBounds.maxZ()
                             + horizontalSearchExpansion;
                     z++) {

                    mutablePos.set(
                            x,
                            y,
                            z
                    );

                    if (!(level
                            .getBlockState(
                                    mutablePos
                            )
                            .getBlock()
                            instanceof SkavenTunnelSourceBlock)) {
                        continue;
                    }

                    SourceReservationArea.WorldBounds
                            existingSourceBounds =
                            LEGACY_TUNNEL_RESERVATION.resolve(
                                    mutablePos.immutable(),
                                    Direction.NORTH
                            );

                    if (overlapsWithBuffer(
                            candidateBounds,
                            existingSourceBounds,
                            separationBuffer
                    )) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    // =========================================================
    // Terrain preparation assessment
    // =========================================================

    /**
     * Returns whether runtime source preparation could safely claim this
     * position.
     *
     * Ordinary terrain, air and liquid may be replaced. Unbreakable blocks
     * and block entities may not be silently removed.
     */
    private boolean canPrepareSite(
            ServerLevel level,
            BlockPos origin,
            Direction facing,
            SourcePlacementProfile placementProfile
    ) {
        SourceReservationArea.WorldBounds preparationBounds =
                placementProfile
                        .preparationArea()
                        .resolve(
                                origin,
                                facing
                        );

        for (int x = preparationBounds.minX();
             x <= preparationBounds.maxX();
             x++) {

            for (int z = preparationBounds.minZ();
                 z <= preparationBounds.maxZ();
                 z++) {

                for (int depth = 1;
                     depth
                             <= placementProfile
                             .foundationDepth();
                     depth++) {

                    BlockPos foundationPos =
                            new BlockPos(
                                    x,
                                    origin.getY()
                                            - depth,
                                    z
                            );

                    if (!canReplaceDuringPreparation(
                            level,
                            foundationPos
                    )) {
                        return false;
                    }
                }

                for (int height = 0;
                     height
                             < placementProfile
                             .clearanceHeight();
                     height++) {

                    BlockPos clearancePos =
                            new BlockPos(
                                    x,
                                    origin.getY()
                                            + height,
                                    z
                            );

                    if (!canReplaceDuringPreparation(
                            level,
                            clearancePos
                    )) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private boolean canReplaceDuringPreparation(
            ServerLevel level,
            BlockPos pos
    ) {
        BlockState blockState =
                level.getBlockState(
                        pos
                );

        if (blockState.isAir()) {
            return true;
        }

        if (level.getBlockEntity(
                pos
        ) != null) {
            return false;
        }

        return blockState.getDestroySpeed(
                level,
                pos
        ) >= 0.0F;
    }

    /**
     * Preferred sites already provide the complete dry foundation and clear
     * emergence volume required by the placement profile.
     *
     * A site that fails this preference check may still be safely preparable.
     */
    private boolean isPreferredSite(
            ServerLevel level,
            BlockPos origin,
            Direction facing,
            SourcePlacementProfile placementProfile
    ) {
        SourceReservationArea.WorldBounds preparationBounds =
                placementProfile
                        .preparationArea()
                        .resolve(
                                origin,
                                facing
                        );

        for (int x = preparationBounds.minX();
             x <= preparationBounds.maxX();
             x++) {

            for (int z = preparationBounds.minZ();
                 z <= preparationBounds.maxZ();
                 z++) {

                for (int depth = 1;
                     depth
                             <= placementProfile
                             .foundationDepth();
                     depth++) {

                    BlockPos foundationPos =
                            new BlockPos(
                                    x,
                                    origin.getY()
                                            - depth,
                                    z
                            );

                    BlockState foundationState =
                            level.getBlockState(
                                    foundationPos
                            );

                    if (!foundationState.isSolidRender(
                            level,
                            foundationPos
                    )) {
                        return false;
                    }

                    if (!foundationState
                            .getFluidState()
                            .isEmpty()) {
                        return false;
                    }

                    if (foundationState.is(
                            BlockTags.LEAVES
                    )) {
                        return false;
                    }

                    if (foundationState.is(
                            BlockTags.LOGS
                    )) {
                        return false;
                    }
                }

                for (int height = 0;
                     height
                             < placementProfile
                             .clearanceHeight();
                     height++) {

                    BlockPos clearancePos =
                            new BlockPos(
                                    x,
                                    origin.getY()
                                            + height,
                                    z
                            );

                    BlockState clearanceState =
                            level.getBlockState(
                                    clearancePos
                            );

                    if (!clearanceState.isAir()) {
                        return false;
                    }

                    if (!clearanceState
                            .getFluidState()
                            .isEmpty()) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    // =========================================================
    // Draft commitment
    // =========================================================

    /**
     * Converts every successful temporary draft into final placement objects
     * before adding anything to FrontPlan.
     *
     * All final groups are built first so a construction failure cannot leave
     * a partially committed front.
     */
    private void commitFrontDrafts(
            FrontPlan frontPlan,
            List<SourceGroupPlacementDraft> frontDrafts
    ) {
        CompositionIndex compositionIndex =
                createCompositionIndex(
                        frontPlan
                );

        List<SourceGroupPlacementPlan>
                completedSourceGroupPlans =
                new ArrayList<>();

        for (SourceGroupPlacementDraft frontDraft
                : frontDrafts) {

            completedSourceGroupPlans.add(
                    buildSourceGroupPlacementPlan(
                            frontPlan,
                            frontDraft,
                            compositionIndex
                    )
            );
        }

        for (SourceGroupPlacementPlan completedPlan
                : completedSourceGroupPlans) {

            frontPlan.addSourceGroupPlacementPlan(
                    completedPlan
            );
        }
    }

    private SourceGroupPlacementPlan
    buildSourceGroupPlacementPlan(
            FrontPlan frontPlan,
            SourceGroupPlacementDraft sourceGroupDraft,
            CompositionIndex compositionIndex
    ) {
        SourceGroupPlacementDemand sourceGroupDemand =
                sourceGroupDraft.getSourceGroupDemand();

        List<
                SourceGroupPlacementDemand
                        .SourceGroupCompositionBinding
                > compositionBindings =
                sourceGroupDemand
                        .getSourceGroupCompositionBindings();

        if (compositionBindings.isEmpty()) {
            throw new IllegalStateException(
                    "Source-group demand "
                            + sourceGroupDemand
                            .getSourceGroupDemandId()
                            + " has no composition bindings."
            );
        }

        SourceGroupComposition initialGroupComposition =
                requireSourceGroupComposition(
                        compositionIndex,
                        compositionBindings
                                .get(0)
                                .sourceGroupCompositionId()
                );

        SourceGroupPlacementPlan sourceGroupPlacementPlan =
                new SourceGroupPlacementPlan(
                        frontPlan.getFrontId(),
                        initialGroupComposition,
                        sourceGroupDemand.getSourceRole(),
                        sourceGroupDemand
                                .getSourceGroupSpatialRules(),
                        sourceGroupDraft
                                .getSourceGroupEnvelope()
                );

        for (int bindingIndex = 1;
             bindingIndex < compositionBindings.size();
             bindingIndex++) {

            SourceGroupComposition boundComposition =
                    requireSourceGroupComposition(
                            compositionIndex,
                            compositionBindings
                                    .get(bindingIndex)
                                    .sourceGroupCompositionId()
                    );

            sourceGroupPlacementPlan
                    .bindSourceGroupComposition(
                            boundComposition
                    );
        }

        for (SourceGroupPlacementDraft
                .PhysicalSourcePlacementDraft physicalSourceDraft
                : sourceGroupDraft
                .getPhysicalSourcePlacements()) {

            SourceGroupPlacementDemand.PhysicalSourceDemand
                    physicalSourceDemand =
                    sourceGroupDemand
                            .getPhysicalSourceDemand(
                                    physicalSourceDraft
                                            .physicalSourceDemandId()
                            );

            if (physicalSourceDemand == null) {
                throw new IllegalStateException(
                        "Source-group draft refers to unknown physical "
                                + "source demand "
                                + physicalSourceDraft
                                .physicalSourceDemandId()
                                + "."
                );
            }

            List<UUID> sourceCompositionIds =
                    physicalSourceDraft
                            .sourceCompositionIds();

            if (sourceCompositionIds.isEmpty()) {
                throw new IllegalStateException(
                        "Physical source demand "
                                + physicalSourceDemand
                                .getPhysicalSourceDemandId()
                                + " has no source-composition bindings."
                );
            }

            SourceGroupComposition.SourceComposition
                    initialSourceComposition =
                    requireSourceComposition(
                            compositionIndex,
                            sourceCompositionIds.get(0)
                    );

            validateInitialSourceComposition(
                    physicalSourceDemand,
                    initialSourceComposition
            );

            SourcePlacementPlan sourcePlacementPlan =
                    sourceGroupPlacementPlan
                            .createSourcePlacementPlan(
                                    initialSourceComposition,
                                    physicalSourceDraft
                                            .placementProfile(),
                                    physicalSourceDraft.facing(),
                                    physicalSourceDraft.origin()
                            );

            sourcePlacementPlan.setPlacedPos(
                    physicalSourceDraft.origin()
            );

            for (int sourceBindingIndex = 1;
                 sourceBindingIndex
                         < sourceCompositionIds.size();
                 sourceBindingIndex++) {

                UUID sourceCompositionId =
                        sourceCompositionIds.get(
                                sourceBindingIndex
                        );

                /*
                 * Resolve the object even though SourcePlacementPlan stores
                 * only its ID. This confirms that every lifetime binding
                 * refers to an actual composition in this front.
                 */
                requireSourceComposition(
                        compositionIndex,
                        sourceCompositionId
                );

                sourcePlacementPlan
                        .bindSourceComposition(
                                sourceCompositionId
                        );
            }

            if (!sourceGroupPlacementPlan
                    .containsReservationCentre(
                            sourcePlacementPlan
                                    .getReservationBounds()
                    )) {
                throw new IllegalStateException(
                        "Committed source placement "
                                + sourcePlacementPlan
                                .getSourcePlacementId()
                                + " lies outside its source-group envelope."
                );
            }
        }

        if (sourceGroupPlacementPlan.getSourceCount()
                != sourceGroupDemand
                .getPhysicalSourceCount()) {
            throw new IllegalStateException(
                    "Committed source group contains "
                            + sourceGroupPlacementPlan
                            .getSourceCount()
                            + " physical sources, but lifetime demand "
                            + "requires "
                            + sourceGroupDemand
                            .getPhysicalSourceCount()
                            + "."
            );
        }

        if (sourceGroupPlacementPlan
                .getTotalSourceGroupLoad()
                != sourceGroupDemand
                .getTotalSourceGroupLoad()) {
            throw new IllegalStateException(
                    "Committed source-group load does not match its complete "
                            + "lifetime demand."
            );
        }

        return sourceGroupPlacementPlan;
    }

    private void validateInitialSourceComposition(
            SourceGroupPlacementDemand.PhysicalSourceDemand
                    physicalSourceDemand,
            SourceGroupComposition.SourceComposition
                    initialSourceComposition
    ) {
        if (initialSourceComposition.getRequiredSourceType()
                != physicalSourceDemand
                .getRequiredSourceType()) {
            throw new IllegalStateException(
                    "Initial source composition uses a different source type "
                            + "from its persistent physical-source demand."
            );
        }

        if (initialSourceComposition.getRequiredSourceSize()
                != physicalSourceDemand
                .getRequiredSourceSize()) {
            throw new IllegalStateException(
                    "Initial source composition uses a different source size "
                            + "from its persistent physical-source demand."
            );
        }

        if (initialSourceComposition.getSourceRole()
                != physicalSourceDemand.getSourceRole()) {
            throw new IllegalStateException(
                    "Initial source composition uses a different source role "
                            + "from its persistent physical-source demand."
            );
        }
    }

    private CompositionIndex createCompositionIndex(
            FrontPlan frontPlan
    ) {
        Map<UUID, SourceGroupComposition>
                sourceGroupCompositions =
                new HashMap<>();

        Map<
                UUID,
                SourceGroupComposition.SourceComposition
                > sourceCompositions =
                new HashMap<>();

        for (FrontPlan.WavePlan wavePlan
                : frontPlan.getWavePlans()) {

            for (SourceGroupComposition sourceGroupComposition
                    : wavePlan.getSourceGroupCompositions()) {

                SourceGroupComposition previousGroup =
                        sourceGroupCompositions.put(
                                sourceGroupComposition
                                        .getSourceGroupCompositionId(),
                                sourceGroupComposition
                        );

                if (previousGroup != null) {
                    throw new IllegalStateException(
                            "Front "
                                    + frontPlan.getFrontId()
                                    + " contains duplicate source-group "
                                    + "composition ID "
                                    + sourceGroupComposition
                                    .getSourceGroupCompositionId()
                                    + "."
                    );
                }

                for (SourceGroupComposition.SourceComposition
                        sourceComposition
                        : sourceGroupComposition
                        .getSourceCompositions()) {

                    SourceGroupComposition.SourceComposition
                            previousSource =
                            sourceCompositions.put(
                                    sourceComposition
                                            .getSourceCompositionId(),
                                    sourceComposition
                            );

                    if (previousSource != null) {
                        throw new IllegalStateException(
                                "Front "
                                        + frontPlan.getFrontId()
                                        + " contains duplicate source "
                                        + "composition ID "
                                        + sourceComposition
                                        .getSourceCompositionId()
                                        + "."
                        );
                    }
                }
            }
        }

        return new CompositionIndex(
                Map.copyOf(
                        sourceGroupCompositions
                ),
                Map.copyOf(
                        sourceCompositions
                )
        );
    }

    private SourceGroupComposition
    requireSourceGroupComposition(
            CompositionIndex compositionIndex,
            UUID sourceGroupCompositionId
    ) {
        SourceGroupComposition sourceGroupComposition =
                compositionIndex
                        .sourceGroupCompositions()
                        .get(
                                sourceGroupCompositionId
                        );

        if (sourceGroupComposition == null) {
            throw new IllegalStateException(
                    "Unknown source-group composition ID "
                            + sourceGroupCompositionId
                            + "."
            );
        }

        return sourceGroupComposition;
    }

    private SourceGroupComposition.SourceComposition
    requireSourceComposition(
            CompositionIndex compositionIndex,
            UUID sourceCompositionId
    ) {
        SourceGroupComposition.SourceComposition
                sourceComposition =
                compositionIndex
                        .sourceCompositions()
                        .get(
                                sourceCompositionId
                        );

        if (sourceComposition == null) {
            throw new IllegalStateException(
                    "Unknown source composition ID "
                            + sourceCompositionId
                            + "."
            );
        }

        return sourceComposition;
    }

    // =========================================================
    // Geometry helpers
    // =========================================================

    private BlockPos getSurfacePos(
            ServerLevel level,
            BlockPos pos
    ) {
        return level.getHeightmapPos(
                Heightmap.Types
                        .MOTION_BLOCKING_NO_LEAVES,
                pos
        ).immutable();
    }

    /**
     * Faces each source towards the incursion target.
     */
    private Direction chooseFacing(
            BlockPos sourcePos,
            BlockPos targetPos
    ) {
        int differenceX =
                targetPos.getX()
                        - sourcePos.getX();

        int differenceZ =
                targetPos.getZ()
                        - sourcePos.getZ();

        if (differenceX == 0
                && differenceZ == 0) {
            return Direction.NORTH;
        }

        if (Math.abs(
                differenceX
        ) >= Math.abs(
                differenceZ
        )) {
            return differenceX > 0
                    ? Direction.EAST
                    : Direction.WEST;
        }

        return differenceZ > 0
                ? Direction.SOUTH
                : Direction.NORTH;
    }

    private double horizontalDistance(
            BlockPos first,
            BlockPos second
    ) {
        return Math.sqrt(
                horizontalDistanceSquared(
                        first,
                        second
                )
        );
    }

    private double horizontalDistanceSquared(
            BlockPos first,
            BlockPos second
    ) {
        double differenceX =
                first.getX()
                        - second.getX();

        double differenceZ =
                first.getZ()
                        - second.getZ();

        return differenceX
                * differenceX
                + differenceZ
                * differenceZ;
    }

    private PlanningStepResult failure(
            String message
    ) {
        return PlanningStepResult.failure(
                IncursionPlanningResult
                        .PlanningStage
                        .SOURCE_PLACEMENT,
                IncursionPlanningResult
                        .PlanningFailureReason
                        .SOURCE_PLACEMENT_FAILED,
                message
        );
    }

    /**
     * Immutable view of reservations owned by incursions that were already
     * active when this planning pass began.
     */
    private record ActiveReservationView(
            List<ActiveIncursionSourceReservationRegistry
                    .SourceGroupReservationSnapshot>
            sourceGroupReservations
    ) {

        private ActiveReservationView {
            if (sourceGroupReservations == null) {
                throw new IllegalArgumentException(
                        "Active source-group reservation list cannot be null."
                );
            }

            sourceGroupReservations =
                    List.copyOf(
                            sourceGroupReservations
                    );
        }

        private static ActiveReservationView capture(
                ServerLevel level,
                UUID currentIncursionId
        ) {
            if (level == null) {
                throw new IllegalArgumentException(
                        "Active-reservation level cannot be null."
                );
            }

            if (currentIncursionId == null) {
                throw new IllegalArgumentException(
                        "Current incursion ID cannot be null."
                );
            }

            List<ActiveIncursionSourceReservationRegistry
                    .SourceGroupReservationSnapshot>
                    capturedReservations =
                    new ArrayList<>();

            for (ActiveIncursionSourceReservationRegistry
                    .SourceGroupReservationSnapshot reservationSnapshot
                    : ActiveIncursionSourceReservationRegistry
                    .getSourceGroupReservations(level)) {

                /*
                 * A repeated planning attempt using the same incursion ID must
                 * not collide with its own stale registry entry.
                 *
                 * Normal new incursions use unique IDs, but this keeps debug
                 * retries deterministic.
                 */
                if (currentIncursionId.equals(
                        reservationSnapshot.incursionId()
                )) {
                    continue;
                }

                capturedReservations.add(
                        reservationSnapshot
                );
            }

            return new ActiveReservationView(
                    capturedReservations
            );
        }

        private int getSourceGroupReservationCount() {
            return sourceGroupReservations.size();
        }

        private int getSourceReservationCount() {
            int sourceReservationCount =
                    0;

            for (ActiveIncursionSourceReservationRegistry
                    .SourceGroupReservationSnapshot sourceGroupReservation
                    : sourceGroupReservations) {

                sourceReservationCount +=
                        sourceGroupReservation
                                .getSourceCount();
            }

            return sourceReservationCount;
        }

        private boolean isEmpty() {
            return sourceGroupReservations.isEmpty();
        }
    }

    private record NetworkClearanceRule(
            WarpFluxNetworkGeometry networkGeometry,
            int minimumClearance
    ) {

        private NetworkClearanceRule {
            if (minimumClearance < 0) {
                throw new IllegalArgumentException(
                        "Minimum network clearance cannot be negative."
                );
            }
        }

        private static NetworkClearanceRule unrestricted(
                int minimumClearance
        ) {
            return new NetworkClearanceRule(
                    null,
                    minimumClearance
            );
        }

        private static NetworkClearanceRule protectedNetwork(
                WarpFluxNetworkGeometry networkGeometry,
                int minimumClearance
        ) {
            if (networkGeometry == null) {
                throw new IllegalArgumentException(
                        "Protected network geometry cannot be null."
                );
            }

            return new NetworkClearanceRule(
                    networkGeometry,
                    minimumClearance
            );
        }

        private boolean allows(
                SourceReservationArea.WorldBounds reservationBounds
        ) {
            if (reservationBounds == null) {
                throw new IllegalArgumentException(
                        "Reservation bounds cannot be null."
                );
            }

            return networkGeometry == null
                    || networkGeometry
                    .hasReservationClearance(
                            reservationBounds,
                            minimumClearance
                    );
        }
    }

    private record SourceSite(
            BlockPos origin,
            Direction facing,
            SourceReservationArea.WorldBounds reservationBounds,
            SourceGroupPlacementDraft.SiteQuality siteQuality
    ) {

        private SourceSite {
            if (origin == null) {
                throw new IllegalArgumentException(
                        "Source-site origin cannot be null."
                );
            }

            if (facing == null
                    || facing.getAxis().isVertical()) {
                throw new IllegalArgumentException(
                        "Source-site facing must be horizontal."
                );
            }

            if (reservationBounds == null) {
                throw new IllegalArgumentException(
                        "Source-site reservation bounds cannot be null."
                );
            }

            if (siteQuality == null) {
                throw new IllegalArgumentException(
                        "Source-site quality cannot be null."
                );
            }

            origin =
                    origin.immutable();
        }
    }

    private record CompositionIndex(
            Map<UUID, SourceGroupComposition>
            sourceGroupCompositions,
            Map<
                    UUID,
                    SourceGroupComposition.SourceComposition
                    > sourceCompositions
    ) {

        private CompositionIndex {
            if (sourceGroupCompositions == null) {
                throw new IllegalArgumentException(
                        "Source-group composition index cannot be null."
                );
            }

            if (sourceCompositions == null) {
                throw new IllegalArgumentException(
                        "Source composition index cannot be null."
                );
            }

            sourceGroupCompositions =
                    Map.copyOf(
                            sourceGroupCompositions
                    );

            sourceCompositions =
                    Map.copyOf(
                            sourceCompositions
                    );
        }
    }
}