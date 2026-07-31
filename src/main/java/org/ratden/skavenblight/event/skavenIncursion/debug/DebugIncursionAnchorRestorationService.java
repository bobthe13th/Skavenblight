package org.ratden.skavenblight.event.skavenIncursion.debug;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.block.custom.debug.DebugIncursionAnchorBlock;
import org.ratden.skavenblight.block.entity.debug.DebugAnchorSourceLink;
import org.ratden.skavenblight.block.entity.debug.DebugAnchorType;
import org.ratden.skavenblight.block.entity.debug.DebugIncursionAnchorEntity;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.front.FrontPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.front.FrontPlacementPattern;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceGroupPlacementPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourcePlacementPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceRole;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reconstructs the in-memory debug-anchor tracker for one restored incursion.
 *
 * Debug anchor blocks and their block-entity metadata persist as ordinary
 * world data. The static DebugIncursionAnchorTracker does not persist and is
 * deliberately cleared when the server stops.
 *
 * Restoration therefore proceeds as follows:
 *
 * 1. derive the expected debug-anchor hierarchy from the immutable plan;
 * 2. search the bounded placement area for each expected marker;
 * 3. reuse the existing marker set when every marker exactly matches;
 * 4. otherwise remove stale markers owned by this incursion;
 * 5. recreate the complete marker set through the ordinary placement service.
 *
 * Debug anchors remain non-authoritative. Failure to reconstruct them must
 * never alter or invalidate the incursion plan or Scenario runtime.
 */
public final class DebugIncursionAnchorRestorationService {

    /**
     * Matches the horizontal fallback radius used by ordinary debug-anchor
     * placement.
     *
     * Restoration inspects the block-entity indexes of already-loaded chunks
     * intersecting this area. It does not scan every block across the complete
     * vertical build range.
     */
    private static final int MARKER_HORIZONTAL_SEARCH_RADIUS =
            4;

    private static final int CHUNK_WIDTH_BLOCKS =
            16;

    /**
     * The debug visualisation palette currently supports six fronts.
     */
    private static final int MAXIMUM_VISUALISED_FRONTS =
            6;

    /**
     * Restores or recreates the complete debug-marker set for one incursion.
     *
     * A successful result may represent either:
     *
     * - existing world markers that were validated and reused; or
     * - a newly recreated marker set.
     */
    public static DebugIncursionAnchorPlacementService.PlacementResult
    restoreAnchors(
            ServerLevel level,
            IncursionPlan incursionPlan
    ) {
        if (level == null) {
            throw new IllegalArgumentException(
                    "Debug-anchor restoration level cannot be null."
            );
        }

        if (incursionPlan == null) {
            throw new IllegalArgumentException(
                    "Debug-anchor restoration plan cannot be null."
            );
        }

        UUID incursionId =
                incursionPlan.getIncursionId();

        if (incursionId == null) {
            throw new IllegalArgumentException(
                    "Debug-anchor restoration plan has no incursion ID."
            );
        }

        List<ExpectedAnchor> expectedAnchors;

        try {
            expectedAnchors =
                    createExpectedAnchors(
                            incursionPlan
                    );
        } catch (IllegalArgumentException
                 | IllegalStateException exception) {

            return DebugIncursionAnchorPlacementService
                    .PlacementResult
                    .failure(
                            incursionId,
                            "Could not derive restored debug anchors: "
                                    + exception.getMessage()
                    );
        }

        ExistingAnchorInspection inspection;

        try {
            inspection =
                    inspectExistingAnchors(
                            level,
                            expectedAnchors
                    );
        } catch (IllegalStateException exception) {
            /*
             * Debug markers are non-authoritative. An unloaded marker-search
             * chunk must not be forced into memory merely to reconstruct the
             * optional visualisation.
             *
             * Nothing has been removed or recreated at this point.
             */
            return DebugIncursionAnchorPlacementService
                    .PlacementResult
                    .failure(
                            incursionId,
                            "Debug-anchor restoration was skipped: "
                                    + exception.getMessage()
                    );
        }

        if (inspection.completeAndValid()) {
            return DebugIncursionAnchorPlacementService
                    .PlacementResult
                    .success(
                            incursionId,
                            inspection.validAnchorPositions()
                    );
        }

        /*
         * The markers are debug-only representations. It is safe to remove
         * stale markers that explicitly identify this incursion before
         * recreating the complete set.
         */
        removeOwnedAnchors(
                level,
                incursionId,
                inspection.ownedAnchorPositions()
        );

        return DebugIncursionAnchorPlacementService.placeAnchors(
                level,
                incursionPlan
        );
    }

    private static List<ExpectedAnchor> createExpectedAnchors(
            IncursionPlan incursionPlan
    ) {
        if (!incursionPlan.hasFrontPlans()) {
            throw new IllegalStateException(
                    "Incursion plan contains no fronts."
            );
        }

        if (incursionPlan.getFrontCount()
                > MAXIMUM_VISUALISED_FRONTS) {

            throw new IllegalStateException(
                    "Debug source visualisation supports at most "
                            + MAXIMUM_VISUALISED_FRONTS
                            + " fronts, but incursion "
                            + incursionPlan.getIncursionId()
                            + " contains "
                            + incursionPlan.getFrontCount()
                            + "."
            );
        }

        List<ExpectedAnchor> expectedAnchors =
                new ArrayList<>();

        for (FrontPlan frontPlan
                : incursionPlan.getFrontPlans()) {

            if (frontPlan == null) {
                throw new IllegalStateException(
                        "Incursion plan contains a null front."
                );
            }

            int frontIndex =
                    frontPlan.getFrontIndex();

            if (frontIndex < 0
                    || frontIndex
                    >= MAXIMUM_VISUALISED_FRONTS) {

                throw new IllegalStateException(
                        "Front "
                                + frontPlan.getFrontId()
                                + " has unsupported front index "
                                + frontIndex
                                + "."
                );
            }

            List<SourceGroupPlacementPlan>
                    sourceGroupPlacementPlans =
                    frontPlan.getSourceGroupPlacementPlans();

            if (sourceGroupPlacementPlans.isEmpty()) {
                throw new IllegalStateException(
                        "Front "
                                + frontPlan.getFrontId()
                                + " contains no physical source groups."
                );
            }

            for (int sourceGroupIndex = 0;
                 sourceGroupIndex
                         < sourceGroupPlacementPlans.size();
                 sourceGroupIndex++) {

                SourceGroupPlacementPlan sourceGroupPlacementPlan =
                        sourceGroupPlacementPlans.get(
                                sourceGroupIndex
                        );

                if (sourceGroupPlacementPlan == null) {
                    throw new IllegalStateException(
                            "Front "
                                    + frontPlan.getFrontId()
                                    + " contains a null physical source "
                                    + "group."
                    );
                }

                if (!frontPlan.getFrontId().equals(
                        sourceGroupPlacementPlan.getFrontId()
                )) {
                    throw new IllegalStateException(
                            "Physical source group "
                                    + sourceGroupPlacementPlan
                                    .getSourceGroupPlacementId()
                                    + " belongs to a different front."
                    );
                }

                DebugAnchorType anchorType =
                        sourceGroupIndex == 0
                                ? DebugAnchorType.FRONT
                                : DebugAnchorType.SOURCE_GROUP;

                BlockPos representedAnchorPos =
                        sourceGroupIndex == 0
                                ? frontPlan.getAnchorPos()
                                : sourceGroupPlacementPlan
                                .getAnchorPos();

                if (representedAnchorPos == null) {
                    throw new IllegalStateException(
                            "Physical source group "
                                    + sourceGroupPlacementPlan
                                    .getSourceGroupPlacementId()
                                    + " has no represented anchor position."
                    );
                }

                List<DebugAnchorSourceLink> sourceLinks =
                        createSourceLinks(
                                sourceGroupPlacementPlan
                        );

                expectedAnchors.add(
                        new ExpectedAnchor(
                                anchorType,
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
                                sourceLinks,
                                representedAnchorPos
                        )
                );
            }
        }

        if (expectedAnchors.isEmpty()) {
            throw new IllegalStateException(
                    "Incursion plan produced no expected debug anchors."
            );
        }

        return List.copyOf(
                expectedAnchors
        );
    }

    private static List<DebugAnchorSourceLink> createSourceLinks(
            SourceGroupPlacementPlan sourceGroupPlacementPlan
    ) {
        if (sourceGroupPlacementPlan.isEmpty()) {
            throw new IllegalStateException(
                    "Physical source group "
                            + sourceGroupPlacementPlan
                            .getSourceGroupPlacementId()
                            + " contains no physical sources."
            );
        }

        List<DebugAnchorSourceLink> sourceLinks =
                new ArrayList<>();

        for (SourcePlacementPlan sourcePlacementPlan
                : sourceGroupPlacementPlan
                .getSourcePlacementPlans()) {

            if (sourcePlacementPlan == null) {
                throw new IllegalStateException(
                        "Physical source group "
                                + sourceGroupPlacementPlan
                                .getSourceGroupPlacementId()
                                + " contains a null source placement."
                );
            }

            if (!sourcePlacementPlan.hasPlacedPos()) {
                throw new IllegalStateException(
                        "Physical source placement "
                                + sourcePlacementPlan
                                .getSourcePlacementId()
                                + " has no final position."
                );
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

    private static ExistingAnchorInspection inspectExistingAnchors(
            ServerLevel level,
            List<ExpectedAnchor> expectedAnchors
    ) {
        Set<BlockPos> ownedAnchorPositions =
                new LinkedHashSet<>();

        List<BlockPos> validAnchorPositions =
                new ArrayList<>();

        boolean completeAndValid =
                true;

        for (ExpectedAnchor expectedAnchor
                : expectedAnchors) {

            List<FoundAnchor> matchingGroupAnchors =
                    findOwnedAnchorsInSearchArea(
                            level,
                            expectedAnchor,
                            ownedAnchorPositions
                    );

            if (matchingGroupAnchors.size() != 1) {
                completeAndValid =
                        false;

                continue;
            }

            FoundAnchor foundAnchor =
                    matchingGroupAnchors.getFirst();

            if (!matchesExpectedAnchor(
                    foundAnchor.anchorEntity(),
                    expectedAnchor
            )) {
                completeAndValid =
                        false;

                continue;
            }

            validAnchorPositions.add(
                    foundAnchor.anchorPos()
            );
        }

        /*
         * A reusable marker set must contain exactly one owned marker for
         * every expected source group and no additional owned markers inside
         * any of the expected placement regions.
         */
        if (ownedAnchorPositions.size()
                != expectedAnchors.size()) {

            completeAndValid =
                    false;
        }

        if (validAnchorPositions.size()
                != expectedAnchors.size()) {

            completeAndValid =
                    false;
        }

        Set<BlockPos> uniqueValidPositions =
                new LinkedHashSet<>(
                        validAnchorPositions
                );

        if (uniqueValidPositions.size()
                != validAnchorPositions.size()) {

            completeAndValid =
                    false;
        }

        return new ExistingAnchorInspection(
                completeAndValid,
                validAnchorPositions,
                ownedAnchorPositions
        );
    }

    /**
     * Inspects the block entities contained in already-loaded chunks
     * intersecting one marker search square.
     *
     * Ordinary marker placement uses the same horizontal radius around the
     * represented planning anchor. Examining the chunk block-entity index
     * finds markers at any height without visiting every block in every
     * vertical column.
     *
     * When any intersecting chunk is unavailable, this method fails before
     * marker removal or recreation. This avoids:
     *
     * - forcing optional debug chunks to load;
     * - mistaking an unseen marker for a missing marker;
     * - creating duplicate markers in an unloaded neighbouring chunk.
     */
    private static List<FoundAnchor> findOwnedAnchorsInSearchArea(
            ServerLevel level,
            ExpectedAnchor expectedAnchor,
            Set<BlockPos> ownedAnchorPositions
    ) {
        List<FoundAnchor> matchingGroupAnchors =
                new ArrayList<>();

        BlockPos representedAnchorPos =
                expectedAnchor.representedAnchorPos();

        int minimumX =
                representedAnchorPos.getX()
                        - MARKER_HORIZONTAL_SEARCH_RADIUS;

        int maximumX =
                representedAnchorPos.getX()
                        + MARKER_HORIZONTAL_SEARCH_RADIUS;

        int minimumZ =
                representedAnchorPos.getZ()
                        - MARKER_HORIZONTAL_SEARCH_RADIUS;

        int maximumZ =
                representedAnchorPos.getZ()
                        + MARKER_HORIZONTAL_SEARCH_RADIUS;

        int minimumChunkX =
                Math.floorDiv(
                        minimumX,
                        CHUNK_WIDTH_BLOCKS
                );

        int maximumChunkX =
                Math.floorDiv(
                        maximumX,
                        CHUNK_WIDTH_BLOCKS
                );

        int minimumChunkZ =
                Math.floorDiv(
                        minimumZ,
                        CHUNK_WIDTH_BLOCKS
                );

        int maximumChunkZ =
                Math.floorDiv(
                        maximumZ,
                        CHUNK_WIDTH_BLOCKS
                );

        for (int chunkX = minimumChunkX;
             chunkX <= maximumChunkX;
             chunkX++) {

            for (int chunkZ = minimumChunkZ;
                 chunkZ <= maximumChunkZ;
                 chunkZ++) {

                /*
                 * getChunkNow returns only an already-loaded LevelChunk. It
                 * does not request generation or synchronously load the
                 * missing chunk.
                 */
                LevelChunk loadedChunk =
                        level.getChunkSource()
                                .getChunkNow(
                                        chunkX,
                                        chunkZ
                                );

                if (loadedChunk == null) {
                    throw new IllegalStateException(
                            "marker-search chunk "
                                    + chunkX
                                    + ", "
                                    + chunkZ
                                    + " is not already loaded. No chunks were "
                                    + "forced to load and no debug markers "
                                    + "were modified."
                    );
                }

                /*
                 * The chunk already indexes its block entities by position.
                 * Debug anchors are therefore found across the full build
                 * height without scanning ordinary blocks.
                 */
                for (BlockEntity blockEntity
                        : loadedChunk
                        .getBlockEntities()
                        .values()) {

                    if (!(blockEntity
                            instanceof DebugIncursionAnchorEntity
                            anchorEntity)) {

                        continue;
                    }

                    BlockPos anchorPos =
                            anchorEntity.getBlockPos();

                    if (anchorPos.getX() < minimumX
                            || anchorPos.getX() > maximumX
                            || anchorPos.getZ() < minimumZ
                            || anchorPos.getZ() > maximumZ) {

                        continue;
                    }

                    if (!(level.getBlockState(anchorPos)
                            .getBlock()
                            instanceof DebugIncursionAnchorBlock)) {

                        continue;
                    }

                    if (!expectedAnchor.incursionId().equals(
                            anchorEntity.getIncursionId()
                    )) {
                        continue;
                    }

                    BlockPos immutableAnchorPos =
                            anchorPos.immutable();

                    ownedAnchorPositions.add(
                            immutableAnchorPos
                    );

                    if (!expectedAnchor
                            .sourceGroupPlacementId()
                            .equals(
                                    anchorEntity
                                            .getSourceGroupPlacementId()
                            )) {

                        continue;
                    }

                    matchingGroupAnchors.add(
                            new FoundAnchor(
                                    immutableAnchorPos,
                                    anchorEntity
                            )
                    );
                }
            }
        }

        return List.copyOf(
                matchingGroupAnchors
        );
    }

    private static boolean matchesExpectedAnchor(
            DebugIncursionAnchorEntity anchorEntity,
            ExpectedAnchor expectedAnchor
    ) {
        if (!anchorEntity.isInitialised()) {
            return false;
        }

        if (anchorEntity.getAnchorType()
                != expectedAnchor.anchorType()) {

            return false;
        }

        if (!expectedAnchor.incursionId().equals(
                anchorEntity.getIncursionId()
        )) {
            return false;
        }

        if (!expectedAnchor.frontId().equals(
                anchorEntity.getFrontId()
        )) {
            return false;
        }

        if (anchorEntity.getFrontIndex()
                != expectedAnchor.frontIndex()) {

            return false;
        }

        if (anchorEntity.getFrontPlacementPattern()
                != expectedAnchor.frontPlacementPattern()) {

            return false;
        }

        if (Double.compare(
                anchorEntity.getThreatShare(),
                expectedAnchor.threatShare()
        ) != 0) {
            return false;
        }

        if (Double.compare(
                anchorEntity.getComplexityShare(),
                expectedAnchor.complexityShare()
        ) != 0) {
            return false;
        }

        if (anchorEntity.isDominant()
                != expectedAnchor.dominant()) {

            return false;
        }

        if (!expectedAnchor
                .sourceGroupPlacementId()
                .equals(
                        anchorEntity
                                .getSourceGroupPlacementId()
                )) {

            return false;
        }

        if (anchorEntity.getSourceGroupIndex()
                != expectedAnchor.sourceGroupIndex()) {

            return false;
        }

        if (anchorEntity.getSourceRole()
                != expectedAnchor.sourceRole()) {

            return false;
        }

        if (anchorEntity.getSourceGroupLoad()
                != expectedAnchor.sourceGroupLoad()) {

            return false;
        }

        if (anchorEntity.getMaximumSourceGroupLoad()
                != expectedAnchor.maximumSourceGroupLoad()) {

            return false;
        }

        return anchorEntity
                .getSourceLinks()
                .equals(
                        expectedAnchor.sourceLinks()
                );
    }

    private static int removeOwnedAnchors(
            ServerLevel level,
            UUID incursionId,
            Set<BlockPos> ownedAnchorPositions
    ) {
        int removedCount =
                0;

        for (BlockPos anchorPos
                : ownedAnchorPositions) {

            if (!(level.getBlockState(anchorPos)
                    .getBlock()
                    instanceof DebugIncursionAnchorBlock)) {

                continue;
            }

            if (!(level.getBlockEntity(anchorPos)
                    instanceof DebugIncursionAnchorEntity
                    anchorEntity)) {

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

    private record ExpectedAnchor(
            DebugAnchorType anchorType,
            UUID incursionId,
            UUID frontId,
            int frontIndex,
            FrontPlacementPattern frontPlacementPattern,
            double threatShare,
            double complexityShare,
            boolean dominant,
            UUID sourceGroupPlacementId,
            int sourceGroupIndex,
            SourceRole sourceRole,
            int sourceGroupLoad,
            int maximumSourceGroupLoad,
            List<DebugAnchorSourceLink> sourceLinks,
            BlockPos representedAnchorPos
    ) {

        private ExpectedAnchor {
            if (anchorType == null) {
                throw new IllegalArgumentException(
                        "Expected debug-anchor type cannot be null."
                );
            }

            if (incursionId == null) {
                throw new IllegalArgumentException(
                        "Expected debug-anchor incursion ID cannot be null."
                );
            }

            if (frontId == null) {
                throw new IllegalArgumentException(
                        "Expected debug-anchor front ID cannot be null."
                );
            }

            if (frontIndex < 0) {
                throw new IllegalArgumentException(
                        "Expected debug-anchor front index cannot be negative."
                );
            }

            if (frontPlacementPattern == null) {
                throw new IllegalArgumentException(
                        "Expected debug-anchor front pattern cannot be null."
                );
            }

            if (!Double.isFinite(threatShare)
                    || !Double.isFinite(complexityShare)) {

                throw new IllegalArgumentException(
                        "Expected debug-anchor allocation shares must be "
                                + "finite."
                );
            }

            if (sourceGroupPlacementId == null) {
                throw new IllegalArgumentException(
                        "Expected debug-anchor source-group ID cannot be null."
                );
            }

            if (sourceGroupIndex < 0) {
                throw new IllegalArgumentException(
                        "Expected debug-anchor source-group index cannot be "
                                + "negative."
                );
            }

            if (anchorType == DebugAnchorType.FRONT
                    && sourceGroupIndex != 0) {

                throw new IllegalArgumentException(
                        "Expected front anchor must represent source-group "
                                + "index zero."
                );
            }

            if (anchorType == DebugAnchorType.SOURCE_GROUP
                    && sourceGroupIndex == 0) {

                throw new IllegalArgumentException(
                        "Expected additional source-group anchor must use an "
                                + "index greater than zero."
                );
            }

            if (sourceRole == null) {
                throw new IllegalArgumentException(
                        "Expected debug-anchor source role cannot be null."
                );
            }

            if (sourceGroupLoad <= 0
                    || maximumSourceGroupLoad <= 0
                    || sourceGroupLoad > maximumSourceGroupLoad) {

                throw new IllegalArgumentException(
                        "Expected debug-anchor source-group load is invalid."
                );
            }

            if (sourceLinks == null
                    || sourceLinks.isEmpty()) {

                throw new IllegalArgumentException(
                        "Expected debug anchor must contain source links."
                );
            }

            if (representedAnchorPos == null) {
                throw new IllegalArgumentException(
                        "Expected represented anchor position cannot be null."
                );
            }

            sourceLinks =
                    List.copyOf(
                            sourceLinks
                    );

            representedAnchorPos =
                    representedAnchorPos.immutable();
        }
    }

    private record FoundAnchor(
            BlockPos anchorPos,
            DebugIncursionAnchorEntity anchorEntity
    ) {

        private FoundAnchor {
            if (anchorPos == null) {
                throw new IllegalArgumentException(
                        "Found debug-anchor position cannot be null."
                );
            }

            if (anchorEntity == null) {
                throw new IllegalArgumentException(
                        "Found debug-anchor entity cannot be null."
                );
            }

            anchorPos =
                    anchorPos.immutable();
        }
    }

    private record ExistingAnchorInspection(
            boolean completeAndValid,
            List<BlockPos> validAnchorPositions,
            Set<BlockPos> ownedAnchorPositions
    ) {

        private ExistingAnchorInspection {
            if (validAnchorPositions == null) {
                throw new IllegalArgumentException(
                        "Valid restored-anchor position list cannot be null."
                );
            }

            if (ownedAnchorPositions == null) {
                throw new IllegalArgumentException(
                        "Owned restored-anchor position set cannot be null."
                );
            }

            validAnchorPositions =
                    List.copyOf(
                            validAnchorPositions
                    );

            ownedAnchorPositions =
                    Set.copyOf(
                            ownedAnchorPositions
                    );
        }
    }

    private DebugIncursionAnchorRestorationService() {
    }
}