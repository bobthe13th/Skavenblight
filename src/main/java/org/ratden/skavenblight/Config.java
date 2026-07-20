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
                    15_000,
                    1_000,
                    1_000_000
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
    public static int minimumSettleDelayMs;

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

        minimumSettleDelayMs =
                MINIMUM_SETTLE_DELAY_MS.get();
    }

    private Config() {
    }
}