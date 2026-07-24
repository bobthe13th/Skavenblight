package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.*;

/**
 * Holds the finalized pathing data for a target. Purely structural.
 * Contains no calculation logic, only data retrieval and indexing.
 *
 * THREAD SAFETY: updateInstructions() is called from the flow field's background
 * calculation thread, while getInstruction() / isChunkMapped() / getMappedBlocksInChunk()
 * are read every tick from the main server thread (via the Goal classes). To keep that
 * safe without locks, the instruction map, mapped-chunk set, and chunk index are all
 * built together into one immutable Indexed object off-thread, then published with a
 * single volatile write. Readers only ever see either the old or the new Indexed
 * snapshot in full - never a partially-rebuilt one, which is what the previous
 * "clear() then repopulate in place" approach risked.
 */
public class FlowFieldState {

    private final BlockPos targetPos;
    private final Set<ChunkPos> territoryChunks;
    private final java.util.function.Predicate<BlockPos> cellFilter;

    private volatile Indexed indexed = Indexed.EMPTY;

    public FlowFieldState(BlockPos targetPos, Set<ChunkPos> territoryChunks) {
        this(targetPos, territoryChunks, null);
    }

    public FlowFieldState(BlockPos targetPos, Set<ChunkPos> territoryChunks, java.util.function.Predicate<BlockPos> cellFilter) {
        this.targetPos = targetPos;
        this.territoryChunks = territoryChunks != null ? territoryChunks : Collections.emptySet();
        this.cellFilter = cellFilter;
    }

    // =================================================================================
    // PUBLIC API
    // =================================================================================

    /**
     * Replaces the active instruction map and rebuilds the internal chunk indices.
     * This should only be called by the FlowFieldCalculator when a calculation finishes.
     * Safe to call from any single thread - the caller (FlowFieldCalculator, gated by
     * StandardFlowField's isCalculatingAsync flag) guarantees only one calculation, and
     * therefore only one caller of this method, runs at a time.
     */
    public void updateInstructions(Map<BlockPos, SiegeNode> newMap) {
        this.indexed = Indexed.build(newMap);
    }

    public SiegeNode getInstruction(BlockPos pos) {
        return this.indexed.instructionMap.get(pos);
    }

    public BlockPos getTargetPos() {
        return this.targetPos;
    }

    public Map<BlockPos, SiegeNode> getInstructionMap() {
        return this.indexed.instructionMap;
    }

    public boolean isEmpty() {
        return this.indexed.instructionMap.isEmpty();
    }

    // =================================================================================
    // BOUNDARY & CHUNK DATA
    // =================================================================================

    public boolean isOutOfBounds(BlockPos pos) {
        if (territoryChunks.isEmpty()) return false; // Global scope
        if (!territoryChunks.contains(new ChunkPos(pos))) return true;
        return cellFilter != null && !cellFilter.test(pos);
    }

    public boolean isChunkMapped(ChunkPos chunkPos) {
        return this.indexed.mappedChunks.contains(chunkPos);
    }

    public List<BlockPos> getMappedBlocksInChunk(ChunkPos chunkPos) {
        return this.indexed.chunkToBlocksIndex.getOrDefault(chunkPos, Collections.emptyList());
    }

    public Set<ChunkPos> getMappedChunks() {
        return this.indexed.mappedChunks;
    }

    /** Exposed so StandardFlowField can bound its terrain snapshot capture to the same chunks. */
    public Set<ChunkPos> getTerritoryChunks() {
        return Collections.unmodifiableSet(this.territoryChunks);
    }

    /**
     * Immutable, fully-built-before-publish snapshot of everything derived from the
     * instruction map. Building one of these never partially mutates shared state.
     */
    private static final class Indexed {
        static final Indexed EMPTY = new Indexed(Collections.emptyMap(), Collections.emptySet(), Collections.emptyMap());

        final Map<BlockPos, SiegeNode> instructionMap;
        final Set<ChunkPos> mappedChunks;
        final Map<ChunkPos, List<BlockPos>> chunkToBlocksIndex;

        private Indexed(Map<BlockPos, SiegeNode> instructionMap, Set<ChunkPos> mappedChunks, Map<ChunkPos, List<BlockPos>> chunkToBlocksIndex) {
            this.instructionMap = instructionMap;
            this.mappedChunks = mappedChunks;
            this.chunkToBlocksIndex = chunkToBlocksIndex;
        }

        static Indexed build(Map<BlockPos, SiegeNode> newMap) {
            Map<BlockPos, SiegeNode> instructionMap = Map.copyOf(newMap);
            Set<ChunkPos> mappedChunks = new HashSet<>();
            Map<ChunkPos, List<BlockPos>> chunkToBlocksIndex = new HashMap<>();

            for (BlockPos pos : instructionMap.keySet()) {
                ChunkPos cp = new ChunkPos(pos);
                mappedChunks.add(cp);
                chunkToBlocksIndex.computeIfAbsent(cp, k -> new ArrayList<>()).add(pos);
            }

            Map<ChunkPos, List<BlockPos>> frozenIndex = new HashMap<>();
            chunkToBlocksIndex.forEach((cp, list) -> frozenIndex.put(cp, List.copyOf(list)));

            return new Indexed(instructionMap, Collections.unmodifiableSet(mappedChunks), Collections.unmodifiableMap(frozenIndex));
        }
    }
}


