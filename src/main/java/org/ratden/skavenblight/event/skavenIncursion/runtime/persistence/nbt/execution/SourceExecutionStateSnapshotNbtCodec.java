package org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.nbt.execution;

import net.minecraft.nbt.CompoundTag;
import org.ratden.skavenblight.block.entity.state.SourceState;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.nbt.IncursionSnapshotNbtSupport;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceExecutionState;

import java.util.UUID;

/**
 * CompoundTag codec for SourceExecutionState.Snapshot.
 *
 * This preserves the mutable state of one persistent physical source
 * placement:
 *
 * - physical source-placement identity;
 * - ordered history of physical source incarnations;
 * - optional current physical incarnation;
 * - optional current SourceState;
 * - whether the latest incarnation is destroyed;
 * - total destruction count.
 *
 * The nullable current runtime-source ID and SourceState are stored together
 * in one optional compound. They therefore cannot be independently omitted or
 * partially restored.
 */
public final class SourceExecutionStateSnapshotNbtCodec {

    private static final String SOURCE_PLACEMENT_ID =
            "source_placement_id";

    private static final String RUNTIME_SOURCE_ID_HISTORY =
            "runtime_source_id_history";

    private static final String CURRENT_SOURCE =
            "current_source";

    private static final String RUNTIME_SOURCE_ID =
            "runtime_source_id";

    private static final String SOURCE_STATE =
            "source_state";

    private static final String CURRENTLY_DESTROYED =
            "currently_destroyed";

    private static final String DESTRUCTION_COUNT =
            "destruction_count";

    private SourceExecutionStateSnapshotNbtCodec() {
    }

    public static CompoundTag write(
            SourceExecutionState.Snapshot snapshot
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Source-execution snapshot cannot be null."
            );
        }

        CompoundTag tag =
                new CompoundTag();

        tag.putUUID(
                SOURCE_PLACEMENT_ID,
                snapshot.sourcePlacementId()
        );

        IncursionSnapshotNbtSupport.putUuidList(
                tag,
                RUNTIME_SOURCE_ID_HISTORY,
                snapshot.runtimeSourceIdHistory()
        );

        UUID runtimeSourceId =
                snapshot.runtimeSourceId();

        SourceState currentSourceState =
                snapshot.currentSourceState();

        if ((runtimeSourceId == null)
                != (currentSourceState == null)) {

            throw new IllegalArgumentException(
                    "Source-execution snapshot must contain both a current "
                            + "runtime source ID and SourceState, or neither."
            );
        }

        if (runtimeSourceId != null) {
            CompoundTag currentSourceTag =
                    new CompoundTag();

            currentSourceTag.putUUID(
                    RUNTIME_SOURCE_ID,
                    runtimeSourceId
            );

            currentSourceTag.putString(
                    SOURCE_STATE,
                    currentSourceState.name()
            );

            tag.put(
                    CURRENT_SOURCE,
                    currentSourceTag
            );
        }

        tag.putBoolean(
                CURRENTLY_DESTROYED,
                snapshot.currentlyDestroyed()
        );

        tag.putInt(
                DESTRUCTION_COUNT,
                snapshot.destructionCount()
        );

        return tag;
    }

    public static SourceExecutionState.Snapshot read(
            CompoundTag tag
    ) {
        if (tag == null) {
            throw new IllegalArgumentException(
                    "Source-execution NBT cannot be null."
            );
        }

        CompoundTag currentSourceTag =
                IncursionSnapshotNbtSupport.readOptionalCompound(
                        tag,
                        CURRENT_SOURCE
                );

        UUID runtimeSourceId =
                null;

        SourceState currentSourceState =
                null;

        if (currentSourceTag != null) {
            runtimeSourceId =
                    IncursionSnapshotNbtSupport.requireUuid(
                            currentSourceTag,
                            RUNTIME_SOURCE_ID
                    );

            currentSourceState =
                    IncursionSnapshotNbtSupport.requireEnum(
                            currentSourceTag,
                            SOURCE_STATE,
                            SourceState.class
                    );
        }

        return new SourceExecutionState.Snapshot(
                IncursionSnapshotNbtSupport.requireUuid(
                        tag,
                        SOURCE_PLACEMENT_ID
                ),
                IncursionSnapshotNbtSupport.readUuidList(
                        tag,
                        RUNTIME_SOURCE_ID_HISTORY
                ),
                runtimeSourceId,
                currentSourceState,
                IncursionSnapshotNbtSupport.requireBoolean(
                        tag,
                        CURRENTLY_DESTROYED
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        DESTRUCTION_COUNT
                )
        );
    }
}