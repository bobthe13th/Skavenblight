package org.ratden.skavenblight.magic.player;

import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.ratden.skavenblight.magic.Wind;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PlayerMagicDataTest {

    @Test
    void constructorDefensivelyCopiesMutableCollections() {
        Map<Wind, Integer> tier = new HashMap<>();
        tier.put(Wind.HYSH, 1);
        Map<Wind, Integer> aptitude = new HashMap<>();
        aptitude.put(Wind.HYSH, 10);
        Set<ResourceLocation> knownSpells = new HashSet<>();
        ResourceLocation boon = ResourceLocation.fromNamespaceAndPath("skavenblight", "boon_of_hysh");
        knownSpells.add(boon);

        PlayerMagicData data = new PlayerMagicData(tier, aptitude, knownSpells, 0, false, 0L);

        // Mutating the caller's original collections must not affect the record.
        tier.put(Wind.AZYR, 5);
        aptitude.put(Wind.AZYR, 50);
        knownSpells.clear();

        assertEquals(0, data.getTier(Wind.AZYR));
        assertEquals(0, data.getAptitude(Wind.AZYR));
        assertEquals(Set.of(boon), data.knownSpells());

        assertThrows(UnsupportedOperationException.class, () -> data.tier().put(Wind.GHUR, 9));
        assertThrows(UnsupportedOperationException.class, () -> data.aptitude().put(Wind.GHUR, 9));
        assertThrows(UnsupportedOperationException.class, () -> data.knownSpells().add(boon));
    }

    @Test
    void emptyHasZeroForEverything() {
        assertEquals(0, PlayerMagicData.EMPTY.getAptitude(Wind.HYSH));
        assertEquals(0, PlayerMagicData.EMPTY.getTier(Wind.HYSH));
        assertTrue(PlayerMagicData.EMPTY.knownSpells().isEmpty());
    }

    @Test
    void withAptitudeReturnsUpdatedCopy() {
        PlayerMagicData updated = PlayerMagicData.EMPTY.withAptitude(Wind.AQSHY, 35);

        assertEquals(35, updated.getAptitude(Wind.AQSHY));
        assertEquals(0, updated.getAptitude(Wind.HYSH));
        assertEquals(0, PlayerMagicData.EMPTY.getAptitude(Wind.AQSHY)); // original untouched
    }

    @Test
    void codecRoundTrips() {
        ResourceLocation fireball = ResourceLocation.fromNamespaceAndPath("skavenblight", "fireball");
        PlayerMagicData data = new PlayerMagicData(
                Map.of(Wind.AQSHY, 1),
                Map.of(Wind.AQSHY, 35),
                Set.of(fireball),
                0,
                false,
                0L
        );

        var encoded = PlayerMagicData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
        PlayerMagicData decoded = PlayerMagicData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();

        assertEquals(35, decoded.getAptitude(Wind.AQSHY));
        assertEquals(1, decoded.getTier(Wind.AQSHY));
        assertEquals(Set.of(fireball), decoded.knownSpells());
    }
}
