package org.ratden.skavenblight.ai.goal.clanrat;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import org.ratden.skavenblight.ai.goal.SiegeGoal;
import org.ratden.skavenblight.ai.pathing.SiegeInteractionHandler;
import org.ratden.skavenblight.ai.pathing.SiegeNode;
import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;
import org.slf4j.Logger;

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

    private static final Logger LOGGER = LogUtils.getLogger();

    protected final PathfinderMob mob;
    protected RegionFlowField flowField;

    private long nextAllowedActionTime = 0;
    private int actionTicks = 0;
    private int stalledTicks = 0;

    protected BlockPos targetPos;
    protected Direction facing;
    protected SiegeNode.SiegeAction targetAction;
    protected boolean supportSolidAtClaim;

    /**
     * Every findTarget() override in this hierarchy (BuildFlowFieldGoal, WidenStairsGoal,
     * SmartBreachGoal) requires the mob to be within this distance of a target before claiming
     * it. Reused in canContinueToUse() below: this goal zeroes the mob's own horizontal velocity
     * every tick (see tick()), so it cannot close a gap by itself - if crowd collision or a push
     * has carried it further than this from its own claimed target, waiting for it to wander
     * back on its own isn't a real possibility. Confirmed via a diagnostic dump: a claimant
     * stalled for 16+ retry cycles while sitting 3.7 blocks from its own target, well past this
     * threshold, with no way to ever close that gap under its own power.
     */
    public static final double MAX_TARGET_CLAIM_DISTANCE = 2.5D;

    /**
     * findEffectiveNode's own lookahead peek distance - deliberately separate from
     * MAX_TARGET_CLAIM_DISTANCE above. The lookahead exists only to let a mob standing one
     * ordinary WALK step short of real work skip the pointless extra tick of walking there
     * first - i.e. it should never see further than a single legitimate adjacent
     * interaction (orthogonal 1.0, diagonal ~1.41) would ever reach on its own. Reusing
     * MAX_TARGET_CLAIM_DISTANCE (2.5, sized for tolerating crowd-shove during an ALREADY
     * claimed build) here let the lookahead claim a target a full two flow-field hops away -
     * confirmed via StaircaseSiegeGroupGameTests + a live debug-item observation: a rat
     * still two cells back from a gap's ledge would build the far stair immediately, before
     * ever walking onto the ledge itself, leaving the newly-built stair unreachable from
     * where the rat actually stood. The lookahead was never meant to affect what gets
     * targeted, only to smooth movement across a surface - this bounds it back to that.
     */
    private static final double LOOKAHEAD_SNAP_DISTANCE = 1.5D;

    protected AbstractSiegeConstructionGoal(PathfinderMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public void setFlowField(RegionFlowField flowField) {
        this.flowField = flowField;
    }

    /**
     * Coordination-only: this goal's own findTarget() result, but ONLY if a valid target exists
     * AND it's currently claimed by a different, living mob - i.e. "I have real work to do here,
     * but someone else already has it." Empty in every other case (no target at all, or an
     * unclaimed target this goal would just claim normally on its own next canUse() check) -
     * callers only care about the specific "blocked by someone else" case. Pure read, same as
     * findTarget()/canUse() - safe to call from outside this goal's own tick cycle (see
     * AwaitFormationGoal, which calls this on sibling goals it doesn't own).
     */
    public Optional<BlockPos> peekClaimedTarget() {
        if (this.flowField == null) return Optional.empty();
        return findTarget()
                .map(Target::pos)
                .filter(pos -> this.flowField.isTargetClaimed(pos));
    }

    /**
     * Diagnostic-only: this goal's own progress on its currently claimed target, or a fixed
     * string if it doesn't hold one right now. Exists to answer "the claimant is alive and
     * plausibly close enough - so why hasn't it finished?" - actionTicks/stalledTicks/
     * nextAllowedActionTime are otherwise fully private to the goal instance, invisible to any
     * external diagnostic (including PathingDebugFileWriter's claimant lookup).
     */
    public String describeState() {
        if (this.targetPos == null) return "<no claimed target>";
        long now = this.mob.level().getGameTime();
        return String.format(
                "targetPos=%s action=%s actionTicks=%d/%d stalledTicks=%d/%s flowFieldNull=%b "
                        + "nextAllowedActionTime=%d(now=%d, %s) supportSolidAtClaim=%b",
                this.targetPos.toShortString(), this.targetAction, this.actionTicks, getActionDurationTicks(),
                this.stalledTicks, getMaxStalledTicks() > 0 ? String.valueOf(getMaxStalledTicks()) : "unbounded",
                this.flowField == null, this.nextAllowedActionTime, now,
                now < this.nextAllowedActionTime ? "IN COOLDOWN" : "clear", this.supportSolidAtClaim);
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

    private long nextRecalculationTime = 0;

    /**
     * Default: mark the affected region dirty after every completed action, debounced the same
     * way BuildFlowFieldGoal already debounces its own explicit call - without this, subclasses
     * that don't override this hook (WidenStairsGoal, SmartBreachGoal) never tell the region
     * system about their own construction (confirmed bug - see the "a mess lol" commit history's
     * fix for BuildFlowFieldGoal, which never generalized to these siblings).
     *
     * <p>Null-checked for the same reason {@code DeployClimbableGoal}'s two analogous
     * {@code forceRecalculation} calls are: {@code ClanratEntity.assignFlowField} can hand this
     * goal a {@code null} flowField at any time (via {@code customServerAiStep}'s periodic
     * region-membership re-check, every 40 ticks, decoupled from whether a goal is mid-chain),
     * including while {@code tick()} is actively executing a chain (a goal only stops on its own
     * on the NEXT {@code canContinueToUse()} check, which does not itself check {@code flowField}
     * - see this class's own {@code tick()}). This is a narrow, pre-existing race window shared by
     * several other unguarded {@code this.flowField} calls in both this class and
     * {@code DeployClimbableGoal} (e.g. {@code releaseTarget}/{@code tryClaimTarget}); fixing all
     * of them is out of scope here - this guard only covers the specific call this hook itself
     * added.
     */
    protected void onChainComplete(ServerLevel level, BlockPos completedPos) {
        if (this.flowField != null && level.getGameTime() >= this.nextRecalculationTime && !this.flowField.isCalculating()) {
            this.flowField.forceRecalculation(completedPos);
            this.nextRecalculationTime = level.getGameTime() + 100;
        }
    }

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
            if (nextNode != null && lookAheadMatch.test(nextNode.action())
                    && !nextNode.pos().equals(currentPos)
                    && currentPos.closerThan(nextNode.pos(), LOOKAHEAD_SNAP_DISTANCE)) {
                return Optional.of(nextNode);
            }
        }

        // A construction target that IS the mob's own current position can never be executed
        // safely - a mob can't place a block into the exact space its body occupies without
        // stepping aside first, which nothing here does. Defense in depth: the one known
        // source of this (SiegeProjectManager's old self-referential anchor instruction) has
        // been removed, but this guard means any future/unknown source degrades to
        // "no instruction" (safe - the mob just waits) instead of silently entombing it.
        // Confirmed via SiegeActivityLog in testing: exact mob-pos == target-pos matches on
        // BUILD_STAIR executions.
        if (node != null && node.action() != SiegeNode.SiegeAction.WALK && node.pos().equals(currentPos)) {
            return Optional.empty();
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
        // A mob mid-leap (FollowFlowFieldGoal's own MoveControl/JumpControl-driven climb across
        // a gap - see its own moveOrHop/nudgeAcross) is still within MAX_TARGET_CLAIM_DISTANCE of
        // its NEXT target well before it lands, since that distance (2.5) is sized for
        // post-claim crowd tolerance, not for "is this mob currently mid-air and in the middle of
        // a completely different goal's own in-progress motion." Without this guard, a
        // construction goal outranking FollowFlowFieldGoal in priority (see
        // ClanratEntity.registerGoals) can preempt an in-progress leap the instant its own
        // distance check is satisfied - confirmed via GameTest diagnostics: BuildFlowFieldGoal
        // claiming and starting its target while the mob was still airborne, mid-climb, at a
        // transient off-chain position. tick() then unconditionally zeroes the mob's own
        // horizontal velocity every tick (see below), killing the leap's own momentum before it
        // ever crosses the gap it was aimed at - the mob falls back roughly where it started, and
        // the whole cycle repeats.
        //
        // A first version of this guard checked bare onGround() - wrong, and confirmed so by a
        // full-suite regression: onGround() defaults false on any entity that hasn't yet had a
        // real physics tick run against it, which describes every synthetic unit-style GameTest
        // in this codebase (SiegeConstructionActionsGameTests, PathingGoalRecalculationGameTests)
        // that positions a mob via setPos() - a raw teleport, not a tick - and calls canUse()
        // immediately after. Bare onGround() blocked every one of those unconditionally, not just
        // the genuine mid-climb case. Checking actual upward velocity instead is the correct
        // discriminator: a real in-progress climb has this.mob.getDeltaMovement().y well above
        // zero (JumpControl.jump()'s own impulse, sustained by MoveControl's JUMPING state until
        // landing - see FollowFlowFieldGoal.tryClimb()'s own doc), while every synthetic test
        // mob's delta movement is Vec3.ZERO (setPos() never touches velocity), so this correctly
        // never fires for them regardless of their (meaningless, never-computed) onGround() value.
        if (!this.mob.onGround() && this.mob.getDeltaMovement().y > 1.0E-2) {
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
                && this.mob.blockPosition().closerThan(this.targetPos, MAX_TARGET_CLAIM_DISTANCE)
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
        if (this.flowField != null) {
            this.flowField.tryClaimTarget(this.targetPos, this.mob);
        }

        // Snapshot of whether solid ground already existed below a climb-dependent target at the
        // moment it was claimed - see SiegeAction#isClimbDependent's javadoc for why "no support
        // yet" must NOT by itself be treated as broken (a macro project's next unbuilt chain step
        // always starts this way). Only a target that HAD support at claim time and lost it before
        // execution is the actual race worth guarding against.
        this.supportSolidAtClaim = this.targetAction.isClimbDependent()
                && this.mob.level().getBlockState(this.targetPos.below()).blocksMotion();
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
                // Logged so a recurring give-up at the same spot (a permanently occupied
                // chokepoint, not just one unlucky tick) is visible in the log across a
                // session rather than only inferable from a dump's momentary snapshot.
                LOGGER.info("[Skavenblight] {} giving up on {} at {} after {} stalled ticks - space never cleared",
                        this.getClass().getSimpleName(), this.targetAction, this.targetPos.toShortString(), this.stalledTicks);

                this.nextAllowedActionTime = this.mob.level().getGameTime() + getGiveUpCooldownTicks();
                // Release here, not just in stop() - this nulls targetPos directly, so by the
                // time stop() naturally runs (canContinueToUse() sees targetPos == null on the
                // next tick) there'd be nothing left for it to release.
                if (this.flowField != null) {
                    this.flowField.releaseTarget(this.targetPos);
                }
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
        if (this.flowField != null) {
            this.flowField.releaseTarget(this.targetPos);
        }
        this.targetPos = null;
        this.facing = null;
        this.targetAction = null;
        this.actionTicks = 0;
        this.stalledTicks = 0;
    }
}
