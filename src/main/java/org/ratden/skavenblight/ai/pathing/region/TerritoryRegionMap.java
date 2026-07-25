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
    private final Map<Integer, FlowFieldState> regionStates = new HashMap<>();

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

        this.regionIndex = newIndex;
        this.regionGraph = newGraph;
        this.routeTree = newRouteTree;
        this.regionStates.clear();
        this.regionStates.putAll(newStates);
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

    public void tick(ServerLevel level) { /* steady-state recompute wiring lands in Task 8 */ }
}
