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
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 *
 * This state can produce an immutable Snapshot and later be restored against
 * the same immutable SourcePlacementPlan.
 *
 * Snapshot restoration is logical only. It does not assume that the recorded
 * physical block is already loaded or still present in the world. A later
 * world-reconciliation stage will verify that relationship before runtime
 * resumes.
 */
public class SourceExecutionState {

    private final SourcePlacementPlan sourcePlacementPlan;
    private final List<UUID> runtimeSourceIdHistory;

    private UUID runtimeSourceId;
    private SourceState currentSourceState;

    private boolean currentlyDestroyed;
    private int destructionCount;

    /**
     * Creates fresh runtime state for a physical source that has not yet been
     * created.
     */
    public SourceExecutionState(
            SourcePlacementPlan sourcePlacementPlan
    ) {
        validateSourcePlacementPlan(
                sourcePlacementPlan
        );

        this.sourcePlacementPlan =
                sourcePlacementPlan;

        this.runtimeSourceIdHistory =
                new ArrayList<>();

        this.runtimeSourceId = null;
        this.currentSourceState = null;

        this.currentlyDestroyed = false;
        this.destructionCount = 0;

        validateInternalState();
    }

    /**
     * Restores logical source progress from an immutable snapshot.
     *
     * The source placement ID must match the supplied immutable plan.
     */
    public static SourceExecutionState restore(
            SourcePlacementPlan sourcePlacementPlan,
            Snapshot snapshot
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Source execution snapshot cannot be null."
            );
        }

        SourceExecutionState restoredState =
                new SourceExecutionState(
                        sourcePlacementPlan
                );

        restoredState.applySnapshot(
                snapshot
        );

        return restoredState;
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
     * Returns whether a physical source incarnation currently exists
     * according to logical runtime state.
     *
     * A dormant or collapsed source may still be physically available. The
     * SourceState determines its operational lifecycle state.
     *
     * After restoration, world reconciliation must confirm that the recorded
     * source actually exists before ordinary ticking resumes.
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
     * Captures the complete mutable history and current state of this planned
     * source location.
     */
    public Snapshot createSnapshot() {
        return new Snapshot(
                getSourcePlacementId(),
                runtimeSourceIdHistory,
                runtimeSourceId,
                currentSourceState,
                currentlyDestroyed,
                destructionCount
        );
    }

    /**
     * Changes the state of the current physical source incarnation.
     *
     * The physical tunnel block is updated first. Logical runtime changes only
     * after the world confirms that it already has, or successfully accepted,
     * the requested SourceState.
     *
     * This also verifies the physical source when the logical runtime already
     * records the requested state.
     *
     * @return true when the physical source exists with the requested state
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

        boolean worldAcceptedState =
                SetSourceState.execute(
                        level,
                        getSourcePos(),
                        sourceState
                );

        if (!worldAcceptedState) {
            /*
             * Do not allow logical runtime to claim a state that the
             * physical source block does not possess.
             */
            return false;
        }

        currentSourceState =
                sourceState;

        validateInternalState();

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

        if (runtimeSourceIdHistory.contains(
                createdSourceId
        )) {
            throw new IllegalStateException(
                    "Physical source creation reused runtime source ID "
                            + createdSourceId
                            + " for source placement "
                            + getSourcePlacementId()
                            + "."
            );
        }

        runtimeSourceId =
                createdSourceId;

        currentSourceState =
                sourceState;

        runtimeSourceIdHistory.add(
                createdSourceId
        );

        currentlyDestroyed =
                false;

        validateInternalState();

        return true;
    }

    /**
     * Attempts to spawn one explicitly selected planned mob.
     *
     * This method does not alter a SourceSpawnQueue. The wave-specific caller
     * remains responsible for committing queue progress after any attached
     * modifier and entity binding have succeeded.
     *
     * This separation prevents a failed attached-mob modifier from consuming
     * the already-budgeted mob it was meant to promote.
     */
    public SpawnAttempt attemptSpawnMob(
            ServerLevel level,
            String mobId,
            LeadershipContext leadershipContext
    ) {
        if (level == null) {
            throw new IllegalArgumentException(
                    "Spawn level cannot be null."
            );
        }

        if (mobId == null
                || mobId.isBlank()) {

            throw new IllegalArgumentException(
                    "Explicit source-spawn mob ID cannot be blank."
            );
        }

        if (leadershipContext == null) {
            throw new IllegalArgumentException(
                    "Leadership context cannot be null."
            );
        }

        if (!isAvailable()) {
            return SpawnAttempt.sourceUnavailable();
        }

        IncursionMobSpawner.SpawnedMob spawnedMob =
                IncursionMobSpawner.spawnOne(
                        mobId,
                        level,
                        getSourcePos(),
                        leadershipContext,
                        runtimeSourceId
                );

        if (spawnedMob == null) {
            return SpawnAttempt.spawnFailed(
                    mobId
            );
        }

        if (!mobId.equals(
                spawnedMob.mobId()
        )) {
            spawnedMob.entity().discard();

            throw new IllegalStateException(
                    "Explicit source spawn requested mob ID "
                            + mobId
                            + " but the runtime spawner returned "
                            + spawnedMob.mobId()
                            + "."
            );
        }

        return SpawnAttempt.spawned(
                spawnedMob.mobId(),
                spawnedMob.entity()
        );
    }

    /**
     * Attempts to spawn the next mob from one wave-specific queue.
     *
     * The queue is reduced only after exactly one entity has successfully
     * entered the ServerLevel. Failed attempts leave the planned mob pending.
     *
     * The returned SpawnAttempt contains the actual entity when successful so
     * the wave-specific runtime can apply attached complexity and create a
     * persistent entity binding.
     */
    public SpawnAttempt attemptNextSpawn(
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
            return SpawnAttempt.sourceUnavailable();
        }

        String mobId =
                spawnQueue.peekNextMobId();

        if (mobId == null) {
            return SpawnAttempt.queueEmpty();
        }

        IncursionMobSpawner.SpawnedMob spawnedMob =
                IncursionMobSpawner.spawnOne(
                        mobId,
                        level,
                        getSourcePos(),
                        leadershipContext,
                        runtimeSourceId
                );

        if (spawnedMob == null) {
            return SpawnAttempt.spawnFailed(
                    mobId
            );
        }

        if (!mobId.equals(
                spawnedMob.mobId()
        )) {
            throw new IllegalStateException(
                    "Source queue requested mob ID "
                            + mobId
                            + " but the runtime spawner returned "
                            + spawnedMob.mobId()
                            + "."
            );
        }

        spawnQueue.markNextMobSpawned(
                mobId
        );

        return SpawnAttempt.spawned(
                spawnedMob.mobId(),
                spawnedMob.entity()
        );
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

        runtimeSourceId =
                null;

        currentSourceState =
                null;

        currentlyDestroyed =
                true;

        destructionCount++;

        validateInternalState();
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

    private void applySnapshot(
            Snapshot snapshot
    ) {
        if (!getSourcePlacementId().equals(
                snapshot.sourcePlacementId()
        )) {
            throw new IllegalArgumentException(
                    "Source execution snapshot belongs to placement "
                            + snapshot.sourcePlacementId()
                            + " but runtime state belongs to placement "
                            + getSourcePlacementId()
                            + "."
            );
        }

        runtimeSourceIdHistory.clear();

        runtimeSourceIdHistory.addAll(
                snapshot.runtimeSourceIdHistory()
        );

        runtimeSourceId =
                snapshot.runtimeSourceId();

        currentSourceState =
                snapshot.currentSourceState();

        currentlyDestroyed =
                snapshot.currentlyDestroyed();

        destructionCount =
                snapshot.destructionCount();

        validateInternalState();
    }

    private void validateInternalState() {
        validateStateValues(
                getSourcePlacementId(),
                runtimeSourceIdHistory,
                runtimeSourceId,
                currentSourceState,
                currentlyDestroyed,
                destructionCount
        );
    }

    private static void validateSourcePlacementPlan(
            SourcePlacementPlan sourcePlacementPlan
    ) {
        if (sourcePlacementPlan == null) {
            throw new IllegalArgumentException(
                    "Source placement plan cannot be null."
            );
        }

        if (sourcePlacementPlan.getSourcePlacementId()
                == null) {
            throw new IllegalArgumentException(
                    "Runtime source placement has no placement ID."
            );
        }

        if (!sourcePlacementPlan.hasPlacedPos()) {
            throw new IllegalArgumentException(
                    "Runtime source requires a completed source placement."
            );
        }
    }

    /**
     * Validates the lifecycle relationship between incarnation history,
     * current incarnation and destruction count.
     *
     * Under the current runtime model:
     *
     * - before first creation, history and destruction count are empty;
     * - while available, the current ID is the last history entry;
     * - after destruction, no current ID or SourceState remains;
     * - every historical incarnation is either currently alive or has been
     *   destroyed exactly once.
     */
    private static void validateStateValues(
            UUID sourcePlacementId,
            List<UUID> runtimeSourceIdHistory,
            UUID runtimeSourceId,
            SourceState currentSourceState,
            boolean currentlyDestroyed,
            int destructionCount
    ) {
        if (sourcePlacementId == null) {
            throw new IllegalArgumentException(
                    "Source execution placement ID cannot be null."
            );
        }

        if (runtimeSourceIdHistory == null) {
            throw new IllegalArgumentException(
                    "Runtime source ID history cannot be null."
            );
        }

        if (destructionCount < 0) {
            throw new IllegalArgumentException(
                    "Source destruction count cannot be negative."
            );
        }

        Set<UUID> uniqueRuntimeSourceIds =
                new HashSet<>();

        for (UUID historicalRuntimeSourceId
                : runtimeSourceIdHistory) {

            if (historicalRuntimeSourceId == null) {
                throw new IllegalArgumentException(
                        "Runtime source ID history cannot contain null."
                );
            }

            if (!uniqueRuntimeSourceIds.add(
                    historicalRuntimeSourceId
            )) {
                throw new IllegalArgumentException(
                        "Runtime source ID history contains duplicate ID "
                                + historicalRuntimeSourceId
                                + "."
                );
            }
        }

        if (runtimeSourceId == null
                && currentSourceState != null) {
            throw new IllegalArgumentException(
                    "A source without a current runtime ID cannot retain "
                            + "SourceState "
                            + currentSourceState
                            + "."
            );
        }

        if (runtimeSourceId != null
                && currentSourceState == null) {
            throw new IllegalArgumentException(
                    "A source with current runtime ID "
                            + runtimeSourceId
                            + " must have a current SourceState."
            );
        }

        if (currentlyDestroyed
                && runtimeSourceId != null) {
            throw new IllegalArgumentException(
                    "A destroyed source cannot retain current runtime ID "
                            + runtimeSourceId
                            + "."
            );
        }

        if (currentlyDestroyed
                && currentSourceState != null) {
            throw new IllegalArgumentException(
                    "A destroyed source cannot retain SourceState "
                            + currentSourceState
                            + "."
            );
        }

        if (runtimeSourceIdHistory.isEmpty()) {
            if (runtimeSourceId != null
                    || currentSourceState != null
                    || currentlyDestroyed
                    || destructionCount != 0) {

                throw new IllegalArgumentException(
                        "A source with no incarnation history must remain in "
                                + "its uncreated initial state."
                );
            }

            return;
        }

        UUID lastRuntimeSourceId =
                runtimeSourceIdHistory.get(
                        runtimeSourceIdHistory.size() - 1
                );

        if (runtimeSourceId != null
                && !runtimeSourceId.equals(
                lastRuntimeSourceId
        )) {
            throw new IllegalArgumentException(
                    "Current runtime source ID "
                            + runtimeSourceId
                            + " is not the most recent incarnation "
                            + lastRuntimeSourceId
                            + "."
            );
        }

        if (runtimeSourceId == null
                && !currentlyDestroyed) {
            throw new IllegalArgumentException(
                    "A source with incarnation history but no current runtime "
                            + "ID must be marked destroyed."
            );
        }

        int expectedDestructionCount =
                runtimeSourceId == null
                        ? runtimeSourceIdHistory.size()
                        : runtimeSourceIdHistory.size() - 1;

        if (destructionCount
                != expectedDestructionCount) {
            throw new IllegalArgumentException(
                    "Source placement "
                            + sourcePlacementId
                            + " has "
                            + runtimeSourceIdHistory.size()
                            + " recorded physical incarnations and current "
                            + "runtime ID "
                            + runtimeSourceId
                            + ", so its destruction count must be "
                            + expectedDestructionCount
                            + " rather than "
                            + destructionCount
                            + "."
            );
        }
    }

    /**
     * Immutable persistence snapshot for one persistent physical source
     * location.
     *
     * The position, type, size, role and composition bindings remain owned by
     * the immutable SourcePlacementPlan.
     */
    public record Snapshot(
            UUID sourcePlacementId,
            List<UUID> runtimeSourceIdHistory,
            UUID runtimeSourceId,
            SourceState currentSourceState,
            boolean currentlyDestroyed,
            int destructionCount
    ) {

        public Snapshot {
            if (runtimeSourceIdHistory == null) {
                throw new IllegalArgumentException(
                        "Source execution snapshot history cannot be null."
                );
            }

            runtimeSourceIdHistory =
                    List.copyOf(
                            runtimeSourceIdHistory
                    );

            validateStateValues(
                    sourcePlacementId,
                    runtimeSourceIdHistory,
                    runtimeSourceId,
                    currentSourceState,
                    currentlyDestroyed,
                    destructionCount
            );
        }

        public boolean hasBeenCreated() {
            return !runtimeSourceIdHistory.isEmpty();
        }

        public boolean isAvailable() {
            return runtimeSourceId != null
                    && !currentlyDestroyed;
        }
    }

    /**
     * Complete result of one streamed source-spawn attempt.
     *
     * Only SPAWNED contains an entity. SPAWN_FAILED retains the requested mob
     * ID for diagnostics, while outcomes that occur before queue selection do
     * not contain a mob ID.
     */
    public record SpawnAttempt(
            SpawnAttemptResult result,
            String mobId,
            Entity spawnedEntity
    ) {

        public SpawnAttempt {
            if (result == null) {
                throw new IllegalArgumentException(
                        "Spawn-attempt result cannot be null."
                );
            }

            switch (result) {
                case SPAWNED -> {
                    if (mobId == null
                            || mobId.isBlank()) {

                        throw new IllegalArgumentException(
                                "A successful spawn attempt requires a mob "
                                        + "ID."
                        );
                    }

                    if (spawnedEntity == null) {
                        throw new IllegalArgumentException(
                                "A successful spawn attempt requires the "
                                        + "spawned entity."
                        );
                    }

                    if (spawnedEntity.isRemoved()) {
                        throw new IllegalArgumentException(
                                "A successful spawn attempt cannot contain a "
                                        + "removed entity."
                        );
                    }
                }

                case SPAWN_FAILED -> {
                    if (mobId == null
                            || mobId.isBlank()) {

                        throw new IllegalArgumentException(
                                "A failed spawn attempt requires the requested "
                                        + "mob ID."
                        );
                    }

                    if (spawnedEntity != null) {
                        throw new IllegalArgumentException(
                                "A failed spawn attempt cannot contain a "
                                        + "spawned entity."
                        );
                    }
                }

                case QUEUE_EMPTY, SOURCE_UNAVAILABLE -> {
                    if (mobId != null
                            || spawnedEntity != null) {

                        throw new IllegalArgumentException(
                                "A spawn attempt that did not select a mob "
                                        + "cannot contain mob or entity data."
                        );
                    }
                }
            }
        }

        public boolean successfullySpawned() {
            return result == SpawnAttemptResult.SPAWNED;
        }

        public static SpawnAttempt spawned(
                String mobId,
                Entity spawnedEntity
        ) {
            return new SpawnAttempt(
                    SpawnAttemptResult.SPAWNED,
                    mobId,
                    spawnedEntity
            );
        }

        public static SpawnAttempt spawnFailed(
                String mobId
        ) {
            return new SpawnAttempt(
                    SpawnAttemptResult.SPAWN_FAILED,
                    mobId,
                    null
            );
        }

        public static SpawnAttempt queueEmpty() {
            return new SpawnAttempt(
                    SpawnAttemptResult.QUEUE_EMPTY,
                    null,
                    null
            );
        }

        public static SpawnAttempt sourceUnavailable() {
            return new SpawnAttempt(
                    SpawnAttemptResult.SOURCE_UNAVAILABLE,
                    null,
                    null
            );
        }
    }

    public enum SpawnAttemptResult {
        SPAWNED,
        SPAWN_FAILED,
        QUEUE_EMPTY,
        SOURCE_UNAVAILABLE
    }
}