package org.ratden.skavenblight.magic.spell;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.item.crafting.Ingredient;
import org.ratden.skavenblight.magic.Wind;

import java.util.Optional;

public record Spell(
        Wind wind,
        int tier,
        int castingNumber,
        CastingTime castingTime,
        // Parsed but not yet consumed by casting logic — component-item cost is a Phase 2 concern.
        Optional<Ingredient> componentItem,
        SpellEffect effect,
        String descriptionKey,
        // Dhar (Dark Magic): any Wind can have a dark-tagged variant. Casting one requires
        // PlayerMagicData.darkMagicUnlocked and grants corruption — see SpellCasting.
        boolean dark
) {
    public static final Codec<Spell> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Wind.CODEC.fieldOf("wind").forGetter(Spell::wind),
            Codec.INT.fieldOf("tier").forGetter(Spell::tier),
            Codec.INT.fieldOf("casting_number").forGetter(Spell::castingNumber),
            CastingTime.CODEC.fieldOf("casting_time").forGetter(Spell::castingTime),
            Ingredient.CODEC.optionalFieldOf("component_item").forGetter(Spell::componentItem),
            SpellEffect.CODEC.fieldOf("effect").forGetter(Spell::effect),
            Codec.STRING.fieldOf("description_key").forGetter(Spell::descriptionKey),
            Codec.BOOL.optionalFieldOf("dark", false).forGetter(Spell::dark)
    ).apply(instance, Spell::new));
}
