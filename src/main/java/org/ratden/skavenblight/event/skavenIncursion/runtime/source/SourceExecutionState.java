package org.ratden.skavenblight.event.skavenIncursion.runtime.source;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.block.entity.state.SourceState;
import org.ratden.skavenblight.event.skavenIncursion.action.mob.IncursionMobSpawner;
import org.ratden.skavenblight.event.skavenIncursion.action.source.SetSourceState;
import org.ratden.skavenblight.event.skavenIncursion.action.source.generic.CreateTunnelSource;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeadershipContext;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourcePlacementPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Mutable runtime state for one persistent planned source location.
 *
 * SourcePlacementPlan remains the immutable planning result. This object
 * tracks the current physical incarnation created at that location.
 *
 * The same planned source location may be reused across waves. Destruction
 * removes the current physical incarnation but does not permanently
 * invalidate the placement or its future-wave assignments.
 *
 * SourceState is tracked as runtime-facing information:
 *
 * - ACTIVE means the current source has delivery work remaining;
 * - DORMANT means the source still exists but is not currently delivering;
 * - COLLAPSED means the source has been formally retired.
 *
 * Player-facing source visuals may represent those states differently from
 * their runtime meaning.
 */
public class SourceExecutionState {

    private final SourcePlacementPlan sourcePlacementPlan;
    private final List<UUID> runtimeSourceIdHistory;

    private UUID runtimeSourceId;
    private SourceState currentSourceState;

    private boolean currentlyDestroyed;
    private int destructionCount;

    public SourceExecutionState(
            SourcePlacementPlan sourcePlacementPlan
    ) {
        if (sourcePlacementPlan == null) {
            throw new IllegalArgumentException(
                    "Source placement plan cannot be null."
            );
        }

        if (!sourcePlacementPlan.hasPlacedPos()) {
            throw new IllegalArgumentException(
                    "Runtime source requires a completed source placement."
            );
        }

        this.sourcePlacementPlan =
                sourcePlacementPlan;

        this.runtimeSourceIdHistory =
                new ArrayList<>();

        this.runtimeSourceId = null;
        this.currentSourceState = null;

        this.currentlyDestroyed = false;
        this.destructionCount = 0;
    }

    public SourcePlacementPlan getSourcePlacementPlan() {
        return sourcePlacementPlan;
    }

    public UUID getSourcePlacementId() {
        return sourcePlacementPlan.getSourcePlacementId();
    }

    public BlockPos getSourcePos() {
        return sourcePlacementPlan.getPlacedPos();
    }

    /**
     * Returns the ID belonging to the current physical source incarnation.
     *
     * Returns null before creation and after destruction.
     */
    public UUID getRuntimeSourceId() {
        return runtimeSourceId;
    }

    /**
     * Returns whether the supplied ID belongs to the current physical source
     * incarnation.
     *
     * After destruction, runtimeSourceId becomes null, so duplicate
     * destruction notifications for the same incarnation are not recognised
     * twice.
     */
    public boolean matchesCurrentRuntimeSourceId(
            UUID candidateRuntimeSourceId
    ) {
        return candidateRuntimeSourceId != null
                && candidateRuntimeSourceId.equals(
                runtimeSourceId
        );
    }

    public List<UUID> getRuntimeSourceIdHistory() {
        return Collections.unmodifiableList(
                runtimeSourceIdHistory
        );
    }

    public UUID getLastRuntimeSourceId() {
        if (runtimeSourceIdHistory.isEmpty()) {
            return null;
        }

        return runtimeSourceIdHistory.get(
                runtimeSourceIdHistory.size() - 1
        );
    }

    /**
     * Returns whether this planned location has successfully created at least
     * one physical source during the incursion.
     */
    public boolean hasBeenCreated() {
        return !runtimeSourceIdHistory.isEmpty();
    }

    /**
     * Returns whether the most recent physical incarnation was destroyed and
     * has not yet been recreated for a later wave.
     */
    public boolean isDestroyed() {
        return currentlyDestroyed;
    }

    public int getDestructionCount() {
        return destructionCount;
    }

    /**
     * Returns whether a physical source incarnation currently exists.
     *
     * A dormant or collapsed source may still be physically available. The
     * SourceState determines its operational lifecycle state.
     */
    public boolean isAvailable() {
        return runtimeSourceId != null
                && !currentlyDestroyed;
    }

    /**
     * Returns the internal state of the current physical source incarnation.
     *
     * Returns null before creation and after destruction.
     */
    public SourceState getCurrentSourceState() {
        return currentSourceState;
    }

    public boolean isInState(
            SourceState sourceState
    ) {
        return sourceState != null
                && currentSourceState == sourceState;
    }

    /**
     * Changes the state of the current physical source incarnation.
     *
     * Repeating the current state is harmless and does not perform another
     * world update.
     *
     * Returns false when no physical source currently exists.
     */
    public boolean setCurrentSourceState(
            ServerLevel level,
            SourceState sourceState
    ) {
        if (level == null) {
            throw new IllegalArgumentException(
                    "Source-state level cannot be null."
            );
        }

        if (sourceState == null) {
            throw new IllegalArgumentException(
                    "Source state cannot be null."
            );
        }

        if (!isAvailable()) {
            return false;
        }

        if (currentSourceState == sourceState) {
            return true;
        }

        SetSourceState.execute(
                level,
                getSourcePos(),
                sourceState
        );

        currentSourceState =
                sourceState;

        return true;
    }

    /**
     * Creates or reactivates the planned physical source.
     *
     * Repeated calls while the source already exists ensure that it has the
     * requested state. This allows a dormant source to become active again
     * when a later wave reuses it.
     *
     * After destruction, a later wave may create a new physical incarnation
     * at the same planned location.
     */
    public boolean createSource(
            ServerLevel level,
            SourceState sourceState,
            LeadershipContext leadershipContext
    ) {
        if (level == null) {
            throw new IllegalArgumentException(
                    "Source level cannot be null."
            );
        }

        if (sourceState == null) {
            throw new IllegalArgumentException(
                    "Source state cannot be null."
            );
        }

        if (leadershipContext == null) {
            throw new IllegalArgumentException(
                    "Leadership context cannot be null."
            );
        }

        /*
         * A source reused by a later wave may currently be dormant. Ensuring
         * that it is available for the new assignment also restores the
         * requested active state.
         */
        if (isAvailable()) {
            return setCurrentSourceState(
                    level,
                    sourceState
            );
        }

        if (sourcePlacementPlan.getSourceType()
                != SourceType.SKAVEN_TUNNEL) {
            throw new UnsupportedOperationException(
                    "No planning-aware runtime creation action exists for "
                            + "source type "
                            + sourcePlacementPlan.getSourceType()
                            + "."
            );
        }

        UUID createdSourceId =
                CreateTunnelSource.execute(
                        level,
                        sourcePlacementPlan,
                        sourceState,
                        leadershipContext
                );

        if (createdSourceId == null) {
            return false;
        }

        runtimeSourceId =
                createdSourceId;

        currentSourceState =
                sourceState;

        runtimeSourceIdHistory.add(
                createdSourceId
        );

        currentlyDestroyed = false;

        return true;
    }

    /**
     * Attempts to spawn the next mob from one wave-specific queue.
     *
     * The queue is reduced only when exactly one entity was successfully
     * spawned. Failed spawn attempts leave the planned mob pending.
     */
    public SpawnAttemptResult attemptNextSpawn(
            ServerLevel level,
            SourceSpawnQueue spawnQueue,
            LeadershipContext leadershipContext
    ) {
        if (level == null) {
            throw new IllegalArgumentException(
                    "Spawn level cannot be null."
            );
        }

        if (spawnQueue == null) {
            throw new IllegalArgumentException(
                    "Source spawn queue cannot be null."
            );
        }

        if (leadershipContext == null) {
            throw new IllegalArgumentException(
                    "Leadership context cannot be null."
            );
        }

        if (!isAvailable()) {
            return SpawnAttemptResult.SOURCE_UNAVAILABLE;
        }

        String mobId =
                spawnQueue.peekNextMobId();

        if (mobId == null) {
            return SpawnAttemptResult.QUEUE_EMPTY;
        }

        int spawnedCount =
                IncursionMobSpawner.spawn(
                        mobId,
                        level,
                        getSourcePos(),
                        1,
                        leadershipContext,
                        runtimeSourceId
                );

        if (spawnedCount <= 0) {
            return SpawnAttemptResult.SPAWN_FAILED;
        }

        if (spawnedCount != 1) {
            throw new IllegalStateException(
                    "A streamed source spawn requested one mob but created "
                            + spawnedCount
                            + "."
            );
        }

        spawnQueue.markNextMobSpawned(
                mobId
        );

        return SpawnAttemptResult.SPAWNED;
    }

    /**
     * Records destruction of the current physical source incarnation.
     *
     * The planned location and its later-wave assignments remain valid.
     * Wave runtime separately cancels the queue belonging to the wave in
     * which this destruction occurred.
     */
    public void markDestroyed() {
        if (!isAvailable()) {
            return;
        }

        runtimeSourceId = null;
        currentSourceState = null;

        currentlyDestroyed = true;
        destructionCount++;
    }

    /**
     * Cancels one wave's remaining assignments without affecting queues
     * belonging to later waves.
     */
    public int cancelCurrentWave(
            SourceSpawnQueue spawnQueue
    ) {
        if (spawnQueue == null) {
            throw new IllegalArgumentException(
                    "Source spawn queue cannot be null."
            );
        }

        return spawnQueue.cancelRemainingMobs();
    }

    public enum SpawnAttemptResult {
        SPAWNED,
        SPAWN_FAILED,
        QUEUE_EMPTY,
        SOURCE_UNAVAILABLE
    }
}