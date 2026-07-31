package org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.nbt;

import net.minecraft.nbt.CompoundTag;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.IncursionChunkLoadPlanSnapshot;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.IncursionPlanSnapshot;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.nbt.IncursionChunkLoadPlanSnapshotNbtCodec;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.nbt.IncursionPlanSnapshotNbtCodec;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.nbt.IncursionSnapshotNbtSupport;
import org.ratden.skavenblight.event.skavenIncursion.runtime.mob.IncursionMobTrackingState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.IncursionTargetSnapshot;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.PersistentIncursionPhase;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.PersistentIncursionSnapshot;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.PlannedScenarioRuntimeSnapshot;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.nbt.mob.IncursionMobTrackingStateSnapshotNbtCodec;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.nbt.scenario.PlannedScenarioRuntimeSnapshotNbtCodec;

/**
 * Strict versioned root codec for one complete persistent incursion record.
 *
 * Version 1 predates persisted chunk-load plans.
 *
 * Version 2 adds the exact immutable IncursionChunkLoadPlanSnapshot admitted
 * with the tactical plan.
 *
 * Version 3 adds persistent tracking for every successfully delivered
 * incursion mob, including the exact represented threat assigned by the
 * admitted plan.
 *
 * Version-1 records cannot be migrated faithfully because they did not
 * preserve the protected Warp Flux network geometry or the calculated
 * source-group and route footprints.
 *
 * Version-2 records cannot be migrated faithfully because they did not
 * preserve which individual mobs had successfully entered the world or the
 * represented threat assigned to those entities. Creating an empty tracking
 * snapshot would incorrectly classify already-delivered threat as neither
 * pending nor spawned.
 *
 * The surrounding SkavenIncursionSavedData codec may independently support
 * empty old containers so cleaned development worlds can be rewritten. This
 * root codec does not approximately migrate non-empty old incursion records.
 */
public final class PersistentIncursionSnapshotNbtCodec {

    private static final int CURRENT_FORMAT_VERSION =
            3;

    private static final String FORMAT_VERSION =
            "format_version";

    private static final String INCURSION_ID =
            "incursion_id";

    private static final String SCENARIO_ID =
            "scenario_id";

    private static final String STRATAGEM_ID =
            "stratagem_id";

    private static final String TARGET =
            "target";

    private static final String INCURSION_PLAN =
            "incursion_plan";

    private static final String CHUNK_LOAD_PLAN =
            "chunk_load_plan";

    private static final String SCENARIO_RUNTIME =
            "scenario_runtime";

    private static final String MOB_TRACKING =
            "mob_tracking";

    private static final String PHASE =
            "phase";

    private PersistentIncursionSnapshotNbtCodec() {
    }

    /**
     * Writes one complete persistent-incursion record using the current
     * format version.
     */
    public static CompoundTag write(
            PersistentIncursionSnapshot snapshot
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Persistent incursion snapshot cannot be null."
            );
        }

        CompoundTag tag =
                writeVersionThree(
                        snapshot
                );

        /*
         * Confirm that the complete root codec immediately reproduces the
         * exact immutable snapshot it was given.
         */
        PersistentIncursionSnapshot reconstructedSnapshot =
                read(
                        tag
                );

        if (!snapshot.equals(
                reconstructedSnapshot
        )) {
            throw new IllegalStateException(
                    "Persistent incursion NBT encoding did not produce an "
                            + "exact snapshot round trip for incursion "
                            + snapshot.incursionId()
                            + "."
            );
        }

        return tag;
    }

    /**
     * Reads one complete persistent-incursion record.
     *
     * Unsupported and incomplete historical formats are rejected explicitly.
     * Approximate migration is not allowed at this authoritative persistence
     * boundary.
     */
    public static PersistentIncursionSnapshot read(
            CompoundTag tag
    ) {
        if (tag == null) {
            throw new IllegalArgumentException(
                    "Persistent incursion NBT cannot be null."
            );
        }

        int formatVersion =
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        FORMAT_VERSION
                );

        return switch (formatVersion) {
            case 3 -> readVersionThree(
                    tag
            );

            case 2 -> throw new IllegalArgumentException(
                    "Persistent incursion format version 2 cannot be "
                            + "restored because it predates authoritative "
                            + "tracking of successfully delivered mobs and "
                            + "their represented threat. Clean up old test "
                            + "incursions before loading this version."
            );

            case 1 -> throw new IllegalArgumentException(
                    "Persistent incursion format version 1 cannot be "
                            + "restored because it predates persisted "
                            + "chunk-load plans. Clean up old test incursions "
                            + "before loading this version."
            );

            default -> throw new IllegalArgumentException(
                    "Unsupported persistent incursion format version "
                            + formatVersion
                            + ". Current supported version is "
                            + CURRENT_FORMAT_VERSION
                            + "."
            );
        };
    }

    public static int getCurrentFormatVersion() {
        return CURRENT_FORMAT_VERSION;
    }

    private static CompoundTag writeVersionThree(
            PersistentIncursionSnapshot snapshot
    ) {
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

        tag.putString(
                SCENARIO_ID,
                snapshot.scenarioId()
        );

        tag.putString(
                STRATAGEM_ID,
                snapshot.stratagemId()
        );

        tag.put(
                TARGET,
                IncursionTargetSnapshotNbtCodec.write(
                        snapshot.targetSnapshot()
                )
        );

        tag.put(
                INCURSION_PLAN,
                IncursionPlanSnapshotNbtCodec.write(
                        snapshot.incursionPlanSnapshot()
                )
        );

        tag.put(
                CHUNK_LOAD_PLAN,
                IncursionChunkLoadPlanSnapshotNbtCodec.write(
                        snapshot.chunkLoadPlanSnapshot()
                )
        );

        tag.put(
                SCENARIO_RUNTIME,
                PlannedScenarioRuntimeSnapshotNbtCodec.write(
                        snapshot.scenarioRuntimeSnapshot()
                )
        );

        tag.put(
                MOB_TRACKING,
                IncursionMobTrackingStateSnapshotNbtCodec.write(
                        snapshot.mobTrackingSnapshot()
                )
        );

        tag.putString(
                PHASE,
                snapshot.phase().name()
        );

        return tag;
    }

    private static PersistentIncursionSnapshot readVersionThree(
            CompoundTag tag
    ) {
        IncursionTargetSnapshot targetSnapshot =
                readTargetSnapshot(
                        tag
                );

        IncursionPlanSnapshot incursionPlanSnapshot =
                readIncursionPlanSnapshot(
                        tag
                );

        IncursionChunkLoadPlanSnapshot chunkLoadPlanSnapshot =
                readChunkLoadPlanSnapshot(
                        tag
                );

        PlannedScenarioRuntimeSnapshot scenarioRuntimeSnapshot =
                readScenarioRuntimeSnapshot(
                        tag
                );

        IncursionMobTrackingState.Snapshot mobTrackingSnapshot =
                readMobTrackingSnapshot(
                        tag
                );

        return new PersistentIncursionSnapshot(
                IncursionSnapshotNbtSupport.requireUuid(
                        tag,
                        INCURSION_ID
                ),
                IncursionSnapshotNbtSupport.requireString(
                        tag,
                        SCENARIO_ID
                ),
                IncursionSnapshotNbtSupport.requireString(
                        tag,
                        STRATAGEM_ID
                ),
                targetSnapshot,
                incursionPlanSnapshot,
                chunkLoadPlanSnapshot,
                scenarioRuntimeSnapshot,
                mobTrackingSnapshot,
                IncursionSnapshotNbtSupport.requireEnum(
                        tag,
                        PHASE,
                        PersistentIncursionPhase.class
                )
        );
    }

    private static IncursionTargetSnapshot readTargetSnapshot(
            CompoundTag tag
    ) {
        CompoundTag targetTag =
                IncursionSnapshotNbtSupport.requireCompound(
                        tag,
                        TARGET
                );

        try {
            return IncursionTargetSnapshotNbtCodec.read(
                    targetTag
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Failed to read persistent incursion target snapshot.",
                    exception
            );
        }
    }

    private static IncursionPlanSnapshot readIncursionPlanSnapshot(
            CompoundTag tag
    ) {
        CompoundTag planTag =
                IncursionSnapshotNbtSupport.requireCompound(
                        tag,
                        INCURSION_PLAN
                );

        try {
            return IncursionPlanSnapshotNbtCodec.read(
                    planTag
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Failed to read persistent immutable IncursionPlan "
                            + "snapshot.",
                    exception
            );
        }
    }

    private static IncursionChunkLoadPlanSnapshot
    readChunkLoadPlanSnapshot(
            CompoundTag tag
    ) {
        CompoundTag chunkLoadPlanTag =
                IncursionSnapshotNbtSupport.requireCompound(
                        tag,
                        CHUNK_LOAD_PLAN
                );

        try {
            return IncursionChunkLoadPlanSnapshotNbtCodec.read(
                    chunkLoadPlanTag
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Failed to read persistent immutable chunk-load plan "
                            + "snapshot.",
                    exception
            );
        }
    }

    private static PlannedScenarioRuntimeSnapshot
    readScenarioRuntimeSnapshot(
            CompoundTag tag
    ) {
        CompoundTag runtimeTag =
                IncursionSnapshotNbtSupport.requireCompound(
                        tag,
                        SCENARIO_RUNTIME
                );

        try {
            return PlannedScenarioRuntimeSnapshotNbtCodec.read(
                    runtimeTag
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Failed to read persistent planned-Scenario runtime "
                            + "snapshot.",
                    exception
            );
        }
    }

    private static IncursionMobTrackingState.Snapshot
    readMobTrackingSnapshot(
            CompoundTag tag
    ) {
        CompoundTag mobTrackingTag =
                IncursionSnapshotNbtSupport.requireCompound(
                        tag,
                        MOB_TRACKING
                );

        try {
            return IncursionMobTrackingStateSnapshotNbtCodec.read(
                    mobTrackingTag
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Failed to read persistent incursion mob-tracking "
                            + "snapshot.",
                    exception
            );
        }
    }
}