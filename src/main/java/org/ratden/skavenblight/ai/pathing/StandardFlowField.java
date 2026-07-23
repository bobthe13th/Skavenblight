package org.ratden.skavenblight.ai.pathing;

import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.Config;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public class StandardFlowField {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Upper bound on how many territory chunks a single snapshot refresh will (re)capture.
    // Protects the main thread from a big spike even if a huge number of chunks go dirty
    // at once (e.g. a large explosion, or the very first calculation for a brand-new,
    // enormous territory). Anything left over stays queued and gets picked up on a later
    // refresh, so a huge base "ramps up" over a few calculation cycles instead of costing
    // one huge synchronous hitch.
    //
    // TerrainSnapshot captures the full build-height column per chunk (~384 rows) rather
    // than a narrow band (~65 rows, the original assumption behind this constant) - about
    // 6x more main-thread work per captured chunk. Scaled down from 64 accordingly so one
    // refresh call costs roughly what it used to; this is what caused a 2+ second main
    // thread hitch the instant the very first calculation for a new territory started.
    private static final int MAX_CHUNKS_PER_SNAPSHOT_REFRESH = 10;

    // Fallback capture radius (in chunks) for flow fields with no bounded territory
    // (empty territoryChunks = "global scope"). We can't snapshot "the whole world", so
    // global-scope flow fields only get a bounded area around the target captured.
    private static final int GLOBAL_SCOPE_SNAPSHOT_CHUNK_RADIUS = 8;

    // --- Subsystems ---
    private final FlowFieldState state;
    private final TerrainEvaluator evaluator;
    private final CalculationThrottler throttler;
    private final SiegeProjectManager projectManager;
    private final FlowFieldCalculator calculator;

    // --- State Management ---
    private volatile boolean isDirty = true;
    private final AtomicBoolean isCalculatingAsync = new AtomicBoolean(false);
    private long lastCalculationStart = 0;
    private long lastBlockChangeTime = 0;
    private final Set<BlockPos> ignoredSkavenEdits = new HashSet<>();

    // --- Terrain Snapshot (built/refreshed on the main thread only; the resulting
    // TerrainSnapshot instance itself is immutable and safe to hand to the background
    // calculation thread) ---
    private TerrainSnapshot terrainSnapshot = null;
    private final Set<ChunkPos> dirtySnapshotChunks = new HashSet<>();

    // --- Chunk Loading Ticket Tracker ---
    private final Set<ChunkPos> forcedChunks = new HashSet<>();

    // --- Construction Target Claims (main-thread only, same as everything else Goal-facing
    // here - vanilla's GoalSelector.tick() runs canUse()+start() for one mob fully before
    // moving to the next, all on the main thread, so no synchronization is needed) ---
    // Without this, every mob near a bottleneck independently computes the same "next"
    // instruction and all converge on the identical target block simultaneously - multiple
    // mobs dog-piling one spot instead of spreading across the available frontier work.
    private final Set<BlockPos> claimedTargets = new HashSet<>();

    public StandardFlowField(ServerLevel level, BlockPos targetPos, Set<ChunkPos> territoryChunks) {
        this.state = new FlowFieldState(targetPos, territoryChunks);
        this.evaluator = new TerrainEvaluator();
        this.throttler = new CalculationThrottler();
        this.projectManager = new SiegeProjectManager(this.evaluator);
        this.calculator = new FlowFieldCalculator(this.evaluator, this.projectManager, this.throttler);

        syncTerritoryChunkTickets(level, territoryChunks);
    }

    public CalculationThrottler getThrottler() {
        return this.throttler;
    }

    // =================================================================================
    // CHUNK TICKET MANAGEMENT
    // =================================================================================

    public void syncTerritoryChunkTickets(ServerLevel level, Set<ChunkPos> newTerritory) {
        if (level == null) return;

        Iterator<ChunkPos> iterator = forcedChunks.iterator();
        while (iterator.hasNext()) {
            ChunkPos cp = iterator.next();
            if (newTerritory == null || !newTerritory.contains(cp)) {
                level.setChunkForced(cp.x, cp.z, false);
                iterator.remove();
                LOGGER.debug("[Skavenblight] Unforced territory chunk: {}", cp);
            }
        }

        if (newTerritory != null) {
            for (ChunkPos cp : newTerritory) {
                if (forcedChunks.add(cp)) {
                    level.setChunkForced(cp.x, cp.z, true);
                    LOGGER.debug("[Skavenblight] Forced territory chunk: {}", cp);
                }
            }
        }
    }

    public void cleanup(ServerLevel level) {
        if (level == null) return;

        for (ChunkPos cp : forcedChunks) {
            level.setChunkForced(cp.x, cp.z, false);
        }
        forcedChunks.clear();
        LOGGER.info("[Skavenblight] Released all forced territory chunks for target at: {}", state.getTargetPos());
    }

    // =================================================================================
    // PUBLIC API FOR AI GOALS & EVENTS
    // =================================================================================

    public void ignoreNextBlockChangeAt(BlockPos pos) {
        this.ignoredSkavenEdits.add(pos.immutable());
    }

    public void onBlockChanged(BlockPos pos) {
        BlockPos immutablePos = pos.immutable();

        if (this.ignoredSkavenEdits.remove(immutablePos)) {
            return;
        }

        if (!state.isOutOfBounds(immutablePos)) {
            this.isDirty = true;
            this.lastBlockChangeTime = System.currentTimeMillis();
            this.dirtySnapshotChunks.add(new ChunkPos(immutablePos));
        }
    }

    public void forceRecalculation() {
        this.isDirty = true;
        this.lastCalculationStart = 0;
    }

    public boolean isCalculating() {
        return this.isCalculatingAsync.get();
    }

    public void calculateMapIfNeeded(ServerLevel level) {
        if (this.isCalculatingAsync.get()) {
            return;
        }

        // Keep server performance metrics fresh prior to pathing run
        this.throttler.tick(level.getServer());

        long currentTime = level.getGameTime();
        boolean terrainSettled = (System.currentTimeMillis() - this.lastBlockChangeTime) >= Config.minimumSettleDelayMs;
        boolean offCooldown = (currentTime - lastCalculationStart) >= 80;

        boolean shouldStart = state.isEmpty() || (this.isDirty && terrainSettled && offCooldown);

        if (shouldStart && this.isCalculatingAsync.compareAndSet(false, true)) {
            this.lastCalculationStart = currentTime;

            // Refresh the terrain snapshot HERE, synchronously, on the main thread. This is
            // the only place the async calculation is allowed to get its world data from -
            // it never touches the live ServerLevel itself. Only dirty/never-captured
            // chunks are actually re-walked (bounded by MAX_CHUNKS_PER_SNAPSHOT_REFRESH),
            // so steady-state refreshes stay cheap regardless of total territory size.
            TerrainSnapshot snapshotForThisRun = refreshTerrainSnapshot(level);

            // If the territory is bigger than one refresh's chunk cap, dirtySnapshotChunks
            // still has leftovers after refreshTerrainSnapshot() - i.e. this snapshot is a
            // partial/incomplete view of the territory. Without this, isDirty gets reset to
            // false below regardless, and since shouldStart above only re-fires on isDirty
            // (or an empty map - true just this once), the ramp-up silently stops forever
            // after this one pass instead of continuing until the whole territory is
            // captured, leaving large chunks of the territory permanently unmapped until an
            // unrelated block edit happens to nudge isDirty back on.
            boolean snapshotIncomplete = !this.dirtySnapshotChunks.isEmpty();

            LOGGER.info("[Skavenblight] Async FlowField calculation STARTED for Target: {}", state.getTargetPos());

            CompletableFuture.runAsync(() -> {
                try {
                    this.calculator.calculateFully(snapshotForThisRun, this.state);
                } catch (Exception e) {
                    LOGGER.error("[Skavenblight] Async FlowField calculation crashed!", e);
                } finally {
                    this.isDirty = snapshotIncomplete;
                    this.isCalculatingAsync.set(false);
                }
            }, Util.backgroundExecutor()).thenAcceptAsync(v -> {
                LOGGER.info("[Skavenblight] Async FlowField calculation FINISHED! Total Nodes: {}", state.getInstructionMap().size());
            }, level.getServer());
        }
    }

    /** Must only be called from the main server thread. */
    private TerrainSnapshot refreshTerrainSnapshot(ServerLevel level) {
        Set<ChunkPos> territoryChunks = state.getTerritoryChunks();
        Set<ChunkPos> captureBounds = territoryChunks.isEmpty()
                ? chunkRadiusAround(state.getTargetPos(), GLOBAL_SCOPE_SNAPSHOT_CHUNK_RADIUS)
                : territoryChunks;

        // Drop bookkeeping for chunks that are no longer relevant (e.g. territory shrank).
        this.dirtySnapshotChunks.retainAll(captureBounds);

        // Anything we've never captured needs to join the dirty set so it keeps getting
        // retried on subsequent refreshes until the per-call cap catches up with it.
        for (ChunkPos cp : captureBounds) {
            if (this.terrainSnapshot == null || !this.terrainSnapshot.hasColumn(cp)) {
                this.dirtySnapshotChunks.add(cp);
            }
        }

        TerrainSnapshot.RefreshResult result = TerrainSnapshot.refresh(
                level, this.terrainSnapshot, captureBounds, this.dirtySnapshotChunks,
                level.getMinBuildHeight(), level.getMaxBuildHeight(), MAX_CHUNKS_PER_SNAPSHOT_REFRESH);

        this.dirtySnapshotChunks.removeAll(result.capturedChunks());
        this.terrainSnapshot = result.snapshot();
        return this.terrainSnapshot;
    }

    private static Set<ChunkPos> chunkRadiusAround(BlockPos center, int radiusChunks) {
        ChunkPos centerChunk = new ChunkPos(center);
        Set<ChunkPos> result = new HashSet<>();
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                result.add(new ChunkPos(centerChunk.x + dx, centerChunk.z + dz));
            }
        }
        return result;
    }

    public SiegeNode getNextSiegeNode(ServerLevel level, BlockPos ratPos) {
        SiegeNode node = state.getInstruction(ratPos);
        if (node == null) return null;

        LiveTerrainAccess live = new LiveTerrainAccess(level);
        return evaluator.isActionCompleted(live, node) ?
                new SiegeNode(getOffsetPostAction(node), SiegeNode.SiegeAction.WALK) : node;
    }

    /** True and claims {@code pos} if it wasn't already claimed by another mob's construction goal. */
    public boolean tryClaimTarget(BlockPos pos) {
        return this.claimedTargets.add(pos.immutable());
    }

    public void releaseTarget(BlockPos pos) {
        if (pos != null) this.claimedTargets.remove(pos);
    }

    public boolean isTargetClaimed(BlockPos pos) {
        return this.claimedTargets.contains(pos);
    }

    public SiegeNode getDynamicWildernessNode(ServerLevel level, BlockPos ratPos) {
        if (state.isEmpty()) return new SiegeNode(state.getTargetPos(), SiegeNode.SiegeAction.WALK);

        BlockPos headingTarget = determineWildernessHeading(ratPos);
        return calculateWildernessStep(level, ratPos, headingTarget);
    }

    // =================================================================================
    // GETTERS & HELPERS
    // =================================================================================
    public Map<BlockPos, SiegeNode> getLiveDebugMap() {
        return this.calculator.getLiveDebugMap();
    }

    public Map<BlockPos, SiegeNode> getInstructionMap() { return state.getInstructionMap(); }
    public BlockPos getTargetPos() { return state.getTargetPos(); }
    public Set<ChunkPos> getMappedChunks() { return state.getMappedChunks(); }
    public Set<ChunkPos> getForcedChunks() { return Collections.unmodifiableSet(this.forcedChunks); }
    public SiegeProjectManager getProjectManager() { return this.projectManager; }
    public int getTerritoryChunkCount() { return state.getTerritoryChunks().size(); }
    public int getCapturedChunkCount() { return this.terrainSnapshot != null ? this.terrainSnapshot.getCapturedChunkCount() : 0; }
    /** Game-time tick the current (or most recent) calculation started at - see level.getGameTime(). */
    public long getLastCalculationStartGameTime() { return this.lastCalculationStart; }

    private BlockPos determineWildernessHeading(BlockPos ratPos) {
        ChunkPos ratChunk = new ChunkPos(ratPos);

        if (state.isChunkMapped(ratChunk)) {
            List<BlockPos> blocks = state.getMappedBlocksInChunk(ratChunk);
            if (!blocks.isEmpty()) {
                return blocks.stream().min(Comparator.comparingDouble(p -> p.distSqr(ratPos))).orElse(state.getTargetPos());
            }
        }
        return state.getTargetPos();
    }

    private SiegeNode calculateWildernessStep(ServerLevel level, BlockPos ratPos, BlockPos heading) {
        int dx = Integer.compare(heading.getX(), ratPos.getX());
        int dy = Integer.compare(heading.getY(), ratPos.getY());
        int dz = Integer.compare(heading.getZ(), ratPos.getZ());

        if (dx == 0 && dy == 0 && dz == 0) return new SiegeNode(ratPos, SiegeNode.SiegeAction.WALK);

        BlockPos stepPos = ratPos.offset(dx, dy, dz);
        SiegeNode.SiegeAction action = evaluator.determineMacroAction(new LiveTerrainAccess(level), stepPos, dy, dx, dz, state.getTargetPos());

        return new SiegeNode(stepPos, action);
    }

    private BlockPos getOffsetPostAction(SiegeNode node) {
        return node.action() == SiegeNode.SiegeAction.MINE ? node.pos() : node.pos().above();
    }
}
