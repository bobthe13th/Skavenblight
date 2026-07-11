package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;

public record SiegeNode(BlockPos pos, SiegeAction action) {

    public enum SiegeAction {
        WALK,         // Path is clear. Use vanilla moveTo().
        MINE,         // Obstacle detected. Break the block in the way.
        BUILD_BRIDGE, // Horizontal gap detected. Place a Skaven Scaffold.
        BUILD_STAIR   // Vertical gap detected. Place a Skaven Scaffold Stair.
    }
}