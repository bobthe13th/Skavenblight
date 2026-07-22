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

    // --- Subsystems ---
    private final FlowFieldState state;
    private final TerrainEvaluator evaluator;
    private final SiegeProjectManager projectManager;
    private final FlowFieldCalculator calculator;

    // --- State Management ---
    private volatile boolean isDirty = true;
    private final AtomicBoolean isCalculatingAsync = new AtomicBoolean(false);
    private long lastCalculationStart = 0;
    private long lastBlockChangeTime = 0;
    private final Set<BlockPos> ignoredSkavenEdits = new HashSet<>();

    // --- Chunk Loading Ticket Tracker ---
    private final Set<ChunkPos> forcedChunks = new HashSet<>();

    public StandardFlowField(ServerLevel level, BlockPos targetPos, Set<ChunkPos> territoryChunks) {
        this.state = new FlowFieldState(targetPos, territoryChunks);
        this.evaluator = new TerrainEvaluator();
        this.projectManager = new SiegeProjectManager(this.evaluator);
        this.calculator = new FlowFieldCalculator(this.evaluator, this.projectManager);

        // Lock territory chunks in memory as soon as the Flow Field is initialized
        syncTerritoryChunkTickets(level, territoryChunks);
    }

    // =================================================================================
    // CHUNK TICKET MANAGEMENT
    // =================================================================================

    /**
     * Forces territory chunks to stay loaded in the world, and releases any chunks
     * no longer inside the active base bounds.
     */
    public void syncTerritoryChunkTickets(ServerLevel level, Set<ChunkPos> newTerritory) {
        if (level == null) return;

        // 1. Release chunks that are no longer part of the territory
        Iterator<ChunkPos> iterator = forcedChunks.iterator();
        while (iterator.hasNext()) {
            ChunkPos cp = iterator.next();
            if (newTerritory == null || !newTerritory.contains(cp)) {
                level.setChunkForced(cp.x, cp.z, false);
                iterator.remove();
                LOGGER.debug("[Skavenblight] Unforced territory chunk: {}", cp);
            }
        }

        // 2. Force load all new territory chunks
        if (newTerritory != null) {
            for (ChunkPos cp : newTerritory) {
                if (forcedChunks.add(cp)) {
                    level.setChunkForced(cp.x, cp.z, true);
                    LOGGER.debug("[Skavenblight] Forced territory chunk: {}", cp);
                }
            }
        }
    }

    /**
     * CRITICAL: Must be called when the base, Nexus, or FlowField is removed/destroyed
     * to prevent server memory leaks!
     */
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
            return; // Calculation already in progress! Do not launch another.
        }

        long currentTime = level.getGameTime();
        boolean terrainSettled = (System.currentTimeMillis() - this.lastBlockChangeTime) >= Config.minimumSettleDelayMs;
        boolean offCooldown = (currentTime - lastCalculationStart) >= 80;

        boolean shouldStart = state.isEmpty() || (this.isDirty && terrainSettled && offCooldown);

        if (shouldStart && this.isCalculatingAsync.compareAndSet(false, true)) {
            this.lastCalculationStart = currentTime;
            LOGGER.info("[Skavenblight] Async FlowField calculation STARTED for Target: {}", state.getTargetPos());

            CompletableFuture.runAsync(() -> {
                try {
                    this.calculator.calculateFully(level, this.state);
                } catch (Exception e) {
                    LOGGER.error("[Skavenblight] Async FlowField calculation crashed!", e);
                } finally {
                    this.isDirty = false;
                    this.isCalculatingAsync.set(false); // ALWAYS release in a finally block!
                }
            }, Util.backgroundExecutor()).thenAcceptAsync(v -> {
                LOGGER.info("[Skavenblight] Async FlowField calculation FINISHED! Total Nodes: {}", state.getInstructionMap().size());
            }, level.getServer());
        }
    }

    public SiegeNode getNextSiegeNode(ServerLevel level, BlockPos ratPos) {
        SiegeNode node = state.getInstruction(ratPos);
        if (node == null) return null;

        return evaluator.isActionCompleted(level, node) ?
                new SiegeNode(getOffsetPostAction(node), SiegeNode.SiegeAction.WALK) : node;
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
        SiegeNode.SiegeAction action = evaluator.determineMacroAction(level, stepPos, dy, dx, dz, state.getTargetPos());

        return new SiegeNode(stepPos, action);
    }

    private BlockPos getOffsetPostAction(SiegeNode node) {
        return node.action() == SiegeNode.SiegeAction.MINE ? node.pos() : node.pos().above();
    }
}