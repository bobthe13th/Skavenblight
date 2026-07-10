package org.ratden.skavenblight.event.skavenIncursion.leadership;

import java.util.UUID;

public class LeaderGroup {
    private final UUID id;
    private final LeaderGroupType type;

    private LeaderRank leaderRank;

    public LeaderGroup(
            UUID id,
            LeaderGroupType type,
            LeaderRank leaderRank
    ) {
        this.id = id;
        this.type = type;
        this.leaderRank = leaderRank;
    }

    public UUID getId() {
        return id;
    }

    public LeaderGroupType getType() {
        return type;
    }

    public LeaderRank getLeaderRank() {
        return leaderRank;
    }

    public void setLeaderRank(LeaderRank leaderRank) {
        this.leaderRank = leaderRank;
    }
}