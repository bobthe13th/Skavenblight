package org.ratden.skavenblight.event.skavenIncursion.runtime.persistence;

/**
 * Persistent lifecycle state of one admitted incursion.
 *
 * This phase belongs to the persistence and active-incursion management
 * layers. It does not replace Scenario-specific progression such as wave
 * transitions, authored aftermath or objective phases.
 */
public enum PersistentIncursionPhase {

    /**
     * The Scenario has unfinished runtime state and may be ticked normally.
     */
    ACTIVE,

    /**
     * The Scenario has finished, but its persistent record is retained until
     * world cleanup, reservation cleanup and manager removal have completed.
     */
    CLEANUP_PENDING,

    /**
     * The Scenario remains unfinished but must not currently tick.
     *
     * This is intended for recoverable restoration or reconciliation
     * problems. A normal server shutdown and restart must not place a valid
     * incursion into this phase.
     */
    SUSPENDED;

    public boolean isTickable() {
        return this == ACTIVE;
    }

    public boolean requiresCleanup() {
        return this == CLEANUP_PENDING;
    }

    public boolean isSuspended() {
        return this == SUSPENDED;
    }
}