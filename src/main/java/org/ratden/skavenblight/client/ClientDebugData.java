package org.ratden.skavenblight.client;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ClientDebugData {
    public static final Set<ChunkPos> territoryChunks = new HashSet<>();
    public static final Map<BlockPos, Direction> flowFieldDirections = new HashMap<>();

    public static void update(Set<ChunkPos> chunks, Map<BlockPos, Direction> directions) {
        territoryChunks.clear();
        territoryChunks.addAll(chunks);

        flowFieldDirections.clear();
        flowFieldDirections.putAll(directions);
    }

    public static void clear() {
        territoryChunks.clear();
        flowFieldDirections.clear();
    }
}