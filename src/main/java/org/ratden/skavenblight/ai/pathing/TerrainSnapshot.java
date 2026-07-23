package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * An immutable, point-in-time copy of the block data the flow field calculation needs -
 * BlockState plus the two level-dependent derived values TerrainEvaluator reads
 * (isSolidRender, destroySpeed) - for the full vertical column (bedrock to build height)
 * of every territory chunk.
 *
 * Captured entirely on the main server thread via {@link #refresh}. Once built, every
 * field here is immutable, so handing a TerrainSnapshot instance to the flow field's
 * background calculation thread is safe. Never call {@link #refresh} off the main thread.
 *
 * Deliberately NOT windowed to a band around the nexus - a nexus can legitimately sit at
 * bedrock or build height with the rest of the base far away vertically (a floating
 * platform needing a bridge down to the ground below, an underground nexus needing a
 * shaft up to daylight), and the macro-project search needs to be able to explore that
 * whole span to find and build the connection, not just react after something already
 * happens to be standing there. Territory chunks are already force-loaded full height via
 * chunk tickets regardless (see StandardFlowField), so this doesn't cost extra chunk
 * loading - only the per-column array size below.
 */
public final class TerrainSnapshot implements TerrainAccess {

    private final Map<ChunkPos, SnapshotChunkColumn> columns;
    private final int minBuildHeight;
    private final int maxBuildHeight;

    private TerrainSnapshot(Map<ChunkPos, SnapshotChunkColumn> columns, int minBuildHeight, int maxBuildHeight) {
        this.columns = columns;
        this.minBuildHeight = minBuildHeight;
        this.maxBuildHeight = maxBuildHeight;
    }

    public boolean hasColumn(ChunkPos pos) {
        return columns.containsKey(pos);
    }

    public int getCapturedChunkCount() {
        return columns.size();
    }

    public record RefreshResult(TerrainSnapshot snapshot, Set<ChunkPos> capturedChunks) {}

    /**
     * Builds a new snapshot, reusing unchanged columns from {@code previous} and only
     * (re)capturing columns in {@code dirtyChunks}, capped at {@code maxChunksToCapture}
     * per call. That cap bounds the worst-case main-thread cost of any single refresh no
     * matter how many chunks are dirty at once (e.g. after a large explosion) - anything
     * left over stays reported as "not captured" so the caller can retry it on a later
     * refresh.
     *
     * {@code minY}/{@code maxYExclusive} bound every (re)captured column, clamped to the
     * level's actual build height - StandardFlowField always passes the level's full
     * min/max build height (see the class doc above for why).
     *
     * Must be called from the server thread.
     */
    public static RefreshResult refresh(ServerLevel level, TerrainSnapshot previous, Set<ChunkPos> territoryChunks,
                                         Set<ChunkPos> dirtyChunks, int minY, int maxYExclusive, int maxChunksToCapture) {
        Map<ChunkPos, SnapshotChunkColumn> newColumns = new HashMap<>();
        Set<ChunkPos> captured = new HashSet<>();

        // Reuse anything we already have that isn't dirty.
        if (previous != null) {
            for (ChunkPos cp : territoryChunks) {
                if (!dirtyChunks.contains(cp) && previous.columns.containsKey(cp)) {
                    newColumns.put(cp, previous.columns.get(cp));
                }
            }
        }

        // Capture whatever's left (dirty or never-seen), bounded by the cap.
        int capturedCount = 0;
        for (ChunkPos cp : territoryChunks) {
            if (newColumns.containsKey(cp)) continue;
            if (capturedCount >= maxChunksToCapture) continue;
            newColumns.put(cp, captureColumn(level, cp, minY, maxYExclusive));
            captured.add(cp);
            capturedCount++;
        }

        return new RefreshResult(new TerrainSnapshot(newColumns, level.getMinBuildHeight(), level.getMaxBuildHeight()), captured);
    }

    private static SnapshotChunkColumn captureColumn(ServerLevel level, ChunkPos cp, int minYRequested, int maxYExclusiveRequested) {
        int minY = Math.max(level.getMinBuildHeight(), minYRequested);
        int maxYExclusive = Math.min(level.getMaxBuildHeight(), maxYExclusiveRequested);
        int ySpan = Math.max(0, maxYExclusive - minY);

        int size = 16 * 16 * ySpan;
        BlockState[] states = new BlockState[size];
        float[] destroySpeeds = new float[size];
        BitSet solidRender = new BitSet(size);

        int baseX = cp.getMinBlockX();
        int baseZ = cp.getMinBlockZ();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int ly = 0; ly < ySpan; ly++) {
            int y = minY + ly;
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    cursor.set(baseX + lx, y, baseZ + lz);
                    int idx = (ly * 16 + lz) * 16 + lx;

                    BlockState bs = level.getBlockState(cursor);
                    states[idx] = bs;
                    destroySpeeds[idx] = bs.getDestroySpeed(level, cursor);
                    if (bs.isSolidRender(level, cursor)) {
                        solidRender.set(idx);
                    }
                }
            }
        }

        return new SnapshotChunkColumn(minY, ySpan, states, destroySpeeds, solidRender);
    }

    // =================================================================================
    // TerrainAccess implementation - safe to call from the background calculation thread
    // =================================================================================

    @Override
    public BlockState getBlockState(BlockPos pos) {
        SnapshotChunkColumn column = columns.get(new ChunkPos(pos));
        BlockState state = column != null ? column.getBlockState(pos.getX() & 15, pos.getY(), pos.getZ() & 15) : null;
        return state != null ? state : Blocks.AIR.defaultBlockState();
    }

    @Override
    public boolean isLoaded(BlockPos pos) {
        SnapshotChunkColumn column = columns.get(new ChunkPos(pos));
        return column != null && column.containsY(pos.getY());
    }

    @Override
    public boolean isOutsideBuildHeight(BlockPos pos) {
        return pos.getY() < minBuildHeight || pos.getY() >= maxBuildHeight;
    }

    @Override
    public boolean isSolidRender(BlockPos pos) {
        SnapshotChunkColumn column = columns.get(new ChunkPos(pos));
        return column != null && column.isSolidRender(pos.getX() & 15, pos.getY(), pos.getZ() & 15);
    }

    @Override
    public float getDestroySpeed(BlockPos pos) {
        SnapshotChunkColumn column = columns.get(new ChunkPos(pos));
        return column != null ? column.getDestroySpeed(pos.getX() & 15, pos.getY(), pos.getZ() & 15) : -1F;
    }

    /**
     * Fixed-size, array-backed storage for one chunk's captured vertical band. Deliberately
     * NOT a Map<BlockPos, ...> - for a territory spanning hundreds of chunks, boxed
     * BlockPos keys and HashMap.Node overhead would dwarf the actual data. Flat arrays
     * indexed by local (x, y, z) are roughly an order of magnitude cheaper per block.
     */
    private static final class SnapshotChunkColumn {
        private final int minY;
        private final int ySpan;
        private final BlockState[] states;
        private final float[] destroySpeeds;
        private final BitSet solidRender;

        SnapshotChunkColumn(int minY, int ySpan, BlockState[] states, float[] destroySpeeds, BitSet solidRender) {
            this.minY = minY;
            this.ySpan = ySpan;
            this.states = states;
            this.destroySpeeds = destroySpeeds;
            this.solidRender = solidRender;
        }

        boolean containsY(int y) {
            return y >= minY && y < minY + ySpan;
        }

        private int index(int localX, int y, int localZ) {
            return ((y - minY) * 16 + localZ) * 16 + localX;
        }

        BlockState getBlockState(int localX, int y, int localZ) {
            return containsY(y) ? states[index(localX, y, localZ)] : null;
        }

        float getDestroySpeed(int localX, int y, int localZ) {
            return containsY(y) ? destroySpeeds[index(localX, y, localZ)] : -1F;
        }

        boolean isSolidRender(int localX, int y, int localZ) {
            return containsY(y) && solidRender.get(index(localX, y, localZ));
        }
    }
}
