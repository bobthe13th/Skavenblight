package org.ratden.skavenblight.event.skavenIncursion.runtime.persistence;

import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.event.skavenIncursion.debug.DebugIncursionAnchorPlacementService;
import org.ratden.skavenblight.event.skavenIncursion.debug.DebugIncursionAnchorTracker;
import org.ratden.skavenblight.event.skavenIncursion.director.ActiveIncursionManager;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.ActiveIncursionSourceReservationRegistry;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.reconciliation.PersistentIncursionWorldReconciliationService;
import org.ratden.skavenblight.event.skavenIncursion.scenario.ScenarioRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reconstructs persistence-aware incursions belonging to one ServerLevel.
 *
 * Restoration proceeds from immutable state towards live runtime:
 *
 * 1. read the already-loaded PersistentIncursionSnapshot;
 * 2. reconstruct the immutable IncursionPlan;
 * 3. rebuild the authoritative physical-reservation snapshot;
 * 4. restore the live Scenario through ScenarioRegistry;
 * 5. rebuild the LivePersistentIncursion owner;
 * 6. reconcile persisted physical-source state with the world;
 * 7. save any logical changes produced by reconciliation;
 * 8. attach the live owner to ActiveIncursionManager;
 * 9. restore non-authoritative debug-anchor tracking.
 *
 * Debug-anchor restoration is deliberately last. A failure in that optional
 * visualisation layer produces a warning but does not suspend or reject an
 * otherwise valid incursion.
 *
 * This service does not:
 *
 * - read NBT directly;
 * - load another dimension's records;
 * - reconnect spawned entities;
 * - tick restored Scenarios.
 *
 * Restoration is attempted independently for each record. One failed
 * incursion therefore does not prevent other valid incursions in the same
 * level from being reconstructed.
 *
 * When an ACTIVE record cannot be restored or safely reconciled, it is
 * changed to SUSPENDED where possible.
 *
 * Authoritative physical reservations remain registered after a failed
 * restoration attempt. The persistent record still owns those areas until it
 * is successfully resumed or explicitly removed.
 *
 * All methods are expected to run on the logical server thread.
 */
public final class PersistentIncursionRestorationService {

    /**
     * Attempts to restore every persistent incursion stored for one level.
     *
     * Records already attached to ActiveIncursionManager are skipped. This
     * makes repeated calls deterministic without duplicating live runtime.
     */
    public static RestorationReport restoreLevel(
            ServerLevel level
    ) {
        if (level == null) {
            throw new IllegalArgumentException(
                    "Persistent incursion restoration level cannot be null."
            );
        }

        SkavenIncursionSavedData savedData =
                SkavenIncursionSavedData.get(
                        level
                );

        /*
         * Work from an immutable copy because failed ACTIVE records may be
         * replaced with SUSPENDED snapshots during restoration.
         */
        List<PersistentIncursionSnapshot> storedSnapshots =
                List.copyOf(
                        savedData.getSnapshots()
                );

        int restoredCount =
                0;

        int skippedCount =
                0;

        int newlySuspendedCount =
                0;

        List<RestorationWarning> warnings =
                new ArrayList<>();

        List<RestorationFailure> failures =
                new ArrayList<>();

        for (PersistentIncursionSnapshot snapshot
                : storedSnapshots) {

            UUID incursionId =
                    snapshot.incursionId();

            if (ActiveIncursionManager.getPersistentIncursion(
                    incursionId
            ) != null) {

                skippedCount++;

                continue;
            }

            RestorationAttempt attempt =
                    restoreSnapshot(
                            level,
                            savedData,
                            snapshot
                    );

            if (attempt.successful()) {
                restoredCount++;

                if (attempt.warningMessage() != null) {
                    warnings.add(
                            new RestorationWarning(
                                    snapshot.incursionId(),
                                    snapshot.scenarioId(),
                                    attempt.warningMessage()
                            )
                    );
                }

                continue;
            }

            if (attempt.markedSuspended()) {
                newlySuspendedCount++;
            }

            failures.add(
                    new RestorationFailure(
                            snapshot.incursionId(),
                            snapshot.scenarioId(),
                            snapshot.phase(),
                            attempt.markedSuspended(),
                            attempt.reservationsRestored(),
                            attempt.failureMessage()
                    )
            );
        }

        return new RestorationReport(
                storedSnapshots.size(),
                restoredCount,
                skippedCount,
                newlySuspendedCount,
                warnings,
                failures
        );
    }

    /**
     * Restores one complete persistent record.
     *
     * Reservation registration occurs immediately after immutable-plan
     * restoration. If a later Scenario, reconciliation or manager step fails,
     * those reservations remain registered because the SavedData record
     * remains authoritative and still owns the physical source areas.
     */
    private static RestorationAttempt restoreSnapshot(
            ServerLevel level,
            SkavenIncursionSavedData savedData,
            PersistentIncursionSnapshot persistentSnapshot
    ) {
        boolean reservationsRestored =
                false;

        LivePersistentIncursion restoredIncursion =
                null;

        /*
         * This tracks the most recent snapshot known to represent the live
         * restoration attempt.
         *
         * Reconciliation may legitimately alter logical source state before
         * the incursion is attached to the manager.
         */
        PersistentIncursionSnapshot latestPersistentSnapshot =
                persistentSnapshot;

        try {
            IncursionPlan restoredIncursionPlan =
                    persistentSnapshot
                            .incursionPlanSnapshot()
                            .restore();

            ActiveIncursionSourceReservationRegistry.register(
                    level,
                    restoredIncursionPlan
            );

            reservationsRestored =
                    true;

            PersistableSkavenScenario<
                    PlannedScenarioRuntimeSnapshot
                    > restoredScenario =
                    ScenarioRegistry.restorePlannedScenario(
                            persistentSnapshot.scenarioId(),
                            level,
                            restoredIncursionPlan,
                            persistentSnapshot
                                    .scenarioRuntimeSnapshot()
                    );

            if (restoredScenario == null) {
                throw new IllegalStateException(
                        "Scenario '"
                                + persistentSnapshot.scenarioId()
                                + "' has no registered planned restoration "
                                + "factory."
                );
            }

            restoredIncursion =
                    LivePersistentIncursion.restore(
                            level,
                            restoredIncursionPlan,
                            restoredScenario,
                            persistentSnapshot
                    );

            PersistentIncursionWorldReconciliationService
                    .ReconciliationReport reconciliationReport =
                    PersistentIncursionWorldReconciliationService.reconcile(
                            level,
                            restoredIncursion
                    );

            if (!reconciliationReport.successful()) {
                throw new IllegalStateException(
                        formatReconciliationConflicts(
                                reconciliationReport
                        )
                );
            }

            /*
             * Missing-source reconciliation may cancel pending mobs or change
             * physical source execution state. Those changes must become
             * persistent before the restored incursion enters live runtime.
             */
            latestPersistentSnapshot =
                    restoredIncursion.createPersistentSnapshot();

            if (!persistentSnapshot.equals(
                    latestPersistentSnapshot
            )) {
                savedData.replaceSnapshot(
                        latestPersistentSnapshot
                );
            }

            /*
             * attachRestoredPersistentIncursion confirms that the live owner
             * exactly reproduces the record currently stored in SavedData.
             */
            ActiveIncursionManager
                    .attachRestoredPersistentIncursion(
                            restoredIncursion
                    );

            /*
             * Debug anchors are representations only. Their restoration occurs
             * after authoritative runtime attachment and cannot invalidate the
             * restored incursion.
             */
            String debugAnchorWarning =
                    restoreDebugAnchors(
                            level,
                            restoredIncursion
                    );

            return RestorationAttempt.success(
                    reservationsRestored,
                    debugAnchorWarning
            );
        } catch (RuntimeException exception) {
            /*
             * Reconciliation applies only after a complete ownership-conflict
             * inspection, but an unexpected application error could still
             * occur after some logical source changes were made.
             *
             * Attempt to preserve the live owner's latest valid snapshot
             * before suspending the record.
             */
            SnapshotRefreshAttempt snapshotRefreshAttempt =
                    refreshSnapshotAfterFailure(
                            savedData,
                            latestPersistentSnapshot,
                            restoredIncursion
                    );

            PersistentIncursionSnapshot suspensionBaseSnapshot =
                    snapshotRefreshAttempt.snapshot();

            SuspensionAttempt suspensionAttempt =
                    suspendFailedActiveRecord(
                            savedData,
                            suspensionBaseSnapshot
                    );

            String failureMessage =
                    createFailureMessage(
                            exception,
                            snapshotRefreshAttempt,
                            suspensionAttempt
                    );

            return RestorationAttempt.failure(
                    suspensionAttempt.markedSuspended(),
                    reservationsRestored,
                    failureMessage
            );
        }
    }

    /**
     * Restores or recreates the optional debug-anchor representation.
     *
     * Any failure is converted into a diagnostic warning. It must never escape
     * into authoritative restoration after the live incursion has already
     * been attached successfully.
     */
    private static String restoreDebugAnchors(
            ServerLevel level,
            LivePersistentIncursion restoredIncursion
    ) {
        try {
            DebugIncursionAnchorPlacementService.PlacementResult
                    placementResult =
                    DebugIncursionAnchorTracker.restoreAndTrack(
                            level,
                            restoredIncursion.getIncursionPlan()
                    );

            if (placementResult.successful()) {
                return null;
            }

            String failureMessage =
                    placementResult.failureMessage();

            if (failureMessage == null
                    || failureMessage.isBlank()) {

                failureMessage =
                        "The debug-anchor restoration service returned an "
                                + "unsuccessful result without details.";
            }

            return "Debug-anchor restoration failed: "
                    + failureMessage;
        } catch (RuntimeException exception) {
            return "Debug-anchor restoration threw "
                    + describeException(
                    exception
            );
        }
    }

    /**
     * Attempts to save the latest state of a partially reconstructed live
     * owner after an unexpected failure.
     *
     * This is primarily defensive. Ownership conflicts are detected before
     * reconciliation changes anything, but an application failure could occur
     * after an earlier action has changed logical source state.
     */
    private static SnapshotRefreshAttempt refreshSnapshotAfterFailure(
            SkavenIncursionSavedData savedData,
            PersistentIncursionSnapshot latestKnownSnapshot,
            LivePersistentIncursion restoredIncursion
    ) {
        if (restoredIncursion == null) {
            return SnapshotRefreshAttempt.notRequired(
                    latestKnownSnapshot
            );
        }

        PersistentIncursionSnapshot storedSnapshot =
                savedData.getSnapshot(
                        latestKnownSnapshot.incursionId()
                );

        PersistentIncursionSnapshot fallbackSnapshot =
                storedSnapshot != null
                        ? storedSnapshot
                        : latestKnownSnapshot;

        try {
            PersistentIncursionSnapshot capturedSnapshot =
                    restoredIncursion.createPersistentSnapshot();

            if (storedSnapshot == null) {
                return SnapshotRefreshAttempt.failed(
                        fallbackSnapshot,
                        "The SavedData record was absent while attempting to "
                                + "preserve partially restored runtime state."
                );
            }

            if (!capturedSnapshot.equals(
                    storedSnapshot
            )) {
                savedData.replaceSnapshot(
                        capturedSnapshot
                );
            }

            return SnapshotRefreshAttempt.succeeded(
                    capturedSnapshot
            );
        } catch (RuntimeException exception) {
            return SnapshotRefreshAttempt.failed(
                    fallbackSnapshot,
                    describeException(
                            exception
                    )
            );
        }
    }

    /**
     * Changes a failed unfinished ACTIVE record to SUSPENDED.
     *
     * Existing SUSPENDED records remain unchanged. CLEANUP_PENDING records
     * cannot become SUSPENDED because their Scenario runtime is already
     * finished.
     */
    private static SuspensionAttempt suspendFailedActiveRecord(
            SkavenIncursionSavedData savedData,
            PersistentIncursionSnapshot persistentSnapshot
    ) {
        if (persistentSnapshot.phase()
                != PersistentIncursionPhase.ACTIVE) {

            return SuspensionAttempt.notRequired();
        }

        try {
            PersistentIncursionSnapshot suspendedSnapshot =
                    persistentSnapshot.withPhase(
                            PersistentIncursionPhase.SUSPENDED
                    );

            savedData.replaceSnapshot(
                    suspendedSnapshot
            );

            return SuspensionAttempt.succeeded();
        } catch (RuntimeException exception) {
            return SuspensionAttempt.failed(
                    describeException(
                            exception
                    )
            );
        }
    }

    private static String formatReconciliationConflicts(
            PersistentIncursionWorldReconciliationService
                    .ReconciliationReport reconciliationReport
    ) {
        StringBuilder builder =
                new StringBuilder();

        int conflictCount =
                reconciliationReport.conflicts()
                        .size();

        builder.append(
                "Physical-source reconciliation found "
        );

        builder.append(
                conflictCount
        );

        builder.append(
                conflictCount == 1
                        ? " ownership conflict"
                        : " ownership conflicts"
        );

        builder.append(
                " for incursion "
        );

        builder.append(
                reconciliationReport.incursionId()
        );

        builder.append(
                "."
        );

        int displayedConflictCount =
                Math.min(
                        conflictCount,
                        3
                );

        for (int index = 0;
             index < displayedConflictCount;
             index++) {

            PersistentIncursionWorldReconciliationService
                    .ReconciliationConflict conflict =
                    reconciliationReport.conflicts()
                            .get(
                                    index
                            );

            builder.append(
                    " ["
            );

            builder.append(
                    conflict.sourcePlacementId()
            );

            builder.append(
                    " at "
            );

            builder.append(
                    conflict.sourcePos()
            );

            builder.append(
                    ": "
            );

            builder.append(
                    conflict.message()
            );

            builder.append(
                    "]"
            );
        }

        if (conflictCount
                > displayedConflictCount) {

            builder.append(
                    " "
            );

            builder.append(
                    conflictCount - displayedConflictCount
            );

            builder.append(
                    " additional conflict"
            );

            if (conflictCount - displayedConflictCount
                    != 1) {

                builder.append(
                        "s"
                );
            }

            builder.append(
                    " omitted."
            );
        }

        return builder.toString();
    }

    private static String createFailureMessage(
            RuntimeException restorationException,
            SnapshotRefreshAttempt snapshotRefreshAttempt,
            SuspensionAttempt suspensionAttempt
    ) {
        StringBuilder builder =
                new StringBuilder(
                        describeException(
                                restorationException
                        )
                );

        if (snapshotRefreshAttempt.refreshFailed()) {
            builder.append(
                    " The latest partially restored runtime state could not "
                            + "be preserved: "
            );

            builder.append(
                    snapshotRefreshAttempt.failureMessage()
            );

            builder.append(
                    "."
            );
        }

        if (suspensionAttempt.suspensionFailed()) {
            builder.append(
                    " The failed ACTIVE record also could not be changed to "
                            + "SUSPENDED: "
            );

            builder.append(
                    suspensionAttempt.failureMessage()
            );

            builder.append(
                    "."
            );
        }

        return builder.toString();
    }

    private static String describeException(
            RuntimeException exception
    ) {
        if (exception == null) {
            return "Unknown persistent-incursion restoration failure.";
        }

        String exceptionMessage =
                exception.getMessage();

        if (exceptionMessage == null
                || exceptionMessage.isBlank()) {

            return exception
                    .getClass()
                    .getSimpleName();
        }

        return exception
                .getClass()
                .getSimpleName()
                + ": "
                + exceptionMessage;
    }

    /**
     * Summary of one level-wide restoration pass.
     */
    public record RestorationReport(
            int storedRecordCount,
            int restoredCount,
            int skippedCount,
            int newlySuspendedCount,
            List<RestorationWarning> warnings,
            List<RestorationFailure> failures
    ) {

        public RestorationReport {
            if (storedRecordCount < 0) {
                throw new IllegalArgumentException(
                        "Stored restoration-record count cannot be negative."
                );
            }

            if (restoredCount < 0) {
                throw new IllegalArgumentException(
                        "Restored-incursion count cannot be negative."
                );
            }

            if (skippedCount < 0) {
                throw new IllegalArgumentException(
                        "Skipped-incursion count cannot be negative."
                );
            }

            if (newlySuspendedCount < 0) {
                throw new IllegalArgumentException(
                        "Newly suspended-incursion count cannot be negative."
                );
            }

            if (warnings == null) {
                throw new IllegalArgumentException(
                        "Restoration warning list cannot be null."
                );
            }

            if (failures == null) {
                throw new IllegalArgumentException(
                        "Restoration failure list cannot be null."
                );
            }

            warnings =
                    List.copyOf(
                            warnings
                    );

            failures =
                    List.copyOf(
                            failures
                    );

            if (restoredCount
                    + skippedCount
                    + failures.size()
                    != storedRecordCount) {

                throw new IllegalArgumentException(
                        "Restoration report counts do not account for every "
                                + "stored persistent-incursion record."
                );
            }

            if (newlySuspendedCount
                    > failures.size()) {

                throw new IllegalArgumentException(
                        "Newly suspended-incursion count cannot exceed the "
                                + "restoration failure count."
                );
            }

            if (warnings.size()
                    > restoredCount) {

                throw new IllegalArgumentException(
                        "Restoration warning count cannot exceed the number "
                                + "of successfully restored incursions."
                );
            }
        }

        public int getWarningCount() {
            return warnings.size();
        }

        public int getFailureCount() {
            return failures.size();
        }

        public boolean restoredEverything() {
            return failures.isEmpty();
        }

        public boolean hadStoredRecords() {
            return storedRecordCount > 0;
        }

        public boolean hadWarnings() {
            return !warnings.isEmpty();
        }

        public boolean hadFailures() {
            return !failures.isEmpty();
        }
    }

    /**
     * Non-fatal diagnostic information produced while restoring an otherwise
     * valid incursion.
     */
    public record RestorationWarning(
            UUID incursionId,
            String scenarioId,
            String message
    ) {

        public RestorationWarning {
            if (incursionId == null) {
                throw new IllegalArgumentException(
                        "Restoration warning incursion ID cannot be null."
                );
            }

            if (scenarioId == null
                    || scenarioId.isBlank()) {

                throw new IllegalArgumentException(
                        "Restoration warning Scenario ID cannot be blank."
                );
            }

            if (message == null
                    || message.isBlank()) {

                throw new IllegalArgumentException(
                        "Restoration warning message cannot be blank."
                );
            }
        }
    }

    /**
     * Diagnostic information for one record that could not enter live
     * runtime.
     */
    public record RestorationFailure(
            UUID incursionId,
            String scenarioId,
            PersistentIncursionPhase originalPhase,
            boolean markedSuspended,
            boolean reservationsRestored,
            String message
    ) {

        public RestorationFailure {
            if (incursionId == null) {
                throw new IllegalArgumentException(
                        "Restoration failure incursion ID cannot be null."
                );
            }

            if (scenarioId == null
                    || scenarioId.isBlank()) {

                throw new IllegalArgumentException(
                        "Restoration failure Scenario ID cannot be blank."
                );
            }

            if (originalPhase == null) {
                throw new IllegalArgumentException(
                        "Restoration failure original phase cannot be null."
                );
            }

            if (message == null
                    || message.isBlank()) {

                throw new IllegalArgumentException(
                        "Restoration failure message cannot be blank."
                );
            }

            if (markedSuspended
                    && originalPhase
                    != PersistentIncursionPhase.ACTIVE) {

                throw new IllegalArgumentException(
                        "Only an originally ACTIVE record can be newly marked "
                                + "SUSPENDED after restoration failure."
                );
            }
        }
    }

    /**
     * Internal result for one record restoration attempt.
     */
    private record RestorationAttempt(
            boolean successful,
            boolean markedSuspended,
            boolean reservationsRestored,
            String warningMessage,
            String failureMessage
    ) {

        private RestorationAttempt {
            if (successful) {
                if (markedSuspended
                        || failureMessage != null) {

                    throw new IllegalArgumentException(
                            "A successful restoration attempt cannot contain "
                                    + "failure state."
                    );
                }

                if (warningMessage != null
                        && warningMessage.isBlank()) {

                    throw new IllegalArgumentException(
                            "A restoration warning cannot be blank."
                    );
                }
            } else {
                if (warningMessage != null) {
                    throw new IllegalArgumentException(
                            "A failed restoration attempt cannot also contain "
                                    + "a success warning."
                    );
                }

                if (failureMessage == null
                        || failureMessage.isBlank()) {

                    throw new IllegalArgumentException(
                            "A failed restoration attempt requires a failure "
                                    + "message."
                    );
                }
            }
        }

        private static RestorationAttempt success(
                boolean reservationsRestored,
                String warningMessage
        ) {
            return new RestorationAttempt(
                    true,
                    false,
                    reservationsRestored,
                    warningMessage,
                    null
            );
        }

        private static RestorationAttempt failure(
                boolean markedSuspended,
                boolean reservationsRestored,
                String failureMessage
        ) {
            return new RestorationAttempt(
                    false,
                    markedSuspended,
                    reservationsRestored,
                    null,
                    failureMessage
            );
        }
    }

    /**
     * Internal result of attempting to preserve the latest reconstructed
     * runtime state after a restoration failure.
     */
    private record SnapshotRefreshAttempt(
            PersistentIncursionSnapshot snapshot,
            boolean refreshFailed,
            String failureMessage
    ) {

        private SnapshotRefreshAttempt {
            if (snapshot == null) {
                throw new IllegalArgumentException(
                        "Snapshot-refresh result requires a fallback snapshot."
                );
            }

            if (refreshFailed) {
                if (failureMessage == null
                        || failureMessage.isBlank()) {

                    throw new IllegalArgumentException(
                            "A failed snapshot refresh requires a failure "
                                    + "message."
                    );
                }
            } else if (failureMessage != null) {
                throw new IllegalArgumentException(
                        "A successful or unnecessary snapshot refresh cannot "
                                + "contain a failure message."
                );
            }
        }

        private static SnapshotRefreshAttempt succeeded(
                PersistentIncursionSnapshot snapshot
        ) {
            return new SnapshotRefreshAttempt(
                    snapshot,
                    false,
                    null
            );
        }

        private static SnapshotRefreshAttempt notRequired(
                PersistentIncursionSnapshot snapshot
        ) {
            return new SnapshotRefreshAttempt(
                    snapshot,
                    false,
                    null
            );
        }

        private static SnapshotRefreshAttempt failed(
                PersistentIncursionSnapshot fallbackSnapshot,
                String failureMessage
        ) {
            return new SnapshotRefreshAttempt(
                    fallbackSnapshot,
                    true,
                    failureMessage
            );
        }
    }

    /**
     * Internal result for changing one failed ACTIVE SavedData record to
     * SUSPENDED.
     */
    private record SuspensionAttempt(
            boolean markedSuspended,
            boolean suspensionFailed,
            String failureMessage
    ) {

        private SuspensionAttempt {
            if (markedSuspended
                    && suspensionFailed) {

                throw new IllegalArgumentException(
                        "A suspension attempt cannot both succeed and fail."
                );
            }

            if (suspensionFailed) {
                if (failureMessage == null
                        || failureMessage.isBlank()) {

                    throw new IllegalArgumentException(
                            "A failed suspension attempt requires a failure "
                                    + "message."
                    );
                }
            } else if (failureMessage != null) {
                throw new IllegalArgumentException(
                        "A non-failed suspension attempt cannot contain a "
                                + "failure message."
                );
            }
        }

        private static SuspensionAttempt succeeded() {
            return new SuspensionAttempt(
                    true,
                    false,
                    null
            );
        }

        private static SuspensionAttempt notRequired() {
            return new SuspensionAttempt(
                    false,
                    false,
                    null
            );
        }

        private static SuspensionAttempt failed(
                String failureMessage
        ) {
            return new SuspensionAttempt(
                    false,
                    true,
                    failureMessage
            );
        }
    }

    private PersistentIncursionRestorationService() {
    }
}