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
            .defineInRange("miningBasePenalty", 80, 0, 500);
    private static final ModConfigSpec.IntValue BUILDING_BASE_PENALTY = BUILDER.comment("Flat pathfinding cost added when a rat has to place a block to move forward.")
            .defineInRange("buildingBasePenalty", 150, 1, 1000);
    private static final ModConfigSpec.IntValue MAX_FLOW_FIELD_NODES = BUILDER.comment("Maximum total nodes a Flow Field is allowed to map, acting as a hard limit on pathfinding range.")
            .defineInRange("maxFlowFieldNodes", 25000, 1000, 1000000);

    // The settle delay config
    private static final ModConfigSpec.IntValue MINIMUM_SETTLE_DELAY_MS = BUILDER.comment("The minimum time in milliseconds the flow field pathfinder will wait after a block change before recalculating.")
            .defineInRange("minimumSettleDelayMs", 1000, 0, 10000);

    // --- Warp Lightning Coil Configs ---
    private static final ModConfigSpec.IntValue WL_COIL_CAPACITY = BUILDER.comment("Max Warp Flux the coil can hold.")
            .defineInRange("wlCoilCapacity", 5000, 0, Integer.MAX_VALUE);
    private static final ModConfigSpec.IntValue WL_COIL_COST_PER_SHOT = BUILDER.comment("Flux cost per lightning arc triggered.")
            .defineInRange("wlCoilCostPerShot", 100, 0, Integer.MAX_VALUE);
    private static final ModConfigSpec.IntValue WL_COIL_COOLDOWN = BUILDER.comment("Number of ticks before firing again.")
            .defineInRange("wlCoilCooldown", 500, 0, 1000000);
    private static final ModConfigSpec.DoubleValue WL_COIL_RANGE = BUILDER.comment("Detection and max chain range in blocks.")
            .defineInRange("wlCoilRange", 6.0, 1.0, 256.0);
    private static final ModConfigSpec.DoubleValue WL_COIL_DAMAGE = BUILDER.comment("Damage dealt per lightning strike.")
            .defineInRange("wlCoilDamage", 6.0, 0.0, 100.0);
    private static final ModConfigSpec.IntValue WL_COIL_CHAIN_COUNT = BUILDER.comment("Maximum number of additional mobs the lightning can chain to.")
            .defineInRange("wlCoilChainCount", 13, 0, 255);
    private static final ModConfigSpec.IntValue WL_COIL_CHAIN_RANGE = BUILDER.comment("Maximum range of mobs the lightning can chain to.")
            .defineInRange("wlCoilChainRange", 13, 0, 255);
    private static final ModConfigSpec.IntValue WL_COIL_POISON_TICKS = BUILDER.comment("Duration of Poison effect in ticks (20 ticks = 1 second).")
            .defineInRange("wlCoilPoisonTicks", 1, 0, 1200);
    private static final ModConfigSpec.IntValue WL_COIL_SLOW_TICKS = BUILDER.comment("Duration of Slowness effect in ticks.")
            .defineInRange("wlCoilSlowTicks", 1000, 0, 1200);

    // --- Research Table Configs ---
    private static final ModConfigSpec.IntValue RESEARCH_TICKS_BASE = BUILDER.comment("Base number of ticks required to complete spell research at the Research Table.")
            .defineInRange("researchTicksBase", 200, 20, 72000);
    private static final ModConfigSpec.IntValue RESEARCH_WIND_THRESHOLD = BUILDER.comment("Base minimum local Wind level required to research a tier-0 spell at the Research Table; each additional tier requires this much more (tier 2 needs 3x this value).")
            .defineInRange("researchWindThreshold", 200, 0, 10000);
    private static final ModConfigSpec.IntValue RESEARCH_WIND_BONUS_REFERENCE = BUILDER.comment("Extra local Wind (above the research requirement) needed to add +1.0x to research speed.")
            .defineInRange("researchWindBonusReference", 300, 1, 100000);
    private static final ModConfigSpec.DoubleValue RESEARCH_WIND_MAX_BONUS_MULTIPLIER = BUILDER.comment("Cap on the speed bonus from abundant Wind - e.g. 2.0 means research can run up to 3x base speed (1x base + up to 2x bonus).")
            .defineInRange("researchWindMaxBonusMultiplier", 2.0, 0.0, 50.0);

    // --- Wind Influence Configs ---
    private static final ModConfigSpec.IntValue WIND_SOURCE_BLOCK_BONUS = BUILDER.comment("Baseline bonus per tagged wind_source block placed in a chunk (see TaggedBlockInfluence).")
            .defineInRange("windSourceBlockBonus", 15, 0, 1000);

    // --- Corruption Configs ---
    private static final ModConfigSpec.IntValue CORRUPTION_TICK_INTERVAL_TICKS = BUILDER.comment("How often (in ticks) each player's Tainted effect is refreshed and their Tome-of-Corruption carry drain is checked.")
            .defineInRange("corruptionTickIntervalTicks", 6000, 20, 72000);
    private static final ModConfigSpec.IntValue DHAR_CAST_CORRUPTION = BUILDER.comment("Corruption Points granted per attempted Dhar (dark-tagged) spell cast, success or failure.")
            .defineInRange("dharCastCorruption", 1, 0, 100);
    private static final ModConfigSpec.IntValue MONOLITH_MAX_WOUNDS = BUILDER.comment("Total Wounds a Chaos Monolith has before it's destroyed (WFRP source: 500).")
            .defineInRange("monolithMaxWounds", 500, 10, 10000);
    private static final ModConfigSpec.IntValue MONOLITH_DAMAGE_PER_HIT = BUILDER.comment("Wounds removed per attack against a Chaos Monolith.")
            .defineInRange("monolithDamagePerHit", 5, 1, 500);
    private static final ModConfigSpec.IntValue MONOLITH_WOUNDS_PER_DAEMON_SUMMON = BUILDER.comment("Every this many Wounds lost, the Monolith summons a Lesser Daemon at the attacker (WFRP source: every 50 Wounds).")
            .defineInRange("monolithWoundsPerDaemonSummon", 50, 1, 10000);
    private static final ModConfigSpec.IntValue MONOLITH_READ_CORRUPTION_CHANCE_PERCENT = BUILDER.comment("Percent chance that reading a Chaos Monolith's runes grants Corruption Points (WFRP source: failing a Hard(-20%) Will Power Test).")
            .defineInRange("monolithReadCorruptionChancePercent", 60, 0, 100);

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

    // --- Flow Field Pathfinding Public Variables ---
    public static int miningPenaltyMultiplier;
    public static int miningBasePenalty;
    public static int buildingBasePenalty;
    public static int maxFlowFieldNodes;
    public static int minimumSettleDelayMs;

    // --- Research Table Public Variables ---
    public static int researchTicksBase;
    public static int researchWindThreshold;
    public static int researchWindBonusReference;
    public static double researchWindMaxBonusMultiplier;

    // --- Wind Influence Public Variables ---
    public static int windSourceBlockBonus;

    // --- Corruption Public Variables ---
    public static int corruptionTickIntervalTicks;
    public static int dharCastCorruption;
    public static int monolithMaxWounds;
    public static int monolithDamagePerHit;
    public static int monolithWoundsPerDaemonSummon;
    public static int monolithReadCorruptionChancePercent;

    // --- Warp Lightning Coil Public Variables ---
    public static int wlCoilCapacity;
    public static int wlCoilCostPerShot;
    public static double wlCoilRange;
    public static double wlCoilDamage;
    public static int wlCoilChainCount;
    public static double wlCoilChainRange;
    public static int wlCoilPoisonTicks;
    public static int wlCoilSlowTicks;

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

        // Load Research Table Configs
        researchTicksBase = RESEARCH_TICKS_BASE.get();
        researchWindThreshold = RESEARCH_WIND_THRESHOLD.get();
        researchWindBonusReference = RESEARCH_WIND_BONUS_REFERENCE.get();
        researchWindMaxBonusMultiplier = RESEARCH_WIND_MAX_BONUS_MULTIPLIER.get();

        // Load Wind Influence Configs
        windSourceBlockBonus = WIND_SOURCE_BLOCK_BONUS.get();

        // Load Corruption Configs
        corruptionTickIntervalTicks = CORRUPTION_TICK_INTERVAL_TICKS.get();
        dharCastCorruption = DHAR_CAST_CORRUPTION.get();
        monolithMaxWounds = MONOLITH_MAX_WOUNDS.get();
        monolithDamagePerHit = MONOLITH_DAMAGE_PER_HIT.get();
        monolithWoundsPerDaemonSummon = MONOLITH_WOUNDS_PER_DAEMON_SUMMON.get();
        monolithReadCorruptionChancePercent = MONOLITH_READ_CORRUPTION_CHANCE_PERCENT.get();

        // Load AI Configs
        territoryChunkRadius = TERRITORY_CHUNK_RADIUS.get();

        // Load Pathfinding Configs
        miningPenaltyMultiplier = MINING_PENALTY_MULTIPLIER.get();
        miningBasePenalty = MINING_BASE_PENALTY.get();
        buildingBasePenalty = BUILDING_BASE_PENALTY.get();
        maxFlowFieldNodes = MAX_FLOW_FIELD_NODES.get();
        minimumSettleDelayMs = MINIMUM_SETTLE_DELAY_MS.get();

        // Load Warp Lightning Coil Configs
        wlCoilCapacity = WL_COIL_CAPACITY.get();
        wlCoilCostPerShot = WL_COIL_COST_PER_SHOT.get();
        wlCoilRange = WL_COIL_RANGE.get();
        wlCoilChainRange = WL_COIL_CHAIN_RANGE.get();
        wlCoilDamage = WL_COIL_DAMAGE.get();
        wlCoilChainCount = WL_COIL_CHAIN_COUNT.get();
        wlCoilPoisonTicks = WL_COIL_POISON_TICKS.get();
        wlCoilSlowTicks = WL_COIL_SLOW_TICKS.get();

        // convert the list of strings into a set of items
        items = ITEM_STRINGS.get().stream().map(itemName -> BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemName))).collect(Collectors.toSet());
    }
}