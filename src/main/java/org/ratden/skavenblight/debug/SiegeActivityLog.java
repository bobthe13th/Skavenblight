package org.ratden.skavenblight.debug;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import org.ratden.skavenblight.ai.pathing.PathAction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Bounded ring buffer of executed (not planned) siege construction/mining actions, for
 * debug dumps (see PathingDebugFileWriter) to reconstruct what actually happened leading
 * up to a snapshot, since a live grid/mob dump only shows a single instant.
 */
public final class SiegeActivityLog {

    private static final int MAX_ENTRIES = 500;
    private static final Deque<Entry> ENTRIES = new ArrayDeque<>(MAX_ENTRIES);

    private SiegeActivityLog() {
    }

    public static synchronized void record(long gameTime, LivingEntity actor, BlockPos targetPos,
                                            PathAction action, String note, Integer regionId) {
        String mobType = actor != null ? BuiltInRegistries.ENTITY_TYPE.getKey(actor.getType()).toString() : "?";
        String mobId = actor != null ? actor.getStringUUID() : "?";
        BlockPos mobPos = actor != null ? actor.blockPosition() : null;

        ENTRIES.addLast(new Entry(gameTime, mobType, mobId, mobPos, targetPos, action, note, regionId));
        while (ENTRIES.size() > MAX_ENTRIES) {
            ENTRIES.removeFirst();
        }
    }

    /** Backward-compatible overload for call sites that don't (yet) have a region id handy. */
    public static void record(long gameTime, LivingEntity actor, BlockPos targetPos, PathAction action, String note) {
        record(gameTime, actor, targetPos, action, note, null);
    }

    public static synchronized List<Entry> recent(int count) {
        List<Entry> snapshot = new ArrayList<>(ENTRIES);
        int fromIndex = Math.max(0, snapshot.size() - count);
        return List.copyOf(snapshot.subList(fromIndex, snapshot.size()));
    }

    public record Entry(long gameTime, String mobType, String mobId, BlockPos mobPos, BlockPos targetPos,
                         PathAction action, String note, Integer regionId) {
    }
}
