package org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.nbt;

import net.minecraft.nbt.CompoundTag;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.IncursionPlanSnapshot;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.nbt.IncursionPlanSnapshotNbtCodec;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.nbt.IncursionSnapshotNbtSupport;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.IncursionTargetSnapshot;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.PersistentIncursionPhase;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.PersistentIncursionSnapshot;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.PlannedScenarioRuntimeSnapshot;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.nbt.scenario.PlannedScenarioRuntimeSnapshotNbtCodec;

/**
 * Versioned CompoundTag codec for one complete
 * PersistentIncursionSnapshot.
 *
 * This is the root codec for one admitted incursion. It joins:
 *
 * - root incursion identity;
 * - Scenario identity;
 * - selected Stratagem identity;
 * - positional target information;
 * - the complete immutable IncursionPlan;
 * - the complete mutable planned-Scenario runtime;
 * - the persistence-layer lifecycle phase.
 *
 * Nested snapshot codecs remain responsible for validating their own
 * structures. Construction of PersistentIncursionSnapshot then validates
 * identity and lifecycle consistency across those structures.
 *
 * The format version belongs here because this compound represents the
 * complete independently stored incursion record. Nested codecs currently
 * read the exact structures selected by this root format.
 */
public final class PersistentIncursionSnapshotNbtCodec {

    private static final int CURRENT_FORMAT_VERSION =
            1;

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

    private static final String SCENARIO_RUNTIME =
            "scenario_runtime";

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
                writeVersionOne(
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
     * Unsupported versions are rejected explicitly. Migration support can be
     * added here later without weakening the strict readers used by the
     * current schema.
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
            case 1 -> readVersionOne(
                    tag
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

    private static CompoundTag writeVersionOne(
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
                SCENARIO_RUNTIME,
                PlannedScenarioRuntimeSnapshotNbtCodec.write(
                        snapshot.scenarioRuntimeSnapshot()
                )
        );

        tag.putString(
                PHASE,
                snapshot.phase().name()
        );

        return tag;
    }

    private static PersistentIncursionSnapshot readVersionOne(
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

        PlannedScenarioRuntimeSnapshot scenarioRuntimeSnapshot =
                readScenarioRuntimeSnapshot(
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
                scenarioRuntimeSnapshot,
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
}