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
import java.util.Optional;
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

    // Separate registry from claimedTargets - a waiting slot must never be mistaken for a build
    // target or vice versa (see AwaitFormationGoal).
    private final Map<BlockPos, Mob> formationSlots = new HashMap<>();

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

    /**
     * The raw computed instruction at {@code pos} within this region's own field, with none of
     * getNextSiegeNode's live "is this action already done" resolution - for debug rendering
     * (PathingDebugFileWriter's grid), which wants to show exactly what the last Dijkstra pass
     * produced, cell by cell, not what a mob standing there right now would be told to do next.
     */
    public SiegeNode getRawInstruction(BlockPos pos) {
        return state.getInstruction(pos);
    }

    // Only these three actions come from determineMacroAction's pure-vertical ("Vertical Shaft /
    // Column", dx==0 && dz==0) branch, where the placed block sits UNDER the mob's new footing -
    // once built, the mob climbs one MORE block up to stand on top of it. Every other action
    // places (or clears) the block the mob steps directly ONTO/INTO: BUILD_STAIR and BUILD_BRIDGE
    // approach diagonally/horizontally and land AT node.pos() itself, MINE clears node.pos() itself
    // for the mob to walk into, and BUILD_LANDING's own isActionCompleted special-case already
    // means "the platform below is filled and pos itself is the clear standing spot". Using
    // node.pos().above() for BUILD_STAIR specifically was confirmed wrong via
    // StaircaseSiegeGroupGameTests: the mob would build the first diagonal step, correctly detect
    // it as completed, but then get pointed one block above the newly-built stair - a position
    // with no instruction of its own at all (only node.pos() itself, the stair's own position, is
    // one of the connector's keyed positions), stranding the mob after exactly one step.
    private static final Set<SiegeNode.SiegeAction> CLIMB_TO_ABOVE_ONCE_BUILT = Set.of(
            SiegeNode.SiegeAction.BUILD_PILLAR, SiegeNode.SiegeAction.BUILD_SPIRAL, SiegeNode.SiegeAction.BUILD_LADDER);

    public SiegeNode getNextSiegeNode(ServerLevel level, BlockPos ratPos) {
        SiegeNode node = state.getInstruction(ratPos);
        if (node == null) return null;

        LiveTerrainAccess live = new LiveTerrainAccess(level);
        return terrainEvaluator.isActionCompleted(live, node)
                ? new SiegeNode(CLIMB_TO_ABOVE_ONCE_BUILT.contains(node.action()) ? node.pos().above() : node.pos(), SiegeNode.SiegeAction.WALK)
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

    /**
     * Same tryClaim/release/isClaimed shape as claimedTargets on purpose, for a consistent
     * mental model - but a SEPARATE map, so a formation-slot claim is never visible as (or
     * confused with) a construction-target claim.
     */
    public boolean tryClaimFormationSlot(BlockPos pos, Mob claimant) {
        BlockPos key = pos.immutable();
        Mob current = formationSlots.get(key);
        if (current != null && current != claimant && current.isAlive()) {
            return false;
        }
        formationSlots.put(key, claimant);
        return true;
    }

    public void releaseFormationSlot(BlockPos pos) {
        if (pos != null) formationSlots.remove(pos);
    }

    public boolean isFormationSlotClaimed(BlockPos pos) {
        Mob owner = formationSlots.get(pos);
        return owner != null && owner.isAlive();
    }

    /** AwaitFormationGoal needs this to reach the real Region object (via getRegionIndex()) for terrain-validated formation-slot search. */
    public TerritoryRegionMap getOwner() {
        return this.owner;
    }

    /**
     * Diagnostic-only: the mob currently claiming {@code pos}, or null if unclaimed. Unlike
     * {@link #isTargetClaimed}, this does NOT check {@code isAlive()} - a dead claimant is
     * exactly the kind of thing a debug dump needs to surface (a live claimant that's alive but
     * stuck/far away looks identical to a healthy in-progress build from {@link #isTargetClaimed}
     * alone).
     */
    public Mob getClaimant(BlockPos pos) {
        return claimedTargets.get(pos);
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
     *
     * @param changedPos the actual position that was just built/mined - NOT this region's own
     * local target. Passing {@code state.getTargetPos()} here (the previous, no-arg version of
     * this method) marks the wrong chunk's terrain snapshot for refresh: onBlockChanged refreshes
     * whichever chunk CONTAINS the position it's given, so a caller reporting its own unrelated
     * target instead of the real construction site left that site's chunk permanently stale,
     * even across generation-incrementing rebuilds - RegionScanner (and FlowFieldCalculator)
     * kept seeing pre-construction terrain there forever. Confirmed in testing: a mob built a
     * real, walkable pillar (SiegeActivityLog recorded the placement), but that position stayed
     * "wilderness" (no region) three rebuild generations later, sending the mob right back to
     * rebuild the same pillar in an endless loop.
     */
    public void forceRecalculation(BlockPos changedPos) {
        owner.onBlockChanged(changedPos);
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

    public Optional<SiegeProject> findProjectFor(BlockPos pos) {
        return projectManager.findProjectContaining(pos);
    }
}
