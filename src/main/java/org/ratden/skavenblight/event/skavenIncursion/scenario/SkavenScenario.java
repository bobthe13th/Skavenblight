package org.ratden.skavenblight.event.skavenIncursion.scenario;

import org.ratden.skavenblight.event.skavenIncursion.runtime.event.SourceDestroyedEvent;

import java.util.UUID;

public interface SkavenScenario {

    ScenarioDefinition getDefinition();

    String getId();

    UUID getInstanceId();

    ScenarioPattern getPattern();

    void tick();

    boolean isFinished();

    /**
     * Receives notification that a physical source owned by this Scenario
     * instance was destroyed.
     *
     * Legacy Scenarios may ignore this event. Planning-aware runtime
     * Scenarios will override it to cancel current-wave source assignments,
     * record destruction counts and trigger authored reactions.
     */
    default void onSourceDestroyed(
            SourceDestroyedEvent event
    ) {
        /*
         * Optional callback retained as a no-op for legacy Scenarios.
         */
    }
}