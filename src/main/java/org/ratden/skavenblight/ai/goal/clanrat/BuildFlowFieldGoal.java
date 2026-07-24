package org.ratden.skavenblight.ai.goal.clanrat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import org.ratden.skavenblight.ai.pathing.SiegeInteractionHandler;
import org.ratden.skavenblight.ai.pathing.SiegeNode;

import java.util.Optional;

public class BuildFlowFieldGoal extends AbstractSiegeConstructionGoal {

    private int recalculateCooldown = 0;

    public BuildFlowFieldGoal(PathfinderMob mob) {
        super(mob);
    }

    private static boolean isBuildAction(SiegeNode.SiegeAction action) {
        return action == SiegeNode.SiegeAction.BUILD_STAIR ||
                action == SiegeNode.SiegeAction.BUILD_BRIDGE ||
                action == SiegeNode.SiegeAction.BUILD_PILLAR ||
                action == SiegeNode.SiegeAction.BUILD_LANDING ||
                action == SiegeNode.SiegeAction.BUILD_SPIRAL ||
                action == SiegeNode.SiegeAction.BUILD_LADDER;
    }

    @Override
    protected Optional<Target> findTarget() {
        if (!(this.mob.level() instanceof ServerLevel serverLevel)) return Optional.empty();
        BlockPos currentPos = this.mob.blockPosition();

        return findEffectiveNode(BuildFlowFieldGoal::isBuildAction)
                .filter(node -> isBuildAction(node.action()))
                .filter(node -> serverLevel.getBlockState(node.pos()).canBeReplaced() && currentPos.closerThan(node.pos(), 2.5D))
                .map(node -> new Target(node.pos(), node.action(), computeApproachFacing(currentPos, node.pos())));
    }

    private Direction computeApproachFacing(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        } else if (dz != 0) {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return this.mob.getDirection();
    }

    @Override
    protected int getActionDurationTicks() { return 15; }

    @Override
    protected long getPostActionCooldownTicks() { return 10; }

    @Override
    protected int getMaxStalledTicks() { return 60; }

    @Override
    protected boolean isTargetStillValid(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).canBeReplaced();
    }

    @Override
    protected void execute(ServerLevel level, BlockPos pos, SiegeNode.SiegeAction action, Direction facing) {
        SiegeInteractionHandler.constructSiegeBlock(level, pos, facing, action, this.flowField, this.mob);
    }

    @Override
    protected void onTick() {
        if (this.recalculateCooldown > 0) this.recalculateCooldown--;
    }

    @Override
    protected void onChainComplete(ServerLevel level, BlockPos completedPos) {
        SiegeNode nextNode = this.flowField.getNextSiegeNode(level, completedPos);
        boolean isEndOfMacroProject = (nextNode == null || !isBuildAction(nextNode.action()));

        if (isEndOfMacroProject && this.recalculateCooldown == 0 && !this.flowField.isCalculating()) {
            this.flowField.forceRecalculation();
            this.recalculateCooldown = 100;
        }
    }
}
