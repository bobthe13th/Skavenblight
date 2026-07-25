package org.ratden.skavenblight.ai.pathing.region;

import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.Config;
import org.ratden.skavenblight.ai.pathing.*;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-network owner of the region graph, route tree, and per-region local flow fields.
 * Replaces StandardFlowField entirely: chunk-ticket management, dirty tracking, and
 * calculation gating all live here now, scoped per-region instead of per-nexus-wide-territory.
 */
public class TerritoryRegionMap {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final TerrainEvaluator terrainEvaluator = new TerrainEvaluator();
    private final SiegeLineTracer lineTracer = new SiegeLineTracer(terrainEvaluator);
    private final RegionScanner regionScanner = new RegionScanner(terrainEvaluator);
    private final SiegeProjectManager projectManager = new SiegeProjectManager(terrainEvaluator);
    private final CalculationThrottler throttler = new CalculationThrottler();
    private final FlowFieldCalculator calculator = new FlowFieldCalculator(terrainEvaluator, projectManager, throttler);

    private volatile TerrainSnapshot terrainSnapshot = null;
    private final Set<ChunkPos> dirtySnapshotChunks = new HashSet<>();
    private final Set<ChunkPos> forcedChunks = new HashSet<>();

    private volatile RegionIndex regionIndex = new RegionIndex(List.of());
    private volatile RegionGraph regionGraph = null;
    private volatile RegionRouteTree routeTree = null;
    private volatile Map<Integer, FlowFieldState> regionStates = Map.of();
    // Per-region query facades handed out to goal-facing code (see getRegionFlowFieldFor). Kept
    // stable (same instance per regionId) across the "cheap path" in recomputeDirtyRegions - only
    // rebuilt here, alongside regionStates, when a full rebuild produces brand-new FlowFieldState
    // objects - so a RegionFlowField's claim table (scoped per-region, see Task 9's design note)
    // stays meaningful across multiple goal lookups instead of resetting on every call.
    private volatile Map<Integer, RegionFlowField> regionFlowFields = Map.of();

    private final AtomicBoolean isCalculatingAsync = new AtomicBoolean(false);
    private long lastCalculationStart = 0;
    private long lastBlockChangeTime = 0;
    private final Set<Integer> dirtyRegionIds = new HashSet<>();
    // Queued so onBlockChanged (any thread reachable from block-update handling today, though in
    // practice only ever called from the main thread) never blocks on the calculation lock.
    private final ConcurrentLinkedQueue<BlockPos> pendingBlockChanges = new ConcurrentLinkedQueue<>();

    public CalculationThrottler getThrottler() {
        return this.throttler;
    }

    public void syncTerritoryChunkTickets(ServerLevel level, Set<ChunkPos> newTerritory) {
        Iterator<ChunkPos> iterator = forcedChunks.iterator();
        while (iterator.hasNext()) {
            ChunkPos cp = iterator.next();
            if (!newTerritory.contains(cp)) {
                level.setChunkForced(cp.x, cp.z, false);
                iterator.remove();
            }
        }
        for (ChunkPos cp : newTerritory) {
            if (forcedChunks.add(cp)) {
                level.setChunkForced(cp.x, cp.z, true);
            }
        }
    }

    public void cleanup(ServerLevel level) {
        for (ChunkPos cp : forcedChunks) {
            level.setChunkForced(cp.x, cp.z, false);
        }
        forcedChunks.clear();
    }

    public void onBlockChanged(BlockPos pos) {
        pendingBlockChanges.add(pos.immutable());
    }

    /** Full rebuild: snapshot -> regions -> graph -> route tree -> per-region fields. Call once at network creation/territory change, then rely on tick() for steady state. */
    public void rebuild(ServerLevel level, Set<ChunkPos> territoryChunks, BlockPos nexusPos) {
        if (isCalculatingAsync.get()) return;
        if (!isCalculatingAsync.compareAndSet(false, true)) return;

        syncTerritoryChunkTickets(level, territoryChunks);
        dirtySnapshotChunks.addAll(territoryChunks);
        TerrainSnapshot.RefreshResult result = TerrainSnapshot.refresh(
                level, terrainSnapshot, territoryChunks, dirtySnapshotChunks,
                level.getMinBuildHeight(), level.getMaxBuildHeight(), Integer.MAX_VALUE);
        dirtySnapshotChunks.removeAll(result.capturedChunks());
        terrainSnapshot = result.snapshot();

        LOGGER.info("[Skavenblight] TerritoryRegionMap rebuild STARTED for nexus {}", nexusPos.toShortString());

        CompletableFuture.runAsync(() -> {
            try {
                rebuildRegionsAndGraph(territoryChunks, nexusPos);
            } catch (Exception e) {
                LOGGER.error("[Skavenblight] TerritoryRegionMap rebuild crashed!", e);
            } finally {
                isCalculatingAsync.set(false);
            }
        }, Util.backgroundExecutor()).thenAcceptAsync(v ->
                LOGGER.info("[Skavenblight] TerritoryRegionMap rebuild FINISHED: {} regions, {} connectors",
                        regionIndex.getRegions().size(), regionGraph != null ? regionGraph.getAllConnectors().size() : 0),
                level.getServer());
    }

    private void rebuildRegionsAndGraph(Set<ChunkPos> territoryChunks, BlockPos nexusPos) {
        TerrainSnapshot snapshot = this.terrainSnapshot;
        List<Region> regions = regionScanner.scan(snapshot, territoryChunks, nexusPos,
                snapshot.getMinBuildHeight(), snapshot.getMaxBuildHeight());
        RegionIndex newIndex = new RegionIndex(regions);
        RegionGraph newGraph = RegionGraph.build(snapshot, newIndex, territoryChunks, nexusPos, terrainEvaluator, lineTracer);

        Region rootRegion = newIndex.regionAt(nexusPos);
        RegionRouteTree newRouteTree = rootRegion != null ? RegionRouteTree.compute(newGraph, rootRegion.getId()) : null;

        Map<Integer, FlowFieldState> newStates = new HashMap<>();
        for (Region region : regions) {
            BlockPos target = region.getId() == (rootRegion != null ? rootRegion.getId() : -1)
                    ? nexusPos
                    : (newRouteTree != null && newRouteTree.getParentConnector(region.getId()) != null
                            ? newRouteTree.getParentConnector(region.getId()).entryFor(region.getId())
                            : null);

            if (target == null) continue; // unreachable region - no local field until a connector exists

            FlowFieldState state = new FlowFieldState(target, territoryChunks, region::contains);
            calculator.calculateFully(snapshot, state);
            newStates.put(region.getId(), state);
        }

        Map<Integer, RegionFlowField> newFlowFields = new HashMap<>();
        for (Map.Entry<Integer, FlowFieldState> entry : newStates.entrySet()) {
            newFlowFields.put(entry.getKey(),
                    new RegionFlowField(this, entry.getKey(), entry.getValue(), projectManager, calculator, throttler));
        }

        this.regionIndex = newIndex;
        this.regionGraph = newGraph;
        this.routeTree = newRouteTree;
        this.regionStates = Map.copyOf(newStates);
        this.regionFlowFields = Map.copyOf(newFlowFields);
    }

    /**
     * Per-region query facade for the region containing {@code pos}, or null if {@code pos}
     * isn't inside any known region (wilderness/unmapped) or that region has no local field yet
     * (unreachable from the route tree - see rebuildRegionsAndGraph). Returns the SAME
     * RegionFlowField instance across repeated calls for the same region (until the next full
     * rebuild), so its claim table is actually shared by every mob currently assigned to that
     * region rather than reset per lookup.
     */
    public RegionFlowField getRegionFlowFieldFor(BlockPos pos) {
        Integer regionId = regionIndex.regionIdAt(pos);
        if (regionId == null) return null;
        return regionFlowFields.get(regionId);
    }

    public boolean isCalculating() {
        return isCalculatingAsync.get();
    }

    public BlockPos getWildernessHeadingTarget(BlockPos pos) {
        List<Region> regions = regionIndex.getRegions();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (Region region : regions) {
            if (region.getMin() == null) continue;
            BlockPos candidate = region.getMin(); // cheap stand-in for "somewhere in this region"
            double dist = candidate.distSqr(pos);
            if (dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }
        return best;
    }

    public RegionIndex getRegionIndex() {
        return regionIndex;
    }

    public RegionRouteTree getRouteTree() {
        return routeTree;
    }

    public RegionGraph getRegionGraph() {
        return regionGraph;
    }

    private static final long RECALC_COOLDOWN_TICKS = 80;

    public void tick(ServerLevel level) {
        if (isCalculatingAsync.get()) return;

        BlockPos changed;
        boolean anyChange = false;
        while ((changed = pendingBlockChanges.poll()) != null) {
            anyChange = true;
            // Check the changed position AND its 6 orthogonal neighbors, marking every distinct
            // region found dirty - not just whichever region (if any) owns the changed position
            // itself. A wall block broken between two regions was never a member of either
            // region (it wasn't walkable), so regionIdAt(changed) alone would find nothing; its
            // flanking neighbor cells, however, resolve to the two regions on either side, so
            // checking them is what actually lets a merge get detected.
            for (BlockPos candidate : neighborsAndSelf(changed)) {
                dirtySnapshotChunks.add(new ChunkPos(candidate));
                Integer regionId = regionIndex.regionIdAt(candidate);
                if (regionId != null) {
                    dirtyRegionIds.add(regionId);
                }
            }
        }
        if (anyChange) {
            lastBlockChangeTime = System.currentTimeMillis();
        }

        if (dirtyRegionIds.isEmpty()) return;

        boolean terrainSettled = (System.currentTimeMillis() - lastBlockChangeTime) >= Config.minimumSettleDelayMs;
        boolean offCooldown = (level.getGameTime() - lastCalculationStart) >= RECALC_COOLDOWN_TICKS;
        if (!terrainSettled || !offCooldown) return;

        if (!isCalculatingAsync.compareAndSet(false, true)) return;
        lastCalculationStart = level.getGameTime();

        Set<Integer> regionsToProcess = Set.copyOf(dirtyRegionIds);
        dirtyRegionIds.clear();

        Set<ChunkPos> territoryChunks = terrainSnapshot != null ? Set.copyOf(getSnapshotChunks()) : Set.of();
        TerrainSnapshot.RefreshResult result = TerrainSnapshot.refresh(
                level, terrainSnapshot, territoryChunks, dirtySnapshotChunks,
                level.getMinBuildHeight(), level.getMaxBuildHeight(), 10);
        dirtySnapshotChunks.removeAll(result.capturedChunks());
        terrainSnapshot = result.snapshot();

        LOGGER.info("[Skavenblight] TerritoryRegionMap recalculating {} dirty region(s)", regionsToProcess.size());

        CompletableFuture.runAsync(() -> {
            try {
                recomputeDirtyRegions(regionsToProcess, territoryChunks);
            } catch (Exception e) {
                LOGGER.error("[Skavenblight] TerritoryRegionMap dirty-region recompute crashed!", e);
            } finally {
                isCalculatingAsync.set(false);
            }
        }, Util.backgroundExecutor());
    }

    private static List<BlockPos> neighborsAndSelf(BlockPos pos) {
        return List.of(pos, pos.above(), pos.below(), pos.north(), pos.south(), pos.east(), pos.west());
    }

    private Set<ChunkPos> getSnapshotChunks() {
        Set<ChunkPos> chunks = new HashSet<>();
        for (Region region : regionIndex.getRegions()) {
            chunks.addAll(region.getChunkCells().keySet());
        }
        return chunks;
    }

    private void recomputeDirtyRegions(Set<Integer> dirtyIds, Set<ChunkPos> territoryChunks) {
        TerrainSnapshot snapshot = this.terrainSnapshot;

        for (int regionId : dirtyIds) {
            Region oldRegion = regionIndex.getRegions().stream().filter(r -> r.getId() == regionId).findFirst().orElse(null);
            if (oldRegion == null || oldRegion.getMin() == null || oldRegion.getMax() == null) continue;

            // Rescan just this region's old footprint (plus its neighbors would require a wider
            // bounding-box expansion - start with the region's own bounds; a merge/split that
            // reaches beyond it is caught on the NEXT tick when the newly-adjacent region's own
            // cells also get marked dirty by the same block-change event, since a merge implies a
            // shared boundary cell whose neighbor set changed too).
            Set<ChunkPos> localBounds = new HashSet<>();
            for (int x = oldRegion.getMin().getX() >> 4; x <= oldRegion.getMax().getX() >> 4; x++) {
                for (int z = oldRegion.getMin().getZ() >> 4; z <= oldRegion.getMax().getZ() >> 4; z++) {
                    localBounds.add(new ChunkPos(x, z));
                }
            }

            List<Region> rescanned = regionScanner.scan(snapshot, localBounds, oldRegion.getMin(),
                    snapshot.getMinBuildHeight(), snapshot.getMaxBuildHeight());

            boolean topologyChanged = rescanned.size() != 1;
            if (!topologyChanged) {
                // Same single region, just recompute its local field against the current route tree.
                FlowFieldState state = regionStates.get(regionId);
                if (state != null) {
                    calculator.calculateFully(snapshot, state);
                }
                continue;
            }

            LOGGER.info("[Skavenblight] Region {} topology changed ({} sub-regions found) - full territory rebuild triggered", regionId, rescanned.size());
            // A genuine split/merge is rare and the region count for a typical base is small (see
            // RegionScanner's manual test notes) - falling back to a full rebuild here is simpler
            // and safer than hand-patching RegionGraph/RegionRouteTree, and still only runs when
            // topology actually changed, not on every terrain edit.
            BlockPos rootTarget = regionIndex.getRegions().stream()
                    .filter(r -> routeTree != null && r.getId() == routeTree.getRootRegionId())
                    .findFirst().map(Region::getMin).orElse(oldRegion.getMin());
            rebuildRegionsAndGraph(territoryChunks, rootTarget);
            return;
        }
    }
}
