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
    // The mob's own blockPosition() at the moment the most recent WALK-type hop was requested
    // (see the field's use in tick() below) - tracks whether the mob's INTEGER block position
    // actually advanced since the last 10-tick request cycle, independent of whether vanilla's
    // own Navigation considers the resulting path "in progress" or "done".
    private BlockPos lastHopOrigin = null;

    public FollowFlowFieldGoal(PathfinderMob mob, double speedModifier) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    public void setFlowField(RegionFlowField flowField) {
        this.flowField = flowField;
        // ClanratEntity.customServerAiStep only ever calls this when the region/generation
        // genuinely changed (it early-returns otherwise - see its own short-circuit check), so
        // every call here means lastHopOrigin's "unchanged since last hop" comparison would be
        // comparing against a hop requested under a DIFFERENT field entirely. Left stale, a mob
        // that happened to be at the same blockPosition when a rebuild swapped fields in (e.g.
        // briefly preempted by a higher-priority goal at the moment of reassignment) would trip
        // the repeat-hop nudge on its very first hop under the new field, not a genuine stuck
        // repeat.
        this.lastHopOrigin = null;
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
        // Deliberately NOT resetting lastHopOrigin here: this goal gets briefly preempted and
        // immediately resumed far more often than its own 10-tick cadence would suggest (every
        // time the flow field resolves to a non-WALK action at the mob's current cell, control
        // hands to a construction goal and back) - resetting on every restart would defeat the
        // repeat-hop detection below, which specifically needs to persist across those restarts.
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
                this.lastHopOrigin = null;
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
                this.lastHopOrigin = null;
                this.mob.getNavigation().stop();
                return;
            }

            // --- strict flow field execution below ---
            BlockPos nextInChain = targetNode.pos();
            int maxLookAhead = 3;

            for (int i = 0; i < maxLookAhead; i++) {
                SiegeNode next = this.flowField.getNextSiegeNode((ServerLevel) this.mob.level(), nextInChain);
                // Also stop at a Y change (added during Task 2's investigation): moveOrHop's own
                // ballistic leap - the only mechanism that ever crosses a Y difference here - is a
                // fixed, single-block-climb impulse (see its own javadoc: "a freshly-built
                // BUILD_STAIR step... diagonally up-and-across"). Two already-built, consecutive
                // WALK-classified stair cells chained by this loop (each 1 Y above the last) can
                // otherwise hand moveOrHop a 2+-block-high target in one call - confirmed via
                // GameTest diagnostics to leave the mob stuck permanently re-attempting the same
                // hop (a fixed vy=0.5D impulse can clear one block's height, not two), the exact
                // signature this loop's multi-node lookahead was never intended to produce. Capping
                // the lookahead to SAME-Y WALK nodes keeps its original purpose (skip a pointless
                // extra tick of pure horizontal walking) without ever handing moveOrHop a climb
                // bigger than what its impulse is tuned for.
                if (next == null || next.pos().equals(nextInChain) || next.action() != SiegeNode.SiegeAction.WALK
                        || next.pos().getY() != nextInChain.getY()) {
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

            // Root-cause fix for the "frozen after one short leg" deadlock (see class-level
            // investigation in docs/superpowers/sdd/2026-07-31-clanrat-gap-crossing-pathing-fix-
            // plan/task-1-report.md, Failure Mode 1): currentPos above is always re-derived from
            // this.mob.blockPosition() - an INTEGER, floored value - every 10-tick cycle. Vanilla's
            // own Navigation.moveTo is free to consider a path "done" once the mob's CONTINUOUS
            // position is merely within its own arrival tolerance of the target, which can leave
            // the mob resting a fraction of a block short of the actual block boundary. When that
            // happens, blockPosition() never advances, so this exact code path re-derives and
            // re-requests the IDENTICAL hop next cycle - and because start and goal now resolve to
            // the same or adjacent pathfinding nodes, Navigation.moveTo hands back a degenerate,
            // already-"done"-but-never-"in progress" Path(length=1) that produces no further
            // motion, forever (confirmed via GameTest diagnostics: this reproduced as a stable,
            // permanent deadlock for the rest of a 6000-tick test budget). The existing stuck-
            // detection escape hatch above can't catch this: it's gated on
            // getNavigation().isInProgress(), which is false the instant this deadlock exists (there's
            // nothing left "in progress" about a path with no remaining nodes) - so it structurally
            // can't observe "declined to path at all AND not moving", only "actively pathing AND not
            // moving".
            //
            // Fix: track the blockPosition this goal was standing at the last time it requested a
            // WALK-type hop. If the CURRENT cycle's blockPosition is unchanged from that (i.e. the
            // mob never actually crossed into the block the last hop targeted), don't trust
            // Navigation's own arrival tolerance a second time for the same hop - bypass it entirely
            // with a direct, deliberate impulse toward the target cell (the same technique moveOrHop
            // already uses for its ballistic gap-leap case), which doesn't depend on Navigation ever
            // considering itself "arrived".
            if (this.lastHopOrigin != null && this.lastHopOrigin.equals(currentPos)) {
                nudgeAcross(currentPos, nextInChain);
            } else {
                moveOrHop(currentPos, nextInChain);
            }
            this.lastHopOrigin = currentPos;
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
     * <p>A first version of this drove the hop via a direct one-tick velocity impulse
     * (mob.setDeltaMovement), reasoning that MoveControl-driven AI steering was too imprecise
     * for a deliberate leap. That reasoning turned out backwards: read against vanilla's own
     * LivingEntity.travel()/MoveControl source, a raw velocity assignment fights the engine
     * instead of using it - travel() derives its ACTUAL per-tick horizontal thrust from
     * MoveControl's own continuously-recomputed heading/speed (via moveRelative), not from
     * whatever was set once via setDeltaMovement; confirmed via GameTest diagnostics that a
     * one-shot velocity impulse's horizontal component was effectively lost within a tick or two
     * even with zero blocking geometry in the path (ground-truth-checked), leaving a purely
     * vertical bounce that fell back onto the exact tile it left. MoveControl.setWantedPosition +
     * JumpControl.jump() is the mechanism vanilla's OWN deliberate short hops (a villager
     * stepping over a 1-block gap, generic ledge-climbing) already use, and MoveControl's own
     * JUMPING operation state (see its tick()) persists driving speed/heading across every game
     * tick until the mob lands, independent of this goal's own 10-tick recheck cadence - so a
     * single call here, gated on being grounded, is enough to carry the whole arc.
     */
    private void moveOrHop(BlockPos from, BlockPos to) {
        if (to.getY() > from.getY() && this.mob.onGround() && to.closerThan(from, 2.5)) {
            this.mob.getJumpControl().jump();
            this.mob.getMoveControl().setWantedPosition(to.getX() + 0.5D, to.getY(), to.getZ() + 0.5D, this.speedModifier);
            return;
        }

        this.mob.getNavigation().moveTo(to.getX() + 0.5D, to.getY(), to.getZ() + 0.5D, this.speedModifier);
    }

    /**
     * Corrective, non-negotiable follow-up to a hop {@code moveOrHop} already tried once for this
     * exact {@code from}/{@code to} pair, whose own re-derivation from floored blockPosition() came
     * back unchanged - i.e. Navigation's own arrival tolerance let the mob stop short last time.
     * Re-issues the same MoveControl/JumpControl mechanism as moveOrHop's own climb branch rather
     * than calling Navigation.moveTo again, precisely because Navigation.moveTo is what stopped
     * short in the first place - retrying through the same API with the same tolerance would just
     * reproduce the identical degenerate outcome.
     */
    private void nudgeAcross(BlockPos from, BlockPos to) {
        // Only ever issue a fresh command while grounded - MoveControl's own JUMPING operation
        // (see its tick()) already owns steering for the rest of the arc once started, and
        // calling setWantedPosition again mid-air would just reset its internal operation state
        // without adding anything useful.
        if (!this.mob.onGround()) return;

        if (to.getY() > from.getY() && to.closerThan(from, 2.5)) {
            this.mob.getJumpControl().jump();
            this.mob.getMoveControl().setWantedPosition(to.getX() + 0.5D, to.getY(), to.getZ() + 0.5D, this.speedModifier);
            return;
        }

        this.mob.getMoveControl().setWantedPosition(to.getX() + 0.5D, to.getY(), to.getZ() + 0.5D, this.speedModifier);
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