package org.ratden.skavenblight.event.skavenIncursion.planning.persistence;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.event.skavenIncursion.planning.chunk.SourceGroupChunkLoadPlan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable persistence-facing snapshot of one physical source group's
 * calculated chunk requirements.
 *
 * This snapshot preserves:
 *
 * - stable physical source-group placement identity;
 * - owning front identity;
 * - source-group anchor position;
 * - complete source-group footprint;
 * - complete route-corridor footprint.
 *
 * The activation footprint is not persisted separately because it is the
 * deterministic union of the group footprint and route corridor.
 *
 * Chunk positions are stored in canonical X/Z order. This makes the saved
 * representation stable even though the live planning model exposes sets.
 */
public record SourceGroupChunkLoadPlanSnapshot(
        UUID sourceGroupPlacementId,
        UUID frontId,
        BlockPos anchorPos,
        List<ChunkPos> groupFootprintChunks,
        List<ChunkPos> routeCorridorChunks
) {

    public SourceGroupChunkLoadPlanSnapshot {
        if (sourceGroupPlacementId == null) {
            throw new IllegalArgumentException(
                    "Source-group chunk snapshot placement ID cannot be null."
            );
        }

        if (frontId == null) {
            throw new IllegalArgumentException(
                    "Source-group chunk snapshot front ID cannot be null."
            );
        }

        if (anchorPos == null) {
            throw new IllegalArgumentException(
                    "Source-group chunk snapshot anchor position cannot be "
                            + "null."
            );
        }

        anchorPos =
                anchorPos.immutable();

        groupFootprintChunks =
                canonicalChunkList(
                        groupFootprintChunks,
                        "Source-group footprint chunks",
                        true
                );

        routeCorridorChunks =
                canonicalChunkList(
                        routeCorridorChunks,
                        "Source-group route-corridor chunks",
                        false
                );

        ChunkPos anchorChunk =
                new ChunkPos(
                        anchorPos
                );

        if (!groupFootprintChunks.contains(
                anchorChunk
        )) {
            throw new IllegalArgumentException(
                    "Source-group chunk snapshot footprint must contain its "
                            + "anchor chunk "
                            + anchorChunk.x
                            + ", "
                            + anchorChunk.z
                            + "."
            );
        }
    }

    /**
     * Captures one calculated physical source-group chunk plan.
     */
    public static SourceGroupChunkLoadPlanSnapshot capture(
            SourceGroupChunkLoadPlan chunkLoadPlan
    ) {
        if (chunkLoadPlan == null) {
            throw new IllegalArgumentException(
                    "Source-group chunk-load plan cannot be null."
            );
        }

        return new SourceGroupChunkLoadPlanSnapshot(
                chunkLoadPlan.getSourceGroupPlacementId(),
                chunkLoadPlan.getFrontId(),
                chunkLoadPlan.getAnchorPos(),
                new ArrayList<>(
                        chunkLoadPlan.getGroupFootprintChunks()
                ),
                new ArrayList<>(
                        chunkLoadPlan.getRouteCorridorChunks()
                )
        );
    }

    /**
     * Restores the immutable calculated source-group chunk plan.
     */
    public SourceGroupChunkLoadPlan restore() {
        SourceGroupChunkLoadPlan restoredPlan =
                new SourceGroupChunkLoadPlan(
                        sourceGroupPlacementId,
                        frontId,
                        anchorPos,
                        new LinkedHashSet<>(
                                groupFootprintChunks
                        ),
                        new LinkedHashSet<>(
                                routeCorridorChunks
                        )
                );

        SourceGroupChunkLoadPlanSnapshot reconstructedSnapshot =
                capture(
                        restoredPlan
                );

        if (!equals(
                reconstructedSnapshot
        )) {
            throw new IllegalArgumentException(
                    "Restored source-group chunk-load plan "
                            + sourceGroupPlacementId
                            + " does not exactly match its saved snapshot."
            );
        }

        return restoredPlan;
    }

    public int getGroupFootprintChunkCount() {
        return groupFootprintChunks.size();
    }

    public int getRouteCorridorChunkCount() {
        return routeCorridorChunks.size();
    }

    public int getActivationChunkCount() {
        Set<ChunkPos> activationChunks =
                new LinkedHashSet<>(
                        groupFootprintChunks
                );

        activationChunks.addAll(
                routeCorridorChunks
        );

        return activationChunks.size();
    }

    private static List<ChunkPos> canonicalChunkList(
            List<ChunkPos> chunks,
            String description,
            boolean requireNonEmpty
    ) {
        if (chunks == null) {
            throw new IllegalArgumentException(
                    description + " cannot be null."
            );
        }

        if (requireNonEmpty
                && chunks.isEmpty()) {

            throw new IllegalArgumentException(
                    description + " cannot be empty."
            );
        }

        ArrayList<ChunkPos> copiedChunks =
                new ArrayList<>();

        Set<ChunkPos> uniqueChunks =
                new LinkedHashSet<>();

        for (ChunkPos chunkPos : chunks) {
            if (chunkPos == null) {
                throw new IllegalArgumentException(
                        description + " cannot contain null."
                );
            }

            ChunkPos copiedChunk =
                    new ChunkPos(
                            chunkPos.x,
                            chunkPos.z
                    );

            if (!uniqueChunks.add(
                    copiedChunk
            )) {
                throw new IllegalArgumentException(
                        description
                                + " contains duplicate chunk "
                                + copiedChunk.x
                                + ", "
                                + copiedChunk.z
                                + "."
                );
            }

            copiedChunks.add(
                    copiedChunk
            );
        }

        copiedChunks.sort(
                Comparator
                        .comparingInt(
                                (ChunkPos chunkPos) ->
                                        chunkPos.x
                        )
                        .thenComparingInt(
                                chunkPos ->
                                        chunkPos.z
                        )
        );

        return List.copyOf(
                copiedChunks
        );
    }
}