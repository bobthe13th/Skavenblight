package org.ratden.skavenblight.event.skavenIncursion.runtime;

import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.block.entity.state.SourceState;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeadershipContext;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceExecutionState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceWaveExecutionState;

import java.util.List;
import java.util.UUID;

/**
 * Reusable runtime controller that progresses through every planned wave in
 * one incursion.
 *
 * IncursionExecutionState owns the persistent source and wave-assignment
 * objects. This controller decides which wave is currently executing and
 * when execution advances to the next planned wave.
 *
 * Initial transition behaviour is deliberately simple:
 *
 * - execute the current wave's complete spawn schedule;
 * - wait for a fixed grace period;
 * - begin the next planned wave;
 * - report completion after the final wave's grace period.
 *
 * Remaining-threat-based hybrid pacing can later replace the fixed transition
 * rule without moving wave orchestration into ActiveIncursionManager or an
 * individual Scenario.
 */
public class IncursionWaveController {

    private final IncursionExecutionState incursionExecutionState;
    private final List<Integer> waveIndexes;

    private final int sourceCreationDelayTicks;
    private final int firstSpawnDelayTicks;
    private final int spawnIntervalTicks;
    private final int waveTransitionGraceTicks;

    private int currentWavePosition;
    private int completedWaveCount;

    private WaveExecutionState currentWaveExecutionState;
    private WaveExecutionState lastCompletedWaveExecutionState;

    private boolean allWavesComplete;

    public IncursionWaveController(
            IncursionExecutionState incursionExecutionState,
            int sourceCreationDelayTicks,
            int firstSpawnDelayTicks,
            int spawnIntervalTicks,
            int waveTransitionGraceTicks
    ) {
        if (incursionExecutionState == null) {
            throw new IllegalArgumentException(
                    "Incursion execution state cannot be null."
            );
        }

        if (incursionExecutionState.getWaveCount() <= 0) {
            throw new IllegalArgumentException(
                    "Wave controller requires at least one planned wave."
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

        if (waveTransitionGraceTicks < 0) {
            throw new IllegalArgumentException(
                    "Wave transition grace period cannot be negative."
            );
        }

        this.incursionExecutionState =
                incursionExecutionState;

        this.waveIndexes =
                List.copyOf(
                        incursionExecutionState.getWaveIndexes()
                );

        this.sourceCreationDelayTicks =
                sourceCreationDelayTicks;

        this.firstSpawnDelayTicks =
                firstSpawnDelayTicks;

        this.spawnIntervalTicks =
                spawnIntervalTicks;

        this.waveTransitionGraceTicks =
                waveTransitionGraceTicks;

        this.currentWavePosition = 0;
        this.completedWaveCount = 0;

        this.currentWaveExecutionState =
                createWaveExecutionState(
                        waveIndexes.getFirst()
                );

        this.lastCompletedWaveExecutionState = null;
        this.allWavesComplete = false;
    }

    public IncursionExecutionState getIncursionExecutionState() {
        return incursionExecutionState;
    }

    public List<Integer> getWaveIndexes() {
        return waveIndexes;
    }

    public int getTotalWaveCount() {
        return waveIndexes.size();
    }

    public int getCompletedWaveCount() {
        return completedWaveCount;
    }

    public int getRemainingWaveCount() {
        return getTotalWaveCount()
                - completedWaveCount;
    }

    public boolean hasCurrentWave() {
        return currentWaveExecutionState != null
                && !allWavesComplete;
    }

    /**
     * Returns the currently executing wave.
     *
     * Returns null after every planned wave has completed.
     */
    public WaveExecutionState getCurrentWaveExecutionState() {
        return hasCurrentWave()
                ? currentWaveExecutionState
                : null;
    }

    public int getCurrentWaveIndex() {
        if (!hasCurrentWave()) {
            return -1;
        }

        return currentWaveExecutionState.getWaveIndex();
    }

    /**
     * Returns the most recently completed wave.
     *
     * This remains available after the complete incursion wave schedule has
     * finished and is useful for reporting and debugging.
     */
    public WaveExecutionState getLastCompletedWaveExecutionState() {
        return lastCompletedWaveExecutionState;
    }

    public boolean areAllWavesComplete() {
        return allWavesComplete;
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

    public int getWaveTransitionGraceTicks() {
        return waveTransitionGraceTicks;
    }

    /**
     * Advances the current wave by one server tick.
     *
     * A newly selected wave begins ticking on the following server tick rather
     * than immediately consuming the transition tick.
     */
    public TickResult tick(
            ServerLevel level,
            LeadershipContext leadershipContext
    ) {
        if (level == null) {
            throw new IllegalArgumentException(
                    "Wave-controller level cannot be null."
            );
        }

        if (leadershipContext == null) {
            throw new IllegalArgumentException(
                    "Wave-controller leadership context cannot be null."
            );
        }

        if (allWavesComplete
                || currentWaveExecutionState == null) {
            return TickResult.complete();
        }

        int tickedWaveIndex =
                currentWaveExecutionState.getWaveIndex();

        WaveExecutionState.TickResult waveTickResult =
                currentWaveExecutionState.tick(
                        level,
                        SourceState.ACTIVE,
                        leadershipContext
                );

        boolean advancedToNextWave = false;

        if (shouldCompleteCurrentWave()) {
            completeCurrentWave();

            if (hasAnotherPlannedWave()) {
                beginNextWave();

                advancedToNextWave = true;
            } else {
                allWavesComplete = true;
                currentWaveExecutionState = null;
            }
        }

        return new TickResult(
                tickedWaveIndex,
                getCurrentWaveIndex(),
                waveTickResult.sourcesCreated(),
                waveTickResult.sourceCreationFailures(),
                waveTickResult.successfulSpawns(),
                waveTickResult.failedSpawnAttempts(),
                advancedToNextWave,
                allWavesComplete
        );
    }

    /**
     * Routes destruction of one physical source incarnation.
     *
     * The current wave gets the first opportunity to consume the event because
     * it may need to cancel queued mobs.
     *
     * A surviving source from an earlier wave may be dormant and absent from
     * the current wave. In that case the controller still recognises and
     * records destruction of the persistent physical source, but no
     * current-wave queue is cancelled.
     */
    public SourceDestructionResult handleSourceDestroyed(
            UUID runtimeSourceId
    ) {
        if (runtimeSourceId == null) {
            throw new IllegalArgumentException(
                    "Destroyed runtime source ID cannot be null."
            );
        }

        if (currentWaveExecutionState != null) {
            WaveExecutionState.SourceDestructionResult waveResult =
                    currentWaveExecutionState.handleSourceDestroyed(
                            runtimeSourceId
                    );

            if (waveResult.sourceRecognised()) {
                return SourceDestructionResult.recognised(
                        runtimeSourceId,
                        waveResult.sourcePlacementId(),
                        true,
                        waveResult.cancelledMobs()
                );
            }
        }

        /*
         * The source may be a dormant survivor from a previous wave that has
         * no assignment in the current wave.
         */
        for (SourceExecutionState sourceExecutionState
                : incursionExecutionState
                .getSourceExecutionStates()) {

            if (!sourceExecutionState
                    .matchesCurrentRuntimeSourceId(
                            runtimeSourceId
                    )) {
                continue;
            }

            UUID sourcePlacementId =
                    sourceExecutionState.getSourcePlacementId();

            sourceExecutionState.markDestroyed();

            return SourceDestructionResult.recognised(
                    runtimeSourceId,
                    sourcePlacementId,
                    false,
                    0
            );
        }

        /*
         * This includes duplicate notifications and events belonging to a
         * different active incursion.
         */
        return SourceDestructionResult.notRecognised(
                runtimeSourceId
        );
    }

    /**
     * Cancels the current and every not-yet-started wave assignment.
     *
     * This is intended for Scenario timeout, forced withdrawal or shutdown.
     * The owning Scenario remains responsible for collapsing or otherwise
     * retiring surviving physical source blocks.
     *
     * Returns the number of mobs cancelled across all remaining waves.
     */
    public int cancelAllRemainingMobs() {
        if (allWavesComplete) {
            return 0;
        }

        int cancelledMobs = 0;

        if (currentWaveExecutionState != null) {
            cancelledMobs +=
                    currentWaveExecutionState
                            .cancelAllRemainingMobs();
        }

        for (int wavePosition =
             currentWavePosition + 1;
             wavePosition < waveIndexes.size();
             wavePosition++) {

            int waveIndex =
                    waveIndexes.get(
                            wavePosition
                    );

            for (SourceWaveExecutionState sourceWaveExecutionState
                    : incursionExecutionState
                    .getWaveSourceStates(
                            waveIndex
                    )) {

                cancelledMobs +=
                        sourceWaveExecutionState
                                .cancelRemainingMobs();
            }
        }

        if (currentWaveExecutionState != null) {
            lastCompletedWaveExecutionState =
                    currentWaveExecutionState;
        }

        completedWaveCount =
                waveIndexes.size();

        currentWaveExecutionState = null;
        allWavesComplete = true;

        return cancelledMobs;
    }

    private boolean shouldCompleteCurrentWave() {
        return currentWaveExecutionState
                .isSpawnScheduleComplete()
                && currentWaveExecutionState
                .getTicksSinceSpawnScheduleCompleted()
                >= waveTransitionGraceTicks;
    }

    private void completeCurrentWave() {
        lastCompletedWaveExecutionState =
                currentWaveExecutionState;

        completedWaveCount++;
    }

    private boolean hasAnotherPlannedWave() {
        return currentWavePosition + 1
                < waveIndexes.size();
    }

    private void beginNextWave() {
        currentWavePosition++;

        int nextWaveIndex =
                waveIndexes.get(
                        currentWavePosition
                );

        currentWaveExecutionState =
                createWaveExecutionState(
                        nextWaveIndex
                );
    }

    private WaveExecutionState createWaveExecutionState(
            int waveIndex
    ) {
        List<SourceWaveExecutionState> sourceWaveStates =
                incursionExecutionState.getWaveSourceStates(
                        waveIndex
                );

        if (sourceWaveStates.isEmpty()) {
            throw new IllegalArgumentException(
                    "Planned wave "
                            + waveIndex
                            + " contains no runtime source assignments."
            );
        }

        return new WaveExecutionState(
                waveIndex,
                sourceWaveStates,
                sourceCreationDelayTicks,
                firstSpawnDelayTicks,
                spawnIntervalTicks
        );
    }

    /**
     * Immutable report describing one controller tick.
     *
     * currentWaveIndex is -1 when the final wave completed during this tick or
     * when the controller had already completed before it was called.
     */
    public record TickResult(
            int tickedWaveIndex,
            int currentWaveIndex,
            int sourcesCreated,
            int sourceCreationFailures,
            int successfulSpawns,
            int failedSpawnAttempts,
            boolean advancedToNextWave,
            boolean allWavesComplete
    ) {
        public TickResult {
            if (tickedWaveIndex < -1) {
                throw new IllegalArgumentException(
                        "Ticked wave index cannot be below -1."
                );
            }

            if (currentWaveIndex < -1) {
                throw new IllegalArgumentException(
                        "Current wave index cannot be below -1."
                );
            }

            if (sourcesCreated < 0
                    || sourceCreationFailures < 0
                    || successfulSpawns < 0
                    || failedSpawnAttempts < 0) {
                throw new IllegalArgumentException(
                        "Wave-controller tick counts cannot be negative."
                );
            }

            if (advancedToNextWave
                    && currentWaveIndex < 0) {
                throw new IllegalArgumentException(
                        "Advancing to another wave requires a current wave."
                );
            }

            if (allWavesComplete
                    && currentWaveIndex != -1) {
                throw new IllegalArgumentException(
                        "A completed wave controller cannot expose a current "
                                + "wave index."
                );
            }
        }

        private static TickResult complete() {
            return new TickResult(
                    -1,
                    -1,
                    0,
                    0,
                    0,
                    0,
                    false,
                    true
            );
        }
    }

    /**
     * Immutable result of routing one source-destruction event through the
     * complete incursion wave runtime.
     *
     * currentWaveAffected is false when a dormant source from an earlier wave
     * was destroyed without cancelling any current-wave assignments.
     */
    public record SourceDestructionResult(
            UUID runtimeSourceId,
            UUID sourcePlacementId,
            boolean sourceRecognised,
            boolean currentWaveAffected,
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
                        "An unrecognised source destruction cannot expose a "
                                + "source placement ID."
                );
            }

            if (!sourceRecognised
                    && currentWaveAffected) {
                throw new IllegalArgumentException(
                        "An unrecognised source cannot affect the current "
                                + "wave."
                );
            }

            if (!currentWaveAffected
                    && cancelledMobs != 0) {
                throw new IllegalArgumentException(
                        "A destruction outside the current wave cannot cancel "
                                + "current-wave mobs."
                );
            }
        }

        private static SourceDestructionResult recognised(
                UUID runtimeSourceId,
                UUID sourcePlacementId,
                boolean currentWaveAffected,
                int cancelledMobs
        ) {
            return new SourceDestructionResult(
                    runtimeSourceId,
                    sourcePlacementId,
                    true,
                    currentWaveAffected,
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
                    false,
                    0
            );
        }
    }
}