package org.ratden.skavenblight.entity;

import net.minecraft.client.renderer.entity.CatRenderer;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.ratden.skavenblight.entity.client.ClanratRenderer;
import org.ratden.skavenblight.entity.client.WarpLightningBoltRenderer;
import org.ratden.skavenblight.entity.custom.ClanratEntity;
import org.ratden.skavenblight.entity.custom.WarpLightningBoltEntity;
import org.ratden.skavenblight.entity.custom.wolfCat.WolfCat;
import org.ratden.skavenblight.entity.custom.WolfRat;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.block.entity.ModBlockEntities;
import org.ratden.skavenblight.block.entity.WarpLightningCoilBlockEntity;
import net.minecraft.core.Direction;

public class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, Skavenblight.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<WolfRat>> RAT_WOLF =
            ENTITY_TYPES.register("rat_wolf", () ->
                    EntityType.Builder.<WolfRat>of(WolfRat::new, MobCategory.MONSTER)
                            .sized(0.5F, 0.7F)
                            .build(Skavenblight.MODID + ":rat_wolf")
            );

    public static final DeferredHolder<EntityType<?>, EntityType<WolfCat>> WOLF_CAT =
            ENTITY_TYPES.register("wolf_cat", () ->
                    EntityType.Builder.<WolfCat>of(WolfCat::new, MobCategory.MONSTER)
                            .sized(0.6F, 0.7F)
                            .build(Skavenblight.MODID + ":wolf_cat")
            );


    public static final DeferredHolder<EntityType<?>, EntityType<ClanratEntity>> CLANRAT =
            ENTITY_TYPES.register("clanrat", () ->
                    EntityType.Builder.<ClanratEntity>of(ClanratEntity::new, MobCategory.MONSTER)
                            .sized(0.6F, 1.8F) // Sized appropriately for a humanoid bipedal rat
                            .build(Skavenblight.MODID + ":clanrat")
            );

    public static final DeferredHolder<EntityType<?>, EntityType<WarpLightningBoltEntity>> WARP_LIGHTNING_BOLT =
            ENTITY_TYPES.register("warp_lightning_bolt", () ->
                    EntityType.Builder.<WarpLightningBoltEntity>of(WarpLightningBoltEntity::new, MobCategory.MISC)
                            .sized(0.0F, 0.0F)
                            .clientTrackingRange(16)
                            .updateInterval(Integer.MAX_VALUE) // Doesn't need to sync movement
                            .build(Skavenblight.MODID + ":warp_lightning_bolt")
            );

    @EventBusSubscriber(modid = Skavenblight.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientEntityEvent { // <-- ADDED 'static' HERE
        @SubscribeEvent
        public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
            // Registers the Skaven Clanrat renderer
            event.registerEntityRenderer(ModEntities.CLANRAT.get(), ClanratRenderer::new);

            // Registers the RatWolf renderer so it isn't invisible either!
            event.registerEntityRenderer(ModEntities.RAT_WOLF.get(), org.ratden.skavenblight.entity.client.RatWolfRenderer::new);
            event.registerEntityRenderer(ModEntities.WOLF_CAT.get(), CatRenderer::new);

            event.registerEntityRenderer(ModEntities.WARP_LIGHTNING_BOLT.get(), WarpLightningBoltRenderer::new);
        }
    }
}