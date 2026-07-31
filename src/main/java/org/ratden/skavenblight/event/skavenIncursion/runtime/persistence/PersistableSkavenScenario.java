package org.ratden.skavenblight.event.skavenIncursion.runtime.persistence;

import org.ratden.skavenblight.event.skavenIncursion.runtime.mob.IncursionMobTrackingState;
import org.ratden.skavenblight.event.skavenIncursion.scenario.SkavenScenario;

import java.util.UUID;

/**
 * Capability implemented by a live Skaven Scenario whose mutable state can be
 * captured as an immutable persistence snapshot.
 *
 * The snapshot type is generic so ordinary planned Scenarios can use
 * PlannedScenarioRuntimeSnapshot while a future specialised Scenario may use
 * a richer snapshot containing additional authored state.
 *
 * Persistence-aware Scenarios are owned and ticked by
 * LivePersistentIncursion. That owner supplies the authoritative
 * IncursionMobTrackingState belonging to the same incursion.
 *
 * This interface does not save NBT or interact with SavedData. It exposes only
 * Scenario-owned persistence, ticking and reconciliation operations.
 *
 * @param <S> immutable persistence snapshot produced by the Scenario
 */
public interface PersistableSkavenScenario<S>
        extends SkavenScenario {

    /**
     * Advances the Scenario using the authoritative persistent mob-tracking
     * state belonging to its live incursion owner.
     *
     * The default implementation temporarily delegates to the original
     * no-argument Scenario tick so the tracking dependency can be propagated
     * through the runtime hierarchy incrementally.
     *
     * Planning-aware Scenarios that deliver mobs must override this method.
     * The compatibility delegation will be removed after the existing
     * Scenario runtime has been converted.
     */
    default void tickPersistent(
            IncursionMobTrackingState mobTrackingState
    ) {
        if (mobTrackingState == null) {
            throw new IllegalArgumentException(
                    "Persistent Scenario tick requires mob-tracking state."
            );
        }

        if (!getInstanceId().equals(
                mobTrackingState.getIncursionId()
        )) {
            throw new IllegalArgumentException(
                    "Scenario instance ID "
                            + getInstanceId()
                            + " does not match mob-tracking incursion ID "
                            + mobTrackingState.getIncursionId()
                            + "."
            );
        }

        tick();
    }

    /**
     * Captures the Scenario's complete current logical runtime state.
     *
     * Creating a snapshot must not modify the world or advance the Scenario.
     */
    S createSnapshot();

    /**
     * Reconciles a physical source incarnation that persistence says should
     * exist but which is absent from the restored world.
     *
     * The Scenario must:
     *
     * - mark the recognised physical incarnation destroyed;
     * - cancel any current-wave delivery dependent on that source;
     * - update any cancellation counters owned by the Scenario;
     * - avoid counting this as a routed player-generated destruction event.
     *
     * This method performs logical runtime reconciliation only. The caller is
     * responsible for inspecting and, where appropriate, repairing the world.
     *
     * @param runtimeSourceId persisted ID of the missing physical incarnation
     * @return true when the Scenario recognised and reconciled that source
     */
    boolean reconcileMissingPhysicalSource(
            UUID runtimeSourceId
    );
}