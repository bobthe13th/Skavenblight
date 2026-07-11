package org.ratden.skavenblight;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@SuppressWarnings("removal")
@EventBusSubscriber(modid = Skavenblight.MODID, bus = EventBusSubscriber.Bus.MOD)
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    private static final ModConfigSpec.BooleanValue LOG_DIRT_BLOCK = BUILDER.comment("Whether to log the dirt block on common setup").define("logDirtBlock", true);
    private static final ModConfigSpec.IntValue MAGIC_NUMBER = BUILDER.comment("A magic number").defineInRange("magicNumber", 42, 0, Integer.MAX_VALUE);
    public static final ModConfigSpec.ConfigValue<String> MAGIC_NUMBER_INTRODUCTION = BUILDER.comment("What you want the introduction message to be for the magic number").define("magicNumberIntroduction", "The magic number is... ");

    // --- Warpstone Nexus Configs ---
    private static final ModConfigSpec.IntValue TIER_0_CAPACITY = BUILDER.comment("Tier 0 Nexus Flux Capacity")
            .defineInRange("tier0Capacity", 1000, 0, Integer.MAX_VALUE);
    private static final ModConfigSpec.IntValue TIER_0_GENERATION = BUILDER.comment("Tier 0 Nexus Flux Generation per tick")
            .defineInRange("tier0Generation", 100, 0, Integer.MAX_VALUE);

    private static final ModConfigSpec.IntValue TIER_1_CAPACITY = BUILDER.comment("Tier 1 Nexus Flux Capacity")
            .defineInRange("tier1Capacity", 10000, 0, Integer.MAX_VALUE);
    private static final ModConfigSpec.IntValue TIER_1_GENERATION = BUILDER.comment("Tier 1 Nexus Flux Generation per tick")
            .defineInRange("tier1Generation", 1000, 0, Integer.MAX_VALUE);

    private static final ModConfigSpec.IntValue TIER_2_CAPACITY = BUILDER.comment("Tier 2 Nexus Flux Capacity")
            .defineInRange("tier2Capacity", 1000000, 0, Integer.MAX_VALUE);
    private static final ModConfigSpec.IntValue TIER_2_GENERATION = BUILDER.comment("Tier 2 Nexus Flux Generation per tick")
            .defineInRange("tier2Generation", 10000, 0, Integer.MAX_VALUE);

    // --- AI and Incursions Configs ---
    private static final ModConfigSpec.IntValue TERRITORY_CHUNK_RADIUS = BUILDER.comment("The radius (in chunks) around Warp Flux conduits that defines the base's territory for AI flow-field generation.")
            .defineInRange("territoryChunkRadius", 2, 0, 8);

    // --- Flow Field Pathfinding Configs ---
    private static final ModConfigSpec.IntValue MINING_PENALTY_MULTIPLIER = BUILDER.comment("Multiplier for block hardness when calculating path cost. Higher values make rats prefer walking around walls.")
            .defineInRange("miningPenaltyMultiplier", 100, 1, 1000);
    private static final ModConfigSpec.IntValue MINING_BASE_PENALTY = BUILDER.comment("Flat cost added to breaking any block. Prevents rats from breaking weak blocks just to save 1 or 2 steps.")
            .defineInRange("miningBasePenalty", 50, 0, 500);
    private static final ModConfigSpec.IntValue BUILDING_BASE_PENALTY = BUILDER.comment("Flat pathfinding cost added when a rat has to place a block to move forward.")
            .defineInRange("buildingBasePenalty", 150, 1, 1000);

    // a list of strings that are treated as resource locations for items
    private static final ModConfigSpec.ConfigValue<List<? extends String>> ITEM_STRINGS = BUILDER.comment("A list of items to log on common setup.").defineListAllowEmpty("items", List.of("minecraft:iron_ingot"), Config::validateItemName);

    static final ModConfigSpec SPEC = BUILDER.build();

    public static boolean logDirtBlock;
    public static int magicNumber;
    public static String magicNumberIntroduction;
    public static Set<Item> items;

    // --- Warpstone Nexus Public Variables ---
    public static int tier0Capacity;
    public static int tier0Generation;
    public static int tier1Capacity;
    public static int tier1Generation;
    public static int tier2Capacity;
    public static int tier2Generation;

    // --- AI and Incursions Public Variables ---
    public static int territoryChunkRadius;

    // --- NEW: Flow Field Pathfinding Public Variables ---
    public static int miningPenaltyMultiplier;
    public static int miningBasePenalty;
    public static int buildingBasePenalty;

    private static boolean validateItemName(final Object obj) {
        return obj instanceof String itemName && BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(itemName));
    }

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        logDirtBlock = LOG_DIRT_BLOCK.get();
        magicNumber = MAGIC_NUMBER.get();
        magicNumberIntroduction = MAGIC_NUMBER_INTRODUCTION.get();

        // Load Warpstone Nexus Configs
        tier0Capacity = TIER_0_CAPACITY.get();
        tier0Generation = TIER_0_GENERATION.get();
        tier1Capacity = TIER_1_CAPACITY.get();
        tier1Generation = TIER_1_GENERATION.get();
        tier2Capacity = TIER_2_CAPACITY.get();
        tier2Generation = TIER_2_GENERATION.get();

        // Load AI Configs
        territoryChunkRadius = TERRITORY_CHUNK_RADIUS.get();

        // --- Load Pathfinding Configs ---
        miningPenaltyMultiplier = MINING_PENALTY_MULTIPLIER.get();
        miningBasePenalty = MINING_BASE_PENALTY.get();
        buildingBasePenalty = BUILDING_BASE_PENALTY.get();

        // convert the list of strings into a set of items
        items = ITEM_STRINGS.get().stream().map(itemName -> BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemName))).collect(Collectors.toSet());
    }
}