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

    /**
     * Three callers, deliberately: {@code RegionScanner.floodFill} (the initial partition scan),
     * {@code RegionGraph.registerConnector} (task-8), which claims a discovered connector's full
     * traced footprint into BOTH of its endpoint regions so a mob mid-crossing can never resolve
     * to no region at all, and {@code TerritoryRegionMap.reclaimConnectorCells} (task-9), which
     * re-applies that same connector-footprint claim onto a freshly-rescanned region so the
     * steady-state dirty-region path doesn't silently drop it (see that method's own doc for why
     * a plain flood-fill rescan alone can never rediscover a connector's cells on its own). The
     * latter two are the only places membership grows AFTER a region's initial scan without a full
     * rebuild or a dirty-region rescan replacing the Region object outright - safe in
     * {@code RegionGraph.registerConnector}'s case because {@code RegionGraph.build} (and every
     * {@code addCell} call it makes) runs to completion before any region's {@code FlowFieldState}
     * is constructed (see TerritoryRegionMap.rebuildRegionsAndGraph), so no calculation pass ever
     * sees a partially-grown BitSet.
     */
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
        // Total function over any BlockPos, not just ones within this region's own world-height
        // bounds - a caller (e.g. TerritoryRegionMap checking a changed block's orthogonal
        // neighbors) can hand this a Y outside [minBuildHeight, minBuildHeight + height), such
        // as one block below the world's minimum build height. cellIndex() has no bounds check
        // of its own, so without this guard an out-of-range Y produces a negative or
        // out-of-range index and BitSet.get() throws IndexOutOfBoundsException instead of just
        // correctly answering "no, this position isn't part of this region."
        int localY = pos.getY() - minBuildHeight;
        if (localY < 0 || localY >= height) {
            return false;
        }
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

    public int getMinBuildHeight() {
        return minBuildHeight;
    }

    public int getHeight() {
        return height;
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

    /**
     * Copies this region's cell/boundary/bounds data into a NEW Region stamped with
     * {@code newId} instead of this one's own id. Used by TerritoryRegionMap's steady-state
     * dirty-region recompute to swap in a freshly-rescanned region's membership while keeping
     * the STABLE external id every other structure (RegionRouteTree, RegionGraph's connectors,
     * the regionStates/regionFlowFields maps) keys off of - those all reference the id, not
     * object identity, so silently renumbering would break every one of them.
     */
    public Region withId(int newId) {
        Region copy = new Region(newId, this.minBuildHeight, this.height);
        for (Map.Entry<ChunkPos, BitSet> entry : this.chunkCells.entrySet()) {
            copy.chunkCells.put(entry.getKey(), (BitSet) entry.getValue().clone());
        }
        copy.boundaryCells.addAll(this.boundaryCells);
        copy.min = this.min;
        copy.max = this.max;
        return copy;
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
