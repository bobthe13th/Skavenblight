package org.ratden.skavenblight;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.ratden.skavenblight.block.ModBlocks;
import org.ratden.skavenblight.block.entity.ModBlockEntities;
import org.ratden.skavenblight.entity.ModEntities;
import org.ratden.skavenblight.item.ModArmorMaterials;
import org.ratden.skavenblight.item.ModCreativeModeTabs;
import org.ratden.skavenblight.item.ModItems;
import org.ratden.skavenblight.screen.ModMenus;
import org.ratden.skavenblight.sound.ModSounds;

/**
 * Main Skavenblight bootstrap class.
 *
 * This class attaches the mod's DeferredRegisters and configuration files.
 * Runtime events and specialised registration relationships are owned by
 * their respective systems.
 */
@Mod(Skavenblight.MODID)
public class Skavenblight {

    public static final String MODID = "skavenblight";

    public Skavenblight(
            IEventBus modEventBus,
            ModContainer modContainer
    ) {
        /*
         * ModBlocks must be class-loaded before ModItems is attached to the
         * event bus because ModBlocks registers its BlockItems through
         * ModItems.ITEMS.
         */
        ModBlocks.register(modEventBus);

        ModArmorMaterials.register(modEventBus);
        ModItems.register(modEventBus);

        ModCreativeModeTabs.register(modEventBus);

        ModEntities.register(modEventBus);
        ModBlockEntities.register(modEventBus);

        ModSounds.register(modEventBus);
        ModMenus.register(modEventBus);

        modContainer.registerConfig(
                ModConfig.Type.COMMON,
                Config.SPEC
        );

        modContainer.registerConfig(
                ModConfig.Type.SERVER,
                org.ratden.skavenblight.config
                        .WarpFluxFurnaceConfig.SPEC,
                "skavenblight-furnace-server.toml"
        );
    }
}