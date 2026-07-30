package org.ratden.skavenblight.magic.corruption;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

public record ChaosManifestationTable(CorruptionTier severity, List<ChaosManifestationTable.WeightedEntry> entries) {

    public static final Codec<ChaosManifestationTable> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            CorruptionTier.CODEC.fieldOf("severity").forGetter(ChaosManifestationTable::severity),
            WeightedEntry.CODEC.listOf().fieldOf("entries").forGetter(ChaosManifestationTable::entries)
    ).apply(instance, ChaosManifestationTable::new));

    public record WeightedEntry(int weight, ChaosManifestationEffect effect) {
        public static final Codec<WeightedEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("weight").forGetter(WeightedEntry::weight),
                ChaosManifestationEffect.CODEC.fieldOf("effect").forGetter(WeightedEntry::effect)
        ).apply(instance, WeightedEntry::new));
    }

    /**
     * Picks the entry containing the given roll, where roll is in [0, totalWeight). Entries are
     * walked in list order and each occupies a contiguous slice of weight-sized width — the same
     * shape as a WFRP d100 table read top to bottom. Callers are responsible for rolling
     * random.nextInt(totalWeight(entries)) themselves so this stays a pure, testable function.
     */
    public static ChaosManifestationEffect pickWeighted(List<WeightedEntry> entries, int roll) {
        int cursor = 0;
        for (WeightedEntry entry : entries) {
            cursor += entry.weight();
            if (roll < cursor) {
                return entry.effect();
            }
        }
        throw new IllegalArgumentException("roll " + roll + " exceeds total weight " + cursor);
    }

    public static int totalWeight(List<WeightedEntry> entries) {
        int total = 0;
        for (WeightedEntry entry : entries) {
            total += entry.weight();
        }
        return total;
    }
}
