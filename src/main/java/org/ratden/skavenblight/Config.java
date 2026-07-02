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

// An example config class. This is not required, but it's a good idea to have one to keep your config organized.
// Demonstrates how to use Neo's config APIs
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

        // convert the list of strings into a set of items
        items = ITEM_STRINGS.get().stream().map(itemName -> BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemName))).collect(Collectors.toSet());
    }
}