package org.ratden.skavenblight.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class WarpFluxFurnaceConfig {
    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec SPEC;

    // Config entries
    public static final ModConfigSpec.IntValue BASE_SMELT_TICKS;
    public static final ModConfigSpec.IntValue FLUX_PER_TICK;
    public static final ModConfigSpec.DoubleValue OUTPUT_MULTIPLIER;

    static {
        BUILDER.push("Warp Flux Furnace Settings");

        BASE_SMELT_TICKS = BUILDER
                .comment(" How many ticks it takes to smelt a single item. (Vanilla furnace is 200 ticks / 10 seconds)")
                .defineInRange("base_smelt_ticks", 100, 1, 72000);

        FLUX_PER_TICK = BUILDER
                .comment(" Amount of Warp Flux consumed per tick while actively smelting.")
                .defineInRange("flux_per_tick", 20, 0, Integer.MAX_VALUE);

        OUTPUT_MULTIPLIER = BUILDER
                .comment(" Multiplier applied to the recipe output stack size. (e.g., 2.0 means 1 ore yields 2 ingots)")
                .defineInRange("output_multiplier", 1.5, 1.0, 64.0);

        BUILDER.pop();
        SPEC = BUILDER.build();
    }
}