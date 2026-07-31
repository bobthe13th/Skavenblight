package org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.nbt.scenario;

import net.minecraft.nbt.CompoundTag;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.nbt.IncursionSnapshotNbtSupport;
import org.ratden.skavenblight.event.skavenIncursion.runtime.IncursionExecutionState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.IncursionWaveController;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.PlannedScenarioRuntimeSnapshot;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.nbt.execution.IncursionExecutionStateSnapshotNbtCodec;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.nbt.execution.IncursionWaveControllerSnapshotNbtCodec;

/**
 * CompoundTag codec for the common runtime snapshot used by ordinary
 * planning-aware Scenarios.
 *
 * This codec is not tied to one Scenario definition. The saved scenarioId is
 * retained as data and will later be used by ScenarioRegistry to select the
 * correct live restoration factory.
 *
 * It preserves:
 *
 * - Scenario definition identity;
 * - Scenario instance and incursion identity;
 * - persisted maximum runtime;
 * - elapsed runtime;
 * - routed source-destruction count;
 * - mobs cancelled specifically by source destruction;
 * - source-collapse state;
 * - finished state;
 * - timeout state;
 * - complete shared IncursionExecutionState snapshot;
 * - complete IncursionWaveController snapshot.
 *
 * A Scenario requiring additional unique persistent state should compose this
 * common snapshot inside a specialised snapshot and encode only its extra
 * fields separately.
 *
 * Schema-version handling belongs to the later top-level persistent-incursion
 * codec.
 */
public final class PlannedScenarioRuntimeSnapshotNbtCodec {

    private static final String SCENARIO_ID =
            "scenario_id";

    private static final String INSTANCE_ID =
            "instance_id";

    private static final String MAXIMUM_RUNTIME_TICKS =
            "maximum_runtime_ticks";

    private static final String ELAPSED_TICKS =
            "elapsed_ticks";

    private static final String TOTAL_SOURCE_DESTRUCTIONS =
            "total_source_destructions";

    private static final String
            TOTAL_MOBS_CANCELLED_BY_DESTRUCTION =
            "total_mobs_cancelled_by_destruction";

    private static final String SOURCES_COLLAPSED =
            "sources_collapsed";

    private static final String FINISHED =
            "finished";

    private static final String TIMED_OUT =
            "timed_out";

    private static final String INCURSION_EXECUTION =
            "incursion_execution";

    private static final String WAVE_CONTROLLER =
            "wave_controller";

    private PlannedScenarioRuntimeSnapshotNbtCodec() {
    }

    /**
     * Writes one exact common planned-Scenario runtime snapshot.
     */
    public static CompoundTag write(
            PlannedScenarioRuntimeSnapshot snapshot
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Planned Scenario runtime snapshot cannot be null."
            );
        }

        CompoundTag tag =
                new CompoundTag();

        tag.putString(
                SCENARIO_ID,
                snapshot.scenarioId()
        );

        tag.putUUID(
                INSTANCE_ID,
                snapshot.instanceId()
        );

        tag.putInt(
                MAXIMUM_RUNTIME_TICKS,
                snapshot.maximumRuntimeTicks()
        );

        tag.putInt(
                ELAPSED_TICKS,
                snapshot.elapsedTicks()
        );

        tag.putInt(
                TOTAL_SOURCE_DESTRUCTIONS,
                snapshot.totalSourceDestructions()
        );

        tag.putInt(
                TOTAL_MOBS_CANCELLED_BY_DESTRUCTION,
                snapshot.totalMobsCancelledByDestruction()
        );

        tag.putBoolean(
                SOURCES_COLLAPSED,
                snapshot.sourcesCollapsed()
        );

        tag.putBoolean(
                FINISHED,
                snapshot.finished()
        );

        tag.putBoolean(
                TIMED_OUT,
                snapshot.timedOut()
        );

        IncursionExecutionState.Snapshot
                incursionExecutionSnapshot =
                snapshot.incursionExecutionSnapshot();

        tag.put(
                INCURSION_EXECUTION,
                IncursionExecutionStateSnapshotNbtCodec.write(
                        incursionExecutionSnapshot
                )
        );

        IncursionWaveController.Snapshot
                waveControllerSnapshot =
                snapshot.waveControllerSnapshot();

        tag.put(
                WAVE_CONTROLLER,
                IncursionWaveControllerSnapshotNbtCodec.write(
                        waveControllerSnapshot
                )
        );

        PlannedScenarioRuntimeSnapshot reconstructedSnapshot =
                read(
                        tag
                );

        if (!snapshot.equals(
                reconstructedSnapshot
        )) {
            throw new IllegalStateException(
                    "Planned Scenario runtime NBT encoding did not produce "
                            + "an exact snapshot round trip for Scenario "
                            + snapshot.scenarioId()
                            + ", instance "
                            + snapshot.instanceId()
                            + "."
            );
        }

        return tag;
    }

    /**
     * Reads and validates one exact common planned-Scenario runtime snapshot.
     */
    public static PlannedScenarioRuntimeSnapshot read(
            CompoundTag tag
    ) {
        if (tag == null) {
            throw new IllegalArgumentException(
                    "Planned Scenario runtime NBT cannot be null."
            );
        }

        IncursionExecutionState.Snapshot
                incursionExecutionSnapshot =
                readIncursionExecutionSnapshot(
                        tag
                );

        IncursionWaveController.Snapshot
                waveControllerSnapshot =
                readWaveControllerSnapshot(
                        tag
                );

        return new PlannedScenarioRuntimeSnapshot(
                IncursionSnapshotNbtSupport.requireString(
                        tag,
                        SCENARIO_ID
                ),
                IncursionSnapshotNbtSupport.requireUuid(
                        tag,
                        INSTANCE_ID
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        MAXIMUM_RUNTIME_TICKS
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        ELAPSED_TICKS
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        TOTAL_SOURCE_DESTRUCTIONS
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        TOTAL_MOBS_CANCELLED_BY_DESTRUCTION
                ),
                IncursionSnapshotNbtSupport.requireBoolean(
                        tag,
                        SOURCES_COLLAPSED
                ),
                IncursionSnapshotNbtSupport.requireBoolean(
                        tag,
                        FINISHED
                ),
                IncursionSnapshotNbtSupport.requireBoolean(
                        tag,
                        TIMED_OUT
                ),
                incursionExecutionSnapshot,
                waveControllerSnapshot
        );
    }

    private static IncursionExecutionState.Snapshot
    readIncursionExecutionSnapshot(
            CompoundTag tag
    ) {
        CompoundTag executionTag =
                IncursionSnapshotNbtSupport.requireCompound(
                        tag,
                        INCURSION_EXECUTION
                );

        try {
            return IncursionExecutionStateSnapshotNbtCodec.read(
                    executionTag
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Failed to read planned Scenario incursion execution "
                            + "state.",
                    exception
            );
        }
    }

    private static IncursionWaveController.Snapshot
    readWaveControllerSnapshot(
            CompoundTag tag
    ) {
        CompoundTag waveControllerTag =
                IncursionSnapshotNbtSupport.requireCompound(
                        tag,
                        WAVE_CONTROLLER
                );

        try {
            return IncursionWaveControllerSnapshotNbtCodec.read(
                    waveControllerTag
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Failed to read planned Scenario wave-controller state.",
                    exception
            );
        }
    }
}