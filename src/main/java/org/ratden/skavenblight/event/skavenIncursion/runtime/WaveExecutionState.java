package org.ratden.skavenblight.event.skavenIncursion.runtime;

import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.block.entity.state.SourceState;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeadershipContext;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceExecutionState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceWaveExecutionState;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mutable runtime controller for one global incursion wave.
 *
 * The wave owns a collection of wave-specific source assignments. Physical
 * SourceExecutionState objects may still be shared with later waves through
 * IncursionExecutionState.
 *
 * Baseline delivery behaviour:
 *
 * - sources become eligible for creation after a configured delay;
 * - each available source attempts one streamed mob spawn per interval;
 * - failed spawn attempts remain queued for later retries;
 * - destroying a source cancels every current-wave assignment bound to that
 *   physical source;
 * - future-wave assignments are not changed;
 * - a physical source remains ACTIVE while any current-wave queue bound to it
 *   still contains mobs;
 * - a surviving source becomes DORMANT after all of its current-wave queues
 *   finish or are cancelled.
 *
 * This class does not decide when the next wave begins. It only reports when
 * every scheduled or cancelled spawn belonging to this wave is complete.
 */
public class WaveExecutionState {

    private final int waveIndex;

    private final List<SourceWaveExecutionState>
            sourceWaveExecutionStates;

    private final int sourceCreationDelayTicks;
    private final int firstSpawnDelayTicks;
    private final int spawnIntervalTicks;

    private int elapsedTicks;
    private int nextSpawnTick;

    private boolean spawnScheduleComplete;
    private int spawnScheduleCompletedTick;

    private int totalSuccessfulSpawns;
    private int totalCancelledMobs;
    private int totalFailedSpawnAttempts;

    public WaveExecutionState(
            int waveIndex,
            List<SourceWaveExecutionState> sourceWaveExecutionStates,
            int sourceCreationDelayTicks,
            int firstSpawnDelayTicks,
            int spawnIntervalTicks
    ) {
        if (waveIndex < 0) {
            throw new IllegalArgumentException(
                    "Wave index cannot be negative."
            );
        }

        if (sourceWaveExecutionStates == null
                || sourceWaveExecutionStates.isEmpty()) {
            throw new IllegalArgumentException(
                    "Wave execution requires at least one source assignment."
            );
        }

        if (sourceCreationDelayTicks < 0) {
            throw new IllegalArgumentException(
                    "Source creation delay cannot be negative."
            );
        }

        if (firstSpawnDelayTicks < sourceCreationDelayTicks) {
            throw new IllegalArgumentException(
                    "First spawn delay cannot occur before source creation."
            );
        }

        if (spawnIntervalTicks <= 0) {
            throw new IllegalArgumentException(
                    "Spawn interval must be greater than zero."
            );
        }

        validateSourceAssignments(
                waveIndex,
                sourceWaveExecutionStates
        );

        this.waveIndex =
                waveIndex;

        this.sourceWaveExecutionStates =
                List.copyOf(
                        sourceWaveExecutionStates
                );

        this.sourceCreationDelayTicks =
                sourceCreationDelayTicks;

        this.firstSpawnDelayTicks =
                firstSpawnDelayTicks;

        this.spawnIntervalTicks =
                spawnIntervalTicks;

        this.elapsedTicks = 0;
        this.nextSpawnTick = firstSpawnDelayTicks;

        this.spawnScheduleComplete = false;
        this.spawnScheduleCompletedTick = -1;

        this.totalSuccessfulSpawns = 0;
        this.totalCancelledMobs = 0;
        this.totalFailedSpawnAttempts = 0;
    }

    public int getWaveIndex() {
        return waveIndex;
    }

    public List<SourceWaveExecutionState>
    getSourceWaveExecutionStates() {
        return sourceWaveExecutionStates;
    }

    public int getSourceCreationDelayTicks() {
        return sourceCreationDelayTicks;
    }

    public int getFirstSpawnDelayTicks() {
        return firstSpawnDelayTicks;
    }

    public int getSpawnIntervalTicks() {
        return spawnIntervalTicks;
    }

    public int getElapsedTicks() {
        return elapsedTicks;
    }

    public int getTotalSuccessfulSpawns() {
        return totalSuccessfulSpawns;
    }

    public int getTotalCancelledMobs() {
        return totalCancelledMobs;
    }

    public int getTotalFailedSpawnAttempts() {
        return totalFailedSpawnAttempts;
    }

    public int getRemainingMobCount() {
        int remainingMobCount = 0;

        for (SourceWaveExecutionState sourceWaveState
                : sourceWaveExecutionStates) {

            remainingMobCount +=
                    sourceWaveState.getRemainingMobCount();
        }

        return remainingMobCount;
    }

    public boolean hasRemainingMobs() {
        return getRemainingMobCount() > 0;
    }

    /**
     * Returns whether every planned spawn has either succeeded or been
     * cancelled.
     */
    public boolean isSpawnScheduleComplete() {
        return spawnScheduleComplete;
    }

    /**
     * Returns the wave tick on which the final scheduled spawn completed.
     *
     * Returns -1 while scheduled delivery is still active.
     */
    public int getSpawnScheduleCompletedTick() {
        return spawnScheduleCompletedTick;
    }

    public int getTicksSinceSpawnScheduleCompleted() {
        if (!spawnScheduleComplete) {
            return 0;
        }

        return elapsedTicks
                - spawnScheduleCompletedTick;
    }

    /**
     * Advances source creation and streamed spawning by one server tick.
     *
     * Each source assignment with remaining mobs may attempt at most one
     * spawn whenever the configured spawn interval is reached.
     */
    public TickResult tick(
            ServerLevel level,
            SourceState sourceState,
            LeadershipContext leadershipContext
    ) {
        if (level == null) {
            throw new IllegalArgumentException(
                    "Wave execution level cannot be null."
            );
        }

        if (sourceState == null) {
            throw new IllegalArgumentException(
                    "Wave source state cannot be null."
            );
        }

        if (leadershipContext == null) {
            throw new IllegalArgumentException(
                    "Wave leadership context cannot be null."
            );
        }

        elapsedTicks++;

        if (spawnScheduleComplete) {
            return new TickResult(
                    0,
                    0,
                    0,
                    0,
                    true
            );
        }

        int sourcesCreatedThisTick = 0;
        int sourceCreationFailuresThisTick = 0;
        int successfulSpawnsThisTick = 0;
        int failedSpawnAttemptsThisTick = 0;

        if (elapsedTicks >= sourceCreationDelayTicks) {
            for (SourceWaveExecutionState sourceWaveState
                    : sourceWaveExecutionStates) {

                if (!sourceWaveState.hasRemainingMobs()) {
                    continue;
                }

                SourceExecutionState sourceExecutionState =
                        sourceWaveState.getSourceExecutionState();

                boolean wasAvailable =
                        sourceExecutionState.isAvailable();

                boolean isAvailable =
                        sourceWaveState.ensureSourceAvailable(
                                level,
                                sourceState,
                                leadershipContext
                        );

                if (!wasAvailable
                        && isAvailable) {
                    sourcesCreatedThisTick++;
                }

                if (!isAvailable) {
                    sourceCreationFailuresThisTick++;
                }
            }
        }

        if (elapsedTicks >= nextSpawnTick) {
            for (SourceWaveExecutionState sourceWaveState
                    : sourceWaveExecutionStates) {

                if (!sourceWaveState.hasRemainingMobs()) {
                    continue;
                }

                SourceExecutionState.SpawnAttemptResult result =
                        sourceWaveState.attemptNextSpawn(
                                level,
                                leadershipContext
                        );

                switch (result) {
                    case SPAWNED -> {
                        successfulSpawnsThisTick++;
                        totalSuccessfulSpawns++;
                    }

                    case SPAWN_FAILED -> {
                        failedSpawnAttemptsThisTick++;
                        totalFailedSpawnAttempts++;
                    }

                    case QUEUE_EMPTY,
                         SOURCE_UNAVAILABLE -> {
                        /*
                         * An unavailable source may be created successfully on
                         * a later tick. An empty queue requires no action.
                         */
                    }
                }
            }

            nextSpawnTick =
                    elapsedTicks
                            + spawnIntervalTicks;
        }

        synchronisePhysicalSourceStates(
                level
        );

        updateSpawnScheduleCompletion();

        return new TickResult(
                sourcesCreatedThisTick,
                sourceCreationFailuresThisTick,
                successfulSpawnsThisTick,
                failedSpawnAttemptsThisTick,
                spawnScheduleComplete
        );
    }

    /**
     * Handles destruction of one physical source incarnation during this
     * wave.
     *
     * The runtime source ID identifies the exact incarnation that
     * disappeared. When recognised, the persistent planned location is
     * retained for later waves, while every current-wave assignment using
     * that location is cancelled.
     *
     * A recognised source may cancel zero mobs when it was destroyed after
     * completing its scheduled delivery. That destruction is still meaningful
     * and should still be recorded by the owning Scenario.
     */
    public SourceDestructionResult handleSourceDestroyed(
            UUID runtimeSourceId
    ) {
        if (runtimeSourceId == null) {
            throw new IllegalArgumentException(
                    "Destroyed runtime source ID cannot be null."
            );
        }

        SourceExecutionState destroyedSourceState =
                null;

        for (SourceWaveExecutionState sourceWaveState
                : sourceWaveExecutionStates) {

            SourceExecutionState candidateSourceState =
                    sourceWaveState.getSourceExecutionState();

            if (!candidateSourceState
                    .matchesCurrentRuntimeSourceId(
                            runtimeSourceId
                    )) {
                continue;
            }

            destroyedSourceState =
                    candidateSourceState;

            break;
        }

        /*
         * This can occur when the source belongs to another wave, another
         * incursion, or the same destruction event was already processed.
         */
        if (destroyedSourceState == null) {
            return SourceDestructionResult.notRecognised(
                    runtimeSourceId
            );
        }

        UUID sourcePlacementId =
                destroyedSourceState.getSourcePlacementId();

        /*
         * Mark the shared physical incarnation once before cancelling its
         * wave-specific assignments.
         */
        destroyedSourceState.markDestroyed();

        int cancelledNow = 0;

        for (SourceWaveExecutionState sourceWaveState
                : sourceWaveExecutionStates) {

            if (!sourcePlacementId.equals(
                    sourceWaveState.getSourcePlacementId()
            )) {
                continue;
            }

            cancelledNow +=
                    sourceWaveState.cancelRemainingMobs();
        }

        totalCancelledMobs +=
                cancelledNow;

        updateSpawnScheduleCompletion();

        return SourceDestructionResult.recognised(
                runtimeSourceId,
                sourcePlacementId,
                cancelledNow
        );
    }

    /**
     * Cancels all remaining scheduled delivery in this wave.
     *
     * This may later be used by Scenario shutdown, timeout or explicitly
     * authored behaviour.
     *
     * This method changes queue state only. The owning Scenario should decide
     * whether surviving physical sources become dormant, collapse immediately,
     * or use another retirement behaviour.
     */
    public int cancelAllRemainingMobs() {
        int cancelledNow = 0;

        for (SourceWaveExecutionState sourceWaveState
                : sourceWaveExecutionStates) {

            cancelledNow +=
                    sourceWaveState.cancelRemainingMobs();
        }

        totalCancelledMobs +=
                cancelledNow;

        updateSpawnScheduleCompletion();

        return cancelledNow;
    }

    /**
     * Synchronises the operational state of every surviving physical source
     * with all current-wave queues assigned to that source.
     *
     * Several SourceWaveExecutionState objects may share one physical source.
     * One completed queue must therefore not make the source dormant while a
     * different queue bound to that same source still contains mobs.
     */
    private void synchronisePhysicalSourceStates(
            ServerLevel level
    ) {
        Map<UUID, SourceExecutionState>
                sourceStatesByPlacementId =
                new LinkedHashMap<>();

        Map<UUID, Boolean>
                sourceHasRemainingAssignments =
                new LinkedHashMap<>();

        for (SourceWaveExecutionState sourceWaveState
                : sourceWaveExecutionStates) {

            SourceExecutionState sourceExecutionState =
                    sourceWaveState.getSourceExecutionState();

            UUID sourcePlacementId =
                    sourceExecutionState.getSourcePlacementId();

            sourceStatesByPlacementId.putIfAbsent(
                    sourcePlacementId,
                    sourceExecutionState
            );

            if (sourceWaveState.hasRemainingMobs()) {
                sourceHasRemainingAssignments.put(
                        sourcePlacementId,
                        true
                );
            } else {
                sourceHasRemainingAssignments.putIfAbsent(
                        sourcePlacementId,
                        false
                );
            }
        }

        for (Map.Entry<UUID, SourceExecutionState> entry
                : sourceStatesByPlacementId.entrySet()) {

            SourceExecutionState sourceExecutionState =
                    entry.getValue();

            if (!sourceExecutionState.isAvailable()) {
                continue;
            }

            boolean hasRemainingAssignments =
                    sourceHasRemainingAssignments.getOrDefault(
                            entry.getKey(),
                            false
                    );

            SourceState requiredState =
                    hasRemainingAssignments
                            ? SourceState.ACTIVE
                            : SourceState.DORMANT;

            sourceExecutionState.setCurrentSourceState(
                    level,
                    requiredState
            );
        }
    }

    private void updateSpawnScheduleCompletion() {
        if (spawnScheduleComplete) {
            return;
        }

        for (SourceWaveExecutionState sourceWaveState
                : sourceWaveExecutionStates) {

            if (!sourceWaveState.isComplete()) {
                return;
            }
        }

        spawnScheduleComplete = true;
        spawnScheduleCompletedTick = elapsedTicks;
    }

    private static void validateSourceAssignments(
            int waveIndex,
            List<SourceWaveExecutionState> sourceWaveExecutionStates
    ) {
        Set<UUID> sourceCompositionIds =
                new HashSet<>();

        for (SourceWaveExecutionState sourceWaveState
                : sourceWaveExecutionStates) {

            if (sourceWaveState == null) {
                throw new IllegalArgumentException(
                        "Wave source assignment cannot be null."
                );
            }

            if (sourceWaveState.getWaveIndex()
                    != waveIndex) {
                throw new IllegalArgumentException(
                        "Source assignment belongs to wave "
                                + sourceWaveState.getWaveIndex()
                                + " rather than wave "
                                + waveIndex
                                + "."
                );
            }

            UUID sourceCompositionId =
                    sourceWaveState.getSourceCompositionId();

            if (!sourceCompositionIds.add(
                    sourceCompositionId
            )) {
                throw new IllegalArgumentException(
                        "Wave contains duplicate source-composition ID "
                                + sourceCompositionId
                                + "."
                );
            }
        }
    }

    /**
     * Immutable result of routing one physical source-destruction notification
     * through this wave.
     *
     * sourceRecognised distinguishes an unknown or duplicate event from a
     * valid destruction that happened after the source had already spawned
     * every mob.
     */
    public record SourceDestructionResult(
            UUID runtimeSourceId,
            UUID sourcePlacementId,
            boolean sourceRecognised,
            int cancelledMobs
    ) {
        public SourceDestructionResult {
            if (runtimeSourceId == null) {
                throw new IllegalArgumentException(
                        "Destroyed runtime source ID cannot be null."
                );
            }

            if (cancelledMobs < 0) {
                throw new IllegalArgumentException(
                        "Cancelled mob count cannot be negative."
                );
            }

            if (sourceRecognised
                    && sourcePlacementId == null) {
                throw new IllegalArgumentException(
                        "A recognised source destruction requires a source "
                                + "placement ID."
                );
            }

            if (!sourceRecognised
                    && sourcePlacementId != null) {
                throw new IllegalArgumentException(
                        "An unrecognised source destruction cannot have a "
                                + "source placement ID."
                );
            }

            if (!sourceRecognised
                    && cancelledMobs != 0) {
                throw new IllegalArgumentException(
                        "An unrecognised source destruction cannot cancel "
                                + "mobs."
                );
            }
        }

        private static SourceDestructionResult recognised(
                UUID runtimeSourceId,
                UUID sourcePlacementId,
                int cancelledMobs
        ) {
            return new SourceDestructionResult(
                    runtimeSourceId,
                    sourcePlacementId,
                    true,
                    cancelledMobs
            );
        }

        private static SourceDestructionResult notRecognised(
                UUID runtimeSourceId
        ) {
            return new SourceDestructionResult(
                    runtimeSourceId,
                    null,
                    false,
                    0
            );
        }
    }

    /**
     * Immutable report describing the work performed during one wave tick.
     */
    public record TickResult(
            int sourcesCreated,
            int sourceCreationFailures,
            int successfulSpawns,
            int failedSpawnAttempts,
            boolean spawnScheduleComplete
    ) {
        public TickResult {
            if (sourcesCreated < 0
                    || sourceCreationFailures < 0
                    || successfulSpawns < 0
                    || failedSpawnAttempts < 0) {
                throw new IllegalArgumentException(
                        "Wave tick counts cannot be negative."
                );
            }
        }
    }
}