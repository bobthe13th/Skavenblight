package org.ratden.skavenblight.block.entity.debug;

import net.minecraft.core.BlockPos;

import java.util.UUID;

/**
 * Immutable debug reference connecting one anchor to one planned physical
 * source.
 *
 * sourcePlacementId identifies the source inside the IncursionPlan.
 * sourcePos supplies the world endpoint used by particle visualisation.
 */
public record DebugAnchorSourceLink(
        UUID sourcePlacementId,
        BlockPos sourcePos
) {

    public DebugAnchorSourceLink {
        if (sourcePlacementId == null) {
            throw new IllegalArgumentException(
                    "Debug source-placement ID cannot be null."
            );
        }

        if (sourcePos == null) {
            throw new IllegalArgumentException(
                    "Debug source position cannot be null."
            );
        }

        sourcePos =
                sourcePos.immutable();
    }
}