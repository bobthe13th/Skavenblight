package org.ratden.skavenblight.event.skavenIncursion.planning.persistence.nbt;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.FrontPlanSnapshot;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.IncursionPlanSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Versioned root CompoundTag codec for one complete immutable IncursionPlan.
 *
 * This codec owns the schema version for the complete immutable planning
 * snapshot tree:
 *
 * IncursionPlanSnapshot
 * -> FrontPlanSnapshot
 * -> WavePlanSnapshot
 * -> SourceGroupCompositionSnapshot
 * -> SourceCompositionSnapshot
 *
 * and:
 *
 * FrontPlanSnapshot
 * -> SourceGroupPlacementSnapshot
 * -> SourcePlacementSnapshot
 *
 * Child codecs intentionally do not carry their own independent version
 * numbers. Any future change to one part of the immutable planning schema
 * increments this root schema version and is migrated from here.
 *
 * The codec preserves:
 *
 * - every structural UUID;
 * - canonical front order;
 * - canonical wave order;
 * - composition order;
 * - physical source-group order;
 * - physical source order;
 * - all source-composition bindings;
 * - all authored budgets, shares, rules and physical placement data.
 *
 * Writing performs an immediate semantic round-trip check through the same
 * version-specific reader. This prevents the codec from emitting NBT that it
 * cannot reconstruct exactly.
 */
public final class IncursionPlanSnapshotNbtCodec {

    /**
     * First complete immutable IncursionPlan persistence schema.
     */
    public static final int CURRENT_SCHEMA_VERSION =
            1;

    private static final String SCHEMA_VERSION =
            "schema_version";

    private static final String INCURSION_ID =
            "incursion_id";

    private static final String FRONT_PLANS =
            "front_plans";

    private IncursionPlanSnapshotNbtCodec() {
    }

    /**
     * Captures and writes one complete immutable IncursionPlan.
     */
    public static CompoundTag writePlan(
            IncursionPlan incursionPlan
    ) {
        if (incursionPlan == null) {
            throw new IllegalArgumentException(
                    "Incursion plan cannot be null."
            );
        }

        return write(
                IncursionPlanSnapshot.capture(
                        incursionPlan
                )
        );
    }

    /**
     * Writes one complete immutable planning snapshot.
     *
     * The generated tag is immediately read through the current schema reader
     * and compared with the supplied snapshot. A codec implementation error is
     * therefore detected before malformed planning data reaches SavedData.
     */
    public static CompoundTag write(
            IncursionPlanSnapshot snapshot
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Incursion-plan snapshot cannot be null."
            );
        }

        CompoundTag tag =
                writeCurrentSchema(
                        snapshot
                );

        IncursionPlanSnapshot reconstructedSnapshot =
                readVersionOne(
                        tag
                );

        if (!snapshot.equals(
                reconstructedSnapshot
        )) {
            throw new IllegalStateException(
                    "Incursion-plan NBT encoding did not produce an exact "
                            + "snapshot round trip for incursion "
                            + snapshot.incursionId()
                            + "."
            );
        }

        return tag;
    }

    /**
     * Reads one versioned immutable planning snapshot.
     *
     * Unknown versions are rejected. Future migration logic should be added
     * as another explicit version branch rather than weakening the current
     * schema reader with guessed defaults.
     */
    public static IncursionPlanSnapshot read(
            CompoundTag tag
    ) {
        if (tag == null) {
            throw new IllegalArgumentException(
                    "Incursion-plan NBT cannot be null."
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
                            "Unsupported incursion-plan schema version "
                                    + schemaVersion
                                    + ". Current supported version is "
                                    + CURRENT_SCHEMA_VERSION
                                    + "."
                    );
        };
    }

    /**
     * Reads and restores one complete immutable IncursionPlan.
     */
    public static IncursionPlan readPlan(
            CompoundTag tag
    ) {
        return read(
                tag
        ).restore();
    }

    private static CompoundTag writeCurrentSchema(
            IncursionPlanSnapshot snapshot
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

        ListTag frontPlanTags =
                new ListTag();

        for (FrontPlanSnapshot frontPlanSnapshot
                : snapshot.frontPlanSnapshots()) {

            frontPlanTags.add(
                    FrontPlanSnapshotNbtCodec.write(
                            frontPlanSnapshot
                    )
            );
        }

        tag.put(
                FRONT_PLANS,
                frontPlanTags
        );

        return tag;
    }

    /**
     * Reads schema version one.
     *
     * This method assumes the caller has already selected version one, but it
     * remains usable by write(...) for its immediate codec round-trip check.
     */
    private static IncursionPlanSnapshot readVersionOne(
            CompoundTag tag
    ) {
        ListTag frontPlanTags =
                IncursionSnapshotNbtSupport.requireCompoundList(
                        tag,
                        FRONT_PLANS
                );

        List<FrontPlanSnapshot> frontPlanSnapshots =
                new ArrayList<>();

        for (int frontPosition = 0;
             frontPosition < frontPlanTags.size();
             frontPosition++) {

            try {
                frontPlanSnapshots.add(
                        FrontPlanSnapshotNbtCodec.read(
                                frontPlanTags.getCompound(
                                        frontPosition
                                )
                        )
                );
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "Failed to read front plan at canonical position "
                                + frontPosition
                                + ".",
                        exception
                );
            }
        }

        IncursionPlanSnapshot snapshot =
                new IncursionPlanSnapshot(
                        IncursionSnapshotNbtSupport.requireUuid(
                                tag,
                                INCURSION_ID
                        ),
                        List.copyOf(
                                frontPlanSnapshots
                        )
                );

        /*
         * Restore and recapture the complete immutable object graph now.
         *
         * Snapshot constructors validate the persisted values and
         * relationships directly. This additional round trip confirms that
         * the live planning classes can also reconstruct every saved identity,
         * composition binding and physical placement without altering them.
         */
        IncursionPlan restoredPlan =
                snapshot.restore();

        IncursionPlanSnapshot recapturedSnapshot =
                IncursionPlanSnapshot.capture(
                        restoredPlan
                );

        if (!snapshot.equals(
                recapturedSnapshot
        )) {
            throw new IllegalArgumentException(
                    "Incursion-plan snapshot "
                            + snapshot.incursionId()
                            + " cannot be restored into an exact immutable "
                            + "planning graph."
            );
        }

        return snapshot;
    }
}