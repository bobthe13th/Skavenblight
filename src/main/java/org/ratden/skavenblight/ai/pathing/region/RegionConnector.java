package org.ratden.skavenblight.ai.pathing.region;

import net.minecraft.core.BlockPos;
import org.ratden.skavenblight.ai.pathing.SiegeProject;
import org.ratden.skavenblight.ai.pathing.TerrainAccess;
import org.ratden.skavenblight.ai.pathing.TerrainEvaluator;

/**
 * One edge in the region graph: a candidate or active SiegeProject connecting two regions.
 * Wraps a plain SiegeProject for construction-progress tracking (isCompleted/getRemainingInstructions)
 * rather than reimplementing it - a connector IS a SiegeProject, just one chosen deliberately by
 * the route tree instead of discovered reactively mid-flood.
 */
public record RegionConnector(int regionA, int regionB, BlockPos entryInA, BlockPos entryInB, int cost, SiegeProject project) {

    public int other(int regionId) {
        if (regionId == regionA) return regionB;
        if (regionId == regionB) return regionA;
        throw new IllegalArgumentException("Region " + regionId + " is not part of this connector");
    }

    public BlockPos entryFor(int regionId) {
        if (regionId == regionA) return entryInA;
        if (regionId == regionB) return entryInB;
        throw new IllegalArgumentException("Region " + regionId + " is not part of this connector");
    }

    public boolean isCompleted(TerrainAccess terrain, TerrainEvaluator evaluator) {
        return project.isCompleted(terrain, evaluator);
    }
}
