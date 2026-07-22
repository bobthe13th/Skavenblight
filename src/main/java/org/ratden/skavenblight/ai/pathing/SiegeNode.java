package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;

public record SiegeNode(BlockPos pos, SiegeAction action) {

    public enum SiegeAction {
        WALK,
        MINE,
        BUILD_BRIDGE,
        BUILD_STAIR,
        BUILD_LANDING, // Forces a 3x3 staging area to break up long staircases
        BUILD_PILLAR,  // Places a block directly beneath the rat to scale vertical shafts
        LEAP,   // Commands the rat to jump a 1-block gap
        BUILD_LADDER
    }
}