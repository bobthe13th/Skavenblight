package org.ratden.skavenblight.event.skavenIncursion.runtime;

import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.block.entity.state.SourceState;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeadershipContext;
import org.ratden.skavenblight.event.skavenIncursion.runtime.mob.IncursionMobTrackingState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceExecutionState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceWaveExecutionState;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reusable runtime controller that progresses through every planned wave in
 * one incursion.
 *
 * IncursionExecutionState owns the persistent physical-source and
 * wave-assignment objects. This controller decides:
 *
 * - which planned wave is currently executing;
 * - how the current wave is timed;
 * - when the transition grace period has elapsed;
 * - when execution advances to the next wave;
 * - whether the complete schedule finished normally or was cancelled.
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
 *
 * The controller can produce an immutable Snapshot and later be restored
 * against an already-restored IncursionExecutionState.
 *
 * Restoration is strict:
 *
 * - the incursion ID must match;
 * - the ordered wave indexes must match;
 * - timing configuration must match;
 * - current and completed-wave positions must be coherent;
 * - current and last-completed WaveExecutionState objects must use the exact
 *   shared SourceWaveExecutionState objects owned by IncursionExecutionState;
 * - duplicated source progress in wave snapshots must exactly match the
 *   restored incursion execution graph.
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

    private CompletionReason completionReason;

    /**
     * Creates a fresh controller beginning at the first planned wave.
     */
    public IncursionWaveController(
            IncursionExecutionState incursionExecutionState,
            int sourceCreationDelayTicks,
            int firstSpawnDelayTicks,
            int spawnIntervalTicks,
            int waveTransitionGraceTicks
    ) {
        validateConfiguration(
                incursionExecutionState,
                sourceCreationDelayTicks,
                firstSpawnDelayTicks,
                spawnIntervalTicks,
                waveTransitionGraceTicks
        );

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

        this.currentWavePosition =
                0;

        this.completedWaveCount =
                0;

        this.currentWaveExecutionState =
                createWaveExecutionState(
                        waveIndexes.getFirst()
                );

        this.lastCompletedWaveExecutionState =
                null;

        this.completionReason =
                CompletionReason.IN_PROGRESS;

        validateInternalState();
    }

    /**
     * Canonical restoration constructor.
     *
     * Every final field is assigned directly by this constructor.
     */
    private IncursionWaveController(
            IncursionExecutionState incursionExecutionState,
            int sourceCreationDelayTicks,
            int firstSpawnDelayTicks,
            int spawnIntervalTicks,
            int waveTransitionGraceTicks,
            int currentWavePosition,
            int completedWaveCount,
            WaveExecutionState currentWaveExecutionState,
            WaveExecutionState lastCompletedWaveExecutionState,
            CompletionReason completionReason
    ) {
        validateConfiguration(
                incursionExecutionState,
                sourceCreationDelayTicks,
                firstSpawnDelayTicks,
                spawnIntervalTicks,
                waveTransitionGraceTicks
        );

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

        this.currentWavePosition =
                currentWavePosition;

        this.completedWaveCount =
                completedWaveCount;

        this.currentWaveExecutionState =
                currentWaveExecutionState;

        this.lastCompletedWaveExecutionState =
                lastCompletedWaveExecutionState;

        this.completionReason =
                completionReason;

        validateInternalState();
    }

    /**
     * Restores one wave controller against an already-restored shared
     * IncursionExecutionState.
     *
     * IncursionExecutionState must be restored first because its
     * SourceWaveExecutionState objects are the shared assignments used by the
     * restored current and last-completed waves.
     */
    public static IncursionWaveController restore(
            IncursionExecutionState incursionExecutionState,
            Snapshot snapshot
    ) {
        if (incursionExecutionState == null) {
            throw new IllegalArgumentException(
                    "Incursion execution state cannot be null."
            );
        }

        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Incursion wave-controller snapshot cannot be null."
            );
        }

        if (!incursionExecutionState
                .getIncursionId()
                .equals(
                        snapshot.incursionId()
                )) {

            throw new IllegalArgumentException(
                    "Wave-controller snapshot belongs to incursion "
                            + snapshot.incursionId()
                            + " but supplied execution state belongs to "
                            + incursionExecutionState.getIncursionId()
                            + "."
            );
        }

        List<Integer> plannedWaveIndexes =
                incursionExecutionState.getWaveIndexes();

        if (!plannedWaveIndexes.equals(
                snapshot.waveIndexes()
        )) {
            throw new IllegalArgumentException(
                    "Wave-controller snapshot contains wave indexes "
                            + snapshot.waveIndexes()
                            + " but the immutable plan contains "
                            + plannedWaveIndexes
                            + "."
            );
        }

        WaveExecutionState restoredCurrentWave =
                restoreWaveExecutionState(
                        incursionExecutionState,
                        snapshot.currentWaveExecutionSnapshot()
                );

        WaveExecutionState restoredLastCompletedWave =
                restoreWaveExecutionState(
                        incursionExecutionState,
                        snapshot.lastCompletedWaveExecutionSnapshot()
                );

        IncursionWaveController restoredController =
                new IncursionWaveController(
                        incursionExecutionState,
                        snapshot.sourceCreationDelayTicks(),
                        snapshot.firstSpawnDelayTicks(),
                        snapshot.spawnIntervalTicks(),
                        snapshot.waveTransitionGraceTicks(),
                        snapshot.currentWavePosition(),
                        snapshot.completedWaveCount(),
                        restoredCurrentWave,
                        restoredLastCompletedWave,
                        snapshot.completionReason()
                );

        Snapshot reconstructedSnapshot =
                restoredController.createSnapshot();

        if (!reconstructedSnapshot.equals(
                snapshot
        )) {
            throw new IllegalArgumentException(
                    "Restored wave controller does not exactly match its "
                            + "saved snapshot for incursion "
                            + snapshot.incursionId()
                            + "."
            );
        }

        return restoredController;
    }

    public IncursionExecutionState getIncursionExecutionState() {
        return incursionExecutionState;
    }

    public UUID getIncursionId() {
        return incursionExecutionState.getIncursionId();
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

    /**
     * Returns the position within the ordered wave-index list.
     *
     * This is not necessarily equal to the authored wave index.
     */
    public int getCurrentWavePosition() {
        return currentWavePosition;
    }

    public boolean hasCurrentWave() {
        return currentWaveExecutionState != null
                && completionReason
                == CompletionReason.IN_PROGRESS;
    }

    /**
     * Returns the currently executing wave.
     *
     * Returns null after normal completion or forced cancellation.
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
     * Returns the most recently completed or force-cancelled active wave.
     *
     * This remains available after the complete schedule finishes and is
     * useful for persistence, reporting and debugging.
     */
    public WaveExecutionState getLastCompletedWaveExecutionState() {
        return lastCompletedWaveExecutionState;
    }

    public CompletionReason getCompletionReason() {
        return completionReason;
    }

    public boolean areAllWavesComplete() {
        return completionReason
                != CompletionReason.IN_PROGRESS;
    }

    public boolean completedNormally() {
        return completionReason
                == CompletionReason.COMPLETED_NORMALLY;
    }

    public boolean wasCancelled() {
        return completionReason
                == CompletionReason.CANCELLED;
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
     * Captures exact controller position, timing, completion state and active
     * wave progress.
     *
     * Current and last-completed wave snapshots contain source-assignment
     * progress already present in IncursionExecutionState.Snapshot. This
     * deliberate duplication is checked during restoration and acts as a
     * consistency check between controller state and the shared execution
     * graph.
     */
    public Snapshot createSnapshot() {
        return new Snapshot(
                getIncursionId(),
                waveIndexes,
                sourceCreationDelayTicks,
                firstSpawnDelayTicks,
                spawnIntervalTicks,
                waveTransitionGraceTicks,
                currentWavePosition,
                completedWaveCount,
                completionReason,
                currentWaveExecutionState == null
                        ? null
                        : currentWaveExecutionState.createSnapshot(),
                lastCompletedWaveExecutionState == null
                        ? null
                        : lastCompletedWaveExecutionState.createSnapshot()
        );
    }

    /**
     * Temporary compatibility route for callers that have not yet propagated
     * persistent mob-tracking ownership into the wave controller.
     *
     * Persistence-aware Scenario runtime should use the three-argument
     * overload. This bridge will be removed after the complete spawn path has
     * been converted.
     */
    @Deprecated
    public TickResult tick(
            ServerLevel level,
            LeadershipContext leadershipContext
    ) {
        return tickInternal(
                level,
                leadershipContext,
                null
        );
    }

    /**
     * Advances the current wave by one server tick using the authoritative
     * persistent mob-tracking state owned by the same incursion.
     *
     * A newly selected wave begins ticking on the following server tick rather
     * than immediately consuming the transition tick.
     */
    public TickResult tick(
            ServerLevel level,
            LeadershipContext leadershipContext,
            IncursionMobTrackingState mobTrackingState
    ) {
        if (mobTrackingState == null) {
            throw new IllegalArgumentException(
                    "Wave-controller mob-tracking state cannot be null."
            );
        }

        if (!getIncursionId().equals(
                mobTrackingState.getIncursionId()
        )) {
            throw new IllegalArgumentException(
                    "Wave-controller incursion ID "
                            + getIncursionId()
                            + " does not match mob-tracking incursion ID "
                            + mobTrackingState.getIncursionId()
                            + "."
            );
        }

        return tickInternal(
                level,
                leadershipContext,
                mobTrackingState
        );
    }

    private TickResult tickInternal(
            ServerLevel level,
            LeadershipContext leadershipContext,
            IncursionMobTrackingState mobTrackingState
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

        if (areAllWavesComplete()
                || currentWaveExecutionState == null) {

            return TickResult.complete();
        }

        int tickedWaveIndex =
                currentWaveExecutionState.getWaveIndex();

        WaveExecutionState.TickResult waveTickResult;

        if (mobTrackingState == null) {
            waveTickResult =
                    currentWaveExecutionState.tick(
                            level,
                            SourceState.ACTIVE,
                            leadershipContext
                    );
        } else {
            waveTickResult =
                    currentWaveExecutionState.tick(
                            level,
                            SourceState.ACTIVE,
                            leadershipContext,
                            mobTrackingState
                    );
        }

        boolean advancedToNextWave =
                false;

        if (shouldCompleteCurrentWave()) {
            completeCurrentWave();

            if (hasAnotherPlannedWave()) {
                beginNextWave();

                advancedToNextWave =
                        true;
            } else {
                currentWaveExecutionState =
                        null;

                completionReason =
                        CompletionReason.COMPLETED_NORMALLY;
            }
        }

        validateInternalState();

        return new TickResult(
                tickedWaveIndex,
                getCurrentWaveIndex(),
                waveTickResult.sourcesCreated(),
                waveTickResult.sourceCreationFailures(),
                waveTickResult.successfulSpawns(),
                waveTickResult.failedSpawnAttempts(),
                advancedToNextWave,
                areAllWavesComplete()
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
                validateInternalState();

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

            validateInternalState();

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
     * This is intended for Scenario timeout, forced withdrawal, invalidation
     * or explicitly authored cancellation.
     *
     * Ordinary server shutdown must persist and restore the controller rather
     * than cancelling it.
     *
     * The owning Scenario remains responsible for collapsing or otherwise
     * retiring surviving physical source blocks.
     *
     * Returns the number of mobs cancelled across all remaining waves.
     */
    public int cancelAllRemainingMobs() {
        if (areAllWavesComplete()) {
            return 0;
        }

        if (currentWaveExecutionState == null) {
            throw new IllegalStateException(
                    "An in-progress wave controller has no current wave to "
                            + "cancel."
            );
        }

        int cancelledMobs =
                currentWaveExecutionState.cancelAllRemainingMobs();

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
                        sourceWaveExecutionState.cancelRemainingMobs();
            }
        }

        lastCompletedWaveExecutionState =
                currentWaveExecutionState;

        completedWaveCount =
                waveIndexes.size();

        currentWaveExecutionState =
                null;

        completionReason =
                CompletionReason.CANCELLED;

        validateInternalState();

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

    private static WaveExecutionState restoreWaveExecutionState(
            IncursionExecutionState incursionExecutionState,
            WaveExecutionState.Snapshot waveSnapshot
    ) {
        if (waveSnapshot == null) {
            return null;
        }

        List<SourceWaveExecutionState> sourceWaveStates =
                incursionExecutionState.getWaveSourceStates(
                        waveSnapshot.waveIndex()
                );

        if (sourceWaveStates.isEmpty()) {
            throw new IllegalArgumentException(
                    "Wave-controller snapshot refers to unknown or empty wave "
                            + waveSnapshot.waveIndex()
                            + "."
            );
        }

        return WaveExecutionState.restore(
                sourceWaveStates,
                waveSnapshot
        );
    }

    private void validateInternalState() {
        validateConfiguration(
                incursionExecutionState,
                sourceCreationDelayTicks,
                firstSpawnDelayTicks,
                spawnIntervalTicks,
                waveTransitionGraceTicks
        );

        if (!waveIndexes.equals(
                incursionExecutionState.getWaveIndexes()
        )) {
            throw new IllegalStateException(
                    "Wave controller no longer contains the same ordered wave "
                            + "indexes as its incursion execution state."
            );
        }

        validateControllerState(
                waveIndexes,
                currentWavePosition,
                completedWaveCount,
                WaveStateView.from(
                        currentWaveExecutionState
                ),
                WaveStateView.from(
                        lastCompletedWaveExecutionState
                ),
                completionReason,
                sourceCreationDelayTicks,
                firstSpawnDelayTicks,
                spawnIntervalTicks
        );

        validateWaveRuntimeBinding(
                currentWaveExecutionState
        );

        validateWaveRuntimeBinding(
                lastCompletedWaveExecutionState
        );

        validateWaveAssignmentProgress();
    }

    /**
     * Confirms that one WaveExecutionState uses the exact shared source-wave
     * assignment objects owned by IncursionExecutionState.
     */
    private void validateWaveRuntimeBinding(
            WaveExecutionState waveExecutionState
    ) {
        if (waveExecutionState == null) {
            return;
        }

        int waveIndex =
                waveExecutionState.getWaveIndex();

        List<SourceWaveExecutionState> expectedAssignments =
                incursionExecutionState.getWaveSourceStates(
                        waveIndex
                );

        List<SourceWaveExecutionState> actualAssignments =
                waveExecutionState.getSourceWaveExecutionStates();

        if (expectedAssignments.size()
                != actualAssignments.size()) {

            throw new IllegalStateException(
                    "Wave "
                            + waveIndex
                            + " contains "
                            + actualAssignments.size()
                            + " controller assignments but "
                            + expectedAssignments.size()
                            + " incursion execution assignments."
            );
        }

        for (int assignmentIndex = 0;
             assignmentIndex < expectedAssignments.size();
             assignmentIndex++) {

            if (actualAssignments.get(
                    assignmentIndex
            ) != expectedAssignments.get(
                    assignmentIndex
            )) {

                throw new IllegalStateException(
                        "Wave "
                                + waveIndex
                                + " assignment "
                                + assignmentIndex
                                + " does not use the shared "
                                + "SourceWaveExecutionState owned by "
                                + "IncursionExecutionState."
                );
            }
        }
    }

    /**
     * Validates broad progression across every wave assignment.
     *
     * Before completion:
     *
     * - waves before the current position must be complete;
     * - future waves must remain untouched.
     *
     * After normal completion or cancellation, every source-wave assignment
     * must be complete.
     */
    private void validateWaveAssignmentProgress() {
        if (areAllWavesComplete()) {
            for (int waveIndex
                    : waveIndexes) {

                for (SourceWaveExecutionState sourceWaveState
                        : incursionExecutionState
                        .getWaveSourceStates(
                                waveIndex
                        )) {

                    if (!sourceWaveState.isComplete()) {
                        throw new IllegalStateException(
                                "Completed wave controller retains pending "
                                        + "mobs in wave "
                                        + waveIndex
                                        + ", source composition "
                                        + sourceWaveState
                                        .getSourceCompositionId()
                                        + "."
                        );
                    }
                }
            }

            return;
        }

        for (int wavePosition = 0;
             wavePosition < waveIndexes.size();
             wavePosition++) {

            int waveIndex =
                    waveIndexes.get(
                            wavePosition
                    );

            for (SourceWaveExecutionState sourceWaveState
                    : incursionExecutionState
                    .getWaveSourceStates(
                            waveIndex
                    )) {

                if (wavePosition < currentWavePosition) {
                    if (!sourceWaveState.isComplete()) {
                        throw new IllegalStateException(
                                "Earlier wave "
                                        + waveIndex
                                        + " retains an incomplete source "
                                        + "assignment."
                        );
                    }

                    continue;
                }

                if (wavePosition == currentWavePosition) {
                    continue;
                }

                if (sourceWaveState.getSuccessfulSpawnCount() != 0
                        || sourceWaveState.getCancelledMobCount() != 0
                        || sourceWaveState.wasCancelled()
                        || sourceWaveState.getRemainingMobCount()
                        != sourceWaveState.getPlannedMobCount()) {

                    throw new IllegalStateException(
                            "Future wave "
                                    + waveIndex
                                    + " contains runtime progress before it "
                                    + "has begun."
                    );
                }
            }
        }
    }

    private static void validateConfiguration(
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

        if (firstSpawnDelayTicks
                < sourceCreationDelayTicks) {

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
    }

    /**
     * Shared validation for mutable wave runtime and immutable wave snapshots.
     *
     * WaveStateView contains only the values required to validate controller
     * position and timing. It does not construct or imitate mutable runtime
     * objects.
     */
    private static void validateControllerState(
            List<Integer> waveIndexes,
            int currentWavePosition,
            int completedWaveCount,
            WaveStateView currentWave,
            WaveStateView lastCompletedWave,
            CompletionReason completionReason,
            int sourceCreationDelayTicks,
            int firstSpawnDelayTicks,
            int spawnIntervalTicks
    ) {
        if (waveIndexes == null
                || waveIndexes.isEmpty()) {

            throw new IllegalArgumentException(
                    "Wave controller requires ordered wave indexes."
            );
        }

        if (completionReason == null) {
            throw new IllegalArgumentException(
                    "Wave-controller completion reason cannot be null."
            );
        }

        if (currentWavePosition < 0
                || currentWavePosition >= waveIndexes.size()) {

            throw new IllegalArgumentException(
                    "Current wave position "
                            + currentWavePosition
                            + " lies outside a wave list of size "
                            + waveIndexes.size()
                            + "."
            );
        }

        if (completedWaveCount < 0
                || completedWaveCount > waveIndexes.size()) {

            throw new IllegalArgumentException(
                    "Completed-wave count "
                            + completedWaveCount
                            + " lies outside the range zero to "
                            + waveIndexes.size()
                            + "."
            );
        }

        validateWaveTimingConfiguration(
                currentWave,
                sourceCreationDelayTicks,
                firstSpawnDelayTicks,
                spawnIntervalTicks
        );

        validateWaveTimingConfiguration(
                lastCompletedWave,
                sourceCreationDelayTicks,
                firstSpawnDelayTicks,
                spawnIntervalTicks
        );

        switch (completionReason) {
            case IN_PROGRESS ->
                    validateInProgressState(
                            waveIndexes,
                            currentWavePosition,
                            completedWaveCount,
                            currentWave,
                            lastCompletedWave
                    );

            case COMPLETED_NORMALLY ->
                    validateNormalCompletionState(
                            waveIndexes,
                            currentWavePosition,
                            completedWaveCount,
                            currentWave,
                            lastCompletedWave
                    );

            case CANCELLED ->
                    validateCancelledState(
                            waveIndexes,
                            currentWavePosition,
                            completedWaveCount,
                            currentWave,
                            lastCompletedWave
                    );
        }
    }

    private static void validateInProgressState(
            List<Integer> waveIndexes,
            int currentWavePosition,
            int completedWaveCount,
            WaveStateView currentWave,
            WaveStateView lastCompletedWave
    ) {
        if (currentWave == null) {
            throw new IllegalArgumentException(
                    "An in-progress wave controller requires a current wave."
            );
        }

        if (completedWaveCount
                != currentWavePosition) {

            throw new IllegalArgumentException(
                    "An in-progress controller at wave position "
                            + currentWavePosition
                            + " must have completed exactly "
                            + currentWavePosition
                            + " earlier waves rather than "
                            + completedWaveCount
                            + "."
            );
        }

        int expectedCurrentWaveIndex =
                waveIndexes.get(
                        currentWavePosition
                );

        if (currentWave.waveIndex()
                != expectedCurrentWaveIndex) {

            throw new IllegalArgumentException(
                    "Current wave runtime belongs to wave "
                            + currentWave.waveIndex()
                            + " but wave position "
                            + currentWavePosition
                            + " requires wave "
                            + expectedCurrentWaveIndex
                            + "."
            );
        }

        if (currentWavePosition == 0) {
            if (lastCompletedWave != null) {
                throw new IllegalArgumentException(
                        "The first active wave cannot have a previously "
                                + "completed wave."
                );
            }

            return;
        }

        if (lastCompletedWave == null) {
            throw new IllegalArgumentException(
                    "An in-progress controller after the first wave requires "
                            + "the most recently completed wave runtime."
            );
        }

        int expectedLastCompletedWaveIndex =
                waveIndexes.get(
                        currentWavePosition - 1
                );

        if (lastCompletedWave.waveIndex()
                != expectedLastCompletedWaveIndex) {

            throw new IllegalArgumentException(
                    "Last-completed wave runtime belongs to wave "
                            + lastCompletedWave.waveIndex()
                            + " but the previous planned wave is "
                            + expectedLastCompletedWaveIndex
                            + "."
            );
        }

        if (!lastCompletedWave.spawnScheduleComplete()) {
            throw new IllegalArgumentException(
                    "Last-completed wave runtime must have a complete spawn "
                            + "schedule."
            );
        }
    }

    private static void validateNormalCompletionState(
            List<Integer> waveIndexes,
            int currentWavePosition,
            int completedWaveCount,
            WaveStateView currentWave,
            WaveStateView lastCompletedWave
    ) {
        int finalWavePosition =
                waveIndexes.size() - 1;

        if (currentWavePosition
                != finalWavePosition) {

            throw new IllegalArgumentException(
                    "A normally completed controller must remain positioned "
                            + "at its final wave."
            );
        }

        if (completedWaveCount
                != waveIndexes.size()) {

            throw new IllegalArgumentException(
                    "A normally completed controller must report every wave "
                            + "as completed."
            );
        }

        if (currentWave != null) {
            throw new IllegalArgumentException(
                    "A normally completed controller cannot retain a current "
                            + "wave runtime."
            );
        }

        if (lastCompletedWave == null) {
            throw new IllegalArgumentException(
                    "A normally completed controller requires its final wave "
                            + "runtime."
            );
        }

        int finalWaveIndex =
                waveIndexes.get(
                        finalWavePosition
                );

        if (lastCompletedWave.waveIndex()
                != finalWaveIndex) {

            throw new IllegalArgumentException(
                    "Normally completed controller exposes wave "
                            + lastCompletedWave.waveIndex()
                            + " as its last completed wave, but final wave is "
                            + finalWaveIndex
                            + "."
            );
        }

        if (!lastCompletedWave.spawnScheduleComplete()) {
            throw new IllegalArgumentException(
                    "Final completed wave must have a complete spawn "
                            + "schedule."
            );
        }
    }

    private static void validateCancelledState(
            List<Integer> waveIndexes,
            int currentWavePosition,
            int completedWaveCount,
            WaveStateView currentWave,
            WaveStateView lastCompletedWave
    ) {
        if (completedWaveCount
                != waveIndexes.size()) {

            throw new IllegalArgumentException(
                    "A cancelled controller must account for every remaining "
                            + "wave assignment as completed or cancelled."
            );
        }

        if (currentWave != null) {
            throw new IllegalArgumentException(
                    "A cancelled controller cannot retain a current wave "
                            + "runtime."
            );
        }

        if (lastCompletedWave == null) {
            throw new IllegalArgumentException(
                    "A cancelled controller requires the wave runtime that "
                            + "was active when cancellation occurred."
            );
        }

        int cancelledWaveIndex =
                waveIndexes.get(
                        currentWavePosition
                );

        if (lastCompletedWave.waveIndex()
                != cancelledWaveIndex) {

            throw new IllegalArgumentException(
                    "Cancelled controller was positioned at wave "
                            + cancelledWaveIndex
                            + " but its retained wave runtime belongs to "
                            + lastCompletedWave.waveIndex()
                            + "."
            );
        }

        if (!lastCompletedWave.spawnScheduleComplete()) {
            throw new IllegalArgumentException(
                    "The active wave retained by a cancelled controller must "
                            + "have no remaining scheduled mobs."
            );
        }
    }

    private static void validateWaveTimingConfiguration(
            WaveStateView waveStateView,
            int sourceCreationDelayTicks,
            int firstSpawnDelayTicks,
            int spawnIntervalTicks
    ) {
        if (waveStateView == null) {
            return;
        }

        if (waveStateView.sourceCreationDelayTicks()
                != sourceCreationDelayTicks) {

            throw new IllegalArgumentException(
                    "Wave runtime source-creation delay does not match its "
                            + "controller."
            );
        }

        if (waveStateView.firstSpawnDelayTicks()
                != firstSpawnDelayTicks) {

            throw new IllegalArgumentException(
                    "Wave runtime first-spawn delay does not match its "
                            + "controller."
            );
        }

        if (waveStateView.spawnIntervalTicks()
                != spawnIntervalTicks) {

            throw new IllegalArgumentException(
                    "Wave runtime spawn interval does not match its "
                            + "controller."
            );
        }
    }

    /**
     * Immutable persistence snapshot for one complete incursion wave
     * controller.
     */
    public record Snapshot(
            UUID incursionId,
            List<Integer> waveIndexes,
            int sourceCreationDelayTicks,
            int firstSpawnDelayTicks,
            int spawnIntervalTicks,
            int waveTransitionGraceTicks,
            int currentWavePosition,
            int completedWaveCount,
            CompletionReason completionReason,
            WaveExecutionState.Snapshot currentWaveExecutionSnapshot,
            WaveExecutionState.Snapshot lastCompletedWaveExecutionSnapshot
    ) {

        public Snapshot {
            if (incursionId == null) {
                throw new IllegalArgumentException(
                        "Wave-controller snapshot incursion ID cannot be "
                                + "null."
                );
            }

            if (waveIndexes == null
                    || waveIndexes.isEmpty()) {

                throw new IllegalArgumentException(
                        "Wave-controller snapshot requires ordered wave "
                                + "indexes."
                );
            }

            waveIndexes =
                    List.copyOf(
                            waveIndexes
                    );

            validateWaveIndexes(
                    waveIndexes
            );

            if (sourceCreationDelayTicks < 0) {
                throw new IllegalArgumentException(
                        "Snapshot source-creation delay cannot be negative."
                );
            }

            if (firstSpawnDelayTicks
                    < sourceCreationDelayTicks) {

                throw new IllegalArgumentException(
                        "Snapshot first-spawn delay cannot occur before source "
                                + "creation."
                );
            }

            if (spawnIntervalTicks <= 0) {
                throw new IllegalArgumentException(
                        "Snapshot spawn interval must be greater than zero."
                );
            }

            if (waveTransitionGraceTicks < 0) {
                throw new IllegalArgumentException(
                        "Snapshot wave-transition grace cannot be negative."
                );
            }

            validateControllerState(
                    waveIndexes,
                    currentWavePosition,
                    completedWaveCount,
                    WaveStateView.from(
                            currentWaveExecutionSnapshot
                    ),
                    WaveStateView.from(
                            lastCompletedWaveExecutionSnapshot
                    ),
                    completionReason,
                    sourceCreationDelayTicks,
                    firstSpawnDelayTicks,
                    spawnIntervalTicks
            );
        }

        public boolean areAllWavesComplete() {
            return completionReason
                    != CompletionReason.IN_PROGRESS;
        }

        public int getCurrentWaveIndex() {
            if (currentWaveExecutionSnapshot == null) {
                return -1;
            }

            return currentWaveExecutionSnapshot.waveIndex();
        }

        private static void validateWaveIndexes(
                List<Integer> waveIndexes
        ) {
            Set<Integer> uniqueWaveIndexes =
                    new HashSet<>();

            int previousWaveIndex =
                    -1;

            for (Integer waveIndex
                    : waveIndexes) {

                if (waveIndex == null
                        || waveIndex < 0) {

                    throw new IllegalArgumentException(
                            "Wave-controller snapshot wave indexes cannot be "
                                    + "null or negative."
                    );
                }

                if (!uniqueWaveIndexes.add(
                        waveIndex
                )) {
                    throw new IllegalArgumentException(
                            "Wave-controller snapshot contains duplicate wave "
                                    + "index "
                                    + waveIndex
                                    + "."
                    );
                }

                if (waveIndex
                        <= previousWaveIndex) {

                    throw new IllegalArgumentException(
                            "Wave-controller snapshot wave indexes must be in "
                                    + "strictly ascending order."
                    );
                }

                previousWaveIndex =
                        waveIndex;
            }
        }
    }

    /**
     * Completion state of the complete planned wave schedule.
     */
    public enum CompletionReason {
        IN_PROGRESS,
        COMPLETED_NORMALLY,
        CANCELLED
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

    /**
     * Minimal immutable view used to validate either a mutable
     * WaveExecutionState or its immutable Snapshot.
     *
     * This avoids inheritance, temporary fake runtime objects, and duplicated
     * controller-state validation.
     */
    private record WaveStateView(
            int waveIndex,
            int sourceCreationDelayTicks,
            int firstSpawnDelayTicks,
            int spawnIntervalTicks,
            boolean spawnScheduleComplete
    ) {

        private static WaveStateView from(
                WaveExecutionState waveExecutionState
        ) {
            if (waveExecutionState == null) {
                return null;
            }

            return new WaveStateView(
                    waveExecutionState.getWaveIndex(),
                    waveExecutionState.getSourceCreationDelayTicks(),
                    waveExecutionState.getFirstSpawnDelayTicks(),
                    waveExecutionState.getSpawnIntervalTicks(),
                    waveExecutionState.isSpawnScheduleComplete()
            );
        }

        private static WaveStateView from(
                WaveExecutionState.Snapshot snapshot
        ) {
            if (snapshot == null) {
                return null;
            }

            return new WaveStateView(
                    snapshot.waveIndex(),
                    snapshot.sourceCreationDelayTicks(),
                    snapshot.firstSpawnDelayTicks(),
                    snapshot.spawnIntervalTicks(),
                    snapshot.spawnScheduleComplete()
            );
        }

        private WaveStateView {
            if (waveIndex < 0) {
                throw new IllegalArgumentException(
                        "Wave-state view index cannot be negative."
                );
            }

            if (sourceCreationDelayTicks < 0) {
                throw new IllegalArgumentException(
                        "Wave-state view source-creation delay cannot be "
                                + "negative."
                );
            }

            if (firstSpawnDelayTicks
                    < sourceCreationDelayTicks) {

                throw new IllegalArgumentException(
                        "Wave-state view first-spawn delay cannot occur before "
                                + "source creation."
                );
            }

            if (spawnIntervalTicks <= 0) {
                throw new IllegalArgumentException(
                        "Wave-state view spawn interval must be greater than "
                                + "zero."
                );
            }
        }
    }
}