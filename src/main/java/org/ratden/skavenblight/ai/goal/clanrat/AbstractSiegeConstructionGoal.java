package org.ratden.skavenblight.ai.goal.clanrat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import org.ratden.skavenblight.ai.goal.SiegeGoal;
import org.ratden.skavenblight.ai.pathing.SiegeInteractionHandler;
import org.ratden.skavenblight.ai.pathing.SiegeNode;
import org.ratden.skavenblight.ai.pathing.StandardFlowField;

import java.util.EnumSet;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Shared skeleton for the "stand next to a block, animate for N ticks, then act on it"
 * goals - approach, animate, wait-for-clear-space-if-needed, execute, cool down. Concrete
 * goals only need to say what block to act on and what the action does; the surrounding
 * plumbing (stall handling, retries, cooldowns) lives here once instead of being
 * copy-pasted per goal.
 *
 * BuildFlowFieldGoal, WidenStairsGoal, and SmartBreachGoal all fit this shape. The
 * multi-state goals (SpiralSapperGoal, WarpSapperGoal, DeployClimbableGoal) have genuinely
 * different control flow (chained sub-steps, an approach-then-flee sequence) and are NOT
 * built on this base - see SiegeActionAnimator for the smaller bit of code they share instead.
 */
public abstract class AbstractSiegeConstructionGoal extends Goal implements SiegeGoal {

    protected final PathfinderMob mob;
    protected StandardFlowField flowField;

    private long nextAllowedActionTime = 0;
    private int actionTicks = 0;
    private int stalledTicks = 0;

    protected BlockPos targetPos;
    protected Direction facing;
    private SiegeNode.SiegeAction targetAction;

    protected AbstractSiegeConstructionGoal(PathfinderMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public void setFlowField(StandardFlowField flowField) {
        this.flowField = flowField;
    }

    // =================================================================================
    // HOOKS - concrete goals implement these
    // =================================================================================

    /** Looks for something in range to act on. Empty means canUse() fails this tick. */
    protected abstract Optional<Target> findTarget();

    /** How many ticks the animation runs before the action fires. */
    protected abstract int getActionDurationTicks();

    /** Applies the actual world change once the animation completes (and the space is clear, if required). */
    protected abstract void execute(ServerLevel level, BlockPos pos, SiegeNode.SiegeAction action, Direction facing);

    /** Cooldown (in ticks) before this goal is willing to fire again after a successful action. */
    protected abstract long getPostActionCooldownTicks();

    /** Whether targetPos still needs this goal's attention (build goals: still replaceable; mining: not yet broken). */
    protected abstract boolean isTargetStillValid(ServerLevel level, BlockPos pos);

    /** True for goals that place a block (need the space entity-free first); false for goals that remove one. */
    protected boolean requiresClearSpace() { return true; }

    /** 0 = retry indefinitely without giving up. Override for goals that should abandon a stalled target. */
    protected int getMaxStalledTicks() { return 0; }

    /** Cooldown applied if getMaxStalledTicks() > 0 and that threshold is hit. */
    protected long getGiveUpCooldownTicks() { return 100; }

    /** Extra per-tick bookkeeping a subclass needs regardless of animation phase (e.g. cooldown timers). */
    protected void onTick() {}

    /** Called right after a successful execute(); override to react to finishing a chain of actions. */
    protected void onChainComplete(ServerLevel level, BlockPos completedPos) {}

    public record Target(BlockPos pos, SiegeNode.SiegeAction action, Direction facing) {
        public Target(BlockPos pos, SiegeNode.SiegeAction action) {
            this(pos, action, null);
        }
    }

    /**
     * Shared "look at the node in front of us, and if it's a WALK step immediately before
     * a node this goal cares about, snap to that node instead" lookup used by build/breach
     * goals that key off the flow field rather than scanning the world directly.
     */
    protected final Optional<SiegeNode> findEffectiveNode(Predicate<SiegeNode.SiegeAction> lookAheadMatch) {
        if (this.flowField == null || !(this.mob.level() instanceof ServerLevel serverLevel)) return Optional.empty();
        BlockPos currentPos = this.mob.blockPosition();

        SiegeNode node = this.flowField.getNextSiegeNode(serverLevel, currentPos);

        if (node == null) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                node = this.flowField.getNextSiegeNode(serverLevel, currentPos.relative(dir));
                if (node != null) break;
            }
        }

        if (node != null && node.action() == SiegeNode.SiegeAction.WALK) {
            SiegeNode nextNode = this.flowField.getNextSiegeNode(serverLevel, node.pos());
            if (nextNode != null && lookAheadMatch.test(nextNode.action()) && currentPos.closerThan(nextNode.pos(), 2.5D)) {
                return Optional.of(nextNode);
            }
        }
        return Optional.ofNullable(node);
    }

    // =================================================================================
    // SHARED GOAL PLUMBING
    // =================================================================================

    @Override
    public boolean canUse() {
        if (this.flowField == null || this.mob.level().getGameTime() < this.nextAllowedActionTime) {
            return false;
        }
        // Skip a target another mob's construction goal already claimed - without this, every
        // mob near a bottleneck independently arrives at the same "next" instruction and all
        // converge on the identical block simultaneously instead of spreading across whatever
        // frontier work is actually available.
        return findTarget().filter(target -> !this.flowField.isTargetClaimed(target.pos())).isPresent();
    }

    @Override
    public boolean canContinueToUse() {
        return this.mob.isAlive()
                && this.actionTicks <= getActionDurationTicks()
                && this.targetPos != null
                && this.mob.level() instanceof ServerLevel serverLevel
                && isTargetStillValid(serverLevel, this.targetPos);
    }

    @Override
    public void start() {
        this.actionTicks = 0;
        this.stalledTicks = 0;

        Target target = findTarget().orElse(null);
        if (target == null) return;

        this.targetPos = target.pos();
        this.targetAction = target.action();
        this.facing = target.facing() != null ? target.facing() : this.mob.getDirection();
        this.flowField.tryClaimTarget(this.targetPos);
    }

    @Override
    public void tick() {
        if (this.targetPos == null || !(this.mob.level() instanceof ServerLevel serverLevel)) return;

        onTick();

        this.mob.setDeltaMovement(0, this.mob.getDeltaMovement().y, 0);
        this.mob.getLookControl().setLookAt(
                this.targetPos.getX() + 0.5D,
                this.targetPos.getY() + 0.5D,
                this.targetPos.getZ() + 0.5D
        );

        if (this.actionTicks % 5 == 0) this.mob.swing(InteractionHand.MAIN_HAND);
        this.actionTicks++;

        if (this.actionTicks < getActionDurationTicks()) return;

        if (requiresClearSpace() && !SiegeInteractionHandler.isSpaceClear(serverLevel, this.targetPos, this.mob)) {
            SiegeInteractionHandler.pushOccupantsAway(serverLevel, this.targetPos, this.mob);
            this.stalledTicks++;

            if (getMaxStalledTicks() > 0 && this.stalledTicks >= getMaxStalledTicks()) {
                this.nextAllowedActionTime = this.mob.level().getGameTime() + getGiveUpCooldownTicks();
                // Release here, not just in stop() - this nulls targetPos directly, so by the
                // time stop() naturally runs (canContinueToUse() sees targetPos == null on the
                // next tick) there'd be nothing left for it to release.
                this.flowField.releaseTarget(this.targetPos);
                this.targetPos = null;
                return;
            }
            this.actionTicks = getActionDurationTicks() - 5;
            return;
        }

        BlockPos completedPos = this.targetPos;
        execute(serverLevel, this.targetPos, this.targetAction, this.facing);
        this.nextAllowedActionTime = this.mob.level().getGameTime() + getPostActionCooldownTicks();
        onChainComplete(serverLevel, completedPos);
    }

    @Override
    public void stop() {
        this.flowField.releaseTarget(this.targetPos);
        this.targetPos = null;
        this.facing = null;
        this.targetAction = null;
        this.actionTicks = 0;
        this.stalledTicks = 0;
    }
}
