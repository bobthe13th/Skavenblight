package org.ratden.skavenblight;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, Skavenblight.MODID);


    public static final DeferredHolder<EntityType<?>, EntityType<RatWolf>> RAT_WOLF =
            ENTITY_TYPES.register("rat_wolf", () ->
                    EntityType.Builder.<RatWolf>of(RatWolf::new, MobCategory.MONSTER)
                            .sized(0.5F, 0.7F)
                            .build(Skavenblight.MODID + ":rat_wolf")
            );

}