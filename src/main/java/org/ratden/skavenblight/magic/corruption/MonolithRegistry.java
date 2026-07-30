package org.ratden.skavenblight.magic.corruption;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory index of currently-loaded Chaos Monolith positions per dimension, rebuilt from
 * BlockEntity load/unload — deliberately not persisted, since every loaded Monolith's BlockEntity
 * re-registers itself on chunk load anyway. Keyed by dimension (not ServerLevel directly) so a
 * level reload can't leave a stale strong reference here.
 */
public final class MonolithRegistry {

    private static final Map<ResourceKey<Level>, Set<BlockPos>> POSITIONS = new ConcurrentHashMap<>();

    private MonolithRegistry() {}

    public static void register(ServerLevel level, BlockPos pos) {
        POSITIONS.computeIfAbsent(level.dimension(), key -> ConcurrentHashMap.newKeySet()).add(pos.immutable());
    }

    public static void unregister(ServerLevel level, BlockPos pos) {
        Set<BlockPos> positions = POSITIONS.get(level.dimension());
        if (positions != null) {
            positions.remove(pos);
        }
    }

    public static boolean isWithinRange(ServerLevel level, BlockPos pos, int radius) {
        Set<BlockPos> positions = POSITIONS.get(level.dimension());
        if (positions == null) {
            return false;
        }
        double radiusSq = (double) radius * radius;
        for (BlockPos monolith : positions) {
            if (monolith.distSqr(pos) <= radiusSq) {
                return true;
            }
        }
        return false;
    }
}
