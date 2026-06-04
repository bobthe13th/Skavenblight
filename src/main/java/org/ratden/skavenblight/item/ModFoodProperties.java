package org.ratden.skavenblight.item;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodProperties;

public class ModFoodProperties {
    public static final FoodProperties RAT_JUICE = new FoodProperties.Builder().nutrition(4).saturationModifier(.5f)
            .effect(() -> new MobEffectInstance(MobEffects.LUCK, 400), 1).build();



}
