package org.ratden.skavenblight.event.skavenIncursion.runtime.persistence;

import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.IncursionPlanSnapshot;

import java.util.Objects;
import java.util.UUID;

/**
 * Complete immutable persistent representation of one admitted incursion.
 *
 * This is the unit that will later be stored by SkavenIncursionSavedData.
 * It joins:
 *
 * - root Scenario identity;
 * - selected Stratagem identity;
 * - the positional target snapshot used during planning;
 * - the complete immutable IncursionPlan snapshot;
 * - the complete common planned-Scenario runtime snapshot;
 * - the persistence-layer lifecycle phase.
 *
 * Some identity values deliberately appear in more than one nested snapshot.
 * That redundancy allows each persistence layer to validate its own data and
 * prevents unrelated plans, runtime graphs or Scenario records from being
 * silently combined.
 *
 * The containing SavedData will eventually use incursionId as its canonical
 * map key.
 */
public record PersistentIncursionSnapshot(
        UUID incursionId,
        String scenarioId,
        String stratagemId,
        IncursionTargetSnapshot targetSnapshot,
        IncursionPlanSnapshot incursionPlanSnapshot,
        PlannedScenarioRuntimeSnapshot scenarioRuntimeSnapshot,
        PersistentIncursionPhase phase
) {

    public PersistentIncursionSnapshot {
        Objects.requireNonNull(
                incursionId,
                "Persistent incursion ID cannot be null."
        );

        requireNonBlank(
                scenarioId,
                "Persistent incursion Scenario ID cannot be blank."
        );

        requireNonBlank(
                stratagemId,
                "Persistent incursion Stratagem ID cannot be blank."
        );

        Objects.requireNonNull(
                targetSnapshot,
                "Persistent incursion target snapshot cannot be null."
        );

        Objects.requireNonNull(
                incursionPlanSnapshot,
                "Persistent incursion plan snapshot cannot be null."
        );

        Objects.requireNonNull(
                scenarioRuntimeSnapshot,
                "Persistent incursion runtime snapshot cannot be null."
        );

        Objects.requireNonNull(
                phase,
                "Persistent incursion phase cannot be null."
        );

        validateIdentities(
                incursionId,
                scenarioId,
                incursionPlanSnapshot,
                scenarioRuntimeSnapshot
        );

        validateLifecycle(
                phase,
                scenarioRuntimeSnapshot
        );
    }

    /**
     * Returns a new snapshot with the same plan and runtime state but a
     * different persistence-layer lifecycle phase.
     *
     * The constructor validates that the requested transition produces a
     * structurally valid snapshot.
     */
    public PersistentIncursionSnapshot withPhase(
            PersistentIncursionPhase newPhase
    ) {
        return new PersistentIncursionSnapshot(
                incursionId,
                scenarioId,
                stratagemId,
                targetSnapshot,
                incursionPlanSnapshot,
                scenarioRuntimeSnapshot,
                newPhase
        );
    }

    /**
     * Returns a new snapshot containing updated Scenario runtime state.
     *
     * This is useful when SavedData replaces one immutable persistent record
     * after the live Scenario has ticked.
     */
    public PersistentIncursionSnapshot withScenarioRuntimeSnapshot(
            PlannedScenarioRuntimeSnapshot newRuntimeSnapshot
    ) {
        return new PersistentIncursionSnapshot(
                incursionId,
                scenarioId,
                stratagemId,
                targetSnapshot,
                incursionPlanSnapshot,
                newRuntimeSnapshot,
                phase
        );
    }

    /**
     * Returns a new snapshot containing both updated runtime state and its
     * corresponding persistence phase.
     *
     * This avoids constructing an invalid intermediate snapshot when a
     * Scenario finishes and moves directly from ACTIVE to CLEANUP_PENDING.
     */
    public PersistentIncursionSnapshot withRuntimeAndPhase(
            PlannedScenarioRuntimeSnapshot newRuntimeSnapshot,
            PersistentIncursionPhase newPhase
    ) {
        return new PersistentIncursionSnapshot(
                incursionId,
                scenarioId,
                stratagemId,
                targetSnapshot,
                incursionPlanSnapshot,
                newRuntimeSnapshot,
                newPhase
        );
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
        return scenarioRuntimeSnapshot.finished();
    }

    public boolean hasTimedOut() {
        return scenarioRuntimeSnapshot.timedOut();
    }

    private static void validateIdentities(
            UUID incursionId,
            String scenarioId,
            IncursionPlanSnapshot incursionPlanSnapshot,
            PlannedScenarioRuntimeSnapshot scenarioRuntimeSnapshot
    ) {
        if (!incursionId.equals(
                incursionPlanSnapshot.incursionId()
        )) {
            throw new IllegalArgumentException(
                    "Persistent incursion ID "
                            + incursionId
                            + " does not match immutable plan ID "
                            + incursionPlanSnapshot.incursionId()
                            + "."
            );
        }

        if (!incursionId.equals(
                scenarioRuntimeSnapshot.instanceId()
        )) {
            throw new IllegalArgumentException(
                    "Persistent incursion ID "
                            + incursionId
                            + " does not match Scenario runtime instance ID "
                            + scenarioRuntimeSnapshot.instanceId()
                            + "."
            );
        }

        if (!scenarioId.equals(
                scenarioRuntimeSnapshot.scenarioId()
        )) {
            throw new IllegalArgumentException(
                    "Persistent incursion Scenario ID '"
                            + scenarioId
                            + "' does not match runtime Scenario ID '"
                            + scenarioRuntimeSnapshot.scenarioId()
                            + "'."
            );
        }
    }

    private static void validateLifecycle(
            PersistentIncursionPhase phase,
            PlannedScenarioRuntimeSnapshot scenarioRuntimeSnapshot
    ) {
        switch (phase) {
            case ACTIVE -> {
                if (scenarioRuntimeSnapshot.finished()) {
                    throw new IllegalArgumentException(
                            "An ACTIVE persistent incursion cannot contain "
                                    + "finished Scenario runtime state."
                    );
                }
            }

            case CLEANUP_PENDING -> {
                if (!scenarioRuntimeSnapshot.finished()) {
                    throw new IllegalArgumentException(
                            "A CLEANUP_PENDING persistent incursion requires "
                                    + "finished Scenario runtime state."
                    );
                }
            }

            case SUSPENDED -> {
                if (scenarioRuntimeSnapshot.finished()) {
                    throw new IllegalArgumentException(
                            "A finished persistent incursion should be "
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