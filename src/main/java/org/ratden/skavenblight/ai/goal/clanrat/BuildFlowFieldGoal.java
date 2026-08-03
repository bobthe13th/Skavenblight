package org.ratden.skavenblight.ai.goal.clanrat;

import net.minecraft.world.entity.PathfinderMob;
import org.ratden.skavenblight.ai.pathing.SiegeNode;

public class BuildFlowFieldGoal extends AbstractSiegeProjectGoal {

    public BuildFlowFieldGoal(PathfinderMob mob) {
        super(mob);
    }

    @Override
    protected boolean matchesAction(SiegeNode.SiegeAction action) {
        return action == SiegeNode.SiegeAction.BUILD_STAIR ||
                action == SiegeNode.SiegeAction.BUILD_BRIDGE ||
                action == SiegeNode.SiegeAction.BUILD_PILLAR ||
                action == SiegeNode.SiegeAction.BUILD_LANDING ||
                action == SiegeNode.SiegeAction.BUILD_SPIRAL ||
                action == SiegeNode.SiegeAction.BUILD_LADDER;
    }
}
