package org.ratden.skavenblight.magic.spell;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * v1 simplification of WFRP casting times: a plain enum, no per-cast channel-length field yet.
 * If a later spell needs a variable channel duration, add a separate int field to Spell rather than
 * parameterizing this enum.
 */
public enum CastingTime implements StringRepresentable {
    INSTANT("instant"),
    HALF_ACTION("half_action"),
    FULL_ACTION("full_action"),
    CHANNELLED("channelled");

    public static final Codec<CastingTime> CODEC = StringRepresentable.fromEnum(CastingTime::values);

    private final String id;

    CastingTime(String id) {
        this.id = id;
    }

    @Override
    public String getSerializedName() {
        return id;
    }
}
