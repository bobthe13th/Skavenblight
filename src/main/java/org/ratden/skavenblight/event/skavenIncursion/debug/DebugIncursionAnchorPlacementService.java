package org.ratden.skavenblight.event.skavenIncursion.debug;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.ratden.skavenblight.block.ModBlocks;
import org.ratden.skavenblight.block.custom.debug.DebugIncursionAnchorBlock;
import org.ratden.skavenblight.block.entity.debug.DebugAnchorSourceLink;
import org.ratden.skavenblight.block.entity.debug.DebugIncursionAnchorEntity;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.front.FrontPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceGroupPlacementPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourcePlacementPlan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Creates and removes the invisible world markers used by incursion debug
 * visualisation.
 *
 * The service converts the structural hierarchy of a completed IncursionPlan
 * into:
 *
 * - one front-anchor marker for each front;
 * - one additional source-group marker for every physical source group after
 *   the first group in that front.
 *
 * The front anchor represents the front's first physical source group.
 * It links directly to that group's sources.
 *
 * Every additional source-group marker links directly to the sources in its
 * own group.
 *
 * No front-to-source-group-marker links are created.
 *
 * These blocks are debug representations only. They do not own the
 * IncursionPlan, physical source groups, source placements or runtime state.
 */
public final class DebugIncursionAnchorPlacementService {

    /**
     * The agreed visualisation palette supports six front indexes:
     *
     * 0 red
     * 1 orange
     * 2 yellow
     * 3 green
     * 4 blue
     * 5 purple
     */
    private static final int MAXIMUM_VISUALISED_FRONTS =
            6;

    /**
     * Places the marker above the complete emergence-clearance height of the
     * highest source in its represented group.
     */
    private static final int MARKER_CLEARANCE_ABOVE_SOURCES =
            2;

    /**
     * Number of additional vertical blocks checked when the preferred marker
     * position is occupied.
     */
    private static final int MARKER_VERTICAL_SEARCH =
            12;

    /**
     * Horizontal fallback radius around the represented anchor column.
     *
     * Marker placement never replaces terrain. When the exact elevated anchor
     * column is occupied, nearby air positions are considered.
     */
    private static final int MARKER_HORIZONTAL_SEARCH_RADIUS =
            4;

    /**
     * Creates all debug anchor markers for one completed IncursionPlan.
     *
     * A partial placement is rolled back if any marker cannot be created.
     */
    public static PlacementResult placeAnchors(
            ServerLevel level,
            IncursionPlan incursionPlan
    ) {
        if (level == null) {
            throw new IllegalArgumentException(
                    "Debug anchor level cannot be null."
            );
        }

        if (incursionPlan == null) {
            throw new IllegalArgumentException(
                    "Debug anchor incursion plan cannot be null."
            );
        }

        if (!incursionPlan.hasFrontPlans()) {
            return PlacementResult.failure(
                    incursionPlan.getIncursionId(),
                    "Incursion plan contains no fronts."
            );
        }

        if (incursionPlan.getFrontCount()
                > MAXIMUM_VISUALISED_FRONTS) {
            return PlacementResult.failure(
                    incursionPlan.getIncursionId(),
                    "Debug source visualisation supports at most "
                            + MAXIMUM_VISUALISED_FRONTS
                            + " fronts, but incursion "
                            + incursionPlan.getIncursionId()
                            + " contains "
                            + incursionPlan.getFrontCount()
                            + "."
            );
        }

        List<BlockPos> placedAnchorPositions =
                new ArrayList<>();

        Set<BlockPos> reservedMarkerPositions =
                new HashSet<>();

        for (FrontPlan frontPlan
                : incursionPlan.getFrontPlans()) {

            AnchorPlacementAttempt frontResult =
                    placeFrontAnchors(
                            level,
                            incursionPlan,
                            frontPlan,
                            placedAnchorPositions,
                            reservedMarkerPositions
                    );

            if (!frontResult.successful()) {
                rollbackPlacedAnchors(
                        level,
                        incursionPlan.getIncursionId(),
                        placedAnchorPositions
                );

                return PlacementResult.failure(
                        incursionPlan.getIncursionId(),
                        frontResult.failureMessage()
                );
            }
        }

        if (placedAnchorPositions.isEmpty()) {
            return PlacementResult.failure(
                    incursionPlan.getIncursionId(),
                    "Incursion plan produced no debug anchor markers."
            );
        }

        return PlacementResult.success(
                incursionPlan.getIncursionId(),
                placedAnchorPositions
        );
    }

    /**
     * Removes markers previously created for one placement result.
     *
     * A block is removed only when it is still a debug anchor and its block
     * entity belongs to the expected incursion.
     */
    public static int removeAnchors(
            ServerLevel level,
            PlacementResult placementResult
    ) {
        if (level == null) {
            throw new IllegalArgumentException(
                    "Debug anchor level cannot be null."
            );
        }

        if (placementResult == null) {
            throw new IllegalArgumentException(
                    "Debug anchor placement result cannot be null."
            );
        }

        if (!placementResult.successful()) {
            return 0;
        }

        return removeAnchorPositions(
                level,
                placementResult.incursionId(),
                placementResult.anchorPositions()
        );
    }

    private static AnchorPlacementAttempt placeFrontAnchors(
            ServerLevel level,
            IncursionPlan incursionPlan,
            FrontPlan frontPlan,
            List<BlockPos> placedAnchorPositions,
            Set<BlockPos> reservedMarkerPositions
    ) {
        if (frontPlan == null) {
            return AnchorPlacementAttempt.failure(
                    "Incursion plan contains a null front."
            );
        }

        if (frontPlan.getFrontIndex() < 0
                || frontPlan.getFrontIndex()
                >= MAXIMUM_VISUALISED_FRONTS) {
            return AnchorPlacementAttempt.failure(
                    "Front "
                            + frontPlan.getFrontId()
                            + " has unsupported front index "
                            + frontPlan.getFrontIndex()
                            + "."
            );
        }

        List<SourceGroupPlacementPlan>
                sourceGroupPlacementPlans =
                frontPlan.getSourceGroupPlacementPlans();

        if (sourceGroupPlacementPlans.isEmpty()) {
            return AnchorPlacementAttempt.failure(
                    "Front "
                            + frontPlan.getFrontId()
                            + " contains no physical source groups."
            );
        }

        for (int sourceGroupIndex = 0;
             sourceGroupIndex
                     < sourceGroupPlacementPlans.size();
             sourceGroupIndex++) {

            SourceGroupPlacementPlan
                    sourceGroupPlacementPlan =
                    sourceGroupPlacementPlans.get(
                            sourceGroupIndex
                    );

            DebugIncursionAnchorBlock anchorBlock =
                    sourceGroupIndex == 0
                            ? ModBlocks.DEBUG_FRONT_ANCHOR.get()
                            : ModBlocks
                            .DEBUG_SOURCE_GROUP_ANCHOR
                            .get();

            /*
             * The front marker represents the first physical group but is
             * positioned around the FrontPlan anchor.
             *
             * Additional group markers are positioned around their own
             * SourceGroupPlacementPlan anchors.
             */
            BlockPos representedAnchorPos =
                    sourceGroupIndex == 0
                            ? frontPlan.getAnchorPos()
                            : sourceGroupPlacementPlan
                            .getAnchorPos();

            AnchorPlacementAttempt placementAttempt =
                    placeSingleAnchor(
                            level,
                            incursionPlan,
                            frontPlan,
                            sourceGroupPlacementPlan,
                            sourceGroupIndex,
                            representedAnchorPos,
                            anchorBlock,
                            placedAnchorPositions,
                            reservedMarkerPositions
                    );

            if (!placementAttempt.successful()) {
                return placementAttempt;
            }
        }

        return AnchorPlacementAttempt.success();
    }

    private static AnchorPlacementAttempt placeSingleAnchor(
            ServerLevel level,
            IncursionPlan incursionPlan,
            FrontPlan frontPlan,
            SourceGroupPlacementPlan sourceGroupPlacementPlan,
            int sourceGroupIndex,
            BlockPos representedAnchorPos,
            DebugIncursionAnchorBlock anchorBlock,
            List<BlockPos> placedAnchorPositions,
            Set<BlockPos> reservedMarkerPositions
    ) {
        if (sourceGroupPlacementPlan == null) {
            return AnchorPlacementAttempt.failure(
                    "Front "
                            + frontPlan.getFrontId()
                            + " contains a null physical source group."
            );
        }

        if (!frontPlan.getFrontId().equals(
                sourceGroupPlacementPlan.getFrontId()
        )) {
            return AnchorPlacementAttempt.failure(
                    "Physical source group "
                            + sourceGroupPlacementPlan
                            .getSourceGroupPlacementId()
                            + " belongs to a different front."
            );
        }

        if (sourceGroupPlacementPlan.isEmpty()) {
            return AnchorPlacementAttempt.failure(
                    "Physical source group "
                            + sourceGroupPlacementPlan
                            .getSourceGroupPlacementId()
                            + " contains no physical sources."
            );
        }

        List<DebugAnchorSourceLink> sourceLinks =
                createSourceLinks(
                        sourceGroupPlacementPlan
                );

        if (sourceLinks.isEmpty()) {
            return AnchorPlacementAttempt.failure(
                    "Physical source group "
                            + sourceGroupPlacementPlan
                            .getSourceGroupPlacementId()
                            + " contains no final source positions."
            );
        }

        BlockPos markerPos =
                findMarkerPosition(
                        level,
                        representedAnchorPos,
                        sourceGroupPlacementPlan,
                        reservedMarkerPositions
                );

        if (markerPos == null) {
            return AnchorPlacementAttempt.failure(
                    "Could not find a safe debug-marker position for "
                            + "physical source group "
                            + sourceGroupPlacementPlan
                            .getSourceGroupPlacementId()
                            + " in front "
                            + frontPlan.getFrontId()
                            + "."
            );
        }

        BlockState markerState =
                anchorBlock.defaultBlockState();

        boolean blockPlaced =
                level.setBlock(
                        markerPos,
                        markerState,
                        Block.UPDATE_ALL
                );

        if (!blockPlaced) {
            return AnchorPlacementAttempt.failure(
                    "Minecraft rejected debug-anchor placement at "
                            + markerPos
                            + "."
            );
        }

        if (!(level.getBlockEntity(markerPos)
                instanceof DebugIncursionAnchorEntity anchorEntity)) {

            level.removeBlock(
                    markerPos,
                    false
            );

            return AnchorPlacementAttempt.failure(
                    "Debug anchor at "
                            + markerPos
                            + " did not create its expected block entity."
            );
        }

        try {
            anchorEntity.initialise(
                    incursionPlan.getIncursionId(),
                    frontPlan.getFrontId(),
                    frontPlan.getFrontIndex(),
                    frontPlan.getPlacementPattern(),
                    frontPlan.getThreatShare(),
                    frontPlan.getComplexityShare(),
                    frontPlan.isDominant(),
                    sourceGroupPlacementPlan
                            .getSourceGroupPlacementId(),
                    sourceGroupIndex,
                    sourceGroupPlacementPlan.getSourceRole(),
                    sourceGroupPlacementPlan
                            .getTotalSourceGroupLoad(),
                    sourceGroupPlacementPlan
                            .getMaximumSourceGroupLoad(),
                    sourceLinks
            );
        } catch (IllegalArgumentException exception) {
            level.removeBlock(
                    markerPos,
                    false
            );

            return AnchorPlacementAttempt.failure(
                    "Could not initialise debug anchor for physical group "
                            + sourceGroupPlacementPlan
                            .getSourceGroupPlacementId()
                            + ": "
                            + exception.getMessage()
            );
        }

        placedAnchorPositions.add(
                markerPos
        );

        reservedMarkerPositions.add(
                markerPos
        );

        return AnchorPlacementAttempt.success();
    }

    private static List<DebugAnchorSourceLink> createSourceLinks(
            SourceGroupPlacementPlan sourceGroupPlacementPlan
    ) {
        List<DebugAnchorSourceLink> sourceLinks =
                new ArrayList<>();

        for (SourcePlacementPlan sourcePlacementPlan
                : sourceGroupPlacementPlan
                .getSourcePlacementPlans()) {

            if (!sourcePlacementPlan.hasPlacedPos()) {
                return List.of();
            }

            sourceLinks.add(
                    new DebugAnchorSourceLink(
                            sourcePlacementPlan
                                    .getSourcePlacementId(),
                            sourcePlacementPlan
                                    .getPlacedPos()
                    )
            );
        }

        return List.copyOf(
                sourceLinks
        );
    }

    /**
     * Finds an air position elevated above the represented group's complete
     * emergence area.
     *
     * The exact anchor column is preferred. A small horizontal fallback is
     * allowed so the debug tool never destroys terrain merely to display a
     * marker.
     */
    private static BlockPos findMarkerPosition(
            ServerLevel level,
            BlockPos representedAnchorPos,
            SourceGroupPlacementPlan sourceGroupPlacementPlan,
            Set<BlockPos> reservedMarkerPositions
    ) {
        BlockPos surfacePos =
                level.getHeightmapPos(
                        Heightmap.Types
                                .MOTION_BLOCKING_NO_LEAVES,
                        representedAnchorPos
                );

        int minimumMarkerY =
                Math.max(
                        representedAnchorPos.getY(),
                        surfacePos.getY()
                ) + MARKER_CLEARANCE_ABOVE_SOURCES;

        for (SourcePlacementPlan sourcePlacementPlan
                : sourceGroupPlacementPlan
                .getSourcePlacementPlans()) {

            if (!sourcePlacementPlan.hasPlacedPos()) {
                continue;
            }

            int sourceClearanceTop =
                    sourcePlacementPlan
                            .getPlacedPos()
                            .getY()
                            + sourcePlacementPlan
                            .getClearanceHeight();

            minimumMarkerY =
                    Math.max(
                            minimumMarkerY,
                            sourceClearanceTop
                                    + MARKER_CLEARANCE_ABOVE_SOURCES
                    );
        }

        int maximumMarkerY =
                Math.min(
                        minimumMarkerY
                                + MARKER_VERTICAL_SEARCH,
                        level.getMaxBuildHeight() - 1
                );

        for (int y = minimumMarkerY;
             y <= maximumMarkerY;
             y++) {

            BlockPos directCandidate =
                    new BlockPos(
                            representedAnchorPos.getX(),
                            y,
                            representedAnchorPos.getZ()
                    );

            if (isMarkerPositionAvailable(
                    level,
                    directCandidate,
                    reservedMarkerPositions
            )) {
                return directCandidate.immutable();
            }

            for (int radius = 1;
                 radius
                         <= MARKER_HORIZONTAL_SEARCH_RADIUS;
                 radius++) {

                BlockPos ringCandidate =
                        findAvailablePositionOnRing(
                                level,
                                representedAnchorPos,
                                y,
                                radius,
                                reservedMarkerPositions
                        );

                if (ringCandidate != null) {
                    return ringCandidate;
                }
            }
        }

        return null;
    }

    private static BlockPos findAvailablePositionOnRing(
            ServerLevel level,
            BlockPos centre,
            int y,
            int radius,
            Set<BlockPos> reservedMarkerPositions
    ) {
        for (int xOffset = -radius;
             xOffset <= radius;
             xOffset++) {

            BlockPos northCandidate =
                    new BlockPos(
                            centre.getX() + xOffset,
                            y,
                            centre.getZ() - radius
                    );

            if (isMarkerPositionAvailable(
                    level,
                    northCandidate,
                    reservedMarkerPositions
            )) {
                return northCandidate.immutable();
            }

            BlockPos southCandidate =
                    new BlockPos(
                            centre.getX() + xOffset,
                            y,
                            centre.getZ() + radius
                    );

            if (isMarkerPositionAvailable(
                    level,
                    southCandidate,
                    reservedMarkerPositions
            )) {
                return southCandidate.immutable();
            }
        }

        for (int zOffset = -radius + 1;
             zOffset <= radius - 1;
             zOffset++) {

            BlockPos westCandidate =
                    new BlockPos(
                            centre.getX() - radius,
                            y,
                            centre.getZ() + zOffset
                    );

            if (isMarkerPositionAvailable(
                    level,
                    westCandidate,
                    reservedMarkerPositions
            )) {
                return westCandidate.immutable();
            }

            BlockPos eastCandidate =
                    new BlockPos(
                            centre.getX() + radius,
                            y,
                            centre.getZ() + zOffset
                    );

            if (isMarkerPositionAvailable(
                    level,
                    eastCandidate,
                    reservedMarkerPositions
            )) {
                return eastCandidate.immutable();
            }
        }

        return null;
    }

    private static boolean isMarkerPositionAvailable(
            ServerLevel level,
            BlockPos markerPos,
            Set<BlockPos> reservedMarkerPositions
    ) {
        if (markerPos.getY()
                < level.getMinBuildHeight()) {
            return false;
        }

        if (markerPos.getY()
                >= level.getMaxBuildHeight()) {
            return false;
        }

        if (reservedMarkerPositions.contains(
                markerPos
        )) {
            return false;
        }

        if (!level.hasChunkAt(
                markerPos
        )) {
            return false;
        }

        if (!level.getBlockState(
                markerPos
        ).isAir()) {
            return false;
        }

        return level.getBlockEntity(
                markerPos
        ) == null;
    }

    private static void rollbackPlacedAnchors(
            ServerLevel level,
            UUID incursionId,
            List<BlockPos> placedAnchorPositions
    ) {
        removeAnchorPositions(
                level,
                incursionId,
                placedAnchorPositions
        );
    }

    private static int removeAnchorPositions(
            ServerLevel level,
            UUID incursionId,
            List<BlockPos> anchorPositions
    ) {
        int removedCount =
                0;

        for (BlockPos anchorPos
                : anchorPositions) {

            if (!(level.getBlockState(anchorPos)
                    .getBlock()
                    instanceof DebugIncursionAnchorBlock)) {
                continue;
            }

            if (!(level.getBlockEntity(anchorPos)
                    instanceof DebugIncursionAnchorEntity anchorEntity)) {
                continue;
            }

            if (!incursionId.equals(
                    anchorEntity.getIncursionId()
            )) {
                continue;
            }

            if (level.removeBlock(
                    anchorPos,
                    false
            )) {
                removedCount++;
            }
        }

        return removedCount;
    }

    private record AnchorPlacementAttempt(
            boolean successful,
            String failureMessage
    ) {

        private AnchorPlacementAttempt {
            boolean hasFailureMessage =
                    failureMessage != null
                            && !failureMessage.isBlank();

            if (successful == hasFailureMessage) {
                throw new IllegalArgumentException(
                        "Anchor-placement attempt must contain either success "
                                + "or a failure message."
                );
            }
        }

        private static AnchorPlacementAttempt success() {
            return new AnchorPlacementAttempt(
                    true,
                    null
            );
        }

        private static AnchorPlacementAttempt failure(
                String failureMessage
        ) {
            return new AnchorPlacementAttempt(
                    false,
                    failureMessage
            );
        }
    }

    /**
     * Records which marker blocks were created for one incursion.
     *
     * Runtime will retain this result so the markers can later be removed
     * safely without searching arbitrary loaded chunks.
     */
    public record PlacementResult(
            UUID incursionId,
            boolean successful,
            List<BlockPos> anchorPositions,
            String failureMessage
    ) {

        public PlacementResult {
            if (incursionId == null) {
                throw new IllegalArgumentException(
                        "Debug-anchor incursion ID cannot be null."
                );
            }

            if (anchorPositions == null) {
                throw new IllegalArgumentException(
                        "Debug-anchor position list cannot be null."
                );
            }

            anchorPositions =
                    List.copyOf(
                            anchorPositions
                    );

            boolean hasPositions =
                    !anchorPositions.isEmpty();

            boolean hasFailureMessage =
                    failureMessage != null
                            && !failureMessage.isBlank();

            if (successful) {
                if (!hasPositions) {
                    throw new IllegalArgumentException(
                            "Successful debug-anchor placement must contain "
                                    + "at least one marker position."
                    );
                }

                if (hasFailureMessage) {
                    throw new IllegalArgumentException(
                            "Successful debug-anchor placement cannot contain "
                                    + "a failure message."
                    );
                }
            } else {
                if (hasPositions) {
                    throw new IllegalArgumentException(
                            "Failed debug-anchor placement cannot retain "
                                    + "marker positions."
                    );
                }

                if (!hasFailureMessage) {
                    throw new IllegalArgumentException(
                            "Failed debug-anchor placement requires a failure "
                                    + "message."
                    );
                }
            }
        }

        public static PlacementResult success(
                UUID incursionId,
                List<BlockPos> anchorPositions
        ) {
            return new PlacementResult(
                    incursionId,
                    true,
                    anchorPositions,
                    null
            );
        }

        public static PlacementResult failure(
                UUID incursionId,
                String failureMessage
        ) {
            return new PlacementResult(
                    incursionId,
                    false,
                    List.of(),
                    failureMessage
            );
        }

        public int getAnchorCount() {
            return anchorPositions.size();
        }
    }

    private DebugIncursionAnchorPlacementService() {
    }
}