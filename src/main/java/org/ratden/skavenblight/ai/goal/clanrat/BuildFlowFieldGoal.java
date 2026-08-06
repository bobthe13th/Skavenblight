package org.ratden.skavenblight.ai.goal.clanrat;

import net.minecraft.world.entity.PathfinderMob;
import org.ratden.skavenblight.ai.pathing.PathAction;

public class BuildFlowFieldGoal extends AbstractSiegeProjectGoal {

    public BuildFlowFieldGoal(PathfinderMob mob) {
        super(mob);
    }

    // The only project-execution goal now that Task 16 deleted every goal that used to split off
    // a subset of construction actions - matches all four.
    @Override
    protected boolean matchesAction(PathAction action) {
        return action == PathAction.TUNNEL ||
                action == PathAction.BRIDGE ||
                action == PathAction.CARVED_STAIR ||
                action == PathAction.AIR_STAIR;
    }
}
