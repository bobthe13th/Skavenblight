package org.ratden.skavenblight.magic.wind;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.ratden.skavenblight.magic.Wind;

import static org.junit.jupiter.api.Assertions.*;

class ChunkWindStateTest {

    @Test
    void defaultsToZero() {
        ChunkWindState state = new ChunkWindState();
        for (Wind wind : Wind.values()) {
            assertEquals(0f, state.getCurrent(wind));
            assertEquals(0f, state.getBaseline(wind));
        }
        assertEquals(0f, state.getDharLevel());
    }

    @Test
    void gettersReflectSetters() {
        ChunkWindState state = new ChunkWindState();
        state.setCurrent(Wind.AQSHY, 123.5f);
        state.setBaseline(Wind.SHYISH, 42f);
        state.setDharLevel(7f);

        assertEquals(123.5f, state.getCurrent(Wind.AQSHY));
        assertEquals(42f, state.getBaseline(Wind.SHYISH));
        assertEquals(7f, state.getDharLevel());
        // unrelated winds untouched
        assertEquals(0f, state.getCurrent(Wind.HYSH));
    }

    @Test
    void roundTripsThroughNbt() {
        ChunkWindState state = new ChunkWindState();
        for (Wind wind : Wind.values()) {
            state.setCurrent(wind, wind.ordinal() * 10f + 1f);
            state.setBaseline(wind, wind.ordinal() * 20f + 2f);
        }
        state.setDharLevel(99.5f);

        CompoundTag tag = state.save(new CompoundTag());
        ChunkWindState loaded = ChunkWindState.load(tag);

        for (Wind wind : Wind.values()) {
            assertEquals(state.getCurrent(wind), loaded.getCurrent(wind), 0.001f);
            assertEquals(state.getBaseline(wind), loaded.getBaseline(wind), 0.001f);
        }
        assertEquals(99.5f, loaded.getDharLevel(), 0.001f);
    }
}
