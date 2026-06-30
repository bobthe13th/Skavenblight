package org.ratden.skavenblight.entity;

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
import org.ratden.skavenblight.entity.custom.ClanratEntity;
import org.ratden.skavenblight.entity.custom.RatWolf;
import org.ratden.skavenblight.Skavenblight;

public class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, Skavenblight.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<RatWolf>> RAT_WOLF =
            ENTITY_TYPES.register("rat_wolf", () ->
                    EntityType.Builder.<RatWolf>of(RatWolf::new, MobCategory.MONSTER)
                            .sized(0.5F, 0.7F)
                            .build(Skavenblight.MODID + ":rat_wolf")
            );


    public static final DeferredHolder<EntityType<?>, EntityType<ClanratEntity>> CLANRAT =
            ENTITY_TYPES.register("clanrat", () ->
                    EntityType.Builder.<ClanratEntity>of(ClanratEntity::new, MobCategory.MONSTER)
                            .sized(0.6F, 1.8F) // Sized appropriately for a humanoid bipedal rat
                            .build(Skavenblight.MODID + ":clanrat")
            );

    @EventBusSubscriber(modid = Skavenblight.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientEntityEvent { // <-- ADDED 'static' HERE
        @SubscribeEvent
        public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
            // Registers the Skaven Clanrat renderer
            event.registerEntityRenderer(ModEntities.CLANRAT.get(), ClanratRenderer::new);

            // Registers the RatWolf renderer so it isn't invisible either!
            event.registerEntityRenderer(ModEntities.RAT_WOLF.get(), org.ratden.skavenblight.entity.client.RatWolfRenderer::new);
        }
    }
    @net.neoforged.bus.api.SubscribeEvent
    public static void registerCapabilities(net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                org.ratden.skavenblight.capability.ModCapabilities.WARP_FLUX,
                org.ratden.skavenblight.block.entity.ModBlockEntities.WARP_FLUX_STORAGE.get(),
                (be, side) -> be.getFluxStorage()
        );

        // Also register your Warpstone Nexus entity if you haven't yet!
        event.registerBlockEntity(
                org.ratden.skavenblight.capability.ModCapabilities.WARP_FLUX,
                org.ratden.skavenblight.block.entity.ModBlockEntities.WARPSTONE_NEXUS.get(),
                (be, side) -> be.getFluxStorage()
        );
    }
}