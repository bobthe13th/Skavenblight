package org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.nbt.mob;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.nbt.IncursionSnapshotNbtSupport;
import org.ratden.skavenblight.event.skavenIncursion.runtime.mob.IncursionMobTrackingState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Versioned CompoundTag codec for IncursionMobTrackingState.Snapshot.
 *
 * This codec preserves the persistent identity and represented threat of
 * every mob successfully delivered by one incursion.
 *
 * Pending mobs remain authoritative in their SourceSpawnQueue snapshots and
 * therefore do not appear here.
 *
 * Cancelled mobs also do not receive tracked-entity records because they
 * never entered the world.
 *
 * A tracked mob's ACTIVE resolution is persistent and is not changed merely
 * because its entity or chunk is temporarily unloaded.
 */
public final class IncursionMobTrackingStateSnapshotNbtCodec {

    private static final int CURRENT_FORMAT_VERSION =
            1;

    private static final String FORMAT_VERSION =
            "format_version";

    private static final String INCURSION_ID =
            "incursion_id";

    private static final String TRACKED_MOBS =
            "tracked_mobs";

    private static final String ENTITY_ID =
            "entity_id";

    private static final String MOB_ID =
            "mob_id";

    private static final String REPRESENTED_THREAT =
            "represented_threat";

    private static final String WAVE_INDEX =
            "wave_index";

    private static final String SOURCE_GROUP_COMPOSITION_ID =
            "source_group_composition_id";

    private static final String SOURCE_COMPOSITION_ID =
            "source_composition_id";

    private static final String SOURCE_PLACEMENT_ID =
            "source_placement_id";

    private static final String RUNTIME_SOURCE_ID =
            "runtime_source_id";

    private static final String ATTACHED_MOB_ASSIGNMENT_ID =
            "attached_mob_assignment_id";

    private static final String RESOLUTION =
            "resolution";

    private IncursionMobTrackingStateSnapshotNbtCodec() {
    }

    /**
     * Writes one complete mob-tracking snapshot.
     */
    public static CompoundTag write(
            IncursionMobTrackingState.Snapshot snapshot
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Incursion mob-tracking snapshot cannot be null."
            );
        }

        CompoundTag tag =
                new CompoundTag();

        tag.putInt(
                FORMAT_VERSION,
                CURRENT_FORMAT_VERSION
        );

        tag.putUUID(
                INCURSION_ID,
                snapshot.incursionId()
        );

        ListTag trackedMobTags =
                new ListTag();

        for (IncursionMobTrackingState.TrackedMobSnapshot trackedMob
                : snapshot.trackedMobs()) {

            trackedMobTags.add(
                    writeTrackedMob(
                            trackedMob
                    )
            );
        }

        tag.put(
                TRACKED_MOBS,
                trackedMobTags
        );

        return tag;
    }

    /**
     * Reads and validates one complete mob-tracking snapshot.
     */
    public static IncursionMobTrackingState.Snapshot read(
            CompoundTag tag
    ) {
        if (tag == null) {
            throw new IllegalArgumentException(
                    "Incursion mob-tracking NBT cannot be null."
            );
        }

        int formatVersion =
                readFormatVersion(
                        tag
                );

        if (formatVersion
                != CURRENT_FORMAT_VERSION) {

            throw unsupportedFormatVersion(
                    formatVersion
            );
        }

        UUID incursionId =
                IncursionSnapshotNbtSupport.requireUuid(
                        tag,
                        INCURSION_ID
                );

        ListTag trackedMobTags =
                IncursionSnapshotNbtSupport.requireCompoundList(
                        tag,
                        TRACKED_MOBS
                );

        List<IncursionMobTrackingState.TrackedMobSnapshot>
                trackedMobs =
                new ArrayList<>();

        for (int trackedMobIndex = 0;
             trackedMobIndex < trackedMobTags.size();
             trackedMobIndex++) {

            try {
                trackedMobs.add(
                        readTrackedMob(
                                trackedMobTags.getCompound(
                                        trackedMobIndex
                                )
                        )
                );
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "Failed to read tracked incursion mob at index "
                                + trackedMobIndex
                                + " for incursion "
                                + incursionId
                                + ".",
                        exception
                );
            }
        }

        return new IncursionMobTrackingState.Snapshot(
                incursionId,
                trackedMobs
        );
    }

    private static CompoundTag writeTrackedMob(
            IncursionMobTrackingState.TrackedMobSnapshot trackedMob
    ) {
        if (trackedMob == null) {
            throw new IllegalArgumentException(
                    "Tracked incursion mob snapshot cannot be null."
            );
        }

        CompoundTag tag =
                new CompoundTag();

        tag.putUUID(
                ENTITY_ID,
                trackedMob.entityId()
        );

        tag.putString(
                MOB_ID,
                trackedMob.mobId()
        );

        tag.putInt(
                REPRESENTED_THREAT,
                trackedMob.representedThreat()
        );

        tag.putInt(
                WAVE_INDEX,
                trackedMob.waveIndex()
        );

        tag.putUUID(
                SOURCE_GROUP_COMPOSITION_ID,
                trackedMob.sourceGroupCompositionId()
        );

        tag.putUUID(
                SOURCE_COMPOSITION_ID,
                trackedMob.sourceCompositionId()
        );

        tag.putUUID(
                SOURCE_PLACEMENT_ID,
                trackedMob.sourcePlacementId()
        );

        tag.putUUID(
                RUNTIME_SOURCE_ID,
                trackedMob.runtimeSourceId()
        );

        if (trackedMob.attachedMobAssignmentId() != null) {
            tag.putUUID(
                    ATTACHED_MOB_ASSIGNMENT_ID,
                    trackedMob.attachedMobAssignmentId()
            );
        }

        tag.putString(
                RESOLUTION,
                trackedMob.resolution().name()
        );

        return tag;
    }

    private static IncursionMobTrackingState.TrackedMobSnapshot
    readTrackedMob(
            CompoundTag tag
    ) {
        if (tag == null) {
            throw new IllegalArgumentException(
                    "Tracked incursion mob NBT cannot be null."
            );
        }

        return new IncursionMobTrackingState.TrackedMobSnapshot(
                IncursionSnapshotNbtSupport.requireUuid(
                        tag,
                        ENTITY_ID
                ),
                IncursionSnapshotNbtSupport.requireString(
                        tag,
                        MOB_ID
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        REPRESENTED_THREAT
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        WAVE_INDEX
                ),
                IncursionSnapshotNbtSupport.requireUuid(
                        tag,
                        SOURCE_GROUP_COMPOSITION_ID
                ),
                IncursionSnapshotNbtSupport.requireUuid(
                        tag,
                        SOURCE_COMPOSITION_ID
                ),
                IncursionSnapshotNbtSupport.requireUuid(
                        tag,
                        SOURCE_PLACEMENT_ID
                ),
                IncursionSnapshotNbtSupport.requireUuid(
                        tag,
                        RUNTIME_SOURCE_ID
                ),
                readOptionalUuid(
                        tag,
                        ATTACHED_MOB_ASSIGNMENT_ID
                ),
                IncursionSnapshotNbtSupport.requireEnum(
                        tag,
                        RESOLUTION,
                        IncursionMobTrackingState
                                .MobResolution.class
                )
        );
    }

    private static UUID readOptionalUuid(
            CompoundTag tag,
            String key
    ) {
        if (!tag.contains(
                key
        )) {
            return null;
        }

        if (!tag.hasUUID(
                key
        )) {
            throw new IllegalArgumentException(
                    "NBT field '"
                            + key
                            + "' is present but is not a valid UUID."
            );
        }

        return tag.getUUID(
                key
        );
    }

    private static int readFormatVersion(
            CompoundTag tag
    ) {
        if (!tag.contains(
                FORMAT_VERSION,
                Tag.TAG_INT
        )) {
            throw new IllegalArgumentException(
                    "Incursion mob-tracking NBT field '"
                            + FORMAT_VERSION
                            + "' is missing or is not a valid integer."
            );
        }

        int formatVersion =
                tag.getInt(
                        FORMAT_VERSION
                );

        if (formatVersion < 1
                || formatVersion > CURRENT_FORMAT_VERSION) {

            throw unsupportedFormatVersion(
                    formatVersion
            );
        }

        return formatVersion;
    }

    private static IllegalArgumentException unsupportedFormatVersion(
            int formatVersion
    ) {
        return new IllegalArgumentException(
                "Unsupported incursion mob-tracking format version "
                        + formatVersion
                        + ". Current supported version is "
                        + CURRENT_FORMAT_VERSION
                        + "."
        );
    }
}