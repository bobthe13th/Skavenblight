package org.ratden.skavenblight.event.skavenIncursion.leadership;

import java.util.UUID;

public record LeadershipContext(UUID scenarioId, UUID vermintideId, UUID fangId, UUID clawId, UUID packId) {

    public static LeadershipContext debug(UUID scenarioId) {
        return new LeadershipContext(
                scenarioId,
                null,
                null,
                null,
                null
        );
    }
}