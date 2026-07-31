package org.ratden.skavenblight.ai.goal.clanrat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import org.ratden.skavenblight.ai.pathing.SiegeNode;

import java.util.Optional;

public class SmartBreachGoal extends AbstractSiegeConstructionGoal {

    public SmartBreachGoal(PathfinderMob mob) {
        super(mob);
    }

    private static boolean isBreachAction(SiegeNode.SiegeAction action) {
        return action == SiegeNode.SiegeAction.MINE;
    }

    @Override
    protected Optional<Target> findTarget() {
        if (!(this.mob.level() instanceof ServerLevel serverLevel)) return Optional.empty();
        BlockPos currentPos = this.mob.blockPosition();

        return findEffectiveNode(SmartBreachGoal::isBreachAction)
                .filter(node -> isBreachAction(node.action()))
                .filter(node -> !serverLevel.getBlockState(node.pos()).isAir() && currentPos.closerThan(node.pos(), MAX_TARGET_CLAIM_DISTANCE))
                .map(node -> new Target(node.pos(), node.action()));
    }

    @Override
    protected int getActionDurationTicks() { return 20; }

    @Override
    protected long getPostActionCooldownTicks() { return 5; }

    @Override
    protected boolean requiresClearSpace() { return false; }

    @Override
    protected boolean isTargetStillValid(ServerLevel level, BlockPos pos) {
        return !level.getBlockState(pos).isAir();
    }

    @Override
    protected void execute(ServerLevel level, BlockPos pos, SiegeNode.SiegeAction action, Direction facing) {
        // Preserved from the original: breaks via the mob itself (not SiegeInteractionHandler.executeBreach)
        // so drops/XP/advancements attribute to the rat, not an anonymous world edit.
        level.destroyBlock(pos, true, this.mob);
    }
}
