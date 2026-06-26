package org.ratden.skavenblight.event.skavenIncursion.scenario;

import java.util.UUID;

public interface SkavenScenario {

    String getId();

    UUID getInstanceId();

    void tick();

    boolean isFinished();
}