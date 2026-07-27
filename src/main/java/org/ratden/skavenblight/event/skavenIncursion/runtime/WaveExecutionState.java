package org.ratden.skavenblight.event.skavenIncursion.runtime;

import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.block.entity.state.SourceState;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeadershipContext;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceExecutionState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceWaveExecutionState;

import java.util.ArrayList;
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
 *
 * The complete mutable wave state can be captured in an immutable Snapshot
 * and restored against an already-restored ordered collection of
 * SourceWaveExecutionState objects.
 *
 * Restoration validates:
 *
 * - wave identity;
 * - timing configuration;
 * - exact source-assignment order and identity;
 * - aggregate spawn and cancellation counters;
 * - remaining queue totals;
 * - completion state and completion tick.
 *
 * Logical restoration does not interact with the world. Physical source and
 * entity reconciliation occurs after the complete incursion runtime has been
 * reconstructed.
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

    /**
     * Creates a fresh wave whose complete source assignments remain pending.
     */
    public WaveExecutionState(
            int waveIndex,
            List<SourceWaveExecutionState> sourceWaveExecutionStates,
            int sourceCreationDelayTicks,
            int firstSpawnDelayTicks,
            int spawnIntervalTicks
    ) {
        this(
                waveIndex,
                sourceWaveExecutionStates,
                sourceCreationDelayTicks,
                firstSpawnDelayTicks,
                spawnIntervalTicks,
                0,
                firstSpawnDelayTicks,
                false,
                -1,
                0,
                0,
                0
        );
    }

    private WaveExecutionState(
            int waveIndex,
            List<SourceWaveExecutionState> sourceWaveExecutionStates,
            int sourceCreationDelayTicks,
            int firstSpawnDelayTicks,
            int spawnIntervalTicks,
            int elapsedTicks,
            int nextSpawnTick,
            boolean spawnScheduleComplete,
            int spawnScheduleCompletedTick,
            int totalSuccessfulSpawns,
            int totalCancelledMobs,
            int totalFailedSpawnAttempts
    ) {
        validateConfiguration(
                waveIndex,
                sourceWaveExecutionStates,
                sourceCreationDelayTicks,
                firstSpawnDelayTicks,
                spawnIntervalTicks
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

        this.elapsedTicks =
                elapsedTicks;

        this.nextSpawnTick =
                nextSpawnTick;

        this.spawnScheduleComplete =
                spawnScheduleComplete;

        this.spawnScheduleCompletedTick =
                spawnScheduleCompletedTick;

        this.totalSuccessfulSpawns =
                totalSuccessfulSpawns;

        this.totalCancelledMobs =
                totalCancelledMobs;

        this.totalFailedSpawnAttempts =
                totalFailedSpawnAttempts;

        validateInternalState();
    }

    /**
     * Restores one wave from saved progress.
     *
     * The supplied source-wave states must already have been restored from the
     * source-wave snapshots contained by the wave snapshot. Their order is
     * significant and must exactly match the saved order.
     */
    public static WaveExecutionState restore(
            List<SourceWaveExecutionState> sourceWaveExecutionStates,
            Snapshot snapshot
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Wave execution snapshot cannot be null."
            );
        }

        validateRestoredSourceAssignments(
                sourceWaveExecutionStates,
                snapshot.sourceWaveExecutionSnapshots()
        );

        return new WaveExecutionState(
                snapshot.waveIndex(),
                sourceWaveExecutionStates,
                snapshot.sourceCreationDelayTicks(),
                snapshot.firstSpawnDelayTicks(),
                snapshot.spawnIntervalTicks(),
                snapshot.elapsedTicks(),
                snapshot.nextSpawnTick(),
                snapshot.spawnScheduleComplete(),
                snapshot.spawnScheduleCompletedTick(),
                snapshot.totalSuccessfulSpawns(),
                snapshot.totalCancelledMobs(),
                snapshot.totalFailedSpawnAttempts()
        );
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

    /**
     * Returns the next wave-relative tick on which streamed spawning becomes
     * eligible.
     */
    public int getNextSpawnTick() {
        return nextSpawnTick;
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

    public int getPlannedMobCount() {
        int plannedMobCount =
                0;

        for (SourceWaveExecutionState sourceWaveState
                : sourceWaveExecutionStates) {

            plannedMobCount +=
                    sourceWaveState.getPlannedMobCount();
        }

        return plannedMobCount;
    }

    public int getRemainingMobCount() {
        int remainingMobCount =
                0;

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
     * Captures the exact mutable progress required to resume this wave.
     */
    public Snapshot createSnapshot() {
        List<SourceWaveExecutionState.Snapshot>
                sourceWaveSnapshots =
                new ArrayList<>();

        for (SourceWaveExecutionState sourceWaveState
                : sourceWaveExecutionStates) {

            sourceWaveSnapshots.add(
                    sourceWaveState.createSnapshot()
            );
        }

        return new Snapshot(
                waveIndex,
                sourceCreationDelayTicks,
                firstSpawnDelayTicks,
                spawnIntervalTicks,
                elapsedTicks,
                nextSpawnTick,
                spawnScheduleComplete,
                spawnScheduleCompletedTick,
                totalSuccessfulSpawns,
                totalCancelledMobs,
                totalFailedSpawnAttempts,
                sourceWaveSnapshots
        );
    }

    /**
     * Temporary compatibility route for callers that have not propagated
     * persistent mob-tracking ownership into wave execution.
     *
     * Persistence-aware runtime should use the four-argument overload.
     */
    @Deprecated
    public TickResult tick(
            ServerLevel level,
            SourceState sourceState,
            LeadershipContext leadershipContext
    ) {
        return tickInternal(
                level,
                sourceState,
                leadershipContext,
                null
        );
    }

    /**
     * Advances source creation and streamed spawning by one server tick using
     * the authoritative persistent mob-tracking state owned by the same
     * incursion.
     */
    public TickResult tick(
            ServerLevel level,
            SourceState sourceState,
            LeadershipContext leadershipContext,
            org.ratden.skavenblight.event.skavenIncursion.runtime.mob
                    .IncursionMobTrackingState mobTrackingState
    ) {
        if (mobTrackingState == null) {
            throw new IllegalArgumentException(
                    "Wave execution mob-tracking state cannot be null."
            );
        }

        if (leadershipContext == null) {
            throw new IllegalArgumentException(
                    "Wave leadership context cannot be null."
            );
        }

        if (!mobTrackingState
                .getIncursionId()
                .equals(
                        leadershipContext.scenarioId()
                )) {

            throw new IllegalArgumentException(
                    "Wave leadership Scenario ID "
                            + leadershipContext.scenarioId()
                            + " does not match mob-tracking incursion ID "
                            + mobTrackingState.getIncursionId()
                            + "."
            );
        }

        return tickInternal(
                level,
                sourceState,
                leadershipContext,
                mobTrackingState
        );
    }

    /**
     * Advances source creation and streamed spawning by one server tick.
     *
     * Each source assignment with remaining mobs may attempt at most one
     * spawn whenever the configured spawn interval is reached.
     */
    private TickResult tickInternal(
            ServerLevel level,
            SourceState sourceState,
            LeadershipContext leadershipContext,
            org.ratden.skavenblight.event.skavenIncursion.runtime.mob
                    .IncursionMobTrackingState mobTrackingState
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

        if (mobTrackingState != null
                && !mobTrackingState
                .getIncursionId()
                .equals(
                        leadershipContext.scenarioId()
                )) {

            throw new IllegalArgumentException(
                    "Wave leadership Scenario ID "
                            + leadershipContext.scenarioId()
                            + " does not match mob-tracking incursion ID "
                            + mobTrackingState.getIncursionId()
                            + "."
            );
        }

        elapsedTicks++;

        if (spawnScheduleComplete) {
            validateInternalState();

            return new TickResult(
                    0,
                    0,
                    0,
                    0,
                    true
            );
        }

        int sourcesCreatedThisTick =
                0;

        int sourceCreationFailuresThisTick =
                0;

        int successfulSpawnsThisTick =
                0;

        int failedSpawnAttemptsThisTick =
                0;

        if (elapsedTicks
                >= sourceCreationDelayTicks) {

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

        if (elapsedTicks
                >= nextSpawnTick) {

            for (SourceWaveExecutionState sourceWaveState
                    : sourceWaveExecutionStates) {

                if (!sourceWaveState.hasRemainingMobs()) {
                    continue;
                }

                SourceExecutionState.SpawnAttemptResult result;

                if (mobTrackingState == null) {
                    result =
                            sourceWaveState.attemptNextSpawn(
                                    level,
                                    leadershipContext
                            );
                } else {
                    result =
                            sourceWaveState.attemptNextSpawn(
                                    level,
                                    leadershipContext,
                                    mobTrackingState
                            );
                }

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

        validateInternalState();

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

        int cancelledNow =
                0;

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

        validateInternalState();

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
        int cancelledNow =
                0;

        for (SourceWaveExecutionState sourceWaveState
                : sourceWaveExecutionStates) {

            cancelledNow +=
                    sourceWaveState.cancelRemainingMobs();
        }

        totalCancelledMobs +=
                cancelledNow;

        updateSpawnScheduleCompletion();

        validateInternalState();

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

        spawnScheduleComplete =
                true;

        spawnScheduleCompletedTick =
                elapsedTicks;
    }

    private void validateInternalState() {
        validateConfiguration(
                waveIndex,
                sourceWaveExecutionStates,
                sourceCreationDelayTicks,
                firstSpawnDelayTicks,
                spawnIntervalTicks
        );

        validateProgressValues(
                sourceWaveExecutionStates,
                elapsedTicks,
                nextSpawnTick,
                spawnScheduleComplete,
                spawnScheduleCompletedTick,
                totalSuccessfulSpawns,
                totalCancelledMobs,
                totalFailedSpawnAttempts
        );
    }

    private static void validateConfiguration(
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

        validateSourceAssignments(
                waveIndex,
                sourceWaveExecutionStates
        );
    }

    private static void validateProgressValues(
            List<SourceWaveExecutionState> sourceWaveExecutionStates,
            int elapsedTicks,
            int nextSpawnTick,
            boolean spawnScheduleComplete,
            int spawnScheduleCompletedTick,
            int totalSuccessfulSpawns,
            int totalCancelledMobs,
            int totalFailedSpawnAttempts
    ) {
        if (elapsedTicks < 0) {
            throw new IllegalArgumentException(
                    "Wave elapsed ticks cannot be negative."
            );
        }

        if (nextSpawnTick < 0) {
            throw new IllegalArgumentException(
                    "Wave next-spawn tick cannot be negative."
            );
        }

        if (totalSuccessfulSpawns < 0) {
            throw new IllegalArgumentException(
                    "Wave successful-spawn total cannot be negative."
            );
        }

        if (totalCancelledMobs < 0) {
            throw new IllegalArgumentException(
                    "Wave cancelled-mob total cannot be negative."
            );
        }

        if (totalFailedSpawnAttempts < 0) {
            throw new IllegalArgumentException(
                    "Wave failed-spawn-attempt total cannot be negative."
            );
        }

        int countedSuccessfulSpawns =
                0;

        int countedCancelledMobs =
                0;

        int countedRemainingMobs =
                0;

        int countedPlannedMobs =
                0;

        boolean everySourceAssignmentComplete =
                true;

        for (SourceWaveExecutionState sourceWaveState
                : sourceWaveExecutionStates) {

            countedSuccessfulSpawns +=
                    sourceWaveState.getSuccessfulSpawnCount();

            countedCancelledMobs +=
                    sourceWaveState.getCancelledMobCount();

            countedRemainingMobs +=
                    sourceWaveState.getRemainingMobCount();

            countedPlannedMobs +=
                    sourceWaveState.getPlannedMobCount();

            if (!sourceWaveState.isComplete()) {
                everySourceAssignmentComplete =
                        false;
            }
        }

        if (totalSuccessfulSpawns
                != countedSuccessfulSpawns) {

            throw new IllegalArgumentException(
                    "Wave reports "
                            + totalSuccessfulSpawns
                            + " successful spawns, but its source "
                            + "assignments report "
                            + countedSuccessfulSpawns
                            + "."
            );
        }

        if (totalCancelledMobs
                != countedCancelledMobs) {

            throw new IllegalArgumentException(
                    "Wave reports "
                            + totalCancelledMobs
                            + " cancelled mobs, but its source assignments "
                            + "report "
                            + countedCancelledMobs
                            + "."
            );
        }

        long accountedMobCount =
                (long) countedSuccessfulSpawns
                        + countedCancelledMobs
                        + countedRemainingMobs;

        if (accountedMobCount
                != countedPlannedMobs) {

            throw new IllegalArgumentException(
                    "Wave source assignments account for "
                            + accountedMobCount
                            + " mobs, but they planned "
                            + countedPlannedMobs
                            + ". Successful: "
                            + countedSuccessfulSpawns
                            + ". Cancelled: "
                            + countedCancelledMobs
                            + ". Remaining: "
                            + countedRemainingMobs
                            + "."
            );
        }

        if (spawnScheduleComplete
                != everySourceAssignmentComplete) {

            throw new IllegalArgumentException(
                    "Wave completion flag must exactly match whether every "
                            + "source assignment is complete."
            );
        }

        if (spawnScheduleComplete) {
            if (spawnScheduleCompletedTick < 0) {
                throw new IllegalArgumentException(
                        "A completed wave requires a non-negative completion "
                                + "tick."
                );
            }

            if (spawnScheduleCompletedTick
                    > elapsedTicks) {

                throw new IllegalArgumentException(
                        "Wave completion tick "
                                + spawnScheduleCompletedTick
                                + " cannot occur after elapsed tick "
                                + elapsedTicks
                                + "."
                );
            }
        } else if (spawnScheduleCompletedTick != -1) {
            throw new IllegalArgumentException(
                    "An incomplete wave must use completion tick -1."
            );
        }
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
     * Confirms that the already-restored source assignments exactly match the
     * saved identities, order, and progress contained by the wave snapshot.
     */
    private static void validateRestoredSourceAssignments(
            List<SourceWaveExecutionState> restoredSourceStates,
            List<SourceWaveExecutionState.Snapshot> savedSourceSnapshots
    ) {
        if (restoredSourceStates == null
                || restoredSourceStates.isEmpty()) {

            throw new IllegalArgumentException(
                    "Restored wave requires at least one source assignment."
            );
        }

        if (savedSourceSnapshots == null
                || savedSourceSnapshots.isEmpty()) {

            throw new IllegalArgumentException(
                    "Wave snapshot requires at least one source assignment."
            );
        }

        if (restoredSourceStates.size()
                != savedSourceSnapshots.size()) {

            throw new IllegalArgumentException(
                    "Restored wave contains "
                            + restoredSourceStates.size()
                            + " source assignments, but its snapshot contains "
                            + savedSourceSnapshots.size()
                            + "."
            );
        }

        for (int assignmentIndex = 0;
             assignmentIndex < restoredSourceStates.size();
             assignmentIndex++) {

            SourceWaveExecutionState restoredState =
                    restoredSourceStates.get(
                            assignmentIndex
                    );

            SourceWaveExecutionState.Snapshot savedSnapshot =
                    savedSourceSnapshots.get(
                            assignmentIndex
                    );

            if (restoredState == null) {
                throw new IllegalArgumentException(
                        "Restored source assignment at index "
                                + assignmentIndex
                                + " cannot be null."
                );
            }

            if (savedSnapshot == null) {
                throw new IllegalArgumentException(
                        "Saved source assignment at index "
                                + assignmentIndex
                                + " cannot be null."
                );
            }

            SourceWaveExecutionState.Snapshot restoredSnapshot =
                    restoredState.createSnapshot();

            if (!restoredSnapshot.equals(
                    savedSnapshot
            )) {
                throw new IllegalArgumentException(
                        "Restored source assignment at index "
                                + assignmentIndex
                                + " does not exactly match its saved state."
                                + "\nExpected placement: "
                                + savedSnapshot.sourcePlacementId()
                                + "."
                                + "\nRestored placement: "
                                + restoredSnapshot.sourcePlacementId()
                                + "."
                                + "\nExpected composition: "
                                + savedSnapshot.sourceCompositionId()
                                + "."
                                + "\nRestored composition: "
                                + restoredSnapshot.sourceCompositionId()
                                + "."
                );
            }
        }
    }

    /**
     * Immutable persistence snapshot for one complete global wave.
     *
     * Source assignment snapshots remain ordered because WaveExecutionState
     * creates, ticks, and spawns through those assignments in that order.
     */
    public record Snapshot(
            int waveIndex,
            int sourceCreationDelayTicks,
            int firstSpawnDelayTicks,
            int spawnIntervalTicks,
            int elapsedTicks,
            int nextSpawnTick,
            boolean spawnScheduleComplete,
            int spawnScheduleCompletedTick,
            int totalSuccessfulSpawns,
            int totalCancelledMobs,
            int totalFailedSpawnAttempts,
            List<SourceWaveExecutionState.Snapshot>
            sourceWaveExecutionSnapshots
    ) {

        public Snapshot {
            if (sourceWaveExecutionSnapshots == null
                    || sourceWaveExecutionSnapshots.isEmpty()) {

                throw new IllegalArgumentException(
                        "Wave snapshot requires at least one source "
                                + "assignment."
                );
            }

            sourceWaveExecutionSnapshots =
                    List.copyOf(
                            sourceWaveExecutionSnapshots
                    );

            validateSnapshotSourceAssignments(
                    waveIndex,
                    sourceWaveExecutionSnapshots
            );

            validateSnapshotConfiguration(
                    waveIndex,
                    sourceCreationDelayTicks,
                    firstSpawnDelayTicks,
                    spawnIntervalTicks
            );

            validateSnapshotProgress(
                    sourceWaveExecutionSnapshots,
                    elapsedTicks,
                    nextSpawnTick,
                    spawnScheduleComplete,
                    spawnScheduleCompletedTick,
                    totalSuccessfulSpawns,
                    totalCancelledMobs,
                    totalFailedSpawnAttempts
            );
        }

        public int getPlannedMobCount() {
            int plannedMobCount =
                    0;

            for (SourceWaveExecutionState.Snapshot sourceSnapshot
                    : sourceWaveExecutionSnapshots) {

                plannedMobCount +=
                        sourceSnapshot.plannedMobCount();
            }

            return plannedMobCount;
        }

        public int getRemainingMobCount() {
            int remainingMobCount =
                    0;

            for (SourceWaveExecutionState.Snapshot sourceSnapshot
                    : sourceWaveExecutionSnapshots) {

                remainingMobCount +=
                        sourceSnapshot.getRemainingMobCount();
            }

            return remainingMobCount;
        }

        private static void validateSnapshotConfiguration(
                int waveIndex,
                int sourceCreationDelayTicks,
                int firstSpawnDelayTicks,
                int spawnIntervalTicks
        ) {
            if (waveIndex < 0) {
                throw new IllegalArgumentException(
                        "Wave snapshot index cannot be negative."
                );
            }

            if (sourceCreationDelayTicks < 0) {
                throw new IllegalArgumentException(
                        "Wave snapshot source-creation delay cannot be "
                                + "negative."
                );
            }

            if (firstSpawnDelayTicks
                    < sourceCreationDelayTicks) {

                throw new IllegalArgumentException(
                        "Wave snapshot first-spawn delay cannot occur before "
                                + "source creation."
                );
            }

            if (spawnIntervalTicks <= 0) {
                throw new IllegalArgumentException(
                        "Wave snapshot spawn interval must be greater than "
                                + "zero."
                );
            }
        }

        private static void validateSnapshotSourceAssignments(
                int waveIndex,
                List<SourceWaveExecutionState.Snapshot> sourceSnapshots
        ) {
            Set<UUID> sourceCompositionIds =
                    new HashSet<>();

            for (SourceWaveExecutionState.Snapshot sourceSnapshot
                    : sourceSnapshots) {

                if (sourceSnapshot == null) {
                    throw new IllegalArgumentException(
                            "Wave snapshot cannot contain a null source "
                                    + "assignment."
                    );
                }

                if (sourceSnapshot.waveIndex()
                        != waveIndex) {

                    throw new IllegalArgumentException(
                            "Saved source assignment belongs to wave "
                                    + sourceSnapshot.waveIndex()
                                    + " rather than wave "
                                    + waveIndex
                                    + "."
                    );
                }

                if (!sourceCompositionIds.add(
                        sourceSnapshot.sourceCompositionId()
                )) {

                    throw new IllegalArgumentException(
                            "Wave snapshot contains duplicate source "
                                    + "composition ID "
                                    + sourceSnapshot.sourceCompositionId()
                                    + "."
                    );
                }
            }
        }

        private static void validateSnapshotProgress(
                List<SourceWaveExecutionState.Snapshot> sourceSnapshots,
                int elapsedTicks,
                int nextSpawnTick,
                boolean spawnScheduleComplete,
                int spawnScheduleCompletedTick,
                int totalSuccessfulSpawns,
                int totalCancelledMobs,
                int totalFailedSpawnAttempts
        ) {
            if (elapsedTicks < 0) {
                throw new IllegalArgumentException(
                        "Wave snapshot elapsed ticks cannot be negative."
                );
            }

            if (nextSpawnTick < 0) {
                throw new IllegalArgumentException(
                        "Wave snapshot next-spawn tick cannot be negative."
                );
            }

            if (totalSuccessfulSpawns < 0
                    || totalCancelledMobs < 0
                    || totalFailedSpawnAttempts < 0) {

                throw new IllegalArgumentException(
                        "Wave snapshot aggregate counters cannot be negative."
                );
            }

            int countedSuccessfulSpawns =
                    0;

            int countedCancelledMobs =
                    0;

            int countedRemainingMobs =
                    0;

            int countedPlannedMobs =
                    0;

            boolean everyAssignmentComplete =
                    true;

            for (SourceWaveExecutionState.Snapshot sourceSnapshot
                    : sourceSnapshots) {

                countedSuccessfulSpawns +=
                        sourceSnapshot.successfulSpawnCount();

                countedCancelledMobs +=
                        sourceSnapshot.cancelledMobCount();

                countedRemainingMobs +=
                        sourceSnapshot.getRemainingMobCount();

                countedPlannedMobs +=
                        sourceSnapshot.plannedMobCount();

                if (!sourceSnapshot.isComplete()) {
                    everyAssignmentComplete =
                            false;
                }
            }

            if (totalSuccessfulSpawns
                    != countedSuccessfulSpawns) {

                throw new IllegalArgumentException(
                        "Wave snapshot reports "
                                + totalSuccessfulSpawns
                                + " successful spawns, but its source "
                                + "snapshots report "
                                + countedSuccessfulSpawns
                                + "."
                );
            }

            if (totalCancelledMobs
                    != countedCancelledMobs) {

                throw new IllegalArgumentException(
                        "Wave snapshot reports "
                                + totalCancelledMobs
                                + " cancelled mobs, but its source snapshots "
                                + "report "
                                + countedCancelledMobs
                                + "."
                );
            }

            long accountedMobCount =
                    (long) countedSuccessfulSpawns
                            + countedCancelledMobs
                            + countedRemainingMobs;

            if (accountedMobCount
                    != countedPlannedMobs) {

                throw new IllegalArgumentException(
                        "Wave snapshot accounts for "
                                + accountedMobCount
                                + " mobs, but its source snapshots planned "
                                + countedPlannedMobs
                                + "."
                );
            }

            if (spawnScheduleComplete
                    != everyAssignmentComplete) {

                throw new IllegalArgumentException(
                        "Wave snapshot completion flag must exactly match "
                                + "whether every source assignment is "
                                + "complete."
                );
            }

            if (spawnScheduleComplete) {
                if (spawnScheduleCompletedTick < 0
                        || spawnScheduleCompletedTick > elapsedTicks) {

                    throw new IllegalArgumentException(
                            "Completed wave snapshot requires a completion "
                                    + "tick between zero and elapsed tick "
                                    + elapsedTicks
                                    + "."
                    );
                }
            } else if (spawnScheduleCompletedTick != -1) {
                throw new IllegalArgumentException(
                        "Incomplete wave snapshot must use completion tick "
                                + "-1."
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