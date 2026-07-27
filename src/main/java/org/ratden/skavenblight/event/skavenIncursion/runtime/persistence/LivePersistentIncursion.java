package org.ratden.skavenblight.event.skavenIncursion.runtime.persistence;

import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.IncursionPlanSnapshot;
import org.ratden.skavenblight.event.skavenIncursion.runtime.event.SourceDestroyedEvent;

import java.util.UUID;

/**
 * Live owner of one persistence-aware planned incursion.
 *
 * This object keeps together:
 *
 * - the ServerLevel in which the incursion is running;
 * - the restored or freshly completed immutable IncursionPlan;
 * - the immutable snapshot of that plan used for persistence;
 * - the selected Scenario and Stratagem identities;
 * - the original target snapshot;
 * - the live persistable Scenario;
 * - the persistence-layer lifecycle phase.
 *
 * The live Scenario continues to own mutable combat execution. This owner
 * coordinates that runtime with persistent metadata and determines when the
 * record should move from ACTIVE to CLEANUP_PENDING.
 *
 * This class does not write SavedData directly. ActiveIncursionManager will
 * later use createPersistentSnapshot() to add or replace the corresponding
 * record in SkavenIncursionSavedData.
 *
 * Restoration performed here is logical only. The supplied IncursionPlan and
 * Scenario must already have been reconstructed from the saved snapshots.
 * World reconciliation remains a later operation.
 */
public final class LivePersistentIncursion {

    private final ServerLevel level;

    private final UUID incursionId;

    private final String scenarioId;
    private final String stratagemId;

    private final IncursionTargetSnapshot targetSnapshot;

    private final IncursionPlan incursionPlan;
    private final IncursionPlanSnapshot incursionPlanSnapshot;

    private final PersistableSkavenScenario<
            PlannedScenarioRuntimeSnapshot
            > scenario;

    private PersistentIncursionPhase phase;

    private LivePersistentIncursion(
            ServerLevel level,
            String scenarioId,
            String stratagemId,
            IncursionTargetSnapshot targetSnapshot,
            IncursionPlan incursionPlan,
            IncursionPlanSnapshot incursionPlanSnapshot,
            PersistableSkavenScenario<
                    PlannedScenarioRuntimeSnapshot
                    > scenario,
            PersistentIncursionPhase phase
    ) {
        if (level == null) {
            throw new IllegalArgumentException(
                    "Live persistent incursion level cannot be null."
            );
        }

        requireNonBlank(
                scenarioId,
                "Live persistent incursion Scenario ID cannot be blank."
        );

        requireNonBlank(
                stratagemId,
                "Live persistent incursion Stratagem ID cannot be blank."
        );

        if (targetSnapshot == null) {
            throw new IllegalArgumentException(
                    "Live persistent incursion target snapshot cannot be "
                            + "null."
            );
        }

        if (incursionPlan == null) {
            throw new IllegalArgumentException(
                    "Live persistent incursion plan cannot be null."
            );
        }

        if (incursionPlanSnapshot == null) {
            throw new IllegalArgumentException(
                    "Live persistent incursion plan snapshot cannot be null."
            );
        }

        if (scenario == null) {
            throw new IllegalArgumentException(
                    "Live persistent incursion Scenario cannot be null."
            );
        }

        if (phase == null) {
            throw new IllegalArgumentException(
                    "Live persistent incursion phase cannot be null."
            );
        }

        UUID plannedIncursionId =
                incursionPlan.getIncursionId();

        if (plannedIncursionId == null) {
            throw new IllegalArgumentException(
                    "Live persistent IncursionPlan has no incursion ID."
            );
        }

        this.level =
                level;

        this.incursionId =
                plannedIncursionId;

        this.scenarioId =
                scenarioId;

        this.stratagemId =
                stratagemId;

        this.targetSnapshot =
                targetSnapshot;

        this.incursionPlan =
                incursionPlan;

        this.incursionPlanSnapshot =
                incursionPlanSnapshot;

        this.scenario =
                scenario;

        this.phase =
                phase;

        validateInternalState();
    }

    /**
     * Creates the live owner for a newly planned and admitted incursion.
     *
     * The supplied Scenario must represent fresh unfinished runtime state.
     * The caller supplies the immutable plan snapshot separately so this
     * owner remains independent of the planning snapshot-capture mechanism.
     */
    public static LivePersistentIncursion createFresh(
            ServerLevel level,
            String stratagemId,
            IncursionTargetSnapshot targetSnapshot,
            IncursionPlan incursionPlan,
            IncursionPlanSnapshot incursionPlanSnapshot,
            PersistableSkavenScenario<
                    PlannedScenarioRuntimeSnapshot
                    > scenario
    ) {
        if (scenario == null) {
            throw new IllegalArgumentException(
                    "Fresh persistent incursion Scenario cannot be null."
            );
        }

        PlannedScenarioRuntimeSnapshot runtimeSnapshot =
                scenario.createSnapshot();

        if (runtimeSnapshot.finished()) {
            throw new IllegalArgumentException(
                    "A fresh persistent incursion cannot begin with finished "
                            + "Scenario runtime state."
            );
        }

        return new LivePersistentIncursion(
                level,
                scenario.getId(),
                stratagemId,
                targetSnapshot,
                incursionPlan,
                incursionPlanSnapshot,
                scenario,
                PersistentIncursionPhase.ACTIVE
        );
    }

    /**
     * Reassembles the live owner around an already restored immutable plan
     * and Scenario.
     *
     * The supplied plan and Scenario must have been restored from the nested
     * snapshots contained in persistentSnapshot. This method confirms that
     * their newly captured persistent representation exactly matches the
     * original saved record.
     */
    public static LivePersistentIncursion restore(
            ServerLevel level,
            IncursionPlan restoredIncursionPlan,
            PersistableSkavenScenario<
                    PlannedScenarioRuntimeSnapshot
                    > restoredScenario,
            PersistentIncursionSnapshot persistentSnapshot
    ) {
        if (persistentSnapshot == null) {
            throw new IllegalArgumentException(
                    "Persistent incursion snapshot cannot be null."
            );
        }

        LivePersistentIncursion restoredIncursion =
                new LivePersistentIncursion(
                        level,
                        persistentSnapshot.scenarioId(),
                        persistentSnapshot.stratagemId(),
                        persistentSnapshot.targetSnapshot(),
                        restoredIncursionPlan,
                        persistentSnapshot.incursionPlanSnapshot(),
                        restoredScenario,
                        persistentSnapshot.phase()
                );

        PersistentIncursionSnapshot reconstructedSnapshot =
                restoredIncursion.createPersistentSnapshot();

        if (!persistentSnapshot.equals(
                reconstructedSnapshot
        )) {
            throw new IllegalArgumentException(
                    "Restored live incursion does not exactly match saved "
                            + "persistent record "
                            + persistentSnapshot.incursionId()
                            + "."
            );
        }

        return restoredIncursion;
    }

    public ServerLevel getLevel() {
        return level;
    }

    public UUID getIncursionId() {
        return incursionId;
    }

    public String getScenarioId() {
        return scenarioId;
    }

    public String getStratagemId() {
        return stratagemId;
    }

    public IncursionTargetSnapshot getTargetSnapshot() {
        return targetSnapshot;
    }

    public IncursionPlan getIncursionPlan() {
        return incursionPlan;
    }

    public IncursionPlanSnapshot getIncursionPlanSnapshot() {
        return incursionPlanSnapshot;
    }

    public PersistableSkavenScenario<
            PlannedScenarioRuntimeSnapshot
            > getScenario() {

        return scenario;
    }

    public PersistentIncursionPhase getPhase() {
        return phase;
    }

    public boolean isTickable() {
        return phase.isTickable();
    }

    public boolean requiresCleanup() {
        return phase.requiresCleanup();
    }

    public boolean isSuspended() {
        return phase.isSuspended();
    }

    public boolean isScenarioFinished() {
        return scenario.isFinished();
    }

    /**
     * Advances the live Scenario when this persistent incursion is ACTIVE.
     *
     * When the Scenario finishes, the persistent record moves immediately to
     * CLEANUP_PENDING. The manager will later save that finished state before
     * performing cleanup and removing the record.
     *
     * SUSPENDED and CLEANUP_PENDING records do not tick.
     */
    public void tick() {
        if (!phase.isTickable()) {
            return;
        }

        scenario.tick();

        synchronisePhaseWithScenario();

        validateInternalState();
    }

    /**
     * Routes a physical-source destruction to the live Scenario.
     *
     * Only ACTIVE records receive runtime events. Events for another
     * incursion ID are ignored.
     *
     * @return true when this owner accepted and routed the event
     */
    public boolean handleSourceDestroyed(
            SourceDestroyedEvent event
    ) {
        if (event == null) {
            throw new IllegalArgumentException(
                    "Source destruction event cannot be null."
            );
        }

        if (!phase.isTickable()) {
            return false;
        }

        if (!incursionId.equals(
                event.scenarioInstanceId()
        )) {
            return false;
        }

        scenario.onSourceDestroyed(
                event
        );

        synchronisePhaseWithScenario();

        validateInternalState();

        return true;
    }

    /**
     * Suspends unfinished runtime without discarding its plan or progress.
     *
     * Suspension is reserved for recoverable restoration or reconciliation
     * failures. Normal shutdown and restart should leave a valid incursion
     * ACTIVE.
     *
     * @return true when the phase changed
     */
    public boolean suspend() {
        if (phase == PersistentIncursionPhase.SUSPENDED) {
            return false;
        }

        if (phase == PersistentIncursionPhase.CLEANUP_PENDING) {
            throw new IllegalStateException(
                    "A cleanup-pending incursion cannot be suspended."
            );
        }

        if (scenario.isFinished()) {
            throw new IllegalStateException(
                    "A finished Scenario cannot be suspended."
            );
        }

        phase =
                PersistentIncursionPhase.SUSPENDED;

        validateInternalState();

        return true;
    }

    /**
     * Resumes previously suspended unfinished runtime.
     *
     * @return true when the phase changed
     */
    public boolean resume() {
        if (phase != PersistentIncursionPhase.SUSPENDED) {
            return false;
        }

        if (scenario.isFinished()) {
            throw new IllegalStateException(
                    "A finished Scenario cannot resume as ACTIVE."
            );
        }

        phase =
                PersistentIncursionPhase.ACTIVE;

        validateInternalState();

        return true;
    }

    /**
     * Captures the complete currently authoritative persistent record.
     *
     * This operation does not tick the Scenario or modify the world.
     */
    public PersistentIncursionSnapshot createPersistentSnapshot() {
        /*
         * Lightweight live ownership is checked first. Scenario snapshot
         * creation then performs the complete nested runtime validation only
         * at the actual persistence boundary.
         */
        validateInternalState();

        PlannedScenarioRuntimeSnapshot runtimeSnapshot =
                scenario.createSnapshot();

        return createPersistentSnapshot(
                runtimeSnapshot
        );
    }

    private PersistentIncursionSnapshot createPersistentSnapshot(
            PlannedScenarioRuntimeSnapshot runtimeSnapshot
    ) {
        return new PersistentIncursionSnapshot(
                incursionId,
                scenarioId,
                stratagemId,
                targetSnapshot,
                incursionPlanSnapshot,
                runtimeSnapshot,
                phase
        );
    }

    /**
     * Moves finished runtime to CLEANUP_PENDING.
     *
     * An active Scenario is never permitted to remain ACTIVE after reporting
     * completion.
     */
    private void synchronisePhaseWithScenario() {
        if (phase != PersistentIncursionPhase.ACTIVE) {
            return;
        }

        if (scenario.isFinished()) {
            phase =
                    PersistentIncursionPhase.CLEANUP_PENDING;
        }
    }

    /**
     * Performs lightweight validation of the live ownership relationship.
     *
     * This method is called during ordinary ticking and lifecycle changes. It
     * must therefore avoid constructing the complete Scenario persistence
     * graph.
     *
     * Full plan, wave, source, queue and lifecycle validation occurs through
     * createPersistentSnapshot() when runtime is actually checkpointed.
     */
    private void validateInternalState() {
        if (!incursionId.equals(
                incursionPlan.getIncursionId()
        )) {
            throw new IllegalStateException(
                    "Live persistent incursion ID does not match its "
                            + "IncursionPlan."
            );
        }

        if (!incursionId.equals(
                incursionPlanSnapshot.incursionId()
        )) {
            throw new IllegalStateException(
                    "Live persistent incursion ID does not match its immutable "
                            + "plan snapshot."
            );
        }

        if (!scenarioId.equals(
                scenario.getId()
        )) {
            throw new IllegalStateException(
                    "Live persistent Scenario ID '"
                            + scenarioId
                            + "' does not match live Scenario ID '"
                            + scenario.getId()
                            + "'."
            );
        }

        if (!incursionId.equals(
                scenario.getInstanceId()
        )) {
            throw new IllegalStateException(
                    "Live persistent incursion ID does not match the live "
                            + "Scenario instance ID."
            );
        }

        boolean scenarioFinished =
                scenario.isFinished();

        switch (phase) {
            case ACTIVE -> {
                if (scenarioFinished) {
                    throw new IllegalStateException(
                            "An ACTIVE live persistent incursion cannot own a "
                                    + "finished Scenario."
                    );
                }
            }

            case CLEANUP_PENDING -> {
                if (!scenarioFinished) {
                    throw new IllegalStateException(
                            "A CLEANUP_PENDING live persistent incursion "
                                    + "requires a finished Scenario."
                    );
                }
            }

            case SUSPENDED -> {
                if (scenarioFinished) {
                    throw new IllegalStateException(
                            "A finished live persistent incursion should be "
                                    + "CLEANUP_PENDING rather than SUSPENDED."
                    );
                }
            }
        }
    }

    private static void requireNonBlank(
            String value,
            String message
    ) {
        if (value == null
                || value.isBlank()) {

            throw new IllegalArgumentException(
                    message
            );
        }
    }
}