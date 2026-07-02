package org.ratden.skavenblight.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.ratden.skavenblight.block.custom.WarpFluxConduitBlock;

public class WarpFluxConduitBlockEntity extends BlockEntity {

    private int glowTicksRemaining = 0;
    private int currentIntensity = 0;

    public WarpFluxConduitBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.WARP_FLUX_CONDUIT.get(), pos, blockState);
        // Note: You will need to register WARP_FLUX_CONDUIT in ModBlockEntities
    }

    public void triggerTransferGlow(int fluxAmount, int maxCapacity) {
        // Ignore the math! Any flux at all immediately pushes the conduit to max brightness.
        this.currentIntensity = 3;

        // Hold this maximum brightness for 10 ticks (half a second) before it begins to fade
        this.glowTicksRemaining = 10;
    }

    // Removed 'static' and the 'WarpFluxConduitBlockEntity entity' parameter!
    public void tick(Level level, BlockPos pos, BlockState state) {
        if (level.isClientSide) return;

        if (this.glowTicksRemaining > 0) {
            this.glowTicksRemaining--;

            if (state.getValue(WarpFluxConduitBlock.GLOW_INTENSITY) != this.currentIntensity) {
                level.setBlock(pos, state.setValue(WarpFluxConduitBlock.GLOW_INTENSITY, this.currentIntensity), 3);
            }

        } else if (this.currentIntensity > 0) {
            // The timer ran out, time to fade down!
            this.currentIntensity--;

            if (this.currentIntensity > 0) {
                // Hold at the lower intensity for a few ticks
                this.glowTicksRemaining = 5;
                level.setBlock(pos, state.setValue(WarpFluxConduitBlock.GLOW_INTENSITY, this.currentIntensity), 3);
            } else {
                // We finally hit 0! Turn off the core AND all the connection arms simultaneously.
                BlockState turnOffState = state.setValue(WarpFluxConduitBlock.GLOW_INTENSITY, 0)
                        .setValue(WarpFluxConduitBlock.NORTH_ACTIVE, false)
                        .setValue(WarpFluxConduitBlock.SOUTH_ACTIVE, false)
                        .setValue(WarpFluxConduitBlock.EAST_ACTIVE, false)
                        .setValue(WarpFluxConduitBlock.WEST_ACTIVE, false)
                        .setValue(WarpFluxConduitBlock.UP_ACTIVE, false)
                        .setValue(WarpFluxConduitBlock.DOWN_ACTIVE, false);

                level.setBlock(pos, turnOffState, 3);
            }
        }
    }
}