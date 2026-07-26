package org.ratden.skavenblight.ai.goal.clanrat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import org.ratden.skavenblight.ai.pathing.LiveTerrainAccess;
import org.ratden.skavenblight.ai.pathing.SiegeInteractionHandler;
import org.ratden.skavenblight.ai.pathing.SiegeLineTracer;
import org.ratden.skavenblight.ai.pathing.TerrainEvaluator;
import org.ratden.skavenblight.entity.custom.ClanratEntity;

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

    private static final int STALL_TICKS_BEFORE_BREACH_ATTEMPT = 60;

    private final ClanratEntity mob;
    private final TerrainEvaluator terrainEvaluator = new TerrainEvaluator();
    private final SiegeLineTracer lineTracer = new SiegeLineTracer(terrainEvaluator);

    private BlockPos lastHeading = null;
    private int stalledTicks = 0;

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
    public void tick() {
        BlockPos heading = this.mob.getStrandedHeading();
        if (heading == null) return;

        if (heading.equals(this.lastHeading)) {
            this.stalledTicks++;
        } else {
            this.stalledTicks = 0;
            this.lastHeading = heading;
        }

        this.mob.getNavigation().moveTo(heading.getX() + 0.5, heading.getY(), heading.getZ() + 0.5, 1.0D);

        if (this.stalledTicks < STALL_TICKS_BEFORE_BREACH_ATTEMPT) return;
        this.stalledTicks = 0;

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

        if (!result.completed()) return;

        result.instructions().forEach((pos, node) ->
                SiegeInteractionHandler.constructSiegeBlock(serverLevel, pos, mob.getDirection(), node.action(), null, this.mob));
    }
}
