package org.ratden.skavenblight.event.skavenIncursion.director;

import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.LivePersistentIncursion;

/**
 * Determines when an active persistent incursion should create its ordinary
 * progress checkpoint.
 *
 * Complete incursion snapshots traverse the full nested runtime graph:
 *
 * - Scenario state;
 * - wave-controller state;
 * - all waves;
 * - physical source states;
 * - source-wave assignments;
 * - spawn queues;
 * - attached-mob bindings.
 *
 * That work is unnecessary on every server tick. Ordinary progress is
 * therefore checkpointed once every second.
 *
 * Each incursion receives a deterministic slot within that second based on
 * its UUID. Several simultaneous incursions are therefore distributed across
 * different ticks instead of all constructing snapshots together.
 *
 * Important state changes such as source destruction, attached-entity
 * reconnection and lifecycle completion retain their separate immediate
 * checkpoint routes.
 */
public final class PersistentIncursionCheckpointPolicy {

    private static final int CHECKPOINT_INTERVAL_TICKS =
            20;

    /**
     * Returns whether this incursion owns the current periodic checkpoint
     * slot.
     */
    public static boolean isPeriodicCheckpointDue(
            LivePersistentIncursion incursion
    ) {
        if (incursion == null) {
            throw new IllegalArgumentException(
                    "Persistent checkpoint incursion cannot be null."
            );
        }

        int checkpointSlot =
                Math.floorMod(
                        incursion.getIncursionId()
                                .hashCode(),
                        CHECKPOINT_INTERVAL_TICKS
                );

        long currentSlot =
                Math.floorMod(
                        incursion.getLevel()
                                .getGameTime(),
                        (long) CHECKPOINT_INTERVAL_TICKS
                );

        return currentSlot
                == checkpointSlot;
    }

    public static int getCheckpointIntervalTicks() {
        return CHECKPOINT_INTERVAL_TICKS;
    }

    private PersistentIncursionCheckpointPolicy() {
    }
}