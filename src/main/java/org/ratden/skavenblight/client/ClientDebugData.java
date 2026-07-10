package org.ratden.skavenblight.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.ai.pathing.SiegeNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ClientDebugData {
    public static final Set<ChunkPos> territoryChunks = new HashSet<>();
    public static final Map<BlockPos, SiegeNode> flowFieldNodes = new HashMap<>();

    // --- NEW: Stores the heat map traffic weights ---
    public static final Map<BlockPos, Integer> trafficMap = new HashMap<>();
    public static int maxTraffic = 1;

    public static void update(Set<ChunkPos> chunks, Map<BlockPos, SiegeNode> nodes) {
        territoryChunks.clear();
        territoryChunks.addAll(chunks);

        flowFieldNodes.clear();
        flowFieldNodes.putAll(nodes);

        calculateTrafficMap();
    }

    private static void calculateTrafficMap() {
        trafficMap.clear();
        maxTraffic = 1;

        // Trace backwards from every block to count how many paths flow through it
        for (BlockPos startPos : flowFieldNodes.keySet()) {
            BlockPos current = startPos;
            int safety = 0; // Prevent infinite loops just in case

            while (current != null && flowFieldNodes.containsKey(current) && safety < 1000) {
                int currentTraffic = trafficMap.getOrDefault(current, 0) + 1;
                trafficMap.put(current, currentTraffic);

                if (currentTraffic > maxTraffic) {
                    maxTraffic = currentTraffic;
                }

                BlockPos next = flowFieldNodes.get(current).pos();
                if (next.equals(current)) break; // We reached the Nexus
                current = next;
                safety++;
            }
        }
    }

    public static void clear() {
        territoryChunks.clear();
        flowFieldNodes.clear();
        trafficMap.clear();
        maxTraffic = 1;
    }
}