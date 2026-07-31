package org.ratden.skavenblight.ai.goal.clanrat;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import org.ratden.skavenblight.ai.goal.SiegeGoal;
import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;
import org.ratden.skavenblight.ai.pathing.SiegeNode;
import org.slf4j.Logger;

import java.util.EnumSet;

public class FollowFlowFieldGoal extends Goal implements SiegeGoal {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final PathfinderMob mob;
    private final double speedModifier;
    private RegionFlowField flowField;

    private int pathingUpdateTimer = 0;
    private Vec3 lastPosition = null;
    private int escapeHatchTicks = 0;
    private BlockPos occupiedLane = null;

    public FollowFlowFieldGoal(PathfinderMob mob, double speedModifier) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    public void setFlowField(RegionFlowField flowField) {
        this.flowField = flowField;
    }

    @Override
    public boolean canUse() {
        if (this.flowField == null || this.mob.getTarget() != null) return false;

        // --- CHANGED: Always return true if we have a flow field.
        // We will decide whether to use Flow Field or Vanilla pathing in the tick() method.
        return true;
    }

    @Override
    public void start() {
        this.lastPosition = this.mob.position();
        this.escapeHatchTicks = 0;
        this.pathingUpdateTimer = 0;
    }

    @Override
    public void tick() {
        if (this.escapeHatchTicks > 0) {
            this.escapeHatchTicks--;
            if (this.mob.getNavigation().isDone()) {
                this.escapeHatchTicks = 0;
            }
            return;
        }

        if (--this.pathingUpdateTimer <= 0) {
            this.pathingUpdateTimer = 10;
            Vec3 currentPosition = this.mob.position();

            if (this.lastPosition != null && this.mob.getNavigation().isInProgress()) {
                double distanceMovedSqr = currentPosition.distanceToSqr(this.lastPosition);
                if (distanceMovedSqr < 0.04) {
                    BlockPos escapePos = findEscapePos(this.mob.blockPosition());
                    // Naturally rate-limited to at most once per ~70 ticks per mob (10-tick
                    // poll + 60-tick escape-hatch cooldown below), so this won't spam even
                    // under an 80-mob load test - INFO rather than DEBUG so it actually lands in
                    // latest.log instead of being buried in debug.log's much higher-volume
                    // output (see SiegeProjectManager's "Line aborted" line - 98% of a whole
                    // session's debug.log in testing). Previously silent entirely - "rats seem
                    // stuck" during testing had no log trail showing where or how often this
                    // actually fires.
                    if (escapePos != null) {
                        LOGGER.info("[Skavenblight] {} stuck at {} (hasn't moved in ~{} ticks) - escape-hatch heading to {}",
                                this.mob.getClass().getSimpleName(), this.mob.blockPosition().toShortString(), this.pathingUpdateTimer, escapePos.toShortString());

                        moveOrHop(this.mob.blockPosition(), escapePos);
                        this.escapeHatchTicks = 60;
                        this.lastPosition = currentPosition;
                        return;
                    } else {
                        LOGGER.info("[Skavenblight] {} stuck at {} (hasn't moved in ~{} ticks) - no escape-hatch target found (no flow-field chain ahead)",
                                this.mob.getClass().getSimpleName(), this.mob.blockPosition().toShortString(), this.pathingUpdateTimer);
                    }
                }
            }
            this.lastPosition = currentPosition;

            BlockPos currentPos = this.mob.blockPosition();
            SiegeNode targetNode = this.flowField.getNextSiegeNode((ServerLevel) this.mob.level(), currentPos);

            // --- Wilderness fallback ---
            // No instruction at our feet. Rather than beeline straight at the raw nexus
            // coordinate (which can be across the exact terrain - a pit, a cliff - that
            // necessitated macro projects in the first place), head toward the nearest
            // block the flow field HAS mapped, if any; only fall back to the raw nexus
            // position when nothing is mapped at all.
            //
            // Hand vanilla navigation the actual far target, not a single manually-computed
            // step toward it - moveTo-ing one adjacent block at a time (as getDynamicWildernessNode
            // does for the debug visualizer's per-tile arrows) gives vanilla's pathfinder
            // nothing to route around, so a rat stalls at the very first obstacle needing a
            // multi-block detour instead of actually pathing in.
            if (targetNode == null) {
                BlockPos heading = this.flowField.getWildernessHeadingTarget(currentPos);
                this.mob.getNavigation().moveTo(
                        heading.getX() + 0.5D,
                        heading.getY(),
                        heading.getZ() + 0.5D,
                        this.speedModifier
                );
                return;
            }

            // If we DO have a node, but it isn't WALK, stop moving so the Builder/Miner goals can take over.
            if (targetNode.action() != SiegeNode.SiegeAction.WALK) {
                this.mob.getNavigation().stop();
                return;
            }

            // --- strict flow field execution below ---
            BlockPos nextInChain = targetNode.pos();
            int maxLookAhead = 3;

            for (int i = 0; i < maxLookAhead; i++) {
                SiegeNode next = this.flowField.getNextSiegeNode((ServerLevel) this.mob.level(), nextInChain);
                if (next == null || next.pos().equals(nextInChain) || next.action() != SiegeNode.SiegeAction.WALK) {
                    break;
                }
                nextInChain = next.pos();
            }

            // Lane-occupancy tracking (see RegionFlowField.tryOccupyLane/isLaneCrowded): the
            // mob occupies the tile it's actually about to walk toward, not any intermediate
            // node inspected by the look-ahead loop above. Release the previously-held lane
            // first if it's changing, then try to claim the new one - if it's already
            // saturated, tryOccupyLane just returns false with no side effects and
            // occupiedLane stays null until a future tick's retry succeeds.
            if (this.occupiedLane != null && !this.occupiedLane.equals(nextInChain)) {
                this.flowField.releaseLane(this.occupiedLane, this.mob);
                this.occupiedLane = null;
            }
            if (this.flowField.tryOccupyLane(nextInChain, this.mob)) {
                this.occupiedLane = nextInChain;
            }

            moveOrHop(currentPos, nextInChain);
        }
    }

    @Override
    public void stop() {
        if (this.occupiedLane != null && this.flowField != null) {
            this.flowField.releaseLane(this.occupiedLane, this.mob);
        }
        this.occupiedLane = null;
    }

    /**
     * A freshly-built BUILD_STAIR step landed on directly out of a macro project (see the
     * class javadoc) sits diagonally up-and-across from the mob's own tile, approached
     * across the same gap the staircase exists to cross - the tile at the mob's own Y in
     * that direction has no floor (that's the gap), so WalkNodeEvaluator never generates an
     * ascend node there at all: Navigation.moveTo silently returns a dead (0/1-node,
     * not-in-progress) path with no error, no log, and no retry. Confirmed via GameTest
     * diagnostics (StaircaseSiegeGroupGameTests): the target cell, its headroom, and the
     * placed stair's BlockState were all correct; only Navigation.moveTo's own pathfind
     * toward it ever failed. Bypass A* for this one short hop and drive it directly the way
     * vanilla's own ad hoc jump behaviors do.
     *
     * <p>Shared between the ordinary chain-following call site above and the escape-hatch
     * above it - the escape-hatch used to call Navigation.moveTo directly, which hits this
     * exact same wall for the identical reason (its own target can just as easily be a
     * just-built stair one hop back across the same gap) with no error and no retry, ever.
     *
     * <p>A first version of this drove the hop via JumpControl.jump() + MoveControl -
     * generic AI steering meant for opportunistic hops while already walking, not a
     * deliberate, precisely-landing leap. Confirmed via repeated GameTest runs to land
     * imprecisely often enough that a chain of ~10+ required hops (one staircase) rarely
     * completed: short landings look "stuck" (falls right back onto the tile it left),
     * off-target landings hand the next tick's flow-field lookup an unexpected cell, and
     * some runs ended mid-arc at timeout. A direct one-tick velocity impulse - the same
     * technique vanilla uses for its own deliberate leaps (Rabbit's hop, Goat's ram jump) -
     * fully determines the arc up front instead of relying on continued AI steering while
     * airborne, so its outcome doesn't depend on exactly when a later tick happens to sample
     * the mob's position.
     */
    private void moveOrHop(BlockPos from, BlockPos to) {
        if (to.getY() > from.getY() && this.mob.onGround() && to.closerThan(from, 2.5)) {
            double flightTicks = 5.0D;
            double vx = (to.getX() + 0.5D - this.mob.getX()) / flightTicks;
            double vz = (to.getZ() + 0.5D - this.mob.getZ()) / flightTicks;
            this.mob.setDeltaMovement(vx, 0.5D, vz);
            this.mob.hasImpulse = true;
            return;
        }

        this.mob.getNavigation().moveTo(to.getX() + 0.5D, to.getY(), to.getZ() + 0.5D, this.speedModifier);
    }

    private BlockPos findEscapePos(BlockPos startPos) {
        BlockPos current = startPos;
        for (int i = 0; i < 6; i++) {
            SiegeNode next = this.flowField.getNextSiegeNode((ServerLevel) this.mob.level(), current);
            if (next == null || next.pos().equals(current)) break;
            current = next.pos();
        }
        return current.equals(startPos) ? null : current;
    }
}