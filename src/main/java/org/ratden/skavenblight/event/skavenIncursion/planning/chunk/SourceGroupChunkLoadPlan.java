package org.ratden.skavenblight.event.skavenIncursion.planning.chunk;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable chunk-footprint result for one persistent physical source group.
 *
 * The group footprint contains the complete spatial area needed to preserve
 * the group's sources, reservations, preparation areas, anchor and immediate
 * movement margin.
 *
 * The route corridor connects the source group to the defended target area.
 * The activation footprint is the union of the group footprint and route
 * corridor. Runtime ticket management will later promote that complete union
 * atomically before any source in the group may activate.
 *
 * This object describes required chunks only. It does not acquire tickets,
 * inspect chunk readiness or execute a source group.
 */
public class SourceGroupChunkLoadPlan {

    private final UUID sourceGroupPlacementId;
    private final UUID frontId;
    private final BlockPos anchorPos;

    private final Set<ChunkPos> groupFootprintChunks;
    private final Set<ChunkPos> routeCorridorChunks;
    private final Set<ChunkPos> activationChunks;

    public SourceGroupChunkLoadPlan(
            UUID sourceGroupPlacementId,
            UUID frontId,
            BlockPos anchorPos,
            Set<ChunkPos> groupFootprintChunks,
            Set<ChunkPos> routeCorridorChunks
    ) {
        if (sourceGroupPlacementId == null) {
            throw new IllegalArgumentException(
                    "Source-group placement ID cannot be null."
            );
        }

        if (frontId == null) {
            throw new IllegalArgumentException(
                    "Front ID cannot be null."
            );
        }

        if (anchorPos == null) {
            throw new IllegalArgumentException(
                    "Source-group anchor position cannot be null."
            );
        }

        this.sourceGroupPlacementId =
                sourceGroupPlacementId;

        this.frontId =
                frontId;

        this.anchorPos =
                anchorPos.immutable();

        this.groupFootprintChunks =
                immutableChunkSet(
                        groupFootprintChunks,
                        "Source-group footprint chunks"
                );

        this.routeCorridorChunks =
                immutableChunkSet(
                        routeCorridorChunks,
                        "Route-corridor chunks"
                );

        if (this.groupFootprintChunks.isEmpty()) {
            throw new IllegalArgumentException(
                    "Source-group footprint cannot be empty."
            );
        }

        if (!this.groupFootprintChunks.contains(
                new ChunkPos(this.anchorPos)
        )) {
            throw new IllegalArgumentException(
                    "Source-group footprint must contain its anchor chunk."
            );
        }

        LinkedHashSet<ChunkPos> activationChunks =
                new LinkedHashSet<>(
                        this.groupFootprintChunks
                );

        activationChunks.addAll(
                this.routeCorridorChunks
        );

        this.activationChunks =
                Collections.unmodifiableSet(
                        activationChunks
                );
    }

    public UUID getSourceGroupPlacementId() {
        return sourceGroupPlacementId;
    }

    public UUID getFrontId() {
        return frontId;
    }

    public BlockPos getAnchorPos() {
        return anchorPos;
    }

    public ChunkPos getAnchorChunk() {
        return new ChunkPos(anchorPos);
    }

    public Set<ChunkPos> getGroupFootprintChunks() {
        return groupFootprintChunks;
    }

    public Set<ChunkPos> getRouteCorridorChunks() {
        return routeCorridorChunks;
    }

    public Set<ChunkPos> getActivationChunks() {
        return activationChunks;
    }

    public int getGroupFootprintChunkCount() {
        return groupFootprintChunks.size();
    }

    public int getRouteCorridorChunkCount() {
        return routeCorridorChunks.size();
    }

    public int getActivationChunkCount() {
        return activationChunks.size();
    }

    public boolean requiresChunk(
            ChunkPos chunkPos
    ) {
        return chunkPos != null
                && activationChunks.contains(
                chunkPos
        );
    }

    private static Set<ChunkPos> immutableChunkSet(
            Set<ChunkPos> chunks,
            String description
    ) {
        if (chunks == null) {
            throw new IllegalArgumentException(
                    description + " cannot be null."
            );
        }

        LinkedHashSet<ChunkPos> copiedChunks =
                new LinkedHashSet<>();

        for (ChunkPos chunkPos : chunks) {
            if (chunkPos == null) {
                throw new IllegalArgumentException(
                        description + " cannot contain null."
                );
            }

            copiedChunks.add(chunkPos);
        }

        return Collections.unmodifiableSet(
                copiedChunks
        );
    }
}