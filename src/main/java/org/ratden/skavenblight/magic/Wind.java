package org.ratden.skavenblight.magic;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * The 8 Winds of Magic. This is the canonical school/wind pairing used everywhere in code —
 * never introduce a parallel "Lore" or "School" enum, extend this one.
 */
public enum Wind implements StringRepresentable {
    HYSH("hysh", "Light", 0xF7F3D9),
    AZYR("azyr", "Heavens", 0x3C6FB0),
    CHAMON("chamon", "Metal", 0xC9A227),
    GHYRAN("ghyran", "Life", 0x3E8E3E),
    AQSHY("aqshy", "Fire", 0xB5342A),
    GHUR("ghur", "Beasts", 0x8A5A2B),
    ULGU("ulgu", "Shadow", 0x6E6E78),
    SHYISH("shyish", "Death", 0x6A3E85);

    public static final Codec<Wind> CODEC = StringRepresentable.fromEnum(Wind::values);

    private final String id;
    private final String loreName;
    private final int color;

    Wind(String id, String loreName, int color) {
        this.id = id;
        this.loreName = loreName;
        this.color = color;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    public String getLoreName() {
        return loreName;
    }

    public int getColor() {
        return color;
    }

    public String getTranslationKey() {
        return "wind.skavenblight." + id;
    }
}
