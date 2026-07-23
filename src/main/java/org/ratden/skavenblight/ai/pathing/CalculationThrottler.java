package org.ratden.skavenblight.ai.pathing;

import net.minecraft.server.MinecraftServer;
import org.ratden.skavenblight.Config;

/**
 * Monitors server performance (MSPT) to dynamically adjust how large a fraction of
 * {@link Config#maxFlowFieldNodes} a single flow field calculation is allowed to use.
 *
 * NOTE: despite the per-tick-sounding name, {@code getNodesPerTick()} is consumed as the
 * node budget for one entire {@code FlowFieldCalculator.calculateFully()} pass, which runs
 * to completion in a single background-thread call - it is not metered incrementally
 * across ticks. An earlier version capped this at a flat 50-500 nodes, completely
 * unrelated to {@code Config.maxFlowFieldNodes} (default 25000) - under perfectly healthy
 * server load, every calculation was silently truncated to at most 500 nodes, nowhere near
 * enough to cover a real territory, which is why flow field arrows never reached a
 * territory's edges. Scaling a fraction of the configured max instead means a healthy
 * server gets the full configured budget, and only actual lag scales it down.
 */
public class CalculationThrottler {

    private static final float MIN_THROTTLE_FRACTION = 0.1f;
    private static final float RECOVERY_STEP_FRACTION = 0.05f;

    // 50ms is exactly 20 TPS. We want to start panicking before we hit that.
    private static final float THROTTLE_THRESHOLD_MSPT = 40.0f;
    private static final float RECOVERY_THRESHOLD_MSPT = 35.0f;

    // Written on the main thread by tick(), read from the flow field's background
    // calculation thread by getNodesPerTick() - volatile so the background thread always
    // observes the latest value instead of a potentially stale cached copy.
    private volatile float currentThrottleFraction = 1.0f;

    /**
     * Called once per tick by the dimension/server manager, NOT by individual rats.
     */
    public void tick(MinecraftServer server) {
        if (server == null) return;

        // In 1.21+ Mojmap, tick times are tracked in nanoseconds.
        // We divide by 1,000,000 to convert back to the MSPT (milliseconds per tick) we want.
        float currentMspt = (float) server.getAverageTickTimeNanos() / 1_000_000.0F;

        if (currentMspt > THROTTLE_THRESHOLD_MSPT) {
            // The server is lagging. Slam the brakes aggressively.
            currentThrottleFraction = Math.max(MIN_THROTTLE_FRACTION, currentThrottleFraction / 2.0f);
        }
        else if (currentMspt < RECOVERY_THRESHOLD_MSPT) {
            // The server is breathing easily. Slowly ramp the processing limit back up.
            currentThrottleFraction = Math.min(1.0f, currentThrottleFraction + RECOVERY_STEP_FRACTION);
        }
    }

    /**
     * Fetched by the FlowFieldCalculator during its processing phase.
     */
    public int getNodesPerTick() {
        return Math.max(1, Math.round(Config.maxFlowFieldNodes * currentThrottleFraction));
    }
}
