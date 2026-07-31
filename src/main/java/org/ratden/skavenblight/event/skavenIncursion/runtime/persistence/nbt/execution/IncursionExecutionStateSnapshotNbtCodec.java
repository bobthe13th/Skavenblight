package org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.nbt.execution;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.nbt.IncursionSnapshotNbtSupport;
import org.ratden.skavenblight.event.skavenIncursion.runtime.IncursionExecutionState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceExecutionState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceWaveExecutionState;

import java.util.ArrayList;
import java.util.List;

/**
 * CompoundTag codec for IncursionExecutionState.Snapshot.
 *
 * IncursionExecutionState owns the complete shared mutable execution graph:
 *
 * - one SourceExecutionState for every persistent physical source placement;
 * - one SourceWaveExecutionState for every source composition assigned during
 *   every wave.
 *
 * The ordering stored here is authoritative:
 *
 * - physical source snapshots remain in immutable plan traversal order;
 * - wave assignment groups remain in ascending wave-index order;
 * - source assignments within a wave remain in immutable plan traversal
 *   order.
 *
 * Restoration of live runtime objects still requires the corresponding
 * immutable IncursionPlan. The IncursionExecutionState.restore(...) method
 * validates every saved identity and assignment against that plan.
 *
 * Schema-version handling belongs to the eventual top-level persistent
 * incursion codec.
 */
public final class IncursionExecutionStateSnapshotNbtCodec {

    private static final String INCURSION_ID =
            "incursion_id";

    private static final String SOURCE_EXECUTION_STATES =
            "source_execution_states";

    private static final String WAVE_SOURCE_ASSIGNMENTS =
            "wave_source_assignments";

    private static final String WAVE_INDEX =
            "wave_index";

    private static final String SOURCE_WAVE_EXECUTION_STATES =
            "source_wave_execution_states";

    private IncursionExecutionStateSnapshotNbtCodec() {
    }

    /**
     * Writes the complete shared execution graph.
     */
    public static CompoundTag write(
            IncursionExecutionState.Snapshot snapshot
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Incursion execution snapshot cannot be null."
            );
        }

        CompoundTag tag =
                new CompoundTag();

        tag.putUUID(
                INCURSION_ID,
                snapshot.incursionId()
        );

        ListTag sourceExecutionTags =
                new ListTag();

        for (SourceExecutionState.Snapshot sourceExecutionSnapshot
                : snapshot.sourceExecutionSnapshots()) {

            sourceExecutionTags.add(
                    SourceExecutionStateSnapshotNbtCodec.write(
                            sourceExecutionSnapshot
                    )
            );
        }

        tag.put(
                SOURCE_EXECUTION_STATES,
                sourceExecutionTags
        );

        ListTag waveAssignmentTags =
                new ListTag();

        for (IncursionExecutionState.WaveSourceAssignmentsSnapshot
                waveAssignmentSnapshot
                : snapshot.waveSourceAssignmentSnapshots()) {

            waveAssignmentTags.add(
                    writeWaveAssignments(
                            waveAssignmentSnapshot
                    )
            );
        }

        tag.put(
                WAVE_SOURCE_ASSIGNMENTS,
                waveAssignmentTags
        );

        return tag;
    }

    /**
     * Reads and validates the complete shared execution graph snapshot.
     */
    public static IncursionExecutionState.Snapshot read(
            CompoundTag tag
    ) {
        if (tag == null) {
            throw new IllegalArgumentException(
                    "Incursion execution NBT cannot be null."
            );
        }

        List<SourceExecutionState.Snapshot>
                sourceExecutionSnapshots =
                readSourceExecutionSnapshots(
                        tag
                );

        List<IncursionExecutionState.WaveSourceAssignmentsSnapshot>
                waveAssignmentSnapshots =
                readWaveAssignmentSnapshots(
                        tag
                );

        return new IncursionExecutionState.Snapshot(
                IncursionSnapshotNbtSupport.requireUuid(
                        tag,
                        INCURSION_ID
                ),
                sourceExecutionSnapshots,
                waveAssignmentSnapshots
        );
    }

    private static List<SourceExecutionState.Snapshot>
    readSourceExecutionSnapshots(
            CompoundTag tag
    ) {
        ListTag sourceExecutionTags =
                IncursionSnapshotNbtSupport.requireCompoundList(
                        tag,
                        SOURCE_EXECUTION_STATES
                );

        List<SourceExecutionState.Snapshot>
                sourceExecutionSnapshots =
                new ArrayList<>();

        for (int sourceIndex = 0;
             sourceIndex < sourceExecutionTags.size();
             sourceIndex++) {

            try {
                sourceExecutionSnapshots.add(
                        SourceExecutionStateSnapshotNbtCodec.read(
                                sourceExecutionTags.getCompound(
                                        sourceIndex
                                )
                        )
                );
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "Failed to read physical source execution state at "
                                + "canonical position "
                                + sourceIndex
                                + ".",
                        exception
                );
            }
        }

        return List.copyOf(
                sourceExecutionSnapshots
        );
    }

    private static List<
            IncursionExecutionState.WaveSourceAssignmentsSnapshot
            >
    readWaveAssignmentSnapshots(
            CompoundTag tag
    ) {
        ListTag waveAssignmentTags =
                IncursionSnapshotNbtSupport.requireCompoundList(
                        tag,
                        WAVE_SOURCE_ASSIGNMENTS
                );

        List<IncursionExecutionState.WaveSourceAssignmentsSnapshot>
                waveAssignmentSnapshots =
                new ArrayList<>();

        for (int wavePosition = 0;
             wavePosition < waveAssignmentTags.size();
             wavePosition++) {

            try {
                waveAssignmentSnapshots.add(
                        readWaveAssignments(
                                waveAssignmentTags.getCompound(
                                        wavePosition
                                )
                        )
                );
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "Failed to read wave source assignments at canonical "
                                + "wave position "
                                + wavePosition
                                + ".",
                        exception
                );
            }
        }

        return List.copyOf(
                waveAssignmentSnapshots
        );
    }

    private static CompoundTag writeWaveAssignments(
            IncursionExecutionState.WaveSourceAssignmentsSnapshot snapshot
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Wave source-assignment snapshot cannot be null."
            );
        }

        CompoundTag tag =
                new CompoundTag();

        tag.putInt(
                WAVE_INDEX,
                snapshot.waveIndex()
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

    private static IncursionExecutionState.WaveSourceAssignmentsSnapshot
    readWaveAssignments(
            CompoundTag tag
    ) {
        int waveIndex =
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        WAVE_INDEX
                );

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

            try {
                sourceWaveExecutionSnapshots.add(
                        SourceWaveExecutionStateSnapshotNbtCodec.read(
                                sourceWaveExecutionTags.getCompound(
                                        assignmentPosition
                                )
                        )
                );
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "Failed to read source assignment at canonical "
                                + "position "
                                + assignmentPosition
                                + " in wave "
                                + waveIndex
                                + ".",
                        exception
                );
            }
        }

        return new IncursionExecutionState.WaveSourceAssignmentsSnapshot(
                waveIndex,
                sourceWaveExecutionSnapshots
        );
    }
}