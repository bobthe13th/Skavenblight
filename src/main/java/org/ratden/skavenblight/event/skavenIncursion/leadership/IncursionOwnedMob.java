package org.ratden.skavenblight.event.skavenIncursion.leadership;

import java.util.UUID;

public interface IncursionOwnedMob {
    void setScenarioId(UUID scenarioId);
    UUID getScenarioId();

    void setSourceId(UUID sourceId);
    UUID getSourceId();

    void setPackId(UUID packId);
    UUID getPackId();

    void setClawId(UUID clawId);
    UUID getClawId();

    void setFangId(UUID fangId);
    UUID getFangId();

    void setVermintideId(UUID vermintideId);
    UUID getVermintideId();
}