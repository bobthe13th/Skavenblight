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

    public CompoundTag save(CompoundTag tag) {
        tag.put("current", floatArrayTag(current));
        tag.put("baseline", floatArrayTag(baseline));
        tag.putFloat("dharLevel", dharLevel);
        return tag;
    }

    public static ChunkWindState load(CompoundTag tag) {
        ChunkWindState state = new ChunkWindState();
        readFloatArrayTag(tag, "current", state.current);
        readFloatArrayTag(tag, "baseline", state.baseline);
        state.dharLevel = tag.getFloat("dharLevel");
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
