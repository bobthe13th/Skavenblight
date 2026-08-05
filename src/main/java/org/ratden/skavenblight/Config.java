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

    private static final ModConfigSpec.IntValue TUNNEL_BASE_COST =
            BUILDER.comment(
                    "Base pathfinding cost for a TUNNEL step (mining "
                            + "straight through)."
            ).defineInRange(
                    "tunnelBaseCost",
                    400,
                    1,
                    10_000
            );

    private static final ModConfigSpec.IntValue BRIDGE_BASE_COST =
            BUILDER.comment(
                    "Base pathfinding cost for a BRIDGE step (building "
                            + "across a gap)."
            ).defineInRange(
                    "bridgeBaseCost",
                    600,
                    1,
                    10_000
            );

    private static final ModConfigSpec.IntValue CARVED_STAIR_BASE_COST =
            BUILDER.comment(
                    "Base pathfinding cost for a CARVED_STAIR step (mining "
                            + "a stair into solid material)."
            ).defineInRange(
                    "carvedStairBaseCost",
                    800,
                    1,
                    10_000
            );

    private static final ModConfigSpec.IntValue AIR_STAIR_BASE_COST =
            BUILDER.comment(
                    "Base pathfinding cost for an AIR_STAIR step (building "
                            + "a stair through open air)."
            ).defineInRange(
                    "airStairBaseCost",
                    1_000,
                    1,
                    10_000
            );

    private static final ModConfigSpec.IntValue BEDROCK_FAILSAFE_RAT_MINUTES =
            BUILDER.comment(
                    "Real-world minutes for ONE rat to clear an "
                            + "unbreakable block. Linear with worker count "
                            + "(25 rats clear it in 1/25th the time) using "
                            + "the same work units as workPerRatPerTick, so "
                            + "this and the build-speed cap can never drift "
                            + "apart."
            ).defineInRange(
                    "bedrockFailsafeRatMinutes",
                    25,
                    1,
                    1_000
            );

    /*
     * Project-scoped siege construction settings
     */

    private static final ModConfigSpec.DoubleValue PROJECT_WORK_RADIUS =
            BUILDER.comment(
                    "How close a rat must be to a siege project's next "
                            + "unbuilt step to register as a worker on it."
            ).defineInRange(
                    "projectWorkRadius",
                    3.5,
                    1.0,
                    16.0
            );

    private static final ModConfigSpec.IntValue MAX_PROJECT_WORKERS =
            BUILDER.comment(
                    "Worker cap for siege project actions that don't "
                            + "auto-widen (pillars, landings, ladders, "
                            + "spirals via the generic fallback)."
            ).defineInRange(
                    "maxProjectWorkers",
                    4,
                    1,
                    100
            );

    private static final ModConfigSpec.IntValue WORKERS_PER_WIDEN_STEP =
            BUILDER.comment(
                    "Worker cap per lane for staircase/bridge projects "
                            + "before they auto-widen by one lane, up to "
                            + "maxProjectWidth."
            ).defineInRange(
                    "workersPerWidenStep",
                    10,
                    1,
                    100
            );

    private static final ModConfigSpec.DoubleValue WORK_PER_RAT_PER_TICK =
            BUILDER.comment(
                    "Build-progress work one registered rat contributes "
                            + "to its siege project per tick."
            ).defineInRange(
                    "workPerRatPerTick",
                    100.0,
                    1.0,
                    10_000.0
            );

    private static final ModConfigSpec.IntValue MAX_PROJECT_WIDTH =
            BUILDER.comment(
                    "Maximum number of lanes a staircase/bridge project "
                            + "may auto-widen to."
            ).defineInRange(
                    "maxProjectWidth",
                    4,
                    1,
                    20
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

    public static int tunnelBaseCost;
    public static int bridgeBaseCost;
    public static int carvedStairBaseCost;
    public static int airStairBaseCost;
    public static int bedrockFailsafeRatMinutes;

    public static double projectWorkRadius;
    public static int maxProjectWorkers;
    public static int workersPerWidenStep;
    public static double workPerRatPerTick;
    public static int maxProjectWidth;

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

        tunnelBaseCost =
                TUNNEL_BASE_COST.get();

        bridgeBaseCost =
                BRIDGE_BASE_COST.get();

        carvedStairBaseCost =
                CARVED_STAIR_BASE_COST.get();

        airStairBaseCost =
                AIR_STAIR_BASE_COST.get();

        bedrockFailsafeRatMinutes =
                BEDROCK_FAILSAFE_RAT_MINUTES.get();

        projectWorkRadius =
                PROJECT_WORK_RADIUS.get();

        maxProjectWorkers =
                MAX_PROJECT_WORKERS.get();

        workersPerWidenStep =
                WORKERS_PER_WIDEN_STEP.get();

        workPerRatPerTick =
                WORK_PER_RAT_PER_TICK.get();

        maxProjectWidth =
                MAX_PROJECT_WIDTH.get();

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