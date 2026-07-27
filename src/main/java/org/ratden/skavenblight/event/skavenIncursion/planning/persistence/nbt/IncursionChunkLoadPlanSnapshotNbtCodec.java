package org.ratden.skavenblight.event.skavenIncursion.planning.persistence.nbt;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.IncursionChunkLoadPlanSnapshot;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.SourceGroupChunkLoadPlanSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Versioned root codec for one complete immutable calculated chunk-load plan.
 *
 * This codec preserves:
 *
 * - incursion identity;
 * - exact protected-base chunks;
 * - every physical source-group footprint;
 * - every route corridor;
 * - per-wave physical source-group requirements.
 *
 * Child source-group codecs deliberately carry no independent version.
 */
public final class IncursionChunkLoadPlanSnapshotNbtCodec {

    public static final int CURRENT_SCHEMA_VERSION =
            1;

    private static final String SCHEMA_VERSION =
            "schema_version";

    private static final String INCURSION_ID =
            "incursion_id";

    private static final String PROTECTED_BASE_CHUNKS =
            "protected_base_chunks";

    private static final String SOURCE_GROUP_PLANS =
            "source_group_plans";

    private static final String WAVE_REQUIREMENTS =
            "wave_requirements";

    private static final String WAVE_INDEX =
            "wave_index";

    private static final String SOURCE_GROUP_PLACEMENT_IDS =
            "source_group_placement_ids";

    private static final String X =
            "x";

    private static final String Z =
            "z";

    private IncursionChunkLoadPlanSnapshotNbtCodec() {
    }

    public static CompoundTag write(
            IncursionChunkLoadPlanSnapshot snapshot
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Incursion chunk-load snapshot cannot be null."
            );
        }

        CompoundTag tag =
                writeVersionOne(
                        snapshot
                );

        IncursionChunkLoadPlanSnapshot reconstructedSnapshot =
                readVersionOne(
                        tag
                );

        if (!snapshot.equals(
                reconstructedSnapshot
        )) {
            throw new IllegalStateException(
                    "Incursion chunk-load NBT encoding did not produce an "
                            + "exact snapshot round trip for incursion "
                            + snapshot.incursionId()
                            + "."
            );
        }

        return tag;
    }

    public static IncursionChunkLoadPlanSnapshot read(
            CompoundTag tag
    ) {
        if (tag == null) {
            throw new IllegalArgumentException(
                    "Incursion chunk-load NBT cannot be null."
            );
        }

        int schemaVersion =
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        SCHEMA_VERSION
                );

        return switch (schemaVersion) {
            case 1 ->
                    readVersionOne(
                            tag
                    );

            default ->
                    throw new IllegalArgumentException(
                            "Unsupported incursion chunk-load schema version "
                                    + schemaVersion
                                    + ". Current supported version is "
                                    + CURRENT_SCHEMA_VERSION
                                    + "."
                    );
        };
    }

    private static CompoundTag writeVersionOne(
            IncursionChunkLoadPlanSnapshot snapshot
    ) {
        CompoundTag tag =
                new CompoundTag();

        tag.putInt(
                SCHEMA_VERSION,
                CURRENT_SCHEMA_VERSION
        );

        tag.putUUID(
                INCURSION_ID,
                snapshot.incursionId()
        );

        tag.put(
                PROTECTED_BASE_CHUNKS,
                writeChunkList(
                        snapshot.protectedBaseChunks()
                )
        );

        ListTag sourceGroupTags =
                new ListTag();

        for (SourceGroupChunkLoadPlanSnapshot sourceGroupSnapshot
                : snapshot.sourceGroupPlanSnapshots()) {

            sourceGroupTags.add(
                    SourceGroupChunkLoadPlanSnapshotNbtCodec.write(
                            sourceGroupSnapshot
                    )
            );
        }

        tag.put(
                SOURCE_GROUP_PLANS,
                sourceGroupTags
        );

        ListTag waveRequirementTags =
                new ListTag();

        for (Map.Entry<Integer, List<UUID>> entry
                : snapshot
                .requiredSourceGroupPlacementIdsByWave()
                .entrySet()) {

            CompoundTag waveRequirementTag =
                    new CompoundTag();

            waveRequirementTag.putInt(
                    WAVE_INDEX,
                    entry.getKey()
            );

            IncursionSnapshotNbtSupport.putUuidList(
                    waveRequirementTag,
                    SOURCE_GROUP_PLACEMENT_IDS,
                    entry.getValue()
            );

            waveRequirementTags.add(
                    waveRequirementTag
            );
        }

        tag.put(
                WAVE_REQUIREMENTS,
                waveRequirementTags
        );

        return tag;
    }

    private static IncursionChunkLoadPlanSnapshot readVersionOne(
            CompoundTag tag
    ) {
        List<ChunkPos> protectedBaseChunks =
                readChunkList(
                        tag,
                        PROTECTED_BASE_CHUNKS
                );

        ListTag sourceGroupTags =
                IncursionSnapshotNbtSupport.requireCompoundList(
                        tag,
                        SOURCE_GROUP_PLANS
                );

        List<SourceGroupChunkLoadPlanSnapshot>
                sourceGroupSnapshots =
                new ArrayList<>();

        for (int groupIndex = 0;
             groupIndex < sourceGroupTags.size();
             groupIndex++) {

            try {
                sourceGroupSnapshots.add(
                        SourceGroupChunkLoadPlanSnapshotNbtCodec.read(
                                sourceGroupTags.getCompound(
                                        groupIndex
                                )
                        )
                );
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "Failed to read source-group chunk-load plan at "
                                + "canonical position "
                                + groupIndex
                                + ".",
                        exception
                );
            }
        }

        ListTag waveRequirementTags =
                IncursionSnapshotNbtSupport.requireCompoundList(
                        tag,
                        WAVE_REQUIREMENTS
                );

        Map<Integer, List<UUID>> waveRequirements =
                new LinkedHashMap<>();

        for (int requirementIndex = 0;
             requirementIndex < waveRequirementTags.size();
             requirementIndex++) {

            CompoundTag requirementTag =
                    waveRequirementTags.getCompound(
                            requirementIndex
                    );

            int waveIndex;

            List<UUID> sourceGroupIds;

            try {
                waveIndex =
                        IncursionSnapshotNbtSupport.requireInt(
                                requirementTag,
                                WAVE_INDEX
                        );

                sourceGroupIds =
                        IncursionSnapshotNbtSupport.readUuidList(
                                requirementTag,
                                SOURCE_GROUP_PLACEMENT_IDS
                        );
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "Failed to read chunk-load wave requirement at "
                                + "canonical position "
                                + requirementIndex
                                + ".",
                        exception
                );
            }

            List<UUID> previousRequirement =
                    waveRequirements.put(
                            waveIndex,
                            sourceGroupIds
                    );

            if (previousRequirement != null) {
                throw new IllegalArgumentException(
                        "Incursion chunk-load NBT contains duplicate wave "
                                + "requirement index "
                                + waveIndex
                                + "."
                );
            }
        }

        return new IncursionChunkLoadPlanSnapshot(
                IncursionSnapshotNbtSupport.requireUuid(
                        tag,
                        INCURSION_ID
                ),
                protectedBaseChunks,
                sourceGroupSnapshots,
                waveRequirements
        );
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
}