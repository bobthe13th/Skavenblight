package org.ratden.skavenblight.event.skavenIncursion.runtime.persistence;

import org.ratden.skavenblight.block.entity.state.SourceState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.IncursionExecutionState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.IncursionWaveController;
import org.ratden.skavenblight.event.skavenIncursion.runtime.WaveExecutionState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceExecutionState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceWaveExecutionState;

import java.util.UUID;

/**
 * Shared immutable persistence snapshot for an ordinary planning-aware
 * Scenario.
 *
 * Most combat Scenarios use the same mutable runtime structure:
 *
 * - one immutable IncursionPlan, persisted separately;
 * - one shared IncursionExecutionState;
 * - one IncursionWaveController;
 * - elapsed runtime and timeout configuration;
 * - routed source-destruction counters;
 * - source-collapse state;
 * - completion and timeout state.
 *
 * Scenario-specific classes should use this common snapshot rather than
 * defining another structurally identical nested record.
 *
 * A Scenario that later requires unique persistent state can compose this
 * snapshot inside a specialised snapshot containing only its additional
 * fields.
 *
 * The immutable IncursionPlan is not stored here. It is persisted separately
 * by the top-level persistent-incursion record and shares instanceId with this
 * runtime snapshot.
 */
public record PlannedScenarioRuntimeSnapshot(
        String scenarioId,
        UUID instanceId,
        int maximumRuntimeTicks,
        int elapsedTicks,
        int totalSourceDestructions,
        int totalMobsCancelledByDestruction,
        boolean sourcesCollapsed,
        boolean finished,
        boolean timedOut,
        IncursionExecutionState.Snapshot incursionExecutionSnapshot,
        IncursionWaveController.Snapshot waveControllerSnapshot
) {

    public PlannedScenarioRuntimeSnapshot {
        if (scenarioId == null
                || scenarioId.isBlank()) {

            throw new IllegalArgumentException(
                    "Planned Scenario snapshot ID cannot be blank."
            );
        }

        if (instanceId == null) {
            throw new IllegalArgumentException(
                    "Planned Scenario snapshot instance ID cannot be null."
            );
        }

        if (maximumRuntimeTicks <= 0) {
            throw new IllegalArgumentException(
                    "Planned Scenario maximum runtime must be greater than "
                            + "zero."
            );
        }

        if (elapsedTicks < 0) {
            throw new IllegalArgumentException(
                    "Planned Scenario elapsed ticks cannot be negative."
            );
        }

        if (totalSourceDestructions < 0) {
            throw new IllegalArgumentException(
                    "Planned Scenario source-destruction count cannot be "
                            + "negative."
            );
        }

        if (totalMobsCancelledByDestruction < 0) {
            throw new IllegalArgumentException(
                    "Planned Scenario destruction-cancellation count cannot "
                            + "be negative."
            );
        }

        if (incursionExecutionSnapshot == null) {
            throw new IllegalArgumentException(
                    "Planned Scenario requires an incursion execution "
                            + "snapshot."
            );
        }

        if (waveControllerSnapshot == null) {
            throw new IllegalArgumentException(
                    "Planned Scenario requires a wave-controller snapshot."
            );
        }

        validateSnapshotIdentities(
                instanceId,
                incursionExecutionSnapshot,
                waveControllerSnapshot
        );

        validateDestructionProgress(
                totalSourceDestructions,
                incursionExecutionSnapshot
        );

        validateCancellationProgress(
                totalMobsCancelledByDestruction,
                incursionExecutionSnapshot
        );

        validateDuplicatedWaveProgress(
                incursionExecutionSnapshot,
                waveControllerSnapshot
        );

        validateLifecycleState(
                maximumRuntimeTicks,
                elapsedTicks,
                sourcesCollapsed,
                finished,
                timedOut,
                waveControllerSnapshot
        );

        validateCollapsedSourceStates(
                sourcesCollapsed,
                incursionExecutionSnapshot
        );
    }

    public boolean isActive() {
        return !finished;
    }

    public boolean completedNormally() {
        return finished
                && !timedOut;
    }

    public int getRecordedPhysicalDestructionCount() {
        return countRecordedPhysicalDestructions(
                incursionExecutionSnapshot
        );
    }

    public int getTotalCancelledMobCount() {
        return countTotalCancelledMobs(
                incursionExecutionSnapshot
        );
    }

    public int getPhysicalSourceCount() {
        return incursionExecutionSnapshot
                .getPhysicalSourceCount();
    }

    public int getWaveCount() {
        return incursionExecutionSnapshot
                .getWaveCount();
    }

    public int getCurrentWaveIndex() {
        return waveControllerSnapshot
                .getCurrentWaveIndex();
    }

    private static void validateSnapshotIdentities(
            UUID instanceId,
            IncursionExecutionState.Snapshot
                    incursionExecutionSnapshot,
            IncursionWaveController.Snapshot
                    waveControllerSnapshot
    ) {
        if (!instanceId.equals(
                incursionExecutionSnapshot.incursionId()
        )) {
            throw new IllegalArgumentException(
                    "Planned Scenario instance ID "
                            + instanceId
                            + " does not match its incursion execution "
                            + "snapshot ID "
                            + incursionExecutionSnapshot.incursionId()
                            + "."
            );
        }

        if (!instanceId.equals(
                waveControllerSnapshot.incursionId()
        )) {
            throw new IllegalArgumentException(
                    "Planned Scenario instance ID "
                            + instanceId
                            + " does not match its wave-controller snapshot "
                            + "ID "
                            + waveControllerSnapshot.incursionId()
                            + "."
            );
        }

        if (!incursionExecutionSnapshot
                .incursionId()
                .equals(
                        waveControllerSnapshot.incursionId()
                )) {

            throw new IllegalArgumentException(
                    "Planned Scenario execution and controller snapshots "
                            + "belong to different incursions."
            );
        }
    }

    private static void validateDestructionProgress(
            int totalSourceDestructions,
            IncursionExecutionState.Snapshot
                    incursionExecutionSnapshot
    ) {
        int recordedPhysicalDestructions =
                countRecordedPhysicalDestructions(
                        incursionExecutionSnapshot
                );

        /*
         * World reconciliation may discover a missing physical source and
         * mark it destroyed without treating that discovery as a routed
         * player-generated SourceDestroyedEvent.
         *
         * The routed Scenario counter may therefore be lower than physical
         * destruction history, but it may never exceed it.
         */
        if (totalSourceDestructions
                > recordedPhysicalDestructions) {

            throw new IllegalArgumentException(
                    "Planned Scenario reports "
                            + totalSourceDestructions
                            + " routed source destructions, but its physical "
                            + "source histories contain only "
                            + recordedPhysicalDestructions
                            + "."
            );
        }
    }

    private static void validateCancellationProgress(
            int totalMobsCancelledByDestruction,
            IncursionExecutionState.Snapshot
                    incursionExecutionSnapshot
    ) {
        int totalCancelledMobs =
                countTotalCancelledMobs(
                        incursionExecutionSnapshot
                );

        /*
         * Timeout or another authored cancellation may cancel mobs without a
         * source destruction. Destruction cancellation is therefore a subset
         * of the complete cancellation total.
         */
        if (totalMobsCancelledByDestruction
                > totalCancelledMobs) {

            throw new IllegalArgumentException(
                    "Planned Scenario reports "
                            + totalMobsCancelledByDestruction
                            + " mobs cancelled by source destruction, but its "
                            + "runtime graph contains only "
                            + totalCancelledMobs
                            + " cancelled mobs in total."
            );
        }
    }

    /**
     * Confirms that current and last-completed wave snapshots duplicate the
     * same source-assignment progress stored by IncursionExecutionState.
     */
    private static void validateDuplicatedWaveProgress(
            IncursionExecutionState.Snapshot
                    incursionExecutionSnapshot,
            IncursionWaveController.Snapshot
                    waveControllerSnapshot
    ) {
        validateDuplicatedWaveSnapshot(
                incursionExecutionSnapshot,
                waveControllerSnapshot
                        .currentWaveExecutionSnapshot()
        );

        validateDuplicatedWaveSnapshot(
                incursionExecutionSnapshot,
                waveControllerSnapshot
                        .lastCompletedWaveExecutionSnapshot()
        );
    }

    private static void validateDuplicatedWaveSnapshot(
            IncursionExecutionState.Snapshot
                    incursionExecutionSnapshot,
            WaveExecutionState.Snapshot
                    waveExecutionSnapshot
    ) {
        if (waveExecutionSnapshot == null) {
            return;
        }

        IncursionExecutionState
                .WaveSourceAssignmentsSnapshot
                savedAssignments =
                incursionExecutionSnapshot
                        .getWaveSourceAssignmentSnapshot(
                                waveExecutionSnapshot.waveIndex()
                        );

        if (savedAssignments == null) {
            throw new IllegalArgumentException(
                    "Wave-controller snapshot refers to wave "
                            + waveExecutionSnapshot.waveIndex()
                            + " which is absent from the incursion execution "
                            + "snapshot."
            );
        }

        if (!savedAssignments
                .sourceWaveExecutionSnapshots()
                .equals(
                        waveExecutionSnapshot
                                .sourceWaveExecutionSnapshots()
                )) {

            throw new IllegalArgumentException(
                    "Wave-controller progress for wave "
                            + waveExecutionSnapshot.waveIndex()
                            + " does not match the shared incursion execution "
                            + "snapshot."
            );
        }
    }

    private static void validateLifecycleState(
            int maximumRuntimeTicks,
            int elapsedTicks,
            boolean sourcesCollapsed,
            boolean finished,
            boolean timedOut,
            IncursionWaveController.Snapshot
                    waveControllerSnapshot
    ) {
        if (timedOut) {
            if (!finished
                    || !sourcesCollapsed) {

                throw new IllegalArgumentException(
                        "A timed-out planned Scenario must be finished and "
                                + "have collapsed its surviving sources."
                );
            }

            if (elapsedTicks
                    < maximumRuntimeTicks) {

                throw new IllegalArgumentException(
                        "A timed-out planned Scenario cannot have fewer "
                                + "elapsed ticks than its maximum runtime."
                );
            }

            if (waveControllerSnapshot.completionReason()
                    != IncursionWaveController
                    .CompletionReason
                    .CANCELLED) {

                throw new IllegalArgumentException(
                        "A timed-out planned Scenario requires a cancelled "
                                + "wave controller."
                );
            }

            return;
        }

        if (finished) {
            if (!sourcesCollapsed) {
                throw new IllegalArgumentException(
                        "A finished planned Scenario must have collapsed its "
                                + "surviving sources."
                );
            }

            if (elapsedTicks
                    >= maximumRuntimeTicks) {

                throw new IllegalArgumentException(
                        "A normally completed planned Scenario cannot reach "
                                + "or exceed its timeout tick."
                );
            }

            if (waveControllerSnapshot.completionReason()
                    != IncursionWaveController
                    .CompletionReason
                    .COMPLETED_NORMALLY) {

                throw new IllegalArgumentException(
                        "A finished non-timeout planned Scenario requires a "
                                + "normally completed wave controller."
                );
            }

            return;
        }

        if (sourcesCollapsed) {
            throw new IllegalArgumentException(
                    "An active planned Scenario cannot report collapsed "
                            + "infrastructure."
            );
        }

        if (elapsedTicks
                >= maximumRuntimeTicks) {

            throw new IllegalArgumentException(
                    "An active planned Scenario cannot have reached its "
                            + "timeout tick."
            );
        }

        if (waveControllerSnapshot.completionReason()
                != IncursionWaveController
                .CompletionReason
                .IN_PROGRESS) {

            throw new IllegalArgumentException(
                    "An active planned Scenario requires an in-progress wave "
                            + "controller."
            );
        }
    }

    /**
     * When the Scenario reports collapsed infrastructure, every surviving
     * physical source incarnation must be COLLAPSED.
     *
     * Destroyed source placements have no current SourceState and remain
     * valid.
     */
    private static void validateCollapsedSourceStates(
            boolean sourcesCollapsed,
            IncursionExecutionState.Snapshot
                    incursionExecutionSnapshot
    ) {
        if (!sourcesCollapsed) {
            return;
        }

        for (SourceExecutionState.Snapshot sourceSnapshot
                : incursionExecutionSnapshot
                .sourceExecutionSnapshots()) {

            SourceState currentSourceState =
                    sourceSnapshot.currentSourceState();

            if (currentSourceState == null) {
                continue;
            }

            if (currentSourceState
                    != SourceState.COLLAPSED) {

                throw new IllegalArgumentException(
                        "Planned Scenario reports collapsed infrastructure, "
                                + "but source placement "
                                + sourceSnapshot.sourcePlacementId()
                                + " remains in state "
                                + currentSourceState
                                + "."
                );
            }
        }
    }

    private static int countRecordedPhysicalDestructions(
            IncursionExecutionState.Snapshot
                    incursionExecutionSnapshot
    ) {
        int destructionCount =
                0;

        for (SourceExecutionState.Snapshot sourceSnapshot
                : incursionExecutionSnapshot
                .sourceExecutionSnapshots()) {

            destructionCount +=
                    sourceSnapshot.destructionCount();
        }

        return destructionCount;
    }

    private static int countTotalCancelledMobs(
            IncursionExecutionState.Snapshot
                    incursionExecutionSnapshot
    ) {
        int cancelledMobCount =
                0;

        for (IncursionExecutionState
                .WaveSourceAssignmentsSnapshot waveSnapshot
                : incursionExecutionSnapshot
                .waveSourceAssignmentSnapshots()) {

            for (SourceWaveExecutionState.Snapshot sourceSnapshot
                    : waveSnapshot
                    .sourceWaveExecutionSnapshots()) {

                cancelledMobCount +=
                        sourceSnapshot.cancelledMobCount();
            }
        }

        return cancelledMobCount;
    }
}