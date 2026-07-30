package org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.nbt.execution;

import net.minecraft.nbt.CompoundTag;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.nbt.IncursionSnapshotNbtSupport;
import org.ratden.skavenblight.event.skavenIncursion.runtime.entity.AttachedMobEntityBindingState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceSpawnQueue;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceWaveExecutionState;

/**
 * CompoundTag codec for SourceWaveExecutionState.Snapshot.
 *
 * This snapshot represents one source-composition assignment during one
 * global wave.
 *
 * It preserves:
 *
 * - wave identity;
 * - physical source-placement identity;
 * - source-composition identity;
 * - planned mob count;
 * - successful spawn count;
 * - cancelled mob count;
 * - cancellation state;
 * - exact remaining SourceSpawnQueue progress;
 * - attached-mob assignment-to-entity bindings.
 *
 * The immutable IncursionPlan remains authoritative for the composition and
 * its original attached-mob assignment identities. Restoration later
 * validates every saved binding against that immutable composition.
 */
public final class SourceWaveExecutionStateSnapshotNbtCodec {

    private static final String WAVE_INDEX =
            "wave_index";

    private static final String SOURCE_PLACEMENT_ID =
            "source_placement_id";

    private static final String SOURCE_COMPOSITION_ID =
            "source_composition_id";

    private static final String PLANNED_MOB_COUNT =
            "planned_mob_count";

    private static final String SUCCESSFUL_SPAWN_COUNT =
            "successful_spawn_count";

    private static final String CANCELLED_MOB_COUNT =
            "cancelled_mob_count";

    private static final String CANCELLED =
            "cancelled";

    private static final String SPAWN_QUEUE =
            "spawn_queue";

    private static final String ATTACHED_MOB_ENTITY_BINDINGS =
            "attached_mob_entity_bindings";

    private SourceWaveExecutionStateSnapshotNbtCodec() {
    }

    /**
     * Writes one exact wave-specific source-assignment snapshot.
     */
    public static CompoundTag write(
            SourceWaveExecutionState.Snapshot snapshot
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Source-wave execution snapshot cannot be null."
            );
        }

        CompoundTag tag =
                new CompoundTag();

        tag.putInt(
                WAVE_INDEX,
                snapshot.waveIndex()
        );

        tag.putUUID(
                SOURCE_PLACEMENT_ID,
                snapshot.sourcePlacementId()
        );

        tag.putUUID(
                SOURCE_COMPOSITION_ID,
                snapshot.sourceCompositionId()
        );

        tag.putInt(
                PLANNED_MOB_COUNT,
                snapshot.plannedMobCount()
        );

        tag.putInt(
                SUCCESSFUL_SPAWN_COUNT,
                snapshot.successfulSpawnCount()
        );

        tag.putInt(
                CANCELLED_MOB_COUNT,
                snapshot.cancelledMobCount()
        );

        tag.putBoolean(
                CANCELLED,
                snapshot.cancelled()
        );

        SourceSpawnQueue.Snapshot spawnQueueSnapshot =
                snapshot.spawnQueueSnapshot();

        tag.put(
                SPAWN_QUEUE,
                SourceSpawnQueueSnapshotNbtCodec.write(
                        spawnQueueSnapshot
                )
        );

        tag.put(
                ATTACHED_MOB_ENTITY_BINDINGS,
                AttachedMobEntityBindingStateSnapshotNbtCodec.write(
                        snapshot
                                .attachedMobEntityBindingSnapshot()
                )
        );

        SourceWaveExecutionState.Snapshot reconstructedSnapshot =
                read(
                        tag
                );

        if (!snapshot.equals(
                reconstructedSnapshot
        )) {
            throw new IllegalStateException(
                    "Source-wave execution NBT encoding did not produce an "
                            + "exact snapshot round trip for source "
                            + "composition "
                            + snapshot.sourceCompositionId()
                            + "."
            );
        }

        return tag;
    }

    /**
     * Reads and validates one exact wave-specific source-assignment snapshot.
     *
     * Records written before entity-binding persistence existed contain no
     * binding compound. Those records are interpreted as having no bindings,
     * which is exact because the previous runtime could not persist any.
     */
    public static SourceWaveExecutionState.Snapshot read(
            CompoundTag tag
    ) {
        if (tag == null) {
            throw new IllegalArgumentException(
                    "Source-wave execution NBT cannot be null."
            );
        }

        CompoundTag attachedMobBindingsTag =
                IncursionSnapshotNbtSupport.readOptionalCompound(
                        tag,
                        ATTACHED_MOB_ENTITY_BINDINGS
                );

        AttachedMobEntityBindingState.Snapshot
                attachedMobEntityBindingSnapshot =
                attachedMobBindingsTag == null
                        ? new AttachedMobEntityBindingState.Snapshot(
                        java.util.List.of()
                )
                        : AttachedMobEntityBindingStateSnapshotNbtCodec.read(
                        attachedMobBindingsTag
                );

        return new SourceWaveExecutionState.Snapshot(
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        WAVE_INDEX
                ),
                IncursionSnapshotNbtSupport.requireUuid(
                        tag,
                        SOURCE_PLACEMENT_ID
                ),
                IncursionSnapshotNbtSupport.requireUuid(
                        tag,
                        SOURCE_COMPOSITION_ID
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        PLANNED_MOB_COUNT
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        SUCCESSFUL_SPAWN_COUNT
                ),
                IncursionSnapshotNbtSupport.requireInt(
                        tag,
                        CANCELLED_MOB_COUNT
                ),
                IncursionSnapshotNbtSupport.requireBoolean(
                        tag,
                        CANCELLED
                ),
                SourceSpawnQueueSnapshotNbtCodec.read(
                        IncursionSnapshotNbtSupport.requireCompound(
                                tag,
                                SPAWN_QUEUE
                        )
                ),
                attachedMobEntityBindingSnapshot
        );
    }
}