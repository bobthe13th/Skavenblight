package org.ratden.skavenblight;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.ratden.skavenblight.block.ModBlocks;
import org.ratden.skavenblight.block.entity.ModBlockEntities;
import org.ratden.skavenblight.entity.ModEntities;
import org.ratden.skavenblight.event.GameOverHandler;
import org.ratden.skavenblight.item.ModCreativeModeTabs;
import org.ratden.skavenblight.item.ModItems;
import org.ratden.skavenblight.network.WarpFluxGridManager;
import org.ratden.skavenblight.screen.ModMenus;
import org.ratden.skavenblight.sound.ModSounds;
import org.slf4j.Logger;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(Skavenblight.MODID)
public class Skavenblight {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "skavenblight";
    // Directly reference a slf4j logger
    private static final Logger LOGGER = LogUtils.getLogger();
    // Create a Deferred Register to hold Blocks which will all be registered under the "skavenblight" namespace
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    // Create a Deferred Register to hold Items which will all be registered under the "skavenblight" namespace
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    // Create a Deferred Register to hold CreativeModeTabs which will all be registered under the "skavenblight" namespace
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // Creates a new Block with the id "skavenblight:example_block", combining the namespace and path
    //public static final DeferredBlock<Block> EXAMPLE_BLOCK = BLOCKS.registerSimpleBlock("example_block", BlockBehaviour.Properties.of().mapColor(MapColor.STONE));
    // Creates a new BlockItem with the id "skavenblight:example_block", combining the namespace and path
   // public static final DeferredItem<BlockItem> EXAMPLE_BLOCK_ITEM = ITEMS.registerSimpleBlockItem("example_block", EXAMPLE_BLOCK);

    // Creates a new food item with the id "skavenblight:example_id", nutrition 1 and saturation 2
    //public static final DeferredItem<Item> EXAMPLE_ITEM = ITEMS.registerSimpleItem("example_item", new Item.Properties().food(new FoodProperties.Builder().alwaysEdible().nutrition(1).saturationModifier(2f).build()));

    // Creates a creative tab with the id "skavenblight:example_tab" for the example item, that is placed after the combat tab
   // public static final DeferredHolder<CreativeModeTab, CreativeModeTab> EXAMPLE_TAB = CREATIVE_MODE_TABS.register("example_tab", () -> CreativeModeTab.builder().title(Component.translatable("itemGroup.skavenblight")).withTabsBefore(CreativeModeTabs.COMBAT).icon(() -> EXAMPLE_ITEM.get().getDefaultInstance()).displayItems((parameters, output) -> {
   //     output.accept(EXAMPLE_ITEM.get()); // Add the example item to the tab. For your own tabs, this method is preferred over the event
    //}).build());

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public Skavenblight(IEventBus modEventBus, ModContainer modContainer) {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register the Deferred Register to the mod event bus so blocks get registered
        BLOCKS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so items get registered
        ITEMS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so tabs get registered
        CREATIVE_MODE_TABS.register(modEventBus);

        // Register ourselves for server and other game events we are interested in.
        // Note that this is necessary if and only if we want *this* class (Skavenblight) to respond directly to events.
        // Do not add this line if there are no @SubscribeEvent-annotated functions in this class, like onServerStarting() below.
        NeoForge.EVENT_BUS.register(this);

        ModCreativeModeTabs.register(modEventBus);

        ModItems.register(modEventBus);
        ModBlocks.register(modEventBus);
        ModEntities.ENTITY_TYPES.register(modEventBus);
        ModBlockEntities.register(modEventBus);

        NeoForge.EVENT_BUS.addListener(GameOverHandler::onServerTick);

        ModSounds.register(modEventBus);

        // Register the item to a creative tab
        modEventBus.addListener(this::addCreative);

        // Register the entity attributes
        modEventBus.addListener(this::registerEntityAttributes);
        // Register our mod's ModConfigSpec so that FML can create and load the config file for us

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        modContainer.registerConfig(ModConfig.Type.SERVER, org.ratden.skavenblight.config.WarpFluxFurnaceConfig.SPEC, "skavenblight-furnace-server.toml");
        // Initialize our custom wealth values when the mod loads - if/when implemented
        //WealthRegistry.registerBaseValues();
        // Add this line right below your other addListener calls in the constructor!
        modEventBus.addListener(this::registerCapabilities);
        ModMenus.register(modEventBus);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // Some common setup code
        LOGGER.info("HELLO FROM COMMON SETUP");

        if (Config.logDirtBlock) LOGGER.info("DIRT BLOCK >> {}", BuiltInRegistries.BLOCK.getKey(Blocks.DIRT));

        LOGGER.info(Config.magicNumberIntroduction + Config.magicNumber);

        Config.items.forEach((item) -> LOGGER.info("ITEM >> {}", item.toString()));
    }

    // Add our ingredients items to the ingredients items tab
    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            event.accept(ModItems.RAW_WARPSTONE);
            event.accept(ModItems.RAT_JUICE);
        }

        if (event.getTabKey() == CreativeModeTabs.NATURAL_BLOCKS){
            event.accept(ModBlocks.WARPSTONE_ORE);
            event.accept(ModBlocks.WARPSTONE_ORE_DEEPSLATE);
        }
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Do something when the server starts
        LOGGER.info("Rats, rats, we're the rats");
    }

    // You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
    @SuppressWarnings("removal")
    @EventBusSubscriber(modid = MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            // Some client setup code
            LOGGER.info("We prey at night, we stalk at night, we're the rats");
            LOGGER.info("I'm the giant rat that makes all of the rules");
            LOGGER.info("Let's see what kind of trouble we can get ourselves into");
            LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
        }
    }

    public void registerEntityAttributes(net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent event) {
        // Links your Clanrat attributes to its actual registered EntityType
        event.put(org.ratden.skavenblight.entity.ModEntities.CLANRAT.get(),
                org.ratden.skavenblight.entity.custom.ClanratEntity.createAttributes().build());
    }

    public void registerCapabilities(net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent event) {
        // Register the capability for the basic storage block
        event.registerBlockEntity(
                org.ratden.skavenblight.capability.ModCapabilities.WARP_FLUX,
                org.ratden.skavenblight.block.entity.ModBlockEntities.WARP_FLUX_STORAGE.get(),
                (blockEntity, side) -> blockEntity.getFluxStorage()
        );

        // Register the capability for the Nexus
        event.registerBlockEntity(
                org.ratden.skavenblight.capability.ModCapabilities.WARP_FLUX,
                org.ratden.skavenblight.block.entity.ModBlockEntities.WARPSTONE_NEXUS.get(),
                (blockEntity, side) -> blockEntity.getFluxStorage()
        );

        event.registerBlockEntity(
                org.ratden.skavenblight.capability.ModCapabilities.WARP_FLUX,
                org.ratden.skavenblight.block.entity.ModBlockEntities.WARP_FLUX_FURNACE.get(),
                (blockEntity, side) -> blockEntity.getFluxStorage()
        );
    }

    //Warp flux network
    @net.neoforged.bus.api.SubscribeEvent
    public void onLevelTick(net.neoforged.neoforge.event.tick.LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            WarpFluxGridManager manager = WarpFluxGridManager.get(serverLevel);
            manager.tickNetworks(serverLevel);
        }
    }
}
