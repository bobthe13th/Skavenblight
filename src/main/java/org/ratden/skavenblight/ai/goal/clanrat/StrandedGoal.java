package org.ratden.skavenblight.ai.goal.clanrat;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.ratden.skavenblight.ai.pathing.LiveTerrainAccess;
import org.ratden.skavenblight.ai.pathing.SiegeInteractionHandler;
import org.ratden.skavenblight.ai.pathing.SiegeLineTracer;
import org.ratden.skavenblight.ai.pathing.SiegeNode;
import org.ratden.skavenblight.ai.pathing.TerrainEvaluator;
import org.ratden.skavenblight.entity.custom.ClanratEntity;
import org.slf4j.Logger;

import java.util.EnumSet;

/**
 * Behavior for a mob whose current region has no route to the target (RegionRouteTree has no
 * entry for it - not yet scanned, or genuinely sealed off). Walks toward the nearest region
 * with a known route (ClanratEntity.customServerAiStep sets strandedHeading to
 * TerritoryRegionMap.getWildernessHeadingTarget, reused here as "nearest region's stand-in
 * position"); if stalled at the boundary, attempts a local, reactive breach using the same
 * line-tracing cost math the region graph's own connector discovery uses (SiegeLineTracer),
 * mirroring the old reactive hitObstacle-triggered behavior as a fallback rather than the
 * primary path.
 */
public class StrandedGoal extends Goal {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Mirrors FollowFlowFieldGoal's own stuck-detection cadence/threshold: check every 10 ticks
    // rather than every tick, so an ordinary walking gait's per-tick jitter can't itself read as
    // progress. 6 checks * 10 ticks = the same 60-tick budget the old heading-based version used.
    private static final int STALL_CHECK_INTERVAL = 10;
    private static final int STALL_CHECKS_BEFORE_BREACH_ATTEMPT = 6;
    private static final double STALL_DISTANCE_SQR_THRESHOLD = 0.04D;

    private final ClanratEntity mob;
    private final TerrainEvaluator terrainEvaluator = new TerrainEvaluator();
    private final SiegeLineTracer lineTracer = new SiegeLineTracer(terrainEvaluator);

    private Vec3 lastPosition = null;
    private int checkTimer = 0;
    private int stalledChecks = 0;

    public StrandedGoal(ClanratEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return this.mob.isStranded();
    }

    @Override
    public boolean canContinueToUse() {
        return this.mob.isStranded();
    }

    @Override
    public void start() {
        this.lastPosition = this.mob.position();
        this.checkTimer = 0;
        this.stalledChecks = 0;
    }

    @Override
    public void tick() {
        BlockPos heading = this.mob.getStrandedHeading();
        if (heading == null) return;

        if (--this.checkTimer > 0) return;
        this.checkTimer = STALL_CHECK_INTERVAL;

        // Re-issuing moveTo unconditionally on every tick (the previous behavior) recomputes a
        // fresh Path via a full search 20x/second. This throttle matches FollowFlowFieldGoal's
        // own re-issue cadence and is strictly cheaper under a large load test.
        //
        // The explicit accuracy=0 here is the actual fix for the stall this goal exists to
        // recover from (confirmed via logged nav diagnostics, 2026-07-29): the convenience
        // overload moveTo(x,y,z,speed) hardcodes PathNavigation's internal accuracy to 1 block,
        // and since this goal's heading is frequently exactly one block away (the nearest
        // boundary cell of the target region), the pathfinder accepted the mob's OWN starting
        // position as "already within accuracy of the goal" and handed back a degenerate,
        // pre-completed 1-node path - logged proof: nodePos0 equaled the mob's own block (not
        // the real target), nextNodeIndex was already past it, and distToTarget was a full 1.0,
        // meaning the path never advanced toward the target at all. Passing accuracy=0 requires
        // the pathfinder to actually route onto the target block before considering it reached.
        boolean issued = this.mob.getNavigation().moveTo(heading.getX() + 0.5, heading.getY(), heading.getZ() + 0.5, 0, 1.0D);
        Path path = this.mob.getNavigation().getPath();

        // Stall is measured by actual distance covered since the last check, not by whether the
        // heading itself changed - a rat walking normally toward a distant, unchanging heading
        // (the common case: the heading only moves when a boundary cell elsewhere becomes
        // closer) previously read as "stalled" every 60 ticks despite making steady progress,
        // triggering attemptLocalBreach on mobs that never needed it.
        Vec3 currentPosition = this.mob.position();
        if (this.lastPosition != null && currentPosition.distanceToSqr(this.lastPosition) < STALL_DISTANCE_SQR_THRESHOLD) {
            this.stalledChecks++;
        } else {
            this.stalledChecks = 0;
        }
        this.lastPosition = currentPosition;

        if (this.stalledChecks < STALL_CHECKS_BEFORE_BREACH_ATTEMPT) return;
        this.stalledChecks = 0;

        // Kept after the accuracy=0 fix above (rather than trimmed back to a plain "stalled"
        // message) since a stall reaching this point now means something genuinely happened -
        // real terrain, collision, or a lane conflict - not the degenerate-path case, and these
        // fields are exactly what distinguished that case last time; cheap insurance against the
        // next "rats seem stuck" report being a different bug in the same spot.
        LOGGER.info("[Skavenblight] {} [{}] stalled at rawPos={} (block {}) heading toward {} - " +
                        "nav: issued={} hasPath={} canReach={} nodes={} nodePos0={} nextIdx={} pathTarget={} distToTarget={}",
                this.mob.getClass().getSimpleName(), this.mob.getUUID().toString().substring(0, 8),
                currentPosition, this.mob.blockPosition().toShortString(), heading.toShortString(),
                issued, path != null, path != null && path.canReach(), path != null ? path.getNodeCount() : -1,
                path != null && path.getNodeCount() > 0 ? path.getNodePos(0).toShortString() : "n/a",
                path != null ? path.getNextNodeIndex() : -1,
                path != null ? path.getTarget().toShortString() : "n/a",
                path != null ? path.getDistToTarget() : -1);

        attemptLocalBreach(heading);
    }

    private void attemptLocalBreach(BlockPos heading) {
        if (!(this.mob.level() instanceof ServerLevel serverLevel)) return;

        BlockPos current = this.mob.blockPosition();
        int dx = Integer.compare(heading.getX(), current.getX());
        int dy = Integer.compare(heading.getY(), current.getY());
        int dz = Integer.compare(heading.getZ(), current.getZ());
        if (dx == 0 && dy == 0 && dz == 0) return;

        LiveTerrainAccess live = new LiveTerrainAccess(serverLevel);
        // Bounds check is deliberately loaded/build-height only, not
        // TerrainEvaluator#isOutOfBounds - that method unconditionally calls
        // FlowFieldState.isOutOfBounds(pos) and would NPE with no FlowFieldState behind a
        // live, unbounded breach attempt. Cost ceiling is unbounded (this is a one-off local
        // attempt, not a flood fill comparing against an existing cheaper-cost map).
        SiegeLineTracer.TraceResult result = lineTracer.trace(live, current, dx, dy, dz, heading, 0,
                pos -> live.isOutsideBuildHeight(pos) || !live.isLoaded(pos),
                pos -> Integer.MAX_VALUE);

        // Mirrors FollowFlowFieldGoal's own "stuck" logging (added there after "rats seem
        // stuck" reports had no log trail at all) - StrandedGoal had the exact same blind spot:
        // a stalled rat at a territory boundary either breaches or sits there forever with
        // nothing in latest.log to say which, or why. INFO rather than DEBUG so it survives
        // into latest.log during a load test instead of being buried in debug.log's volume.
        if (!result.completed()) {
            LOGGER.info("[Skavenblight] {} stalled at {} heading toward {} - local breach aborted (direction {},{},{})",
                    this.mob.getClass().getSimpleName(), current.toShortString(), heading.toShortString(), dx, dy, dz);
            return;
        }

        // A trace landing on already-walkable ground completes with WALK/LEAP-only instructions
        // - constructSiegeBlock no-ops on both (nothing to place), so calling this "breaching"
        // was pure dead work that also misrepresented what happened in the log (traced
        // 2026-07-29: 959/959 stall events logged "breaching 1 step" for a completely open,
        // wall-free boundary with nothing to place). If nothing in the trace needs real
        // construction, there's no obstacle at all - the stall is a navigation problem, not a
        // terrain one, and the nav diagnostics logged by the caller already cover that case.
        boolean needsConstruction = result.instructions().values().stream()
                .anyMatch(node -> node.action() != SiegeNode.SiegeAction.WALK && node.action() != SiegeNode.SiegeAction.LEAP);
        if (!needsConstruction) {
            LOGGER.info("[Skavenblight] {} stalled at {} heading toward {} - no obstacle to breach (trace is all WALK/LEAP)",
                    this.mob.getClass().getSimpleName(), current.toShortString(), heading.toShortString());
            return;
        }

        LOGGER.info("[Skavenblight] {} stalled at {} heading toward {} - breaching {} step(s) to {}",
                this.mob.getClass().getSimpleName(), current.toShortString(), heading.toShortString(),
                result.instructions().size(), result.endPos() != null ? result.endPos().toShortString() : "?");

        result.instructions().forEach((pos, node) ->
                SiegeInteractionHandler.constructSiegeBlock(serverLevel, pos, mob.getDirection(), node.action(), null, this.mob));
    }
}
