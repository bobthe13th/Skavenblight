package org.ratden.skavenblight.event.skavenIncursion.leadership;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class LeadershipRegistry {
    private final UUID scenarioId;
    private final Map<UUID, LeaderGroup> leaderGroups = new HashMap<>();

    public LeadershipRegistry(UUID scenarioId) {
        this.scenarioId = scenarioId;
    }

    public UUID getScenarioId() {
        return scenarioId;
    }

    public LeaderGroup createLeaderGroup(
            LeaderGroupType groupType,
            LeaderRank leaderRank
    ) {
        LeaderGroup leaderGroup = new LeaderGroup(
                UUID.randomUUID(),
                groupType,
                leaderRank
        );

        registerLeaderGroup(leaderGroup);

        return leaderGroup;
    }

    public void registerLeaderGroup(LeaderGroup leaderGroup) {
        leaderGroups.put(leaderGroup.getId(), leaderGroup);
    }

    public LeaderGroup getLeaderGroup(UUID leaderGroupId) {
        return leaderGroups.get(leaderGroupId);
    }

    public Collection<LeaderGroup> getLeaderGroups() {
        return leaderGroups.values();
    }

    public LeadershipContext createContext(
            LeaderGroup vermintideGroup,
            LeaderGroup fangGroup,
            LeaderGroup clawGroup,
            LeaderGroup packGroup
    ) {
        return new LeadershipContext(
                scenarioId,
                getGroupIdOrNull(vermintideGroup),
                getGroupIdOrNull(fangGroup),
                getGroupIdOrNull(clawGroup),
                getGroupIdOrNull(packGroup)
        );
    }

    public LeadershipContext createPackContext(LeaderGroup packGroup) {
        return createContext(null, null, null, packGroup);
    }

    private UUID getGroupIdOrNull(LeaderGroup leaderGroup) {
        if (leaderGroup == null) {
            return null;
        }

        return leaderGroup.getId();
    }
}