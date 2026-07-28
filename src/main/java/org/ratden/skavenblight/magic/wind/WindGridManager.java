package org.ratden.skavenblight.magic.wind;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import org.ratden.skavenblight.magic.Wind;
import org.ratden.skavenblight.magic.wind.influence.TimeOfDayInfluence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ambient per-chunk wind levels. Sibling to WarpFluxGridManager: same SavedData + tick-from-
 * LevelTickEvent.Post shape, but winds have no conduit graph to flood-fill — every loaded chunk is
 * ticked independently.
 */
public class WindGridManager extends SavedData {

    /** Extension point: Phase 2 tasks append BiomeInfluence, TaggedBlockInfluence, etc. here. */
    private static final List<WindBaselineInfluence> INFLUENCES = new ArrayList<>();
    static {
        INFLUENCES.add(new TimeOfDayInfluence());
    }

    /** Fraction of the current-to-baseline gap closed per tick. */
    private static final float DRIFT_RATE = 0.02f;

    private final Map<ChunkPos, ChunkWindState> chunkStates = new HashMap<>();
    private final Set<ChunkPos> loadedChunks = new HashSet<>();

    public static WindGridManager get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new Factory<>(WindGridManager::new, WindGridManager::load, null),
                "skavenblight_winds"
        );
    }

    public ChunkWindState getOrCreate(ChunkPos pos) {
        return chunkStates.computeIfAbsent(pos, p -> new ChunkWindState());
    }

    public void markLoaded(ChunkPos pos) {
        loadedChunks.add(pos);
    }

    public void markUnloaded(ChunkPos pos) {
        loadedChunks.remove(pos);
    }

    public void tick(ServerLevel level) {
        float[] baselineDelta = new float[Wind.values().length];
        for (ChunkPos pos : loadedChunks) {
            ChunkWindState state = getOrCreate(pos);
            Arrays.fill(baselineDelta, 0f);
            for (WindBaselineInfluence influence : INFLUENCES) {
                influence.apply(level, pos, baselineDelta);
            }
            for (Wind wind : Wind.values()) {
                float newBaseline = Math.max(0f, baselineDelta[wind.ordinal()]);
                state.setBaseline(wind, newBaseline);
                float currentValue = state.getCurrent(wind);
                state.setCurrent(wind, currentValue + (newBaseline - currentValue) * DRIFT_RATE);
            }
        }
        this.setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<ChunkPos, ChunkWindState> entry : chunkStates.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putLong("pos", entry.getKey().toLong());
            entryTag.put("state", entry.getValue().save(new CompoundTag()));
            list.add(entryTag);
        }
        tag.put("chunks", list);
        return tag;
    }

    public static WindGridManager load(CompoundTag tag, HolderLookup.Provider registries) {
        WindGridManager manager = new WindGridManager();
        if (tag.contains("chunks", Tag.TAG_LIST)) {
            ListTag list = tag.getList("chunks", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entryTag = list.getCompound(i);
                ChunkPos pos = new ChunkPos(entryTag.getLong("pos"));
                ChunkWindState state = ChunkWindState.load(entryTag.getCompound("state"));
                manager.chunkStates.put(pos, state);
            }
        }
        return manager;
    }
}
