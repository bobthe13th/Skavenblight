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
        BUILD_LADDER,
        BUILD_SPIRAL;

        /**
         * True for actions where the placed block is meant to be stood ON (climbed), so a
         * mid-construction race that yanks away solid ground out from under the target is worth
         * guarding against. Deliberately NOT true for every action: a macro SiegeProject climbing
         * through open space plans a whole chain of these in advance, where step N's support is
         * step N-1 - built moments earlier, not present when step N was originally selected. Once
         * placed, a stair/pillar/spiral is self-supporting for pathing purposes regardless of what
         * ends up below it (see TerrainEvaluator.isWalkableTerrain's scaffold short-circuit), so
         * "no support yet" is the normal, expected state for the next unbuilt chain step - only
         * "had support, then lost it" (see AbstractSiegeConstructionGoal#supportSolidAtClaim) is
         * the actual bug this exists to catch. BUILD_BRIDGE/BUILD_LANDING are excluded outright -
         * they're expected to have open air below at placement time by design.
         */
        public boolean isClimbDependent() {
            return this == BUILD_STAIR || this == BUILD_PILLAR || this == BUILD_SPIRAL;
        }
    }
}