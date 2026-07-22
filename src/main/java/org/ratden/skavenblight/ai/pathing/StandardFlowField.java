package org.ratden.skavenblight.ai.pathing;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.Config;
import org.slf4j.Logger;

import java.util.*;

/**
 * The Facade for the FlowField pathfinding system.
 * Orchestrates the modular subsystems and provides a clean API for AI Goals.
 */
public class StandardFlowField {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int[][] CARDINAL_OFFSETS = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };

    // --- Subsystems ---
    private final FlowFieldState state;
    private final TerrainEvaluator evaluator;
    private final SiegeProjectManager projectManager;
    private final FlowFieldCalculator calculator;
    private final CalculationThrottler throttler;

    // --- State Management ---
    private boolean isDirty = true;
    private long lastCalculationStart = 0;
    private long lastBlockChangeTime = 0;
    private final Set<BlockPos> ignoredSkavenEdits = new HashSet<>();

    public StandardFlowField(BlockPos targetPos, Set<ChunkPos> territoryChunks) {
        this.state = new FlowFieldState(targetPos, territoryChunks);
        this.evaluator = new TerrainEvaluator();
        this.projectManager = new SiegeProjectManager(this.evaluator);
        this.calculator = new FlowFieldCalculator(this.evaluator, this.projectManager);
        this.throttler = new CalculationThrottler();
    }

    // =================================================================================
    // PUBLIC API FOR AI GOALS & EVENTS
    // =================================================================================

    public void ignoreNextBlockChangeAt(BlockPos pos) {
        this.ignoredSkavenEdits.add(pos.immutable());
    }

    public void onBlockChanged(BlockPos pos) {
        BlockPos immutablePos = pos.immutable();

        // ADD THIS: Check if a Skaven just edited this block.
        // If remove() returns true, it was on the list, so we abort!
        if (this.ignoredSkavenEdits.remove(immutablePos)) {
            return;
        }

        // Only care about blocks inside our mapped territory
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
        return this.calculator.isCalculating();
    }

    public void calculateMapIfNeeded(ServerLevel level) {
        // 1. Tick the throttler to monitor server MSPT[cite: 22]
        this.throttler.tick(level.getServer());

        long currentTime = level.getGameTime();
        boolean terrainSettled = (System.currentTimeMillis() - this.lastBlockChangeTime) >= Config.minimumSettleDelayMs;
        boolean offCooldown = (currentTime - lastCalculationStart) >= 80; // Configurable refresh rate[cite: 15]

        // 2. Decide if we need to kick off a new calculation thread
        boolean shouldStart = state.isEmpty() || (this.isDirty && terrainSettled && offCooldown);

        if (this.calculator.isCalculating() || shouldStart) {
            if (!this.calculator.isCalculating()) {
                this.isDirty = false;
                this.lastCalculationStart = currentTime;
                LOGGER.info("[Skavenblight] FlowField calculation STARTED! Target: {}", state.getTargetPos());
            }

            // 3. Let the calculator process a slice of the queue based on the throttler's allowance[cite: 21, 22]
            this.calculator.calculateMapIfNeeded(level, this.state, this.throttler.getNodesPerTick());

            if (!this.calculator.isCalculating()) {
                LOGGER.info("[Skavenblight] FlowField calculation FINISHED! Total Nodes: {}", state.getInstructionMap().size());
            }
        }
    }

    public SiegeNode getNextSiegeNode(ServerLevel level, BlockPos ratPos) {
        SiegeNode node = state.getInstruction(ratPos);
        if (node == null) return null;

        // If the action at this location is already completed, convert it to a standard WALK action[cite: 15, 16]
        return evaluator.isActionCompleted(level, node) ?
                new SiegeNode(getOffsetPostAction(node), SiegeNode.SiegeAction.WALK) : node;
    }

    public SiegeNode getDynamicWildernessNode(ServerLevel level, BlockPos ratPos) {
        if (state.isEmpty()) return new SiegeNode(state.getTargetPos(), SiegeNode.SiegeAction.WALK);

        BlockPos headingTarget = determineWildernessHeading(ratPos);
        return calculateWildernessStep(level, ratPos, headingTarget);
    }

    // =================================================================================
    // GETTERS (Delegated to State)
    // =================================================================================

    public Map<BlockPos, SiegeNode> getInstructionMap() { return state.getInstructionMap(); }
    public BlockPos getTargetPos() { return state.getTargetPos(); }
    public Set<ChunkPos> getMappedChunks() { return state.getMappedChunks(); }

    // =================================================================================
    // WILDERNESS ROUTING & HELPERS
    // =================================================================================

    private BlockPos determineWildernessHeading(BlockPos ratPos) {
        ChunkPos ratChunk = new ChunkPos(ratPos);

        if (state.isChunkMapped(ratChunk)) {
            List<BlockPos> blocks = state.getMappedBlocksInChunk(ratChunk);
            if (!blocks.isEmpty()) {
                return blocks.stream().min(Comparator.comparingDouble(p -> p.distSqr(ratPos))).orElse(state.getTargetPos());
            }
        } else {
            ChunkPos nextChunk = findMacroNavMeshRoute(ratChunk);
            if (nextChunk != null) return new BlockPos((nextChunk.x << 4) + 8, ratPos.getY(), (nextChunk.z << 4) + 8);
        }
        return state.getTargetPos();
    }

    private SiegeNode calculateWildernessStep(ServerLevel level, BlockPos ratPos, BlockPos heading) {
        int dx = Integer.compare(heading.getX(), ratPos.getX());
        int dy = Integer.compare(heading.getY(), ratPos.getY());
        int dz = Integer.compare(heading.getZ(), ratPos.getZ());

        if (dx == 0 && dy == 0 && dz == 0) return new SiegeNode(ratPos, SiegeNode.SiegeAction.WALK);
        return evaluator.determineMacroAction(level, ratPos.offset(dx, dy, dz), dy, dx, dz);
    }

    private ChunkPos findMacroNavMeshRoute(ChunkPos startChunk) {
        if (state.getMappedChunks().isEmpty()) return null;

        Queue<ChunkPos> queue = new LinkedList<>();
        Map<ChunkPos, ChunkPos> cameFrom = new HashMap<>();
        queue.add(startChunk);
        cameFrom.put(startChunk, null);

        while (!queue.isEmpty()) {
            ChunkPos current = queue.poll();
            if (state.isChunkMapped(current)) {
                ChunkPos curr = current;
                while (cameFrom.get(curr) != null && !cameFrom.get(curr).equals(startChunk)) {
                    curr = cameFrom.get(curr);
                }
                return curr;
            }

            for (int[] offset : CARDINAL_OFFSETS) {
                ChunkPos next = new ChunkPos(current.x + offset[0], current.z + offset[1]);
                // Validate against the territory bounds handled by the State class[cite: 20]
                if (!state.isOutOfBounds(new BlockPos(next.x << 4, 0, next.z << 4)) && !cameFrom.containsKey(next)) {
                    cameFrom.put(next, current);
                    queue.add(next);
                }
            }
        }
        return null;
    }

    private BlockPos getOffsetPostAction(SiegeNode node) {
        return node.action() == SiegeNode.SiegeAction.MINE ? node.pos() : node.pos().above();
    }
}