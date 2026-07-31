package org.ratden.skavenblight.event.skavenIncursion.planning.persistence.nbt;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.SourceGroupChunkLoadPlanSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Strict child codec for one physical source-group chunk-load snapshot.
 *
 * Schema versioning is owned by IncursionChunkLoadPlanSnapshotNbtCodec.
 */
public final class SourceGroupChunkLoadPlanSnapshotNbtCodec {

    private static final String SOURCE_GROUP_PLACEMENT_ID =
            "source_group_placement_id";

    private static final String FRONT_ID =
            "front_id";

    private static final String ANCHOR_POS =
            "anchor_pos";

    private static final String GROUP_FOOTPRINT_CHUNKS =
            "group_footprint_chunks";

    private static final String ROUTE_CORRIDOR_CHUNKS =
            "route_corridor_chunks";

    private static final String X =
            "x";

    private static final String Y =
            "y";

    private static final String Z =
            "z";

    private SourceGroupChunkLoadPlanSnapshotNbtCodec() {
    }

    public static CompoundTag write(
            SourceGroupChunkLoadPlanSnapshot snapshot
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Source-group chunk-load snapshot cannot be null."
            );
        }

        CompoundTag tag =
                new CompoundTag();

        tag.putUUID(
                SOURCE_GROUP_PLACEMENT_ID,
                snapshot.sourceGroupPlacementId()
        );

        tag.putUUID(
                FRONT_ID,
                snapshot.frontId()
        );

        tag.put(
                ANCHOR_POS,
                writeBlockPos(
                        snapshot.anchorPos()
                )
        );

        tag.put(
                GROUP_FOOTPRINT_CHUNKS,
                writeChunkList(
                        snapshot.groupFootprintChunks()
                )
        );

        tag.put(
                ROUTE_CORRIDOR_CHUNKS,
                writeChunkList(
                        snapshot.routeCorridorChunks()
                )
        );

        return tag;
    }

    public static SourceGroupChunkLoadPlanSnapshot read(
            CompoundTag tag
    ) {
        if (tag == null) {
            throw new IllegalArgumentException(
                    "Source-group chunk-load NBT cannot be null."
            );
        }

        SourceGroupChunkLoadPlanSnapshot snapshot =
                new SourceGroupChunkLoadPlanSnapshot(
                        IncursionSnapshotNbtSupport.requireUuid(
                                tag,
                                SOURCE_GROUP_PLACEMENT_ID
                        ),
                        IncursionSnapshotNbtSupport.requireUuid(
                                tag,
                                FRONT_ID
                        ),
                        readBlockPos(
                                IncursionSnapshotNbtSupport.requireCompound(
                                        tag,
                                        ANCHOR_POS
                                )
                        ),
                        readChunkList(
                                tag,
                                GROUP_FOOTPRINT_CHUNKS
                        ),
                        readChunkList(
                                tag,
                                ROUTE_CORRIDOR_CHUNKS
                        )
                );

        CompoundTag reconstructedTag =
                write(
                        snapshot
                );

        if (!tag.equals(
                reconstructedTag
        )) {
            throw new IllegalArgumentException(
                    "Source-group chunk-load NBT contains data that cannot be "
                            + "reconstructed exactly for physical group "
                            + snapshot.sourceGroupPlacementId()
                            + "."
            );
        }

        return snapshot;
    }

    private static ListTag writeChunkList(
            List<ChunkPos> chunks
    ) {
        if (chunks == null) {
            throw new IllegalArgumentException(
                    "Chunk list cannot be null."
            );
        }

        ListTag chunkTags =
                new ListTag();

        for (ChunkPos chunkPos : chunks) {
            if (chunkPos == null) {
                throw new IllegalArgumentException(
                        "Chunk list cannot contain null."
                );
            }

            CompoundTag chunkTag =
                    new CompoundTag();

            chunkTag.putInt(
                    X,
                    chunkPos.x
            );

            chunkTag.putInt(
                    Z,
                    chunkPos.z
            );

            chunkTags.add(
                    chunkTag
            );
        }

        return chunkTags;
    }

    private static List<ChunkPos> readChunkList(
            CompoundTag tag,
            String key
    ) {
        ListTag chunkTags =
                IncursionSnapshotNbtSupport.requireCompoundList(
                        tag,
                        key
                );

        List<ChunkPos> chunks =
                new ArrayList<>();

        for (int chunkIndex = 0;
             chunkIndex < chunkTags.size();
             chunkIndex++) {

            CompoundTag chunkTag =
                    chunkTags.getCompound(
                            chunkIndex
                    );

            try {
                chunks.add(
                        new ChunkPos(
                                IncursionSnapshotNbtSupport.requireInt(
                                        chunkTag,
                                        X
                                ),
                                IncursionSnapshotNbtSupport.requireInt(
                                        chunkTag,
                                        Z
                                )
                        )
                );
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "Chunk list '"
                                + key
                                + "' contains an invalid entry at index "
                                + chunkIndex
                                + ".",
                        exception
                );
            }
        }

        return List.copyOf(
                chunks
        );
    }

    private static CompoundTag writeBlockPos(
            BlockPos blockPos
    ) {
        if (blockPos == null) {
            throw new IllegalArgumentException(
                    "Block position cannot be null."
            );
        }

        CompoundTag tag =
                new CompoundTag();

        tag.putInt(
                X,
                blockPos.getX()
        );

        tag.putInt(
                Y,
                blockPos.getY()
        );

        tag.putInt(
                Z,
                blockPos.getZ()
        );

        return tag;
    }

    private static BlockPos readBlockPos(
            CompoundTag tag
    ) {
        if (tag == null) {
            throw new IllegalArgumentException(
                    "Block-position NBT cannot be null."
            );
        }

        return new BlockPos(
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        X
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        Y
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        Z
                )
        );
    }
}