package org.ratden.skavenblight.event.skavenIncursion.planning.chunk;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.event.skavenIncursion.director.IncursionTargetType;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlanningContext;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.SourceGroupComposition;
import org.ratden.skavenblight.event.skavenIncursion.planning.front.FrontPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceGroupEnvelope;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceGroupPlacementPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourcePlacementPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceReservationArea;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.WarpFluxNetworkGeometry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Derives an immutable IncursionChunkLoadPlan from one completed tactical plan
 * and the exact planning context that produced it.
 *
 * This first implementation intentionally favours deterministic, inspectable
 * and slightly conservative footprints over a highly dynamic moving ticket
 * system.
 *
 * Baseline policy:
 *
 * - the defended target/base footprint receives a one-chunk interaction
 *   margin;
 * - each physical source group includes its envelope, every reservation and
 *   preparation area, its anchor, and a one-chunk movement margin;
 * - each group receives a buffered chunk corridor to the nearest defended
 *   chunk;
 * - all planned chunks belong to the retained loaded footprint;
 * - the base is the baseline ticking footprint;
 * - complete source-group activation footprints are promoted per wave.
 *
 * This calculator performs no world mutation and acquires no chunk tickets.
 */
public class IncursionChunkLoadPlanCalculator {

    public static final int BASE_INTERACTION_MARGIN_CHUNKS = 1;
    public static final int SOURCE_GROUP_MOVEMENT_MARGIN_CHUNKS = 1;
    public static final int ROUTE_CORRIDOR_BUFFER_CHUNKS = 1;

    public IncursionChunkLoadPlan calculate(
            IncursionPlan incursionPlan,
            IncursionPlanningContext planningContext
    ) {
        if (incursionPlan == null) {
            throw new IllegalArgumentException(
                    "Incursion plan cannot be null."
            );
        }

        if (planningContext == null) {
            throw new IllegalArgumentException(
                    "Incursion planning context cannot be null."
            );
        }

        if (!incursionPlan.hasFrontPlans()) {
            throw new IllegalArgumentException(
                    "Incursion plan must contain at least one front before "
                            + "chunk requirements can be calculated."
            );
        }

        Set<ChunkPos> protectedBaseChunks =
                calculateProtectedBaseChunks(
                        planningContext
                );

        Map<UUID, UUID> physicalGroupIdByCompositionId =
                indexPhysicalGroupsByCompositionId(
                        incursionPlan
                );

        List<SourceGroupChunkLoadPlan> sourceGroupPlans =
                calculateSourceGroupPlans(
                        incursionPlan,
                        protectedBaseChunks
                );

        Map<Integer, Set<UUID>> requiredGroupIdsByWave =
                calculateRequiredGroupIdsByWave(
                        incursionPlan,
                        physicalGroupIdByCompositionId
                );

        return new IncursionChunkLoadPlan(
                incursionPlan.getIncursionId(),
                protectedBaseChunks,
                sourceGroupPlans,
                requiredGroupIdsByWave
        );
    }

    private static Set<ChunkPos> calculateProtectedBaseChunks(
            IncursionPlanningContext planningContext
    ) {
        LinkedHashSet<ChunkPos> baseChunks =
                new LinkedHashSet<>();

        if (planningContext.targetType()
                == IncursionTargetType.NEXUS
                && planningContext
                .hasProtectedNetworkGeometrySnapshot()) {

            WarpFluxNetworkGeometry networkGeometry =
                    planningContext
                            .protectedNetworkGeometrySnapshot();

            for (BlockPos protectedPos
                    : networkGeometry.getProtectedPositions()) {

                baseChunks.add(
                        new ChunkPos(protectedPos)
                );
            }
        } else {
            int radius =
                    planningContext.baseRadius();

            BlockPos targetPos =
                    planningContext.targetPos();

            addChunksIntersectingBounds(
                    baseChunks,
                    targetPos.getX() - radius,
                    targetPos.getX() + radius,
                    targetPos.getZ() - radius,
                    targetPos.getZ() + radius
            );
        }

        if (baseChunks.isEmpty()) {
            baseChunks.add(
                    new ChunkPos(
                            planningContext.targetPos()
                    )
            );
        }

        return expandChunks(
                baseChunks,
                BASE_INTERACTION_MARGIN_CHUNKS
        );
    }

    private static Map<UUID, UUID>
    indexPhysicalGroupsByCompositionId(
            IncursionPlan incursionPlan
    ) {
        LinkedHashMap<UUID, UUID> groupIdByCompositionId =
                new LinkedHashMap<>();

        for (FrontPlan frontPlan
                : incursionPlan.getFrontPlans()) {

            for (SourceGroupPlacementPlan groupPlacementPlan
                    : frontPlan.getSourceGroupPlacementPlans()) {

                UUID sourceGroupPlacementId =
                        groupPlacementPlan
                                .getSourceGroupPlacementId();

                for (UUID sourceGroupCompositionId
                        : groupPlacementPlan
                        .getSourceGroupCompositionIds()) {

                    UUID previousGroupId =
                            groupIdByCompositionId.put(
                                    sourceGroupCompositionId,
                                    sourceGroupPlacementId
                            );

                    if (previousGroupId != null
                            && !previousGroupId.equals(
                            sourceGroupPlacementId
                    )) {
                        throw new IllegalStateException(
                                "Source-group composition "
                                        + sourceGroupCompositionId
                                        + " is bound to physical groups "
                                        + previousGroupId
                                        + " and "
                                        + sourceGroupPlacementId
                                        + "."
                        );
                    }
                }
            }
        }

        return groupIdByCompositionId;
    }

    private static List<SourceGroupChunkLoadPlan>
    calculateSourceGroupPlans(
            IncursionPlan incursionPlan,
            Set<ChunkPos> protectedBaseChunks
    ) {
        ArrayList<SourceGroupChunkLoadPlan> groupPlans =
                new ArrayList<>();

        for (FrontPlan frontPlan
                : incursionPlan.getFrontPlans()) {

            for (SourceGroupPlacementPlan groupPlacementPlan
                    : frontPlan.getSourceGroupPlacementPlans()) {

                Set<ChunkPos> groupFootprintChunks =
                        calculateSourceGroupFootprintChunks(
                                groupPlacementPlan
                        );

                ChunkPos groupAnchorChunk =
                        new ChunkPos(
                                groupPlacementPlan
                                        .getAnchorPos()
                        );

                ChunkPos nearestBaseChunk =
                        findNearestChunk(
                                groupAnchorChunk,
                                protectedBaseChunks
                        );

                Set<ChunkPos> routeCorridorChunks =
                        calculateRouteCorridorChunks(
                                groupAnchorChunk,
                                nearestBaseChunk
                        );

                groupPlans.add(
                        new SourceGroupChunkLoadPlan(
                                groupPlacementPlan
                                        .getSourceGroupPlacementId(),
                                frontPlan.getFrontId(),
                                groupPlacementPlan.getAnchorPos(),
                                groupFootprintChunks,
                                routeCorridorChunks
                        )
                );
            }
        }

        return groupPlans;
    }

    private static Set<ChunkPos>
    calculateSourceGroupFootprintChunks(
            SourceGroupPlacementPlan groupPlacementPlan
    ) {
        LinkedHashSet<ChunkPos> groupChunks =
                new LinkedHashSet<>();

        SourceGroupEnvelope envelope =
                groupPlacementPlan
                        .getSourceGroupEnvelope();

        addChunksIntersectingBounds(
                groupChunks,
                envelope.getMinimumX(),
                envelope.getMaximumX(),
                envelope.getMinimumZ(),
                envelope.getMaximumZ()
        );

        groupChunks.add(
                new ChunkPos(
                        groupPlacementPlan.getAnchorPos()
                )
        );

        for (SourcePlacementPlan sourcePlacementPlan
                : groupPlacementPlan
                .getSourcePlacementPlans()) {

            addChunksIntersectingBounds(
                    groupChunks,
                    sourcePlacementPlan
                            .getReservationBounds()
            );

            addChunksIntersectingBounds(
                    groupChunks,
                    sourcePlacementPlan
                            .getPreparationBounds()
            );
        }

        return expandChunks(
                groupChunks,
                SOURCE_GROUP_MOVEMENT_MARGIN_CHUNKS
        );
    }

    private static Map<Integer, Set<UUID>>
    calculateRequiredGroupIdsByWave(
            IncursionPlan incursionPlan,
            Map<UUID, UUID> physicalGroupIdByCompositionId
    ) {
        TreeMap<Integer, Set<UUID>> requiredGroupIdsByWave =
                new TreeMap<>();

        for (FrontPlan frontPlan
                : incursionPlan.getFrontPlans()) {

            for (FrontPlan.WavePlan wavePlan
                    : frontPlan.getWavePlans()) {

                Set<UUID> requiredGroupIds =
                        requiredGroupIdsByWave
                                .computeIfAbsent(
                                        wavePlan.getWaveIndex(),
                                        ignored ->
                                                new LinkedHashSet<>()
                                );

                for (SourceGroupComposition groupComposition
                        : wavePlan
                        .getSourceGroupCompositions()) {

                    UUID sourceGroupCompositionId =
                            groupComposition
                                    .getSourceGroupCompositionId();

                    UUID sourceGroupPlacementId =
                            physicalGroupIdByCompositionId.get(
                                    sourceGroupCompositionId
                            );

                    if (sourceGroupPlacementId == null) {
                        throw new IllegalStateException(
                                "Wave "
                                        + wavePlan.getWaveIndex()
                                        + " source-group composition "
                                        + sourceGroupCompositionId
                                        + " has no physical source-group "
                                        + "placement binding."
                        );
                    }

                    requiredGroupIds.add(
                            sourceGroupPlacementId
                    );
                }
            }
        }

        return requiredGroupIdsByWave;
    }

    private static Set<ChunkPos> calculateRouteCorridorChunks(
            ChunkPos sourceGroupChunk,
            ChunkPos destinationChunk
    ) {
        Set<ChunkPos> routeLine =
                createSupercoverChunkLine(
                        sourceGroupChunk,
                        destinationChunk
                );

        return expandChunks(
                routeLine,
                ROUTE_CORRIDOR_BUFFER_CHUNKS
        );
    }

    private static Set<ChunkPos> createSupercoverChunkLine(
            ChunkPos start,
            ChunkPos end
    ) {
        LinkedHashSet<ChunkPos> chunks =
                new LinkedHashSet<>();

        int differenceX =
                end.x - start.x;

        int differenceZ =
                end.z - start.z;

        int stepsX =
                Math.abs(differenceX);

        int stepsZ =
                Math.abs(differenceZ);

        int directionX =
                Integer.compare(
                        differenceX,
                        0
                );

        int directionZ =
                Integer.compare(
                        differenceZ,
                        0
                );

        int currentX =
                start.x;

        int currentZ =
                start.z;

        chunks.add(
                new ChunkPos(
                        currentX,
                        currentZ
                )
        );

        if (stepsX == 0) {
            while (currentZ != end.z) {
                currentZ += directionZ;

                chunks.add(
                        new ChunkPos(
                                currentX,
                                currentZ
                        )
                );
            }

            return chunks;
        }

        if (stepsZ == 0) {
            while (currentX != end.x) {
                currentX += directionX;

                chunks.add(
                        new ChunkPos(
                                currentX,
                                currentZ
                        )
                );
            }

            return chunks;
        }

        int progressedX = 0;
        int progressedZ = 0;

        while (progressedX < stepsX
                || progressedZ < stepsZ) {

            long decision =
                    (1L + 2L * progressedX)
                            * stepsZ
                            - (1L + 2L * progressedZ)
                            * stepsX;

            if (decision == 0L) {
                /*
                 * The line crosses an exact chunk corner. Include both
                 * orthogonal neighbours as well as the diagonal destination
                 * so no chunk touched by the line is omitted.
                 */
                chunks.add(
                        new ChunkPos(
                                currentX + directionX,
                                currentZ
                        )
                );

                chunks.add(
                        new ChunkPos(
                                currentX,
                                currentZ + directionZ
                        )
                );

                currentX += directionX;
                currentZ += directionZ;
                progressedX++;
                progressedZ++;
            } else if (decision < 0L) {
                currentX += directionX;
                progressedX++;
            } else {
                currentZ += directionZ;
                progressedZ++;
            }

            chunks.add(
                    new ChunkPos(
                            currentX,
                            currentZ
                    )
            );
        }

        return chunks;
    }

    private static ChunkPos findNearestChunk(
            ChunkPos origin,
            Set<ChunkPos> candidates
    ) {
        ChunkPos nearestChunk =
                null;

        long nearestDistanceSquared =
                Long.MAX_VALUE;

        for (ChunkPos candidate : candidates) {
            long differenceX =
                    (long) candidate.x - origin.x;

            long differenceZ =
                    (long) candidate.z - origin.z;

            long distanceSquared =
                    differenceX * differenceX
                            + differenceZ * differenceZ;

            if (distanceSquared < nearestDistanceSquared) {
                nearestDistanceSquared =
                        distanceSquared;

                nearestChunk =
                        candidate;
            }
        }

        if (nearestChunk == null) {
            throw new IllegalStateException(
                    "Cannot calculate a source route without a defended "
                            + "destination chunk."
            );
        }

        return nearestChunk;
    }

    private static Set<ChunkPos> expandChunks(
            Set<ChunkPos> chunks,
            int margin
    ) {
        if (chunks == null) {
            throw new IllegalArgumentException(
                    "Chunks cannot be null."
            );
        }

        if (margin < 0) {
            throw new IllegalArgumentException(
                    "Chunk margin cannot be negative."
            );
        }

        LinkedHashSet<ChunkPos> expandedChunks =
                new LinkedHashSet<>();

        for (ChunkPos chunkPos : chunks) {
            if (chunkPos == null) {
                throw new IllegalArgumentException(
                        "Chunks cannot contain null."
                );
            }

            for (int offsetX = -margin;
                 offsetX <= margin;
                 offsetX++) {

                for (int offsetZ = -margin;
                     offsetZ <= margin;
                     offsetZ++) {

                    expandedChunks.add(
                            new ChunkPos(
                                    chunkPos.x + offsetX,
                                    chunkPos.z + offsetZ
                            )
                    );
                }
            }
        }

        return expandedChunks;
    }

    private static void addChunksIntersectingBounds(
            Set<ChunkPos> chunks,
            SourceReservationArea.WorldBounds bounds
    ) {
        if (bounds == null) {
            throw new IllegalArgumentException(
                    "World bounds cannot be null."
            );
        }

        addChunksIntersectingBounds(
                chunks,
                bounds.minX(),
                bounds.maxX(),
                bounds.minZ(),
                bounds.maxZ()
        );
    }

    private static void addChunksIntersectingBounds(
            Set<ChunkPos> chunks,
            int minimumBlockX,
            int maximumBlockX,
            int minimumBlockZ,
            int maximumBlockZ
    ) {
        if (chunks == null) {
            throw new IllegalArgumentException(
                    "Chunk destination set cannot be null."
            );
        }

        if (minimumBlockX > maximumBlockX) {
            throw new IllegalArgumentException(
                    "Minimum block X cannot exceed maximum block X."
            );
        }

        if (minimumBlockZ > maximumBlockZ) {
            throw new IllegalArgumentException(
                    "Minimum block Z cannot exceed maximum block Z."
            );
        }

        int minimumChunkX =
                Math.floorDiv(
                        minimumBlockX,
                        16
                );

        int maximumChunkX =
                Math.floorDiv(
                        maximumBlockX,
                        16
                );

        int minimumChunkZ =
                Math.floorDiv(
                        minimumBlockZ,
                        16
                );

        int maximumChunkZ =
                Math.floorDiv(
                        maximumBlockZ,
                        16
                );

        for (int chunkX = minimumChunkX;
             chunkX <= maximumChunkX;
             chunkX++) {

            for (int chunkZ = minimumChunkZ;
                 chunkZ <= maximumChunkZ;
                 chunkZ++) {

                chunks.add(
                        new ChunkPos(
                                chunkX,
                                chunkZ
                        )
                );
            }
        }
    }
}