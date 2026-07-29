package org.ratden.skavenblight.magic.wind;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.ratden.skavenblight.magic.Wind;

/** Per-chunk wind levels: a "current" value drifting toward a recomputed "baseline". */
public final class ChunkWindState {

    private final float[] current = new float[Wind.values().length];
    private final float[] baseline = new float[Wind.values().length];
    private float dharLevel = 0f;
    private final int[] taggedBlockCount = new int[Wind.values().length];

    public float getCurrent(Wind wind) {
        return current[wind.ordinal()];
    }

    public void setCurrent(Wind wind, float value) {
        current[wind.ordinal()] = value;
    }

    public float getBaseline(Wind wind) {
        return baseline[wind.ordinal()];
    }

    public void setBaseline(Wind wind, float value) {
        baseline[wind.ordinal()] = value;
    }

    public float getDharLevel() {
        return dharLevel;
    }

    public void setDharLevel(float value) {
        dharLevel = value;
    }

    public int getTaggedBlockCount(Wind wind) {
        return taggedBlockCount[wind.ordinal()];
    }

    public void addTaggedBlockCount(Wind wind, int delta) {
        taggedBlockCount[wind.ordinal()] = Math.max(0, taggedBlockCount[wind.ordinal()] + delta);
    }

    public CompoundTag save(CompoundTag tag) {
        tag.put("current", floatArrayTag(current));
        tag.put("baseline", floatArrayTag(baseline));
        tag.putFloat("dharLevel", dharLevel);
        tag.putIntArray("taggedBlockCount", taggedBlockCount);
        return tag;
    }

    public static ChunkWindState load(CompoundTag tag) {
        ChunkWindState state = new ChunkWindState();
        readFloatArrayTag(tag, "current", state.current);
        readFloatArrayTag(tag, "baseline", state.baseline);
        state.dharLevel = tag.getFloat("dharLevel");
        if (tag.contains("taggedBlockCount")) {
            int[] counts = tag.getIntArray("taggedBlockCount");
            System.arraycopy(counts, 0, state.taggedBlockCount, 0,
                    Math.min(counts.length, state.taggedBlockCount.length));
        }
        return state;
    }

    private static ListTag floatArrayTag(float[] values) {
        ListTag list = new ListTag();
        for (float v : values) {
            list.add(FloatTag.valueOf(v));
        }
        return list;
    }

    private static void readFloatArrayTag(CompoundTag tag, String key, float[] target) {
        if (!tag.contains(key, Tag.TAG_LIST)) return;
        ListTag list = tag.getList(key, Tag.TAG_FLOAT);
        for (int i = 0; i < Math.min(list.size(), target.length); i++) {
            target[i] = list.getFloat(i);
        }
    }
}
