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
 *
 * <p>It carries TWO projects for the one physical line, because a traced line's instructions are
 * direction-locked while RegionGraph/RegionRouteTree treat this edge as undirected. {@code regionA}
 * is the region whose boundary cell ({@code entryInA}) the trace started from; {@code regionB} is
 * wherever that trace landed ({@code entryInB}). Either can end up as the route tree's CHILD - the
 * side whose mobs have to cross - and which one it is, is effectively arbitrary (it depends on which
 * boundary cell's trace happened to win for that pair). {@link #projectFor(int)} hands out the
 * orientation that actually leads away from the child and toward the parent; never grab a raw
 * project field to inject into a region's calculation.
 */
public record RegionConnector(int regionA, int regionB, BlockPos entryInA, BlockPos entryInB, int cost,
                               SiegeProject projectTowardA, SiegeProject projectTowardB) {

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

    /**
     * The project to inject when {@code childRegionId} is the side that has to cross this connector
     * (i.e. this connector is that region's parent hop in the route tree). Mobs there flow to
     * {@code entryFor(childRegionId)}, so what they need is the orientation whose instructions lead
     * OUT of that entry and across the line - which is the other region's orientation.
     */
    public SiegeProject projectFor(int childRegionId) {
        if (childRegionId == regionA) return projectTowardB;
        if (childRegionId == regionB) return projectTowardA;
        throw new IllegalArgumentException("Region " + childRegionId + " is not part of this connector");
    }

    /**
     * Both orientations describe the same physical work (the same line's blocks, paired one position
     * apart), so either one answers "is this connector built yet"; this uses the anchor-ward map so
     * the answer is unchanged from before the second orientation existed.
     */
    public boolean isCompleted(TerrainAccess terrain, TerrainEvaluator evaluator) {
        return projectTowardA.isCompleted(terrain, evaluator);
    }
}
