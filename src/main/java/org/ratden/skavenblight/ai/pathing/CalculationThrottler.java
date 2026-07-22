package org.ratden.skavenblight.ai.pathing;

import net.minecraft.server.MinecraftServer;
import org.ratden.skavenblight.Config;

/**
 * Monitors server performance (MSPT) to dynamically adjust how many
 * pathing nodes the FlowFieldCalculator is allowed to process per tick.
 */
public class CalculationThrottler {

    // These could eventually be pulled directly from your Config file
    private static final int MIN_NODES_PER_TICK = 50;
    private static final int MAX_NODES_PER_TICK = 500;

    // 50ms is exactly 20 TPS. We want to start panicking before we hit that.
    private static final float THROTTLE_THRESHOLD_MSPT = 40.0f;
    private static final float RECOVERY_THRESHOLD_MSPT = 35.0f;

    private int currentNodesPerTick;

    public CalculationThrottler() {
        // Assume the server is healthy when the dimension loads
        this.currentNodesPerTick = MAX_NODES_PER_TICK;
    }

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
            currentNodesPerTick = Math.max(MIN_NODES_PER_TICK, currentNodesPerTick / 2);
        }
        else if (currentMspt < RECOVERY_THRESHOLD_MSPT) {
            // The server is breathing easily. Slowly ramp the processing limit back up.
            currentNodesPerTick = Math.min(MAX_NODES_PER_TICK, currentNodesPerTick + 15);
        }
    }

    /**
     * Fetched by the FlowFieldCalculator during its processing phase.
     */
    public int getNodesPerTick() {
        return currentNodesPerTick;
    }
}