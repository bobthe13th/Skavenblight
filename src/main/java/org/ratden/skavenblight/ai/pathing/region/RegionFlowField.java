package org.ratden.skavenblight.ai.pathing.region;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import org.ratden.skavenblight.ai.pathing.*;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
    // Stateless, but getNextSiegeNode runs per-mob per-tick on the hottest path in a system meant
    // to carry hundreds of mobs - allocate it once here instead of once per call.
    private final TerrainEvaluator terrainEvaluator = new TerrainEvaluator();

    private final Map<BlockPos, Mob> claimedTargets = new HashMap<>();

    // Tracks how many mobs are currently funneling through a given connector lane (e.g. a
    // narrow bridge/staircase's entry node), so WidenStairsGoal can widen a saturated lane
    // proactively instead of only reacting after a rat is already stuck off-path.
    private final Map<BlockPos, Set<Mob>> laneOccupants = new HashMap<>();
    private static final int MAX_LANE_OCCUPANTS = 2;

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
        return terrainEvaluator.isActionCompleted(live, node)
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

    /** True if there's room for {@code mob} on the lane at {@code connectorEntry} - callers should widen (see WidenStairsGoal) once this starts returning false often. */
    public boolean tryOccupyLane(BlockPos connectorEntry, Mob mob) {
        Set<Mob> occupants = laneOccupants.computeIfAbsent(connectorEntry.immutable(), k -> new HashSet<>());
        occupants.removeIf(m -> !m.isAlive());
        if (occupants.size() >= MAX_LANE_OCCUPANTS && !occupants.contains(mob)) {
            return false;
        }
        occupants.add(mob);
        return true;
    }

    public void releaseLane(BlockPos connectorEntry, Mob mob) {
        Set<Mob> occupants = laneOccupants.get(connectorEntry.immutable());
        if (occupants != null) occupants.remove(mob);
    }

    public boolean isLaneCrowded(BlockPos connectorEntry) {
        Set<Mob> occupants = laneOccupants.get(connectorEntry.immutable());
        return occupants != null && occupants.size() >= MAX_LANE_OCCUPANTS;
    }

    /**
     * Asks the owning TerritoryRegionMap to recompute this region. Routed through
     * {@link TerritoryRegionMap#onBlockChanged(BlockPos)} rather than kicking off a calculation
     * directly, so it reuses the existing queue + dirty-region tracking (and with it the
     * settle-delay/cooldown gating in {@code tick()}) instead of needing a throttle of its own.
     *
     * <p>This used to be an empty no-op, on the assumption that a construction goal finishing a
     * build step already marked its own position dirty like any other block change. Nothing does:
     * the only dirty-marking path is SiegeBlockEventHandler listening to NeoForge BlockEvents, and
     * SiegeInteractionHandler's direct level.setBlock/destroyBlock calls don't fire those.
     */
    public void forceRecalculation() {
        owner.onBlockChanged(state.getTargetPos());
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
