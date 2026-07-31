package org.ratden.skavenblight.ai.pathing.region;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.*;

/**
 * O(1) "which region contains this position" lookup. Built once after a RegionScanner pass by
 * stamping a per-chunk regionId array, mirroring Region's own per-chunk BitSet layout - not by
 * scanning the region list (the previous implementation's actual behavior despite its own doc
 * comment claiming O(1); it never scaled past small region counts). Used both by mobs (find
 * current region) and by dirty-tracking (map a changed block to its owning region).
 */
public class RegionIndex {

    private final List<Region> regions;
    private final Map<ChunkPos, int[]> regionIdByCell = new HashMap<>();
    private final int minBuildHeight;
    private final int height;

    public RegionIndex(List<Region> regions) {
        this.regions = List.copyOf(regions);
        this.minBuildHeight = regions.isEmpty() ? 0 : regions.get(0).getMinBuildHeight();
        this.height = regions.isEmpty() ? 0 : regions.get(0).getHeight();

        for (Region region : regions) {
            for (Map.Entry<ChunkPos, BitSet> entry : region.getChunkCells().entrySet()) {
                int[] ids = regionIdByCell.computeIfAbsent(entry.getKey(), c -> {
                    int[] arr = new int[16 * 16 * height];
                    Arrays.fill(arr, -1);
                    return arr;
                });
                BitSet bits = entry.getValue();
                for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
                    ids[i] = region.getId();
                }
            }
        }
    }

    public Region regionAt(BlockPos pos) {
        Integer id = regionIdAt(pos);
        if (id == null) return null;
        for (Region region : regions) {
            if (region.getId() == id) return region;
        }
        return null;
    }

    public Integer regionIdAt(BlockPos pos) {
        int localY = pos.getY() - minBuildHeight;
        if (height == 0 || localY < 0 || localY >= height) return null;

        int[] ids = regionIdByCell.get(new ChunkPos(pos));
        if (ids == null) return null;

        int localX = pos.getX() & 15;
        int localZ = pos.getZ() & 15;
        int id = ids[(localX * 16 + localZ) * height + localY];
        return id == -1 ? null : id;
    }

    public List<Region> getRegions() {
        return regions;
    }

    /**
     * Re-stamps this index's flat per-chunk arrays for exactly {@code changedChunks}, using the
     * SAME "iterate {@link #regions} in list order, last matching region wins" semantics the
     * constructor above uses - not a per-cell overwrite in whatever order the caller happens to
     * process cells in, which would silently change which region wins a shared-cell tie-break
     * (see {@code RegionGraph.registerConnector}: both a connector's endpoint regions legitimately
     * claim the SAME cells, and {@code TerritoryRegionMap}'s parent/child connector-instruction
     * injection - see {@code injectSharedConnectorProjects} - was built assuming this index's
     * existing scan-order tie-break, not some other one).
     *
     * <p>Package-private: the one caller is {@code RegionGraph.build}, immediately after its own
     * {@code registerConnector} calls have finished mutating {@link Region#addCell} on this same
     * {@link #regions} list - see that method's doc for why this exists (avoiding a second full
     * {@code new RegionIndex(regions)} construction, which for a territory with many occupied
     * chunks is a real, avoidable per-chunk {@code int[16*16*height]} allocation repeated a second
     * time for chunks a connector never even touched).
     */
    void refreshChunks(Collection<ChunkPos> changedChunks) {
        if (height == 0 || changedChunks.isEmpty()) return;
        for (ChunkPos chunk : changedChunks) {
            int[] ids = regionIdByCell.computeIfAbsent(chunk, c -> new int[16 * 16 * height]);
            Arrays.fill(ids, -1);
            for (Region region : regions) {
                BitSet bits = region.getChunkCells().get(chunk);
                if (bits == null) continue;
                for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
                    ids[i] = region.getId();
                }
            }
        }
    }
}
