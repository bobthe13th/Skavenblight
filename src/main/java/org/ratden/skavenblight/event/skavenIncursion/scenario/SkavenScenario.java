package org.ratden.skavenblight.event.skavenIncursion.scenario;

import java.util.UUID;

public interface SkavenScenario {

    ScenarioDefinition getDefinition();

    String getId();

    UUID getInstanceId();

    ScenarioPattern getPattern();

    void tick();

    boolean isFinished();
}