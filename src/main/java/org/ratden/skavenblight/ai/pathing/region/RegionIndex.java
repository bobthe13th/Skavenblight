package org.ratden.skavenblight.ai.pathing.region;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * O(1) "which region contains this position" lookup, built once after a RegionScanner pass.
 * Used both by mobs (find current region) and by dirty-tracking (map a changed block to its
 * owning region).
 */
public class RegionIndex {

    private final List<Region> regions;

    public RegionIndex(List<Region> regions) {
        this.regions = List.copyOf(regions);
    }

    public Region regionAt(BlockPos pos) {
        for (Region region : regions) {
            if (region.contains(pos)) {
                return region;
            }
        }
        return null;
    }

    public Integer regionIdAt(BlockPos pos) {
        Region region = regionAt(pos);
        return region != null ? region.getId() : null;
    }

    public List<Region> getRegions() {
        return regions;
    }
}
