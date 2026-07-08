package org.ratden.skavenblight.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ClientDebugData {
    public static final Set<ChunkPos> territoryChunks = new HashSet<>();
    // --- CHANGED: Map now stores BlockPos as the value instead of Direction ---
    public static final Map<BlockPos, BlockPos> flowFieldNodes = new HashMap<>();

    public static void update(Set<ChunkPos> chunks, Map<BlockPos, BlockPos> nodes) {
        territoryChunks.clear();
        territoryChunks.addAll(chunks);

        flowFieldNodes.clear();
        flowFieldNodes.putAll(nodes);
    }

    public static void clear() {
        territoryChunks.clear();
        flowFieldNodes.clear();
    }
}