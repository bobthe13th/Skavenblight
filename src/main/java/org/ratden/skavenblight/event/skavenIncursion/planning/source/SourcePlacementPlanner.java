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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Converts completed source compositions into physical source-placement
 * plans.
 *
 * This planner:
 *
 * - reuses compatible physical sources across later waves;
 * - resolves source placement profiles;
 * - chooses source facing;
 * - searches for suitable world coordinates;
 * - prevents reservation-area overlap;
 * - records placement results in the IncursionPlan.
 *
 * It does not place source blocks, prepare terrain, or spawn mobs.
 * Runtime execution performs those actions after the complete plan has been
 * validated.
 */
public class SourcePlacementPlanner {

    private static final int RANDOM_SEARCH_ATTEMPTS = 48;
    private static final int RANDOM_EXPANSION_INTERVAL = 8;

    private static final int FALLBACK_SEARCH_MAX_RADIUS = 64;
    private static final int FALLBACK_SEARCH_STEP = 2;

    private static final int EXISTING_SOURCE_VERTICAL_SEARCH = 6;

    /**
     * Existing legacy tunnel blocks do not yet expose their reservation
     * profile through a persistent runtime source record.
     *
     * Until that exists, treat every discovered legacy tunnel block as
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

        for (FrontPlan frontPlan : incursionPlan.getFrontPlans()) {
            if (frontPlan.hasSourceGroupPlacementPlans()) {
                return failure(
                        "Front "
                                + frontPlan.getFrontId()
                                + " already contains source-placement results."
                );
            }
        }

        List<SourcePlacementPlan> reservedSourcePlacements =
                new ArrayList<>();

        NetworkClearanceRule networkClearanceRule =
                resolveNetworkClearanceRule(context);

        for (FrontPlan frontPlan : incursionPlan.getFrontPlans()) {
            PlanningStepResult frontResult =
                    planFrontPlacements(
                            context,
                            frontPlan,
                            reservedSourcePlacements,
                            networkClearanceRule
                    );

            if (frontResult.hasFailed()) {
                return frontResult;
            }
        }

        return PlanningStepResult.success();
    }

    // =========================================================
    // Planning orchestration
    // =========================================================

    private PlanningStepResult planFrontPlacements(
            IncursionPlanningContext context,
            FrontPlan frontPlan,
            List<SourcePlacementPlan> reservedSourcePlacements,
            NetworkClearanceRule networkClearanceRule
    ) {
        if (!frontPlan.hasWavePlans()) {
            return failure(
                    "Front "
                            + frontPlan.getFrontId()
                            + " contains no wave plans."
            );
        }

        for (FrontPlan.WavePlan wavePlan
                : frontPlan.getWavePlans()) {
            for (SourceGroupComposition sourceGroupComposition
                    : wavePlan.getSourceGroupCompositions()) {

                PlanningStepResult groupResult =
                        planSourceGroupPlacement(
                                context,
                                frontPlan,
                                wavePlan,
                                sourceGroupComposition,
                                reservedSourcePlacements,
                                networkClearanceRule
                        );

                if (groupResult.hasFailed()) {
                    return groupResult;
                }
            }
        }

        return PlanningStepResult.success();
    }

    private PlanningStepResult planSourceGroupPlacement(
            IncursionPlanningContext context,
            FrontPlan frontPlan,
            FrontPlan.WavePlan wavePlan,
            SourceGroupComposition sourceGroupComposition,
            List<SourcePlacementPlan> reservedSourcePlacements,
            NetworkClearanceRule networkClearanceRule
    ) {
        if (sourceGroupComposition == null
                || sourceGroupComposition.isEmpty()) {
            return failure(
                    "Front "
                            + frontPlan.getFrontId()
                            + ", wave "
                            + wavePlan.getWaveIndex()
                            + " contains an empty source-group composition."
            );
        }

        SourceRole sourceGroupRole =
                resolveCommonSourceRole(
                        sourceGroupComposition
                );

        if (sourceGroupRole == null) {
            return failure(
                    "Source-group composition "
                            + sourceGroupComposition
                            .getSourceGroupCompositionId()
                            + " contains mixed source roles."
            );
        }

        SourceGroupPlacementPlan sourceGroupPlacementPlan =
                findReusableSourceGroup(
                        frontPlan,
                        sourceGroupRole
                );

        boolean newSourceGroup =
                sourceGroupPlacementPlan == null;

        if (!newSourceGroup) {
            sourceGroupPlacementPlan.bindSourceGroupComposition(
                    sourceGroupComposition
            );
        }

        Set<UUID> sourcePlacementsUsedThisComposition =
                new HashSet<>();

        for (SourceGroupComposition.SourceComposition
                sourceComposition
                : sourceGroupComposition.getSourceCompositions()) {

            SourcePlacementPlan reusableSource = null;

            if (sourceGroupPlacementPlan != null) {
                reusableSource =
                        findReusableSource(
                                sourceGroupPlacementPlan,
                                sourceComposition,
                                sourcePlacementsUsedThisComposition
                        );
            }

            if (reusableSource != null) {
                reusableSource.bindSourceComposition(
                        sourceComposition.getSourceCompositionId()
                );

                sourcePlacementsUsedThisComposition.add(
                        reusableSource.getSourcePlacementId()
                );

                continue;
            }

            SourcePlacementProfile placementProfile =
                    SourcePlacementProfileCatalogue.require(
                            sourceComposition
                                    .getRequiredSourceType(),
                            sourceComposition
                                    .getRequiredSourceSize()
                    );

            BlockPos searchAnchor =
                    sourceGroupPlacementPlan == null
                            ? frontPlan.getAnchorPos()
                            : sourceGroupPlacementPlan.getAnchorPos();

            SourceSite sourceSite =
                    findSourceSite(
                            context.level(),
                            searchAnchor,
                            context.targetPos(),
                            sourceGroupRole,
                            placementProfile,
                            reservedSourcePlacements,
                            networkClearanceRule
                    );

            if (sourceSite == null) {
                return failure(
                        "Could not find a viable site for source composition "
                                + sourceComposition
                                .getSourceCompositionId()
                                + " in front "
                                + frontPlan.getFrontId()
                                + ", wave "
                                + wavePlan.getWaveIndex()
                                + "."
                );
            }

            if (sourceGroupPlacementPlan == null) {
                sourceGroupPlacementPlan =
                        new SourceGroupPlacementPlan(
                                frontPlan.getFrontId(),
                                sourceGroupComposition,
                                sourceGroupRole,
                                sourceSite.origin()
                        );
            }

            SourcePlacementPlan sourcePlacementPlan =
                    sourceGroupPlacementPlan
                            .createSourcePlacementPlan(
                                    sourceComposition,
                                    placementProfile,
                                    sourceSite.facing(),
                                    sourceSite.origin()
                            );

            sourcePlacementPlan.setPlacedPos(
                    sourceSite.origin()
            );

            reservedSourcePlacements.add(
                    sourcePlacementPlan
            );

            sourcePlacementsUsedThisComposition.add(
                    sourcePlacementPlan.getSourcePlacementId()
            );
        }

        if (sourceGroupPlacementPlan == null
                || sourceGroupPlacementPlan.isEmpty()) {
            return failure(
                    "Source-group composition "
                            + sourceGroupComposition
                            .getSourceGroupCompositionId()
                            + " produced no physical source placements."
            );
        }

        if (newSourceGroup) {
            frontPlan.addSourceGroupPlacementPlan(
                    sourceGroupPlacementPlan
            );
        }

        return PlanningStepResult.success();
    }

    // =========================================================
    // Source-group and source reuse
    // =========================================================

    /**
     * Baseline behaviour uses one reusable physical source group for each
     * source role within a front.
     *
     * A later authored topology system may allow several distinct groups
     * with the same role.
     */
    private SourceGroupPlacementPlan findReusableSourceGroup(
            FrontPlan frontPlan,
            SourceRole sourceRole
    ) {
        for (SourceGroupPlacementPlan placementPlan
                : frontPlan.getSourceGroupPlacementPlans()) {
            if (placementPlan.getSourceRole()
                    == sourceRole) {
                return placementPlan;
            }
        }

        return null;
    }

    private SourcePlacementPlan findReusableSource(
            SourceGroupPlacementPlan sourceGroupPlacementPlan,
            SourceGroupComposition.SourceComposition sourceComposition,
            Set<UUID> sourcePlacementsUsedThisComposition
    ) {
        SourcePlacementPlan bestMatch = null;

        for (SourcePlacementPlan sourcePlacementPlan
                : sourceGroupPlacementPlan
                .getSourcePlacementPlans()) {

            if (sourcePlacementsUsedThisComposition.contains(
                    sourcePlacementPlan.getSourcePlacementId()
            )) {
                continue;
            }

            if (!isSourceCompatible(
                    sourcePlacementPlan,
                    sourceComposition
            )) {
                continue;
            }

            if (bestMatch == null
                    || sourcePlacementPlan.getCapacityUnits()
                    < bestMatch.getCapacityUnits()) {
                bestMatch = sourcePlacementPlan;
            }
        }

        return bestMatch;
    }

    private boolean isSourceCompatible(
            SourcePlacementPlan sourcePlacementPlan,
            SourceGroupComposition.SourceComposition sourceComposition
    ) {
        if (sourcePlacementPlan.getSourceType()
                != sourceComposition.getRequiredSourceType()) {
            return false;
        }

        if (sourcePlacementPlan.getSourceRole()
                != sourceComposition.getSourceRole()) {
            return false;
        }

        return sourcePlacementPlan
                .getSourceSize()
                .canFit(
                        sourceComposition.getRequiredSourceSize()
                );
    }

    private SourceRole resolveCommonSourceRole(
            SourceGroupComposition sourceGroupComposition
    ) {
        SourceRole commonRole = null;

        for (SourceGroupComposition.SourceComposition
                sourceComposition
                : sourceGroupComposition.getSourceCompositions()) {

            if (commonRole == null) {
                commonRole =
                        sourceComposition.getSourceRole();

                continue;
            }

            if (commonRole
                    != sourceComposition.getSourceRole()) {
                return null;
            }
        }

        return commonRole;
    }

    // =========================================================
    // Site search
    // =========================================================

    /**
     * Searches preferred natural terrain first.
     *
     * When no preferred site is found, it accepts a preparable site whose
     * reservation and preparation volume can be safely claimed by the source.
     *
     * A preparable site may include ordinary terrain, air, or liquid. Runtime
     * source creation is responsible for constructing the authored foundation
     * and clearing the emergence volume.
     */
    private SourceSite findSourceSite(
            ServerLevel level,
            BlockPos searchAnchor,
            BlockPos targetPos,
            SourceRole sourceRole,
            SourcePlacementProfile placementProfile,
            List<SourcePlacementPlan> reservedSourcePlacements,
            NetworkClearanceRule networkClearanceRule
    ) {
        BlockPos surfaceAnchor =
                getSurfacePos(
                        level,
                        searchAnchor
                );

        SourceSite directSite =
                assessCandidate(
                        level,
                        surfaceAnchor,
                        targetPos,
                        sourceRole,
                        placementProfile,
                        reservedSourcePlacements,
                        networkClearanceRule
                );

        if (directSite != null
                && directSite.quality()
                == SiteQuality.PREFERRED) {
            return directSite;
        }

        SourceSite randomPreferredSite =
                findRandomSite(
                        level,
                        surfaceAnchor,
                        targetPos,
                        sourceRole,
                        placementProfile,
                        reservedSourcePlacements,
                        networkClearanceRule,
                        true
                );

        if (randomPreferredSite != null) {
            return randomPreferredSite;
        }

        if (directSite != null) {
            return directSite;
        }

        SourceSite randomPreparableSite =
                findRandomSite(
                        level,
                        surfaceAnchor,
                        targetPos,
                        sourceRole,
                        placementProfile,
                        reservedSourcePlacements,
                        networkClearanceRule,
                        false
                );

        if (randomPreparableSite != null) {
            return randomPreparableSite;
        }

        SourceSite fallbackPreferredSite =
                findFallbackSite(
                        level,
                        surfaceAnchor,
                        targetPos,
                        sourceRole,
                        placementProfile,
                        reservedSourcePlacements,
                        networkClearanceRule,
                        true
                );

        if (fallbackPreferredSite != null) {
            return fallbackPreferredSite;
        }

        return findFallbackSite(
                level,
                surfaceAnchor,
                targetPos,
                sourceRole,
                placementProfile,
                reservedSourcePlacements,
                networkClearanceRule,
                false
        );
    }

    private SourceSite findRandomSite(
            ServerLevel level,
            BlockPos searchAnchor,
            BlockPos targetPos,
            SourceRole sourceRole,
            SourcePlacementProfile placementProfile,
            List<SourcePlacementPlan> reservedSourcePlacements,
            NetworkClearanceRule networkClearanceRule,
            boolean preferredOnly
    ) {
        RandomSource random =
                level.getRandom();

        int footprintScale =
                Math.max(
                        placementProfile
                                .reservationArea()
                                .width(),
                        placementProfile
                                .reservationArea()
                                .depth()
                );

        for (int attempt = 0;
             attempt < RANDOM_SEARCH_ATTEMPTS;
             attempt++) {

            int expansion =
                    attempt / RANDOM_EXPANSION_INTERVAL;

            int searchRadius =
                    footprintScale
                            + expansion * footprintScale;

            int offsetX =
                    random.nextInt(
                            searchRadius * 2 + 1
                    ) - searchRadius;

            int offsetZ =
                    random.nextInt(
                            searchRadius * 2 + 1
                    ) - searchRadius;

            BlockPos candidate =
                    searchAnchor.offset(
                            offsetX,
                            0,
                            offsetZ
                    );

            SourceSite sourceSite =
                    assessCandidate(
                            level,
                            candidate,
                            targetPos,
                            sourceRole,
                            placementProfile,
                            reservedSourcePlacements,
                            networkClearanceRule
                    );

            if (sourceSite == null) {
                continue;
            }

            if (preferredOnly
                    && sourceSite.quality()
                    != SiteQuality.PREFERRED) {
                continue;
            }

            return sourceSite;
        }

        return null;
    }

    /**
     * Deterministic expanding-ring fallback used after the random search.
     *
     * Every returned candidate is still fully validated. Failure returns null
     * rather than forcing an unsafe coordinate.
     */
    private SourceSite findFallbackSite(
            ServerLevel level,
            BlockPos searchAnchor,
            BlockPos targetPos,
            SourceRole sourceRole,
            SourcePlacementProfile placementProfile,
            List<SourcePlacementPlan> reservedSourcePlacements,
            NetworkClearanceRule networkClearanceRule,
            boolean preferredOnly
    ) {
        for (int radius = FALLBACK_SEARCH_STEP;
             radius <= FALLBACK_SEARCH_MAX_RADIUS;
             radius += FALLBACK_SEARCH_STEP) {

            for (int x = -radius;
                 x <= radius;
                 x += FALLBACK_SEARCH_STEP) {

                SourceSite northSite =
                        assessFallbackCandidate(
                                level,
                                searchAnchor.offset(
                                        x,
                                        0,
                                        -radius
                                ),
                                targetPos,
                                sourceRole,
                                placementProfile,
                                reservedSourcePlacements,
                                networkClearanceRule,
                                preferredOnly
                        );

                if (northSite != null) {
                    return northSite;
                }

                SourceSite southSite =
                        assessFallbackCandidate(
                                level,
                                searchAnchor.offset(
                                        x,
                                        0,
                                        radius
                                ),
                                targetPos,
                                sourceRole,
                                placementProfile,
                                reservedSourcePlacements,
                                networkClearanceRule,
                                preferredOnly
                        );

                if (southSite != null) {
                    return southSite;
                }
            }

            for (int z = -radius
                    + FALLBACK_SEARCH_STEP;
                 z <= radius
                         - FALLBACK_SEARCH_STEP;
                 z += FALLBACK_SEARCH_STEP) {

                SourceSite westSite =
                        assessFallbackCandidate(
                                level,
                                searchAnchor.offset(
                                        -radius,
                                        0,
                                        z
                                ),
                                targetPos,
                                sourceRole,
                                placementProfile,
                                reservedSourcePlacements,
                                networkClearanceRule,
                                preferredOnly
                        );

                if (westSite != null) {
                    return westSite;
                }

                SourceSite eastSite =
                        assessFallbackCandidate(
                                level,
                                searchAnchor.offset(
                                        radius,
                                        0,
                                        z
                                ),
                                targetPos,
                                sourceRole,
                                placementProfile,
                                reservedSourcePlacements,
                                networkClearanceRule,
                                preferredOnly
                        );

                if (eastSite != null) {
                    return eastSite;
                }
            }
        }

        return null;
    }

    private SourceSite assessFallbackCandidate(
            ServerLevel level,
            BlockPos candidate,
            BlockPos targetPos,
            SourceRole sourceRole,
            SourcePlacementProfile placementProfile,
            List<SourcePlacementPlan> reservedSourcePlacements,
            NetworkClearanceRule networkClearanceRule,
            boolean preferredOnly
    ) {
        SourceSite sourceSite =
                assessCandidate(
                        level,
                        candidate,
                        targetPos,
                        sourceRole,
                        placementProfile,
                        reservedSourcePlacements,
                        networkClearanceRule
                );

        if (sourceSite == null) {
            return null;
        }

        if (preferredOnly
                && sourceSite.quality()
                != SiteQuality.PREFERRED) {
            return null;
        }

        return sourceSite;
    }

    // =========================================================
    // Candidate assessment
    // =========================================================

    private SourceSite assessCandidate(
            ServerLevel level,
            BlockPos horizontalCandidate,
            BlockPos targetPos,
            SourceRole sourceRole,
            SourcePlacementProfile placementProfile,
            List<SourcePlacementPlan> reservedSourcePlacements,
            NetworkClearanceRule networkClearanceRule
    ) {
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

        if (!isWithinBuildHeight(
                level,
                origin,
                placementProfile
        )) {
            return null;
        }

        if (!hasRequiredNetworkClearance(
                reservationBounds,
                sourceRole,
                networkClearanceRule
        )) {
            return null;
        }

        if (overlapsReservedPlacement(
                reservationBounds,
                reservedSourcePlacements
        )) {
            return null;
        }

        if (overlapsExistingTunnelSource(
                level,
                reservationBounds,
                origin.getY()
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

        SiteQuality siteQuality =
                isPreferredSite(
                        level,
                        origin,
                        facing,
                        placementProfile
                )
                        ? SiteQuality.PREFERRED
                        : SiteQuality.PREPARABLE;

        return new SourceSite(
                origin,
                facing,
                siteQuality
        );
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
     * Ordinary incursion sources must remain outside the hard exclusion band
     * surrounding the active Nexus-backed Warp Flux network.
     *
     * The complete reservation footprint is checked, not only the source
     * block at its centre. This rule is independent of the current incursion
     * target: a player-targeted source still cannot appear inside the
     * protected base network.
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
     * All currently implemented source roles use the hard network exclusion
     * rule. A future authored internal-breach source must opt out here
     * explicitly rather than bypassing the boundary accidentally.
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
     * Creates the hard-clearance rule from the immutable network snapshot
     * captured when the planning context was created.
     *
     * This applies even when the incursion targets a player. Every planning
     * stage and whole-plan retry therefore measures source reservations against
     * the same protected base shape.
     */
    private NetworkClearanceRule resolveNetworkClearanceRule(
            IncursionPlanningContext context
    ) {
        int minimumClearance =
                context
                        .frontDistanceProfile()
                        .getHardMinimumNetworkClearance();

        WarpFluxNetworkGeometry networkGeometry =
                context.protectedNetworkGeometrySnapshot();

        if (networkGeometry == null) {
            return NetworkClearanceRule.unrestricted(
                    minimumClearance
            );
        }

        return NetworkClearanceRule.protectedNetwork(
                networkGeometry,
                minimumClearance
        );
    }

    private boolean overlapsReservedPlacement(
            SourceReservationArea.WorldBounds candidateBounds,
            List<SourcePlacementPlan> reservedSourcePlacements
    ) {
        for (SourcePlacementPlan reservedPlacement
                : reservedSourcePlacements) {
            if (candidateBounds.overlaps(
                    reservedPlacement.getReservationBounds()
            )) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks nearby legacy tunnel blocks using a provisional 5x5 reservation
     * rather than comparing only source-block centres.
     */
    private boolean overlapsExistingTunnelSource(
            ServerLevel level,
            SourceReservationArea.WorldBounds candidateBounds,
            int candidateY
    ) {
        int legacyHalfExtent =
                LEGACY_TUNNEL_RESERVATION.width() / 2;

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

        for (int x = candidateBounds.minX()
                - legacyHalfExtent;
             x <= candidateBounds.maxX()
                     + legacyHalfExtent;
             x++) {

            for (int y = minimumSearchY;
                 y <= maximumSearchY;
                 y++) {

                for (int z = candidateBounds.minZ()
                        - legacyHalfExtent;
                     z <= candidateBounds.maxZ()
                             + legacyHalfExtent;
                     z++) {

                    mutablePos.set(
                            x,
                            y,
                            z
                    );

                    if (!(level
                            .getBlockState(mutablePos)
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

                    if (candidateBounds.overlaps(
                            existingSourceBounds
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
     * Returns whether the source's authored preparation operation could
     * claim this site.
     *
     * Ordinary terrain, air and liquid are permitted. Unbreakable blocks and
     * block entities are not silently removed by source preparation.
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
                     depth <= placementProfile.foundationDepth();
                     depth++) {

                    BlockPos foundationPos =
                            new BlockPos(
                                    x,
                                    origin.getY() - depth,
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
                     height < placementProfile.clearanceHeight();
                     height++) {

                    BlockPos clearancePos =
                            new BlockPos(
                                    x,
                                    origin.getY() + height,
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
                level.getBlockState(pos);

        if (blockState.isAir()) {
            return true;
        }

        if (level.getBlockEntity(pos) != null) {
            return false;
        }

        return blockState.getDestroySpeed(
                level,
                pos
        ) >= 0.0F;
    }

    /**
     * Preferred sites already provide the complete dry foundation and clear
     * emergence volume required by the profile.
     *
     * Sites that fail this preference test may still be preparable.
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
                     depth <= placementProfile.foundationDepth();
                     depth++) {

                    BlockPos foundationPos =
                            new BlockPos(
                                    x,
                                    origin.getY() - depth,
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
                     height < placementProfile.clearanceHeight();
                     height++) {

                    BlockPos clearancePos =
                            new BlockPos(
                                    x,
                                    origin.getY() + height,
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
    // Geometry helpers
    // =========================================================

    private BlockPos getSurfacePos(
            ServerLevel level,
            BlockPos pos
    ) {
        return level.getHeightmapPos(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                pos
        ).immutable();
    }

    /**
     * Faces the source toward the incursion target.
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

        if (Math.abs(differenceX)
                >= Math.abs(differenceZ)) {
            return differenceX > 0
                    ? Direction.EAST
                    : Direction.WEST;
        }

        return differenceZ > 0
                ? Direction.SOUTH
                : Direction.NORTH;
    }

    private PlanningStepResult failure(
            String message
    ) {
        return PlanningStepResult.failure(
                IncursionPlanningResult.PlanningStage
                        .SOURCE_PLACEMENT,
                IncursionPlanningResult.PlanningFailureReason
                        .SOURCE_PLACEMENT_FAILED,
                message
        );
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
                    || networkGeometry.hasReservationClearance(
                    reservationBounds,
                    minimumClearance
            );
        }
    }

    private enum SiteQuality {
        PREFERRED,
        PREPARABLE
    }

    private record SourceSite(
            BlockPos origin,
            Direction facing,
            SiteQuality quality
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

            if (quality == null) {
                throw new IllegalArgumentException(
                        "Source-site quality cannot be null."
                );
            }

            origin = origin.immutable();
        }
    }
}