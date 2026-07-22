package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.*;

/**
 * Holds the finalized pathing data for a target. Purely structural.
 * Contains no calculation logic, only data retrieval and indexing.
 */
public class FlowFieldState {

    private final BlockPos targetPos;
    private final Set<ChunkPos> territoryChunks;

    private Map<BlockPos, SiegeNode> instructionMap = new HashMap<>();
    private final Set<ChunkPos> mappedChunks = new HashSet<>();
    private final Map<ChunkPos, List<BlockPos>> chunkToBlocksIndex = new HashMap<>();

    public FlowFieldState(BlockPos targetPos, Set<ChunkPos> territoryChunks) {
        this.targetPos = targetPos;
        this.territoryChunks = territoryChunks != null ? territoryChunks : Collections.emptySet();
    }

    // =================================================================================
    // PUBLIC API
    // =================================================================================

    /**
     * Replaces the active instruction map and rebuilds the internal chunk indices.
     * This should only be called by the FlowFieldCalculator when a calculation finishes.
     */
    public void updateInstructions(Map<BlockPos, SiegeNode> newMap) {
        this.instructionMap = newMap;
        rebuildChunkIndices();
    }

    public SiegeNode getInstruction(BlockPos pos) {
        return this.instructionMap.get(pos);
    }

    public BlockPos getTargetPos() {
        return this.targetPos;
    }

    public Map<BlockPos, SiegeNode> getInstructionMap() {
        return Collections.unmodifiableMap(this.instructionMap);
    }

    public boolean isEmpty() {
        return this.instructionMap.isEmpty();
    }

    // =================================================================================
    // BOUNDARY & CHUNK DATA
    // =================================================================================

    public boolean isOutOfBounds(BlockPos pos) {
        if (territoryChunks.isEmpty()) return false; // Global scope
        return !territoryChunks.contains(new ChunkPos(pos));
    }

    public boolean isChunkMapped(ChunkPos chunkPos) {
        return this.mappedChunks.contains(chunkPos);
    }

    public List<BlockPos> getMappedBlocksInChunk(ChunkPos chunkPos) {
        return this.chunkToBlocksIndex.getOrDefault(chunkPos, Collections.emptyList());
    }

    public Set<ChunkPos> getMappedChunks() {
        return Collections.unmodifiableSet(this.mappedChunks);
    }

    private void rebuildChunkIndices() {
        this.mappedChunks.clear();
        this.chunkToBlocksIndex.clear();

        for (BlockPos pos : this.instructionMap.keySet()) {
            ChunkPos cp = new ChunkPos(pos);
            this.mappedChunks.add(cp);
            this.chunkToBlocksIndex.computeIfAbsent(cp, k -> new ArrayList<>()).add(pos);
        }
    }
}