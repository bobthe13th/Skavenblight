package org.ratden.skavenblight.block.custom.debug;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.ratden.skavenblight.block.entity.debug.DebugAnchorType;
import org.ratden.skavenblight.block.entity.debug.DebugIncursionAnchorEntity;

/**
 * Invisible, non-colliding marker for one incursion planning anchor.
 *
 * This block is only a debug representation. Removing or replacing it must
 * never alter the IncursionPlan, runtime Scenario, front, source group or
 * physical sources it describes.
 *
 * The block remains invisible at all times. The later visualisation system
 * will display marker particles and coloured source links around it when
 * debugging is enabled.
 */
public class DebugIncursionAnchorBlock extends Block
        implements EntityBlock {

    private final DebugAnchorType anchorType;

    public DebugIncursionAnchorBlock(
            DebugAnchorType anchorType,
            Properties properties
    ) {
        super(properties);

        if (anchorType == null) {
            throw new IllegalArgumentException(
                    "Debug anchor type cannot be null."
            );
        }

        this.anchorType =
                anchorType;
    }

    public DebugAnchorType getAnchorType() {
        return anchorType;
    }

    @Override
    public RenderShape getRenderShape(
            BlockState state
    ) {
        return RenderShape.INVISIBLE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(
            BlockPos pos,
            BlockState state
    ) {
        return new DebugIncursionAnchorEntity(
                pos,
                state
        );
    }
}