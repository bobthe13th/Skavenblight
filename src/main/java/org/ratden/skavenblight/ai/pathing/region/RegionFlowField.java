package org.ratden.skavenblight.ai.pathing.region;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import org.ratden.skavenblight.ai.pathing.*;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-region query facade handed to SiegeGoals, replacing StandardFlowField's role for
 * goal-facing code. Deliberately thin: chunk-ticket management and dirty tracking live on
 * TerritoryRegionMap (network-wide); this class only wraps one region's FlowFieldState plus
 * a claim table scoped to that region.
 */
public class RegionFlowField {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final TerritoryRegionMap owner;
    private final int regionId;
    private final FlowFieldState state;
    private final SiegeProjectManager projectManager;
    private final FlowFieldCalculator calculator;
    private final CalculationThrottler throttler;

    private final Map<BlockPos, Mob> claimedTargets = new HashMap<>();

    public RegionFlowField(TerritoryRegionMap owner, int regionId, FlowFieldState state,
                            SiegeProjectManager projectManager, FlowFieldCalculator calculator, CalculationThrottler throttler) {
        this.owner = owner;
        this.regionId = regionId;
        this.state = state;
        this.projectManager = projectManager;
        this.calculator = calculator;
        this.throttler = throttler;
    }

    public int getRegionId() {
        return regionId;
    }

    public SiegeNode getNextSiegeNode(ServerLevel level, BlockPos ratPos) {
        SiegeNode node = state.getInstruction(ratPos);
        if (node == null) return null;

        LiveTerrainAccess live = new LiveTerrainAccess(level);
        TerrainEvaluator evaluator = new TerrainEvaluator();
        return evaluator.isActionCompleted(live, node)
                ? new SiegeNode(node.action() == SiegeNode.SiegeAction.MINE ? node.pos() : node.pos().above(), SiegeNode.SiegeAction.WALK)
                : node;
    }

    public boolean tryClaimTarget(BlockPos pos, Mob claimant) {
        BlockPos key = pos.immutable();
        Mob current = claimedTargets.get(key);
        if (current != null && current != claimant && current.isAlive()) {
            return false;
        }
        claimedTargets.put(key, claimant);
        return true;
    }

    public void releaseTarget(BlockPos pos) {
        if (pos != null) claimedTargets.remove(pos);
    }

    public boolean isTargetClaimed(BlockPos pos) {
        Mob owner = claimedTargets.get(pos);
        return owner != null && owner.isAlive();
    }

    public void forceRecalculation() {
        // Region recalculation is driven by TerritoryRegionMap.tick's dirty-region tracking;
        // a construction goal finishing a build step marks its own position dirty the same way
        // an ordinary block change would, so the next tick's settle-delay/cooldown gating picks
        // it up naturally.
    }

    public boolean isCalculating() {
        return owner.isCalculating();
    }

    public BlockPos getWildernessHeadingTarget(BlockPos ratPos) {
        return owner.getWildernessHeadingTarget(ratPos);
    }

    public Map<BlockPos, SiegeNode> getInstructionMap() {
        return state.getInstructionMap();
    }

    public BlockPos getTargetPos() {
        return state.getTargetPos();
    }

    public Map<BlockPos, SiegeNode> getLiveDebugMap() {
        return calculator.getLiveDebugMap();
    }
}
