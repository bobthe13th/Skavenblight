package org.ratden.skavenblight.entity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.entity.custom.ClanratEntity;
import org.ratden.skavenblight.entity.custom.WarpLightningBoltEntity;
import org.ratden.skavenblight.entity.custom.WolfRat;
import org.ratden.skavenblight.entity.custom.wolfCat.WolfCat;

/**
 * Registers the EntityTypes added by Skavenblight.
 *
 * Attributes and client renderers are registered separately by
 * ModEntityAttributes and ModEntityRenderers.
 */
public final class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(
                    Registries.ENTITY_TYPE,
                    Skavenblight.MODID
            );

    public static final DeferredHolder<
            EntityType<?>,
            EntityType<WolfRat>
            > RAT_WOLF =
            ENTITY_TYPES.register(
                    "rat_wolf",
                    () -> EntityType.Builder
                            .<WolfRat>of(
                                    WolfRat::new,
                                    MobCategory.MONSTER
                            )
                            .sized(0.5F, 0.7F)
                            .build(
                                    Skavenblight.MODID + ":rat_wolf"
                            )
            );

    public static final DeferredHolder<
            EntityType<?>,
            EntityType<WolfCat>
            > WOLF_CAT =
            ENTITY_TYPES.register(
                    "wolf_cat",
                    () -> EntityType.Builder
                            .<WolfCat>of(
                                    WolfCat::new,
                                    MobCategory.MONSTER
                            )
                            .sized(0.6F, 0.7F)
                            .build(
                                    Skavenblight.MODID + ":wolf_cat"
                            )
            );

    public static final DeferredHolder<
            EntityType<?>,
            EntityType<ClanratEntity>
            > CLANRAT =
            ENTITY_TYPES.register(
                    "clanrat",
                    () -> EntityType.Builder
                            .<ClanratEntity>of(
                                    ClanratEntity::new,
                                    MobCategory.MONSTER
                            )
                            .sized(0.6F, 1.8F)
                            .build(
                                    Skavenblight.MODID + ":clanrat"
                            )
            );

    public static final DeferredHolder<
            EntityType<?>,
            EntityType<WarpLightningBoltEntity>
            > WARP_LIGHTNING_BOLT =
            ENTITY_TYPES.register(
                    "warp_lightning_bolt",
                    () -> EntityType.Builder
                            .<WarpLightningBoltEntity>of(
                                    WarpLightningBoltEntity::new,
                                    MobCategory.MISC
                            )
                            .sized(0.0F, 0.0F)
                            .clientTrackingRange(16)
                            .updateInterval(Integer.MAX_VALUE)
                            .build(
                                    Skavenblight.MODID + ":warp_lightning_bolt"
                            )
            );

    public static void register(IEventBus eventBus) {
        ENTITY_TYPES.register(eventBus);
    }

    private ModEntities() {
    }
}