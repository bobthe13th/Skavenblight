package org.ratden.skavenblight.magic.potion;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;

/**
 * Represents a potion in the Warhammer Fantasy Roleplay (WFRP) Realms of Sorcery system.
 * Defined by its unique characteristics including Name, Effect, Lag Time, Volatility,
 * Ingredient Cost, Locale, Difficulty, Creation Number, and Creation Time.
 */
public record AlchemicalPotion(
        String name,
        String effectDescription,
        int lagTimeTicks,
        int volatility,
        double ingredientCost,
        String ingredientLocale,
        int ingredientDifficulty,
        int creationNumber,
        int creationTimeTicks
) {
    public static final Codec<AlchemicalPotion> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("name").forGetter(AlchemicalPotion::name),
            Codec.STRING.fieldOf("effect_description").forGetter(AlchemicalPotion::effectDescription),
            Codec.INT.fieldOf("lag_time_ticks").forGetter(AlchemicalPotion::lagTimeTicks),
            Codec.INT.fieldOf("volatility").forGetter(AlchemicalPotion::volatility),
            Codec.DOUBLE.fieldOf("ingredient_cost").forGetter(AlchemicalPotion::ingredientCost),
            Codec.STRING.fieldOf("ingredient_locale").forGetter(AlchemicalPotion::ingredientLocale),
            Codec.INT.fieldOf("ingredient_difficulty").forGetter(AlchemicalPotion::ingredientDifficulty),
            Codec.INT.fieldOf("creation_number").forGetter(AlchemicalPotion::creationNumber),
            Codec.INT.fieldOf("creation_time_ticks").forGetter(AlchemicalPotion::creationTimeTicks)
    ).apply(instance, AlchemicalPotion::new));
}
