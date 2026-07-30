package org.ratden.skavenblight.magic.player;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import org.ratden.skavenblight.magic.Wind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public record PlayerMagicData(
        Map<Wind, Integer> tier,
        Map<Wind, Integer> aptitude,
        Set<ResourceLocation> knownSpells,
        int corruptionPoints,
        boolean darkMagicUnlocked,
        long lastCleanseGameTime
) {
    public PlayerMagicData {
        tier = Map.copyOf(tier);
        aptitude = Map.copyOf(aptitude);
        knownSpells = Set.copyOf(knownSpells);
    }

    public static final Codec<PlayerMagicData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Wind.CODEC, Codec.INT).fieldOf("tier").forGetter(PlayerMagicData::tier),
            Codec.unboundedMap(Wind.CODEC, Codec.INT).fieldOf("aptitude").forGetter(PlayerMagicData::aptitude),
            ResourceLocation.CODEC.listOf()
                    .<Set<ResourceLocation>>xmap(HashSet::new, ArrayList::new)
                    .fieldOf("known_spells")
                    .forGetter(PlayerMagicData::knownSpells),
            Codec.INT.optionalFieldOf("corruption_points", 0).forGetter(PlayerMagicData::corruptionPoints),
            Codec.BOOL.optionalFieldOf("dark_magic_unlocked", false).forGetter(PlayerMagicData::darkMagicUnlocked),
            Codec.LONG.optionalFieldOf("last_cleanse_game_time", 0L).forGetter(PlayerMagicData::lastCleanseGameTime)
    ).apply(instance, PlayerMagicData::new));

    public static final PlayerMagicData EMPTY = new PlayerMagicData(Map.of(), Map.of(), Set.of(), 0, false, 0L);

    public int getAptitude(Wind wind) {
        return aptitude.getOrDefault(wind, 0);
    }

    public int getTier(Wind wind) {
        return tier.getOrDefault(wind, 0);
    }

    public PlayerMagicData withAptitude(Wind wind, int value) {
        Map<Wind, Integer> updated = new HashMap<>(aptitude);
        updated.put(wind, value);
        return new PlayerMagicData(tier, updated, knownSpells, corruptionPoints, darkMagicUnlocked, lastCleanseGameTime);
    }

    public PlayerMagicData withTier(Wind wind, int value) {
        Map<Wind, Integer> updated = new HashMap<>(tier);
        updated.put(wind, value);
        return new PlayerMagicData(updated, aptitude, knownSpells, corruptionPoints, darkMagicUnlocked, lastCleanseGameTime);
    }

    public PlayerMagicData withKnownSpell(ResourceLocation spellId) {
        Set<ResourceLocation> updated = new HashSet<>(knownSpells);
        updated.add(spellId);
        return new PlayerMagicData(tier, aptitude, updated, corruptionPoints, darkMagicUnlocked, lastCleanseGameTime);
    }

    public PlayerMagicData withCorruptionPoints(int value) {
        return new PlayerMagicData(tier, aptitude, knownSpells, value, darkMagicUnlocked, lastCleanseGameTime);
    }

    public PlayerMagicData withDarkMagicUnlocked(boolean value) {
        return new PlayerMagicData(tier, aptitude, knownSpells, corruptionPoints, value, lastCleanseGameTime);
    }

    public PlayerMagicData withLastCleanseGameTime(long value) {
        return new PlayerMagicData(tier, aptitude, knownSpells, corruptionPoints, darkMagicUnlocked, value);
    }
}
