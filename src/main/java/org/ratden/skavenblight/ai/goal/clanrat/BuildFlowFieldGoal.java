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
                .filter(node -> serverLevel.getBlockState(node.pos()).canBeReplaced() && currentPos.closerThan(node.pos(), MAX_TARGET_CLAIM_DISTANCE))
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
        return level.getBlockState(pos).canBeReplaced()
                && (!this.supportSolidAtClaim || level.getBlockState(pos.below()).blocksMotion());
    }

    @Override
    protected void execute(ServerLevel level, BlockPos pos, SiegeNode.SiegeAction action, Direction facing) {
        SiegeInteractionHandler.constructSiegeBlock(level, pos, facing, action, this.flowField, this.mob, this.supportSolidAtClaim);
    }

    @Override
    protected void onTick() {
        if (this.recalculateCooldown > 0) this.recalculateCooldown--;
    }

    @Override
    protected void onChainComplete(ServerLevel level, BlockPos completedPos) {
        // this.flowField can go null at any time via ClanratEntity.assignFlowField's periodic
        // region-membership re-check (every 40 ticks), decoupled from whether this goal is
        // mid-chain - see AbstractSiegeConstructionGoal's own default onChainComplete, which
        // already guards against exactly this. This override never got that guard and crashed
        // the server with an NPE on this exact race (confirmed via a real crash report: a
        // successful build's onChainComplete ran the same tick the field was reassigned to null).
        if (this.flowField == null) return;

        SiegeNode nextNode = this.flowField.getNextSiegeNode(level, completedPos);
        boolean isEndOfMacroProject = (nextNode == null || !isBuildAction(nextNode.action()));

        if (isEndOfMacroProject && this.recalculateCooldown == 0 && !this.flowField.isCalculating()) {
            this.flowField.forceRecalculation(completedPos);
            this.recalculateCooldown = 100;
        }
    }
}
