package org.ratden.skavenblight.ai.pathing.region;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * One connectivity component of walkable cells within a territory - the unit the region
 * graph routes between. Cell membership is stored as one BitSet per chunk column (same
 * flat/compact style as TerrainSnapshot) rather than a raw Set<BlockPos>, since a region
 * covering a full build-height column across many chunks can hold a very large number of
 * cells.
 */
public class Region {

    private final int id;
    private final int minBuildHeight;
    private final int height;

    private final Map<ChunkPos, BitSet> chunkCells = new HashMap<>();
    private final Set<BlockPos> boundaryCells = new HashSet<>();

    private BlockPos min;
    private BlockPos max;

    public Region(int id, int minBuildHeight, int height) {
        this.id = id;
        this.minBuildHeight = minBuildHeight;
        this.height = height;
    }

    public void addCell(BlockPos pos) {
        ChunkPos chunk = new ChunkPos(pos);
        BitSet bits = chunkCells.computeIfAbsent(chunk, c -> new BitSet(16 * 16 * height));
        bits.set(cellIndex(pos));
        expandBounds(pos);
    }

    public void addBoundaryCell(BlockPos pos) {
        boundaryCells.add(pos.immutable());
    }

    public boolean contains(BlockPos pos) {
        BitSet bits = chunkCells.get(new ChunkPos(pos));
        return bits != null && bits.get(cellIndex(pos));
    }

    public Set<BlockPos> getBoundaryCells() {
        return java.util.Collections.unmodifiableSet(boundaryCells);
    }

    public Map<ChunkPos, BitSet> getChunkCells() {
        return java.util.Collections.unmodifiableMap(chunkCells);
    }

    public int getId() {
        return id;
    }

    public BlockPos getMin() {
        return min;
    }

    public BlockPos getMax() {
        return max;
    }

    public int cellCount() {
        return chunkCells.values().stream().mapToInt(BitSet::cardinality).sum();
    }

    private int cellIndex(BlockPos pos) {
        int localX = pos.getX() & 15;
        int localZ = pos.getZ() & 15;
        int localY = pos.getY() - minBuildHeight;
        return (localX * 16 + localZ) * height + localY;
    }

    private void expandBounds(BlockPos pos) {
        if (min == null) {
            min = pos.immutable();
            max = pos.immutable();
            return;
        }
        min = new BlockPos(Math.min(min.getX(), pos.getX()), Math.min(min.getY(), pos.getY()), Math.min(min.getZ(), pos.getZ()));
        max = new BlockPos(Math.max(max.getX(), pos.getX()), Math.max(max.getY(), pos.getY()), Math.max(max.getZ(), pos.getZ()));
    }
}
