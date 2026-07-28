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

    // The network's REAL configured territory and nexus, as handed to the last rebuild(). Both
    // are needed by tick()/recomputeDirtyRegions long after that call returned:
    //  - territoryChunks: tick() used to derive its bounds from "chunks that currently contain at
    //    least one region cell", which can only ever shrink - a territory chunk with no walkable
    //    cells yet (or one that hasn't been scanned) was silently dropped from the snapshot
    //    forever, and dirty chunks outside the derived set were never captured or cleared.
    //  - nexusPos: the full-rebuild fallback used to re-root at a Region's bounding-box corner
    //    (getMin()), which is not the nexus and usually isn't even inside the region.
    private volatile Set<ChunkPos> territoryChunks = Set.of();
    private volatile BlockPos nexusPos = null;

    // Bumped on every completed full rebuild. Region ids are renumbered from 0 by RegionScanner
    // on each scan (and seeded from an unordered chunk set), so "region 3" before a rebuild is
    // not "region 3" after one. Anything caching a region id (see ClanratEntity) must pair it
    // with this generation or it will happily keep a RegionFlowField wrapping an orphaned
    // FlowFieldState that this map no longer recomputes.
    private volatile long generation = 0;

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

    /**
     * Re-issues every chunk ticket this map believes it already holds. Needed when a DIFFERENT
     * TerritoryRegionMap whose territory overlapped ours releases its own tickets (see
     * WarpFluxGridManager's network merge): {@code level.setChunkForced} is one level-wide set,
     * so the other map's release also drops a shared chunk we still depend on, while our own
     * forcedChunks bookkeeping still lists it - which would make syncTerritoryChunkTickets skip
     * re-forcing it.
     */
    public void reassertChunkTickets(ServerLevel level) {
        for (ChunkPos cp : forcedChunks) {
            level.setChunkForced(cp.x, cp.z, true);
        }
    }

    public void onBlockChanged(BlockPos pos) {
        pendingBlockChanges.add(pos.immutable());
    }

    /** Full rebuild: snapshot -> regions -> graph -> route tree -> per-region fields. Call once at network creation/territory change, then rely on tick() for steady state. */
    public void rebuild(ServerLevel level, Set<ChunkPos> territoryChunks, BlockPos nexusPos) {
        if (isCalculatingAsync.get()) return;
        if (!isCalculatingAsync.compareAndSet(false, true)) return;

        // Snapshot the caller's set immediately. Everything below - including the background
        // rebuild and every FlowFieldState's bounds check - uses this immutable copy, so a caller
        // handing us a set it keeps mutating (WarpFluxNetwork.updateTerritory rebuilds its
        // territory HashSet in place on the main thread) can't corrupt an in-flight pass.
        Set<ChunkPos> territory = Set.copyOf(territoryChunks);

        // Remembered for tick()/recomputeDirtyRegions - see the field docs.
        this.territoryChunks = territory;
        this.nexusPos = nexusPos.immutable();

        syncTerritoryChunkTickets(level, territory);
        dirtySnapshotChunks.addAll(territory);
        TerrainSnapshot.RefreshResult result = TerrainSnapshot.refresh(
                level, terrainSnapshot, territory, dirtySnapshotChunks,
                level.getMinBuildHeight(), level.getMaxBuildHeight(), Integer.MAX_VALUE);
        dirtySnapshotChunks.removeAll(result.capturedChunks());
        terrainSnapshot = result.snapshot();

        LOGGER.info("[Skavenblight] TerritoryRegionMap rebuild STARTED for nexus {}", nexusPos.toShortString());

        CompletableFuture.runAsync(() -> {
            try {
                rebuildRegionsAndGraph(territory, nexusPos);
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

        // The nexus block itself is solid (see WARPSTONE_NEXUS/ACTIVE_WARPSTONE_NEXUS in
        // ModBlocks - plain full-collision blocks, no shape override), so RegionScanner never
        // assigns nexusPos to any region: it only ever adds walkable cells. A direct
        // newIndex.regionAt(nexusPos) lookup was therefore always null, which made rootRegion,
        // and everything downstream of it (the whole route tree and every region's target),
        // always null too - no region ever got a real FlowFieldState. Check the nexus's
        // orthogonal neighbors as well, same as tick()'s dirty-marking already does for the
        // identical "the block of interest itself isn't walkable" situation.
        Region rootRegion = neighborsAndSelf(nexusPos).stream()
                .map(newIndex::regionAt)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        RegionRouteTree newRouteTree = rootRegion != null ? RegionRouteTree.compute(newGraph, rootRegion.getId()) : null;

        Map<Integer, FlowFieldState> newStates = new HashMap<>();
        for (Region region : regions) {
            boolean isRoot = rootRegion != null && region.getId() == rootRegion.getId();
            RegionConnector parentConnector = isRoot ? null
                    : (newRouteTree != null ? newRouteTree.getParentConnector(region.getId()) : null);

            BlockPos target = isRoot ? nexusPos
                    : (parentConnector != null ? parentConnector.entryFor(region.getId()) : null);

            if (target == null) continue; // unreachable region - no local field until a connector exists

            // Seed this region's chosen connector as the shared project manager's single active
            // project, so injectActiveProjects feeds its not-yet-built instructions (the actual
            // build/mine orders for the bridge/staircase/tunnel out of this region) into the pass
            // below. Without this nothing ever tells a mob to construct a connector at all.
            // Safe with one shared manager because regions are processed strictly sequentially
            // here - setActiveConnectorProject clears and re-seeds immediately before the pass
            // that consumes it, so no region can see another's project.
            // Cap this region's own reactive macro-project search to a short local-gap length once
            // it already has a route-tree-assigned parent connector - long-range connectivity is
            // that connector's job now (see SiegeProjectManager.setMaxCandidateProjectLength's
            // doc), not another sunburst-style search from evaluateMacroProjects. A region with no
            // parent connector yet (including the root) keeps the generous territory-scale default,
            // since local discovery may still be the only way it connects to anything.
            projectManager.setMaxCandidateProjectLength(
                    parentConnector != null ? 6 : SiegeProjectManager.DEFAULT_MAX_CANDIDATE_PROJECT_LENGTH);

            // projectFor(region.getId()) picks the orientation that leads OUT of this (child)
            // region: a connector's traced instructions are direction-locked, and this region can
            // be on either end of it (see RegionConnector).
            projectManager.setActiveConnectorProject(parentConnector != null ? parentConnector.projectFor(region.getId()) : null);

            FlowFieldState state = new FlowFieldState(target, territoryChunks, region::contains);
            // Unthrottled: this is the one-shot rebuild pass, run once at world-join/territory
            // change - see FlowFieldCalculator.calculateFully's 3-arg overload doc for why it
            // must not inherit the live MSPT throttle meant for steady-state recompute.
            calculator.calculateFully(snapshot, state, false);
            newStates.put(region.getId(), state);

            // Diagnostic only (systematic-debugging Phase 1 evidence-gathering): the deep dump
            // only ever reports budget/exhaustion stats for whichever single region the debug
            // item is currently targeting, so a large region's Dijkstra pass getting cut short by
            // CalculationThrottler mid-rebuild - while still being marked reachable at the graph
            // level - was previously invisible. See docs/superpowers/plans for the investigation
            // this is gathering evidence for.
            if (calculator.isLastPassBudgetExhausted()) {
                LOGGER.warn("[Skavenblight] Region {} flow field pass hit its node budget ({} nodes, throttled cap {}) - {} region cells may be left without instructions this rebuild",
                        region.getId(), calculator.getLastPassNodeCount(), throttler.getNodesPerTick(), region.cellCount());
            } else {
                LOGGER.info("[Skavenblight] Region {} flow field pass completed: {} nodes for {} region cells (throttled cap {})",
                        region.getId(), calculator.getLastPassNodeCount(), region.cellCount(), throttler.getNodesPerTick());
            }
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
        // Published last, alongside the new maps: every region id above is freshly renumbered, so
        // anything caching one must be able to notice they all just changed meaning. Written only
        // from the single-flight rebuild path (guarded by isCalculatingAsync), hence a plain
        // volatile increment rather than an atomic.
        this.generation++;
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

    /**
     * Where a mob outside any mapped region (or inside an unreachable one) should head. Only
     * ROUTE-REACHABLE regions are considered - heading toward a region the route tree can't reach
     * from the nexus is no better than where the mob already is, and for a stranded mob it can be
     * its own region. The heading itself is the nearest of that region's boundary cells: those are
     * real walkable member cells at the region's edge, unlike getMin(), which is a bounding-box
     * corner that's frequently inside solid rock and not even part of the region.
     *
     * <p>Never returns null once any region exists at all (FollowFlowFieldGoal/StrandedGoal rely
     * on that): if nothing is reachable, it falls back to the old nearest-getMin() behavior.
     */
    public BlockPos getWildernessHeadingTarget(BlockPos pos) {
        List<Region> regions = regionIndex.getRegions();
        RegionRouteTree tree = this.routeTree;

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        if (tree != null) {
            for (Region region : regions) {
                if (!tree.isReachable(region.getId())) continue;
                for (BlockPos boundaryCell : region.getBoundaryCells()) {
                    double dist = boundaryCell.distSqr(pos);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = boundaryCell;
                    }
                }
            }
        }
        if (best != null) return best;

        // Nothing reachable (no route tree yet, or every region sealed off) - keep the contract
        // and hand back the nearest region's bounding-box corner as a last resort.
        for (Region region : regions) {
            if (region.getMin() == null) continue;
            BlockPos candidate = region.getMin();
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

    /** Bumped on every completed full rebuild - see the {@code generation} field doc. */
    public long getGeneration() {
        return generation;
    }

    /** Network-wide siege project manager (shared by every region's calculation pass) - exposed for debug dumps. */
    public SiegeProjectManager getProjectManager() {
        return projectManager;
    }

    /** Network-wide flow-field calculator (shared by every region's calculation pass) - exposed for debug dumps. */
    public FlowFieldCalculator getCalculator() {
        return calculator;
    }

    /** Chunk columns actually captured by the current terrain snapshot, or 0 if none captured yet. */
    public int getCapturedChunkCount() {
        TerrainSnapshot snapshot = this.terrainSnapshot;
        return snapshot != null ? snapshot.getCapturedChunkCount() : 0;
    }

    /** Size of the territory this map was last rebuilt against. */
    public int getTerritoryChunkCount() {
        return this.territoryChunks.size();
    }

    public RegionRouteTree getRouteTree() {
        return routeTree;
    }

    public RegionGraph getRegionGraph() {
        return regionGraph;
    }

    private static final long RECALC_COOLDOWN_TICKS = 80;

    public void tick(ServerLevel level) {
        // Feeds the throttler its MSPT sample. Without this its dynamic node budget never moves
        // off its static maximum, so processCalculationQueue's Math.min(...) is a no-op.
        this.throttler.tick(level.getServer());

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

        // The network's REAL territory as of the last rebuild, not "chunks that happen to contain
        // a region cell right now" - see the territoryChunks field doc for why the latter can only
        // ever shrink and silently strands dirty chunks outside it.
        Set<ChunkPos> territoryChunks = this.territoryChunks;
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
                //
                // Critical fix: rescanned.get(0) is discarded here in the old code, and the
                // recompute below ran against oldRegion's STALE membership forever - any cell
                // that became newly walkable (a rat-built stair, a mined tunnel) got a real
                // Dijkstra instruction reaching it (the terrain snapshot IS fresh), but
                // FlowFieldState.isOutOfBounds kept rejecting it as "out of region bounds" via
                // oldRegion::contains, since oldRegion's own BitSet was never updated to include
                // it. Confirmed in testing: a mob built a real, walkable pillar, but that exact
                // position stayed "wilderness" three rebuild generations later - only a FULL
                // rebuild (which replaces Region objects wholesale) ever picked up growth, so a
                // region only advanced by roughly whatever a mob managed to build before the
                // next full rebuild happened to fire, not fluidly as construction progressed.
                //
                // Stamped with the OLD regionId (not the local rescan's own 0-based numbering)
                // so RegionRouteTree/RegionGraph/regionStates/regionFlowFields - all keyed by
                // this int - keep referring to the same region, just with membership that now
                // matches current terrain.
                Region freshRegion = rescanned.get(0).withId(regionId);
                List<Region> updatedRegions = new ArrayList<>(regionIndex.getRegions());
                updatedRegions.replaceAll(r -> r.getId() == regionId ? freshRegion : r);
                this.regionIndex = new RegionIndex(updatedRegions);

                FlowFieldState state = regionStates.get(regionId);
                if (state != null) {
                    // Swap in the fresh membership predicate in place - see FlowFieldState's
                    // cellFilter field doc for why this mutates the existing state rather than
                    // building a new one (which would also orphan this region's RegionFlowField,
                    // and with it its claim/lane-occupancy tables).
                    state.updateCellFilter(freshRegion::contains);

                    // Re-seed this region's connector instructions for the pass (see the matching
                    // call in rebuildRegionsAndGraph). Uses the CURRENT route tree - this is the
                    // steady-state single-region path, not a full rebuild, so no new tree exists.
                    RegionConnector parentConnector = routeTree != null ? routeTree.getParentConnector(regionId) : null;
                    // Same local-gap cap as rebuildRegionsAndGraph's matching call - see that
                    // call's doc and SiegeProjectManager.setMaxCandidateProjectLength's own doc.
                    projectManager.setMaxCandidateProjectLength(
                            parentConnector != null ? 6 : SiegeProjectManager.DEFAULT_MAX_CANDIDATE_PROJECT_LENGTH);
                    projectManager.setActiveConnectorProject(parentConnector != null ? parentConnector.projectFor(regionId) : null);
                    calculator.calculateFully(snapshot, state);
                }
                continue;
            }

            LOGGER.info("[Skavenblight] Region {} topology changed ({} sub-regions found) - full territory rebuild triggered", regionId, rescanned.size());
            // A genuine split/merge is rare and the region count for a typical base is small (see
            // RegionScanner's manual test notes) - falling back to a full rebuild here is simpler
            // and safer than hand-patching RegionGraph/RegionRouteTree, and still only runs when
            // topology actually changed, not on every terrain edit.
            // Re-root at the REAL nexus position this map was built for, not at the root region's
            // bounding-box corner (getMin()), which isn't the nexus and usually isn't even inside
            // that region - re-rooting there produced a route tree/flow field aimed at solid rock.
            BlockPos rootTarget = this.nexusPos != null ? this.nexusPos : oldRegion.getMin();
            rebuildRegionsAndGraph(territoryChunks, rootTarget);
            return;
        }
    }
}
