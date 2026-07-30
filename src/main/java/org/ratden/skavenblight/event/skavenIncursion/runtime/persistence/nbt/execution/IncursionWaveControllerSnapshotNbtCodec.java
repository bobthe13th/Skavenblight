package org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.nbt.execution;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.nbt.IncursionSnapshotNbtSupport;
import org.ratden.skavenblight.event.skavenIncursion.runtime.IncursionWaveController;
import org.ratden.skavenblight.event.skavenIncursion.runtime.WaveExecutionState;

import java.util.ArrayList;
import java.util.List;

/**
 * CompoundTag codec for IncursionWaveController.Snapshot.
 *
 * The wave controller owns progression through the complete ordered wave
 * schedule. This codec preserves:
 *
 * - incursion identity;
 * - canonical ordered wave indexes;
 * - source-creation timing;
 * - first-spawn timing;
 * - spawn interval;
 * - inter-wave transition grace;
 * - current position in the ordered wave list;
 * - completed-wave count;
 * - completion reason;
 * - optional current-wave execution state;
 * - optional last-completed-wave execution state.
 *
 * Current and last-completed wave snapshots deliberately duplicate
 * source-assignment progress already stored in
 * IncursionExecutionState.Snapshot. Restoration later compares those copies
 * and rejects inconsistent persisted runtime state.
 *
 * Schema-version handling belongs to the eventual top-level persistent
 * incursion codec.
 */
public final class IncursionWaveControllerSnapshotNbtCodec {

    private static final String INCURSION_ID =
            "incursion_id";

    private static final String WAVE_INDEXES =
            "wave_indexes";

    private static final String SOURCE_CREATION_DELAY_TICKS =
            "source_creation_delay_ticks";

    private static final String FIRST_SPAWN_DELAY_TICKS =
            "first_spawn_delay_ticks";

    private static final String SPAWN_INTERVAL_TICKS =
            "spawn_interval_ticks";

    private static final String WAVE_TRANSITION_GRACE_TICKS =
            "wave_transition_grace_ticks";

    private static final String CURRENT_WAVE_POSITION =
            "current_wave_position";

    private static final String COMPLETED_WAVE_COUNT =
            "completed_wave_count";

    private static final String COMPLETION_REASON =
            "completion_reason";

    private static final String CURRENT_WAVE_EXECUTION =
            "current_wave_execution";

    private static final String LAST_COMPLETED_WAVE_EXECUTION =
            "last_completed_wave_execution";

    private IncursionWaveControllerSnapshotNbtCodec() {
    }

    /**
     * Writes one exact wave-controller snapshot.
     */
    public static CompoundTag write(
            IncursionWaveController.Snapshot snapshot
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Incursion wave-controller snapshot cannot be null."
            );
        }

        CompoundTag tag =
                new CompoundTag();

        tag.putUUID(
                INCURSION_ID,
                snapshot.incursionId()
        );

        putWaveIndexes(
                tag,
                snapshot.waveIndexes()
        );

        tag.putInt(
                SOURCE_CREATION_DELAY_TICKS,
                snapshot.sourceCreationDelayTicks()
        );

        tag.putInt(
                FIRST_SPAWN_DELAY_TICKS,
                snapshot.firstSpawnDelayTicks()
        );

        tag.putInt(
                SPAWN_INTERVAL_TICKS,
                snapshot.spawnIntervalTicks()
        );

        tag.putInt(
                WAVE_TRANSITION_GRACE_TICKS,
                snapshot.waveTransitionGraceTicks()
        );

        tag.putInt(
                CURRENT_WAVE_POSITION,
                snapshot.currentWavePosition()
        );

        tag.putInt(
                COMPLETED_WAVE_COUNT,
                snapshot.completedWaveCount()
        );

        tag.putString(
                COMPLETION_REASON,
                snapshot.completionReason().name()
        );

        WaveExecutionState.Snapshot currentWaveSnapshot =
                snapshot.currentWaveExecutionSnapshot();

        if (currentWaveSnapshot != null) {
            tag.put(
                    CURRENT_WAVE_EXECUTION,
                    WaveExecutionStateSnapshotNbtCodec.write(
                            currentWaveSnapshot
                    )
            );
        }

        WaveExecutionState.Snapshot lastCompletedWaveSnapshot =
                snapshot.lastCompletedWaveExecutionSnapshot();

        if (lastCompletedWaveSnapshot != null) {
            tag.put(
                    LAST_COMPLETED_WAVE_EXECUTION,
                    WaveExecutionStateSnapshotNbtCodec.write(
                            lastCompletedWaveSnapshot
                    )
            );
        }

        /*
         * Confirm that this codec can immediately reproduce the exact
         * immutable snapshot it was given.
         */
        IncursionWaveController.Snapshot reconstructedSnapshot =
                read(
                        tag
                );

        if (!snapshot.equals(
                reconstructedSnapshot
        )) {
            throw new IllegalStateException(
                    "Wave-controller NBT encoding did not produce an exact "
                            + "snapshot round trip for incursion "
                            + snapshot.incursionId()
                            + "."
            );
        }

        return tag;
    }

    /**
     * Reads and validates one exact wave-controller snapshot.
     */
    public static IncursionWaveController.Snapshot read(
            CompoundTag tag
    ) {
        if (tag == null) {
            throw new IllegalArgumentException(
                    "Incursion wave-controller NBT cannot be null."
            );
        }

        WaveExecutionState.Snapshot currentWaveSnapshot =
                readOptionalWaveSnapshot(
                        tag,
                        CURRENT_WAVE_EXECUTION
                );

        WaveExecutionState.Snapshot lastCompletedWaveSnapshot =
                readOptionalWaveSnapshot(
                        tag,
                        LAST_COMPLETED_WAVE_EXECUTION
                );

        return new IncursionWaveController.Snapshot(
                IncursionSnapshotNbtSupport.requireUuid(
                        tag,
                        INCURSION_ID
                ),
                readWaveIndexes(
                        tag
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        SOURCE_CREATION_DELAY_TICKS
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        FIRST_SPAWN_DELAY_TICKS
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        SPAWN_INTERVAL_TICKS
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        WAVE_TRANSITION_GRACE_TICKS
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        CURRENT_WAVE_POSITION
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        COMPLETED_WAVE_COUNT
                ),
                IncursionSnapshotNbtSupport.requireEnum(
                        tag,
                        COMPLETION_REASON,
                        IncursionWaveController
                                .CompletionReason.class
                ),
                currentWaveSnapshot,
                lastCompletedWaveSnapshot
        );
    }

    /**
     * Stores canonical wave indexes as an integer array.
     *
     * Wave indexes are ordered scalar values rather than independent compound
     * objects, so an integer-array tag is a direct representation.
     */
    private static void putWaveIndexes(
            CompoundTag tag,
            List<Integer> waveIndexes
    ) {
        if (tag == null) {
            throw new IllegalArgumentException(
                    "Wave-index destination tag cannot be null."
            );
        }

        if (waveIndexes == null
                || waveIndexes.isEmpty()) {

            throw new IllegalArgumentException(
                    "Wave-controller snapshot requires at least one wave "
                            + "index."
            );
        }

        int[] storedWaveIndexes =
                new int[waveIndexes.size()];

        for (int index = 0;
             index < waveIndexes.size();
             index++) {

            Integer waveIndex =
                    waveIndexes.get(
                            index
                    );

            if (waveIndex == null) {
                throw new IllegalArgumentException(
                        "Wave-index list cannot contain null."
                );
            }

            if (waveIndex < 0) {
                throw new IllegalArgumentException(
                        "Wave index cannot be negative."
                );
            }

            storedWaveIndexes[index] =
                    waveIndex;
        }

        tag.putIntArray(
                WAVE_INDEXES,
                storedWaveIndexes
        );
    }

    private static List<Integer> readWaveIndexes(
            CompoundTag tag
    ) {
        if (!tag.contains(
                WAVE_INDEXES,
                Tag.TAG_INT_ARRAY
        )) {
            throw new IllegalArgumentException(
                    "NBT field '"
                            + WAVE_INDEXES
                            + "' is missing or is not a valid integer array."
            );
        }

        int[] storedWaveIndexes =
                tag.getIntArray(
                        WAVE_INDEXES
                );

        if (storedWaveIndexes.length == 0) {
            throw new IllegalArgumentException(
                    "Wave-controller NBT requires at least one saved wave "
                            + "index."
            );
        }

        List<Integer> waveIndexes =
                new ArrayList<>();

        for (int storedWaveIndex
                : storedWaveIndexes) {

            waveIndexes.add(
                    storedWaveIndex
            );
        }

        return List.copyOf(
                waveIndexes
        );
    }

    private static WaveExecutionState.Snapshot
    readOptionalWaveSnapshot(
            CompoundTag tag,
            String key
    ) {
        CompoundTag waveTag =
                IncursionSnapshotNbtSupport.readOptionalCompound(
                        tag,
                        key
                );

        if (waveTag == null) {
            return null;
        }

        try {
            return WaveExecutionStateSnapshotNbtCodec.read(
                    waveTag
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Failed to read optional wave-execution snapshot '"
                            + key
                            + "'.",
                    exception
            );
        }
    }
}