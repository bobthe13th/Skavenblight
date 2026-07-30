package org.ratden.skavenblight;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Declares and loads Skavenblight's general common configuration.
 *
 * System-specific configuration may use a separate class and config file
 * where that provides clearer ownership, as WarpFluxFurnaceConfig does.
 */
@EventBusSubscriber(
        modid = Skavenblight.MODID,
        bus = EventBusSubscriber.Bus.MOD
)
public final class Config {

    private static final ModConfigSpec.Builder BUILDER =
            new ModConfigSpec.Builder();

    /*
     * Warpstone Nexus settings
     */

    private static final ModConfigSpec.IntValue TIER_0_CAPACITY =
            BUILDER.comment(
                    "Tier 0 Nexus Flux Capacity"
            ).defineInRange(
                    "tier0Capacity",
                    1_000,
                    0,
                    Integer.MAX_VALUE
            );

    private static final ModConfigSpec.IntValue TIER_0_GENERATION =
            BUILDER.comment(
                    "Tier 0 Nexus Flux Generation per tick"
            ).defineInRange(
                    "tier0Generation",
                    100,
                    0,
                    Integer.MAX_VALUE
            );

    private static final ModConfigSpec.IntValue TIER_1_CAPACITY =
            BUILDER.comment(
                    "Tier 1 Nexus Flux Capacity"
            ).defineInRange(
                    "tier1Capacity",
                    10_000,
                    0,
                    Integer.MAX_VALUE
            );

    private static final ModConfigSpec.IntValue TIER_1_GENERATION =
            BUILDER.comment(
                    "Tier 1 Nexus Flux Generation per tick"
            ).defineInRange(
                    "tier1Generation",
                    1_000,
                    0,
                    Integer.MAX_VALUE
            );

    private static final ModConfigSpec.IntValue TIER_2_CAPACITY =
            BUILDER.comment(
                    "Tier 2 Nexus Flux Capacity"
            ).defineInRange(
                    "tier2Capacity",
                    1_000_000,
                    0,
                    Integer.MAX_VALUE
            );

    private static final ModConfigSpec.IntValue TIER_2_GENERATION =
            BUILDER.comment(
                    "Tier 2 Nexus Flux Generation per tick"
            ).defineInRange(
                    "tier2Generation",
                    10_000,
                    0,
                    Integer.MAX_VALUE
            );

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
    private static final ModConfigSpec.IntValue MONOLITH_CASTING_RADIUS_BLOCKS = BUILDER.comment("Radius in blocks around a Chaos Monolith where casting gets a bonus but any failure triggers a Catastrophic Chaos Manifestation (WFRP source: 100 feet, approximated at Minecraft scale).")
            .defineInRange("monolithCastingRadiusBlocks", 20, 1, 200);
    private static final ModConfigSpec.IntValue TOME_CARRY_CORRUPTION_PER_CHECK = BUILDER.comment("Corruption Points gained per corruptionTickIntervalTicks while a Tome of Corruption is anywhere in the player's inventory.")
            .defineInRange("tomeCarryCorruptionPerCheck", 1, 0, 100);
    private static final ModConfigSpec.IntValue CLEANSE_HYSH_REQUIREMENT = BUILDER.comment("Minimum local Hysh Wind level required to use a Cleansing Ward.")
            .defineInRange("cleanseHyshRequirement", 300, 0, 10000);
    private static final ModConfigSpec.IntValue CLEANSE_AMOUNT = BUILDER.comment("Corruption Points removed by a single successful Cleansing Ward use.")
            .defineInRange("cleanseAmount", 15, 1, 1000);
    private static final ModConfigSpec.IntValue CLEANSE_COOLDOWN_TICKS = BUILDER.comment("Minimum ticks between a player's Cleansing Ward uses (default: one Minecraft day).")
            .defineInRange("cleanseCooldownTicks", 24000, 0, 1000000);
    private static final ModConfigSpec.IntValue MONOLITH_READ_COOLDOWN_TICKS = BUILDER.comment("Minimum ticks between successful rune-reads on the same Chaos Monolith, regardless of who reads it (prevents spam-clicking one Monolith to instantly max Corruption).")
            .defineInRange("monolithReadCooldownTicks", 1200, 0, 1000000);

    /*
     * AI territory settings
     */

    private static final ModConfigSpec.IntValue
            TERRITORY_CHUNK_RADIUS =
            BUILDER.comment(
                    "The radius, measured in chunks, around Warp Flux "
                            + "networks that defines their territory for "
                            + "AI flow-field generation."
            ).defineInRange(
                    "territoryChunkRadius",
                    2,
                    0,
                    8
            );

    /*
     * Flow-field pathfinding settings
     */

    private static final ModConfigSpec.IntValue
            MINING_PENALTY_MULTIPLIER =
            BUILDER.comment(
                    "Multiplier applied to block hardness when calculating "
                            + "path cost. Higher values make rats prefer "
                            + "walking around walls."
            ).defineInRange(
                    "miningPenaltyMultiplier",
                    100,
                    1,
                    1_000
            );

    private static final ModConfigSpec.IntValue MINING_BASE_PENALTY =
            BUILDER.comment(
                    "Flat cost added to breaking any block. This prevents "
                            + "rats from breaking weak blocks merely to "
                            + "save one or two steps."
            ).defineInRange(
                    "miningBasePenalty",
                    50,
                    0,
                    500
            );

    private static final ModConfigSpec.IntValue BUILDING_BASE_PENALTY =
            BUILDER.comment(
                    "Flat pathfinding cost added when a rat must place a "
                            + "block to continue moving."
            ).defineInRange(
                    "buildingBasePenalty",
                    150,
                    1,
                    1_000
            );

    private static final ModConfigSpec.IntValue MAX_FLOW_FIELD_NODES =
            BUILDER.comment(
                    "Maximum number of nodes a flow field may map. This "
                            + "acts as a hard limit on pathfinding range."
            ).defineInRange(
                    "maxFlowFieldNodes",
                    25_000,
                    1_000,
                    1_000_000
            );

    private static final ModConfigSpec.IntValue
            REGION_SCAN_MAX_CELLS =
            BUILDER.comment(
                    "Maximum walkable cells RegionScanner will flood-fill "
                            + "in a single territory scan pass."
            ).defineInRange(
                    "regionScanMaxCells",
                    200_000,
                    10_000,
                    2_000_000
            );

    private static final ModConfigSpec.IntValue
            MINIMUM_SETTLE_DELAY_MS =
            BUILDER.comment(
                    "Minimum time in milliseconds that the flow-field "
                            + "pathfinder waits after a block change before "
                            + "recalculating."
            ).defineInRange(
                    "minimumSettleDelayMs",
                    1_000,
                    0,
                    10_000
            );

    /*
     * Warp Lightning Coil settings
     */

    private static final ModConfigSpec.IntValue WL_COIL_CAPACITY =
            BUILDER.comment(
                    "Max Warp Flux the coil can hold."
            ).defineInRange(
                    "wlCoilCapacity",
                    5_000,
                    0,
                    Integer.MAX_VALUE
            );

    private static final ModConfigSpec.IntValue
            WL_COIL_COST_PER_SHOT =
            BUILDER.comment(
                    "Flux cost per lightning arc triggered."
            ).defineInRange(
                    "wlCoilCostPerShot",
                    100,
                    0,
                    Integer.MAX_VALUE
            );

    private static final ModConfigSpec.IntValue WL_COIL_COOLDOWN =
            BUILDER.comment(
                    "Number of ticks before firing again."
            ).defineInRange(
                    "wlCoilCooldown",
                    500,
                    0,
                    1_000_000
            );

    private static final ModConfigSpec.DoubleValue WL_COIL_RANGE =
            BUILDER.comment(
                    "Detection and max chain range in blocks."
            ).defineInRange(
                    "wlCoilRange",
                    6.0,
                    1.0,
                    256.0
            );

    private static final ModConfigSpec.DoubleValue WL_COIL_DAMAGE =
            BUILDER.comment(
                    "Damage dealt per lightning strike."
            ).defineInRange(
                    "wlCoilDamage",
                    6.0,
                    0.0,
                    100.0
            );

    private static final ModConfigSpec.IntValue
            WL_COIL_CHAIN_COUNT =
            BUILDER.comment(
                    "Maximum number of additional mobs the lightning can "
                            + "chain to."
            ).defineInRange(
                    "wlCoilChainCount",
                    13,
                    0,
                    255
            );

    private static final ModConfigSpec.IntValue
            WL_COIL_CHAIN_RANGE =
            BUILDER.comment(
                    "Maximum range of mobs the lightning can chain to."
            ).defineInRange(
                    "wlCoilChainRange",
                    13,
                    0,
                    255
            );

    private static final ModConfigSpec.IntValue
            WL_COIL_POISON_TICKS =
            BUILDER.comment(
                    "Duration of Poison effect in ticks (20 ticks = 1 "
                            + "second)."
            ).defineInRange(
                    "wlCoilPoisonTicks",
                    1,
                    0,
                    1_200
            );

    private static final ModConfigSpec.IntValue
            WL_COIL_SLOW_TICKS =
            BUILDER.comment(
                    "Duration of Slowness effect in ticks."
            ).defineInRange(
                    "wlCoilSlowTicks",
                    1_000,
                    0,
                    1_200
            );

    public static final ModConfigSpec SPEC =
            BUILDER.build();

    /*
     * Loaded runtime values
     */

    public static int tier0Capacity;
    public static int tier0Generation;

    public static int tier1Capacity;
    public static int tier1Generation;

    public static int tier2Capacity;
    public static int tier2Generation;

    public static int territoryChunkRadius;

    public static int miningPenaltyMultiplier;
    public static int miningBasePenalty;
    public static int buildingBasePenalty;
    public static int maxFlowFieldNodes;
    public static int regionScanMaxCells;
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
    public static int monolithCastingRadiusBlocks;
    public static int tomeCarryCorruptionPerCheck;
    public static int cleanseHyshRequirement;
    public static int cleanseAmount;
    public static int cleanseCooldownTicks;
    public static int monolithReadCooldownTicks;

    // --- Warp Lightning Coil Public Variables ---
    public static int wlCoilCapacity;
    public static int wlCoilCostPerShot;
    public static double wlCoilRange;
    public static double wlCoilDamage;
    public static int wlCoilChainCount;
    public static double wlCoilChainRange;
    public static int wlCoilPoisonTicks;
    public static int wlCoilSlowTicks;

    /**
     * Refreshes the runtime values when the common configuration is loaded
     * or reloaded.
     */
    @SubscribeEvent
    public static void onLoad(
            ModConfigEvent event
    ) {
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
        monolithCastingRadiusBlocks = MONOLITH_CASTING_RADIUS_BLOCKS.get();
        tomeCarryCorruptionPerCheck = TOME_CARRY_CORRUPTION_PER_CHECK.get();
        cleanseHyshRequirement = CLEANSE_HYSH_REQUIREMENT.get();
        cleanseAmount = CLEANSE_AMOUNT.get();
        cleanseCooldownTicks = CLEANSE_COOLDOWN_TICKS.get();
        monolithReadCooldownTicks = MONOLITH_READ_COOLDOWN_TICKS.get();

        territoryChunkRadius =
                TERRITORY_CHUNK_RADIUS.get();

        miningPenaltyMultiplier =
                MINING_PENALTY_MULTIPLIER.get();

        miningBasePenalty =
                MINING_BASE_PENALTY.get();

        buildingBasePenalty =
                BUILDING_BASE_PENALTY.get();

        maxFlowFieldNodes =
                MAX_FLOW_FIELD_NODES.get();

        regionScanMaxCells =
                REGION_SCAN_MAX_CELLS.get();

        minimumSettleDelayMs =
                MINIMUM_SETTLE_DELAY_MS.get();

        wlCoilCapacity =
                WL_COIL_CAPACITY.get();

        wlCoilCostPerShot =
                WL_COIL_COST_PER_SHOT.get();

        wlCoilRange =
                WL_COIL_RANGE.get();

        wlCoilDamage =
                WL_COIL_DAMAGE.get();

        wlCoilChainCount =
                WL_COIL_CHAIN_COUNT.get();

        wlCoilChainRange =
                WL_COIL_CHAIN_RANGE.get();

        wlCoilPoisonTicks =
                WL_COIL_POISON_TICKS.get();

        wlCoilSlowTicks =
                WL_COIL_SLOW_TICKS.get();
    }

    private Config() {
    }
}