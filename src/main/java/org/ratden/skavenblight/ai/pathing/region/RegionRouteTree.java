package org.ratden.skavenblight.ai.pathing.region;

import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Dijkstra over a RegionGraph, rooted at the region containing the current target (usually the
 * nexus). For every reachable region, records which connector to use and which neighboring
 * region it leads toward - the "main path" routing skeleton. A connector is treated as an
 * undirected edge: once built it's traversable both ways, even though it was discovered by
 * tracing outward from one specific region's boundary.
 */
public class RegionRouteTree {

    private final int rootRegionId;
    private final Map<Integer, RegionConnector> parentConnector = new HashMap<>();
    private final Map<Integer, Integer> parentRegion = new HashMap<>();
    private final Map<Integer, Integer> hopCost = new HashMap<>();

    private RegionRouteTree(int rootRegionId) {
        this.rootRegionId = rootRegionId;
    }

    public static RegionRouteTree compute(RegionGraph graph, int rootRegionId) {
        RegionRouteTree tree = new RegionRouteTree(rootRegionId);
        tree.hopCost.put(rootRegionId, 0);

        PriorityQueue<int[]> queue = new PriorityQueue<>((a, b) -> Integer.compare(a[1], b[1])); // [regionId, cost]
        queue.add(new int[]{rootRegionId, 0});

        while (!queue.isEmpty()) {
            int[] entry = queue.poll();
            int regionId = entry[0];
            int cost = entry[1];

            if (cost > tree.hopCost.getOrDefault(regionId, Integer.MAX_VALUE)) continue;

            for (RegionConnector connector : graph.getConnectorsFor(regionId)) {
                int neighbor = connector.other(regionId);
                int candidateCost = cost + connector.cost();

                if (candidateCost < tree.hopCost.getOrDefault(neighbor, Integer.MAX_VALUE)) {
                    tree.hopCost.put(neighbor, candidateCost);
                    tree.parentRegion.put(neighbor, regionId);
                    tree.parentConnector.put(neighbor, connector);
                    queue.add(new int[]{neighbor, candidateCost});
                }
            }
        }

        return tree;
    }

    public boolean isReachable(int regionId) {
        return regionId == rootRegionId || hopCost.containsKey(regionId);
    }

    public RegionConnector getParentConnector(int regionId) {
        return parentConnector.get(regionId);
    }

    public Integer getParentRegion(int regionId) {
        return parentRegion.get(regionId);
    }

    public int getHopCost(int regionId) {
        return hopCost.getOrDefault(regionId, Integer.MAX_VALUE);
    }

    public int getRootRegionId() {
        return rootRegionId;
    }
}
