package org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.nbt.execution;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.nbt.IncursionSnapshotNbtSupport;
import org.ratden.skavenblight.event.skavenIncursion.runtime.WaveExecutionState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceWaveExecutionState;

import java.util.ArrayList;
import java.util.List;

/**
 * CompoundTag codec for WaveExecutionState.Snapshot.
 *
 * One WaveExecutionState controls the timing and aggregate progress of one
 * global incursion wave.
 *
 * This codec preserves:
 *
 * - wave identity;
 * - source-creation delay;
 * - first-spawn delay;
 * - spawn interval;
 * - elapsed wave ticks;
 * - the next scheduled spawn tick;
 * - spawn-schedule completion state;
 * - the tick on which scheduled delivery completed;
 * - aggregate successful-spawn count;
 * - aggregate cancelled-mob count;
 * - aggregate failed-spawn-attempt count;
 * - ordered source-wave execution snapshots.
 *
 * Source-wave snapshots deliberately duplicate assignment progress already
 * stored by IncursionExecutionState.Snapshot. Restoration later compares both
 * copies and rejects inconsistent persisted runtime state.
 *
 * Schema-version handling belongs to the eventual top-level persistent
 * incursion codec.
 */
public final class WaveExecutionStateSnapshotNbtCodec {

    private static final String WAVE_INDEX =
            "wave_index";

    private static final String SOURCE_CREATION_DELAY_TICKS =
            "source_creation_delay_ticks";

    private static final String FIRST_SPAWN_DELAY_TICKS =
            "first_spawn_delay_ticks";

    private static final String SPAWN_INTERVAL_TICKS =
            "spawn_interval_ticks";

    private static final String ELAPSED_TICKS =
            "elapsed_ticks";

    private static final String NEXT_SPAWN_TICK =
            "next_spawn_tick";

    private static final String SPAWN_SCHEDULE_COMPLETE =
            "spawn_schedule_complete";

    private static final String SPAWN_SCHEDULE_COMPLETED_TICK =
            "spawn_schedule_completed_tick";

    private static final String TOTAL_SUCCESSFUL_SPAWNS =
            "total_successful_spawns";

    private static final String TOTAL_CANCELLED_MOBS =
            "total_cancelled_mobs";

    private static final String TOTAL_FAILED_SPAWN_ATTEMPTS =
            "total_failed_spawn_attempts";

    private static final String SOURCE_WAVE_EXECUTION_STATES =
            "source_wave_execution_states";

    private WaveExecutionStateSnapshotNbtCodec() {
    }

    /**
     * Writes one exact wave-execution snapshot.
     */
    public static CompoundTag write(
            WaveExecutionState.Snapshot snapshot
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Wave-execution snapshot cannot be null."
            );
        }

        CompoundTag tag =
                new CompoundTag();

        tag.putInt(
                WAVE_INDEX,
                snapshot.waveIndex()
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
                ELAPSED_TICKS,
                snapshot.elapsedTicks()
        );

        tag.putInt(
                NEXT_SPAWN_TICK,
                snapshot.nextSpawnTick()
        );

        tag.putBoolean(
                SPAWN_SCHEDULE_COMPLETE,
                snapshot.spawnScheduleComplete()
        );

        tag.putInt(
                SPAWN_SCHEDULE_COMPLETED_TICK,
                snapshot.spawnScheduleCompletedTick()
        );

        tag.putInt(
                TOTAL_SUCCESSFUL_SPAWNS,
                snapshot.totalSuccessfulSpawns()
        );

        tag.putInt(
                TOTAL_CANCELLED_MOBS,
                snapshot.totalCancelledMobs()
        );

        tag.putInt(
                TOTAL_FAILED_SPAWN_ATTEMPTS,
                snapshot.totalFailedSpawnAttempts()
        );

        ListTag sourceWaveExecutionTags =
                new ListTag();

        for (SourceWaveExecutionState.Snapshot
                sourceWaveExecutionSnapshot
                : snapshot.sourceWaveExecutionSnapshots()) {

            sourceWaveExecutionTags.add(
                    SourceWaveExecutionStateSnapshotNbtCodec.write(
                            sourceWaveExecutionSnapshot
                    )
            );
        }

        tag.put(
                SOURCE_WAVE_EXECUTION_STATES,
                sourceWaveExecutionTags
        );

        return tag;
    }

    /**
     * Reads and validates one exact wave-execution snapshot.
     */
    public static WaveExecutionState.Snapshot read(
            CompoundTag tag
    ) {
        if (tag == null) {
            throw new IllegalArgumentException(
                    "Wave-execution NBT cannot be null."
            );
        }

        int waveIndex =
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        WAVE_INDEX
                );

        List<SourceWaveExecutionState.Snapshot>
                sourceWaveExecutionSnapshots =
                readSourceWaveExecutionSnapshots(
                        tag,
                        waveIndex
                );

        return new WaveExecutionState.Snapshot(
                waveIndex,
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
                        ELAPSED_TICKS
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        NEXT_SPAWN_TICK
                ),
                IncursionSnapshotNbtSupport.requireBoolean(
                        tag,
                        SPAWN_SCHEDULE_COMPLETE
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        SPAWN_SCHEDULE_COMPLETED_TICK
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        TOTAL_SUCCESSFUL_SPAWNS
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        TOTAL_CANCELLED_MOBS
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        TOTAL_FAILED_SPAWN_ATTEMPTS
                ),
                sourceWaveExecutionSnapshots
        );
    }

    private static List<SourceWaveExecutionState.Snapshot>
    readSourceWaveExecutionSnapshots(
            CompoundTag tag,
            int containingWaveIndex
    ) {
        ListTag sourceWaveExecutionTags =
                IncursionSnapshotNbtSupport.requireCompoundList(
                        tag,
                        SOURCE_WAVE_EXECUTION_STATES
                );

        List<SourceWaveExecutionState.Snapshot>
                sourceWaveExecutionSnapshots =
                new ArrayList<>();

        for (int assignmentPosition = 0;
             assignmentPosition < sourceWaveExecutionTags.size();
             assignmentPosition++) {

            SourceWaveExecutionState.Snapshot
                    sourceWaveExecutionSnapshot;

            try {
                sourceWaveExecutionSnapshot =
                        SourceWaveExecutionStateSnapshotNbtCodec.read(
                                sourceWaveExecutionTags.getCompound(
                                        assignmentPosition
                                )
                        );
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "Failed to read source-wave execution state at "
                                + "canonical assignment position "
                                + assignmentPosition
                                + " in wave "
                                + containingWaveIndex
                                + ".",
                        exception
                );
            }

            if (sourceWaveExecutionSnapshot.waveIndex()
                    != containingWaveIndex) {

                throw new IllegalArgumentException(
                        "Source-wave execution state at assignment position "
                                + assignmentPosition
                                + " claims to belong to wave "
                                + sourceWaveExecutionSnapshot.waveIndex()
                                + " rather than containing wave "
                                + containingWaveIndex
                                + "."
                );
            }

            sourceWaveExecutionSnapshots.add(
                    sourceWaveExecutionSnapshot
            );
        }

        return List.copyOf(
                sourceWaveExecutionSnapshots
        );
    }
}