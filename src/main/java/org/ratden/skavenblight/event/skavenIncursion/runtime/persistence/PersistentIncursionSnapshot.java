package org.ratden.skavenblight.event.skavenIncursion.runtime.persistence;

import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.IncursionChunkLoadPlanSnapshot;
import org.ratden.skavenblight.event.skavenIncursion.planning.persistence.IncursionPlanSnapshot;
import org.ratden.skavenblight.event.skavenIncursion.runtime.mob.IncursionMobTrackingState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.validation.PersistentIncursionMobTrackingValidator;

import java.util.Objects;
import java.util.UUID;

/**
 * Complete immutable persistent representation of one admitted incursion.
 *
 * This is the canonical record stored by SkavenIncursionSavedData. It joins:
 *
 * - root Scenario identity;
 * - selected Stratagem identity;
 * - the positional target snapshot used during planning;
 * - the complete immutable IncursionPlan snapshot;
 * - the exact calculated chunk-load plan admitted with that IncursionPlan;
 * - the complete common planned-Scenario runtime snapshot;
 * - persistent tracking for every successfully delivered incursion mob;
 * - the persistence-layer lifecycle phase.
 *
 * Some identity values deliberately appear in more than one nested snapshot.
 * That redundancy allows each persistence layer to validate its own data and
 * prevents unrelated plans, chunk footprints, runtime graphs, mob-tracking
 * records or Scenario records from being silently combined.
 *
 * Pending mobs remain authoritative in the Scenario runtime's
 * SourceSpawnQueue snapshots.
 *
 * Successfully delivered mobs are authoritative in mobTrackingSnapshot.
 *
 * Cancelled mobs appear in neither remaining queues nor successful-spawn
 * tracking. Their threat can therefore be derived later from the immutable
 * planned threat, pending queue threat and successfully delivered threat.
 *
 * The containing SavedData uses incursionId as its canonical map key.
 */
public record PersistentIncursionSnapshot(
        UUID incursionId,
        String scenarioId,
        String stratagemId,
        IncursionTargetSnapshot targetSnapshot,
        IncursionPlanSnapshot incursionPlanSnapshot,
        IncursionChunkLoadPlanSnapshot chunkLoadPlanSnapshot,
        PlannedScenarioRuntimeSnapshot scenarioRuntimeSnapshot,
        IncursionMobTrackingState.Snapshot mobTrackingSnapshot,
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
                chunkLoadPlanSnapshot,
                "Persistent incursion chunk-load plan snapshot cannot be "
                        + "null."
        );

        Objects.requireNonNull(
                scenarioRuntimeSnapshot,
                "Persistent incursion runtime snapshot cannot be null."
        );

        Objects.requireNonNull(
                mobTrackingSnapshot,
                "Persistent incursion mob-tracking snapshot cannot be null."
        );

        Objects.requireNonNull(
                phase,
                "Persistent incursion phase cannot be null."
        );

        validateIdentities(
                incursionId,
                scenarioId,
                incursionPlanSnapshot,
                chunkLoadPlanSnapshot,
                scenarioRuntimeSnapshot,
                mobTrackingSnapshot
        );

        validateLifecycle(
                phase,
                scenarioRuntimeSnapshot
        );

        PersistentIncursionMobTrackingValidator.validate(
                incursionId,
                scenarioRuntimeSnapshot,
                mobTrackingSnapshot
        );
    }

    /**
     * Returns a new snapshot with the same planning, Scenario runtime and mob
     * tracking state but a different persistence-layer lifecycle phase.
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
                chunkLoadPlanSnapshot,
                scenarioRuntimeSnapshot,
                mobTrackingSnapshot,
                newPhase
        );
    }

    /**
     * Returns a new snapshot containing updated Scenario runtime state.
     *
     * The immutable tactical plan, chunk-load plan and mob-tracking state
     * remain unchanged.
     *
     * This method is suitable for timer and controller progress that does not
     * also change successful mob-delivery state.
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
                chunkLoadPlanSnapshot,
                newRuntimeSnapshot,
                mobTrackingSnapshot,
                phase
        );
    }

    /**
     * Returns a new snapshot containing updated persistent mob-tracking state.
     *
     * This is suitable for lifecycle changes such as a tracked mob becoming
     * defeated while Scenario queue state remains unchanged.
     */
    public PersistentIncursionSnapshot withMobTrackingSnapshot(
            IncursionMobTrackingState.Snapshot newMobTrackingSnapshot
    ) {
        return new PersistentIncursionSnapshot(
                incursionId,
                scenarioId,
                stratagemId,
                targetSnapshot,
                incursionPlanSnapshot,
                chunkLoadPlanSnapshot,
                scenarioRuntimeSnapshot,
                newMobTrackingSnapshot,
                phase
        );
    }

    /**
     * Returns a new snapshot containing updated Scenario runtime and mob
     * tracking state.
     *
     * Successful delivery changes both sides of the accounting boundary:
     *
     * - the consumed queue entry is removed from pending runtime state;
     * - the successfully spawned entity is added to persistent mob tracking.
     *
     * Capturing both in one replacement avoids persisting an intermediate
     * state in which represented threat exists in both places or neither.
     */
    public PersistentIncursionSnapshot
    withRuntimeAndMobTrackingSnapshots(
            PlannedScenarioRuntimeSnapshot newRuntimeSnapshot,
            IncursionMobTrackingState.Snapshot newMobTrackingSnapshot
    ) {
        return new PersistentIncursionSnapshot(
                incursionId,
                scenarioId,
                stratagemId,
                targetSnapshot,
                incursionPlanSnapshot,
                chunkLoadPlanSnapshot,
                newRuntimeSnapshot,
                newMobTrackingSnapshot,
                phase
        );
    }

    /**
     * Returns a new snapshot containing updated Scenario runtime state and its
     * corresponding persistence phase.
     *
     * Mob-tracking state remains unchanged.
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
                chunkLoadPlanSnapshot,
                newRuntimeSnapshot,
                mobTrackingSnapshot,
                newPhase
        );
    }

    /**
     * Returns a new snapshot containing updated Scenario runtime state,
     * persistent mob tracking and lifecycle phase.
     *
     * This is the atomic replacement method for a transition that changes all
     * three mutable branches at once.
     */
    public PersistentIncursionSnapshot
    withRuntimeMobTrackingAndPhase(
            PlannedScenarioRuntimeSnapshot newRuntimeSnapshot,
            IncursionMobTrackingState.Snapshot newMobTrackingSnapshot,
            PersistentIncursionPhase newPhase
    ) {
        return new PersistentIncursionSnapshot(
                incursionId,
                scenarioId,
                stratagemId,
                targetSnapshot,
                incursionPlanSnapshot,
                chunkLoadPlanSnapshot,
                newRuntimeSnapshot,
                newMobTrackingSnapshot,
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
            IncursionChunkLoadPlanSnapshot chunkLoadPlanSnapshot,
            PlannedScenarioRuntimeSnapshot scenarioRuntimeSnapshot,
            IncursionMobTrackingState.Snapshot mobTrackingSnapshot
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
                chunkLoadPlanSnapshot.incursionId()
        )) {
            throw new IllegalArgumentException(
                    "Persistent incursion ID "
                            + incursionId
                            + " does not match immutable chunk-load plan ID "
                            + chunkLoadPlanSnapshot.incursionId()
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

        if (!incursionId.equals(
                mobTrackingSnapshot.incursionId()
        )) {
            throw new IllegalArgumentException(
                    "Persistent incursion ID "
                            + incursionId
                            + " does not match mob-tracking incursion ID "
                            + mobTrackingSnapshot.incursionId()
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