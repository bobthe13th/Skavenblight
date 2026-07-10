package org.ratden.skavenblight.event.skavenIncursion.leadership;

import java.util.UUID;

public class LeadershipContext {
    private final UUID scenarioId;
    private final UUID vermintideId;
    private final UUID fangId;
    private final UUID clawId;
    private final UUID packId;

    public LeadershipContext(
            UUID scenarioId,
            UUID vermintideId,
            UUID fangId,
            UUID clawId,
            UUID packId
    ) {
        this.scenarioId = scenarioId;
        this.vermintideId = vermintideId;
        this.fangId = fangId;
        this.clawId = clawId;
        this.packId = packId;
    }

    public static LeadershipContext debug(UUID scenarioId) {
        return new LeadershipContext(
                scenarioId,
                null,
                null,
                null,
                null
        );
    }

    public UUID getScenarioId() {
        return scenarioId;
    }

    public UUID getVermintideId() {
        return vermintideId;
    }

    public UUID getFangId() {
        return fangId;
    }

    public UUID getClawId() {
        return clawId;
    }

    public UUID getPackId() {
        return packId;
    }
}