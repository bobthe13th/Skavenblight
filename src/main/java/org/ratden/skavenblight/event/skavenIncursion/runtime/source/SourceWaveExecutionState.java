package org.ratden.skavenblight.event.skavenIncursion.runtime.source;

import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.block.entity.state.SourceState;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeadershipContext;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.SourceGroupComposition;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourcePlacementPlan;

import java.util.UUID;

/**
 * Runtime state for one physical source's assignment in one wave.
 *
 * SourceExecutionState persists across waves and represents the planned
 * source location and its current physical incarnation.
 *
 * SourceSpawnQueue belongs only to this wave. Destroying the source therefore
 * cancels this queue without deleting assignments belonging to later waves.
 */
public class SourceWaveExecutionState {

    private final int waveIndex;

    private final SourceExecutionState sourceExecutionState;
    private final UUID sourceCompositionId;
    private final SourceSpawnQueue spawnQueue;

    private final int plannedMobCount;

    private int successfulSpawnCount;
    private int cancelledMobCount;
    private boolean cancelled;

    public SourceWaveExecutionState(
            int waveIndex,
            SourceExecutionState sourceExecutionState,
            SourceGroupComposition.SourceComposition sourceComposition
    ) {
        if (waveIndex < 0) {
            throw new IllegalArgumentException(
                    "Wave index cannot be negative."
            );
        }

        if (sourceExecutionState == null) {
            throw new IllegalArgumentException(
                    "Source execution state cannot be null."
            );
        }

        if (sourceComposition == null) {
            throw new IllegalArgumentException(
                    "Source composition cannot be null."
            );
        }

        SourcePlacementPlan sourcePlacementPlan =
                sourceExecutionState.getSourcePlacementPlan();

        UUID sourceCompositionId =
                sourceComposition.getSourceCompositionId();

        if (!sourcePlacementPlan.isBoundToSourceComposition(
                sourceCompositionId
        )) {
            throw new IllegalArgumentException(
                    "Source placement "
                            + sourcePlacementPlan.getSourcePlacementId()
                            + " is not bound to source composition "
                            + sourceCompositionId
                            + "."
            );
        }

        if (sourcePlacementPlan.getSourceType()
                != sourceComposition.getRequiredSourceType()) {
            throw new IllegalArgumentException(
                    "Source placement type does not match the source "
                            + "composition requirement."
            );
        }

        if (sourcePlacementPlan.getSourceRole()
                != sourceComposition.getSourceRole()) {
            throw new IllegalArgumentException(
                    "Source placement role does not match the source "
                            + "composition requirement."
            );
        }

        if (!sourcePlacementPlan
                .getSourceSize()
                .canFit(
                        sourceComposition.getRequiredSourceSize()
                )) {
            throw new IllegalArgumentException(
                    "Source placement is too small for the source "
                            + "composition requirement."
            );
        }

        this.waveIndex =
                waveIndex;

        this.sourceExecutionState =
                sourceExecutionState;

        this.sourceCompositionId =
                sourceCompositionId;

        this.spawnQueue =
                new SourceSpawnQueue(
                        sourceComposition
                );

        this.plannedMobCount =
                sourceComposition.getTotalMobCount();

        this.successfulSpawnCount = 0;
        this.cancelledMobCount = 0;
        this.cancelled = false;
    }

    public int getWaveIndex() {
        return waveIndex;
    }

    public SourceExecutionState getSourceExecutionState() {
        return sourceExecutionState;
    }

    public UUID getSourcePlacementId() {
        return sourceExecutionState.getSourcePlacementId();
    }

    public UUID getSourceCompositionId() {
        return sourceCompositionId;
    }

    public SourceSpawnQueue getSpawnQueue() {
        return spawnQueue;
    }

    public int getPlannedMobCount() {
        return plannedMobCount;
    }

    public int getSuccessfulSpawnCount() {
        return successfulSpawnCount;
    }

    public int getCancelledMobCount() {
        return cancelledMobCount;
    }

    public int getRemainingMobCount() {
        return spawnQueue.getRemainingMobCount();
    }

    public boolean hasRemainingMobs() {
        return spawnQueue.hasRemainingMobs();
    }

    /**
     * A wave-source assignment is complete when it has either spawned or
     * cancelled every planned mob.
     */
    public boolean isComplete() {
        return spawnQueue.isEmpty();
    }

    public boolean wasCancelled() {
        return cancelled;
    }

    public boolean completedNormally() {
        return isComplete()
                && !cancelled;
    }

    /**
     * Creates the physical source when it does not currently exist.
     *
     * This may recreate a source destroyed during an earlier wave.
     */
    public boolean ensureSourceAvailable(
            ServerLevel level,
            SourceState sourceState,
            LeadershipContext leadershipContext
    ) {
        return sourceExecutionState.createSource(
                level,
                sourceState,
                leadershipContext
        );
    }

    /**
     * Attempts one streamed spawn from this wave's queue.
     *
     * Successful spawns are counted here. Failed attempts leave the queue
     * unchanged for a later retry.
     */
    public SourceExecutionState.SpawnAttemptResult
    attemptNextSpawn(
            ServerLevel level,
            LeadershipContext leadershipContext
    ) {
        SourceExecutionState.SpawnAttemptResult result =
                sourceExecutionState.attemptNextSpawn(
                        level,
                        spawnQueue,
                        leadershipContext
                );

        if (result
                == SourceExecutionState
                .SpawnAttemptResult
                .SPAWNED) {
            successfulSpawnCount++;
        }

        return result;
    }

    /**
     * Handles destruction of the physical source during this wave.
     *
     * The persistent source location remains valid for later waves, but all
     * mobs still pending in this wave are cancelled.
     *
     * Returns the number of newly cancelled mobs.
     */
    public int handleSourceDestroyed() {
        sourceExecutionState.markDestroyed();

        return cancelRemainingMobs();
    }

    /**
     * Cancels this wave's remaining source assignment.
     *
     * Calling this after the queue has already completed does not retroactively
     * mark a successfully completed assignment as cancelled.
     */
    public int cancelRemainingMobs() {
        int cancelledNow =
                sourceExecutionState.cancelCurrentWave(
                        spawnQueue
                );

        if (cancelledNow > 0) {
            cancelled = true;

            cancelledMobCount +=
                    cancelledNow;
        }

        return cancelledNow;
    }
}