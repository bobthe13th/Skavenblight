package org.ratden.skavenblight.magic;

import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WindTest {

    @Test
    void hasExactlyEightWinds() {
        assertEquals(8, Wind.values().length);
    }

    @Test
    void codecRoundTripsBySerializedName() {
        for (Wind wind : Wind.values()) {
            var encoded = Wind.CODEC.encodeStart(JsonOps.INSTANCE, wind).getOrThrow();
            Wind decoded = Wind.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
            assertEquals(wind, decoded);
        }
    }

    @Test
    void translationKeysAreNamespacedAndUnique() {
        long distinctKeys = java.util.Arrays.stream(Wind.values())
                .map(Wind::getTranslationKey)
                .distinct()
                .count();
        assertEquals(8, distinctKeys);
        assertEquals("wind.skavenblight.hysh", Wind.HYSH.getTranslationKey());
    }
}
