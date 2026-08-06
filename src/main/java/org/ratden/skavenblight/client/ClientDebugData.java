package org.ratden.skavenblight.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.ai.pathing.FlowStep;

import java.util.*;

public class ClientDebugData {
    public static final Set<ChunkPos> territoryChunks = new HashSet<>();
    public static final Map<BlockPos, FlowStep> flowFieldNodes = new HashMap<>();

    // --- NEW: Macro NavMesh Data ---
    public static final Set<ChunkPos> mappedChunks = new HashSet<>();
    public static final Map<ChunkPos, ChunkPos> macroArrows = new HashMap<>();
    public static int currentMode = 0;

    public static final Map<BlockPos, Integer> trafficMap = new HashMap<>();
    public static int maxTraffic = 1;

    // Updated update method signature
    public static void update(Set<ChunkPos> chunks, Map<BlockPos, FlowStep> nodes, Set<ChunkPos> mapped, int mode) {
        territoryChunks.clear();
        territoryChunks.addAll(chunks);

        flowFieldNodes.clear();
        flowFieldNodes.putAll(nodes);

        mappedChunks.clear();
        mappedChunks.addAll(mapped);

        currentMode = mode;

        if (currentMode == 0) {
            calculateTrafficMap();
        } else if (currentMode == 1) {
            calculateMacroNavMesh();
        } else if (currentMode == 2) {
            trafficMap.clear(); // Wilderness mode doesn't track traffic
        }
    }

    public static void calculateTrafficMap() {
        trafficMap.clear();
        maxTraffic = 1;
        for (BlockPos startPos : flowFieldNodes.keySet()) {
            BlockPos current = startPos;
            int safety = 0;
            while (current != null && flowFieldNodes.containsKey(current) && safety < 1000) {
                int currentTraffic = trafficMap.getOrDefault(current, 0) + 1;
                trafficMap.put(current, currentTraffic);
                if (currentTraffic > maxTraffic) maxTraffic = currentTraffic;

                BlockPos next = flowFieldNodes.get(current).pos();
                if (next.equals(current)) break;
                current = next;
                safety++;
            }
        }
    }

    public static void clear() {
        territoryChunks.clear();
        flowFieldNodes.clear();
        mappedChunks.clear();
        macroArrows.clear();
        trafficMap.clear();
        maxTraffic = 1;
    }

    /**
     * Calculates chunk-to-chunk routing for the Macro NavMesh view.
     * Uses a Breadth-First Search (BFS) starting from all mapped chunks
     * and flooding outward into the unmapped territory chunks.
     */
    public static void calculateMacroNavMesh() {
        macroArrows.clear();

        // If we have no mapped chunks, there's nowhere to route to.
        if (mappedChunks.isEmpty()) return;

        Queue<ChunkPos> queue = new LinkedList<>(mappedChunks);
        Set<ChunkPos> visited = new HashSet<>(mappedChunks);

        while (!queue.isEmpty()) {
            ChunkPos current = queue.poll();

            // Check the 4 cardinal neighboring chunks (North, South, East, West)
            ChunkPos[] neighbors = new ChunkPos[] {
                    new ChunkPos(current.x + 1, current.z),
                    new ChunkPos(current.x - 1, current.z),
                    new ChunkPos(current.x, current.z + 1),
                    new ChunkPos(current.x, current.z - 1)
            };

            for (ChunkPos neighbor : neighbors) {
                // If the neighbor is part of the Nexus territory but hasn't been mapped yet
                if (territoryChunks.contains(neighbor) && !visited.contains(neighbor)) {
                    visited.add(neighbor);

                    // Point this wilderness chunk toward the current chunk (which is closer to the core)
                    macroArrows.put(neighbor, current);

                    // Add it to the queue to keep flooding outward
                    queue.add(neighbor);
                }
            }
        }
    }
}