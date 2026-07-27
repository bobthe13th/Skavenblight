package org.ratden.skavenblight.event.skavenIncursion.runtime.persistence;

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
 * This interface does not save NBT or interact with SavedData. It exposes only
 * Scenario-owned persistence and reconciliation operations.
 *
 * @param <S> immutable persistence snapshot produced by the Scenario
 */
public interface PersistableSkavenScenario<S>
        extends SkavenScenario {

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