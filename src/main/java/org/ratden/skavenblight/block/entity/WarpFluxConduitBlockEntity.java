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
        // Calculate intensity based on transfer size relative to config max
        float percentage = (float) fluxAmount / maxCapacity;

        int targetIntensity;
        if (percentage > 0.75f) targetIntensity = 3; // Strong
        else if (percentage > 0.25f) targetIntensity = 2; // Medium
        else targetIntensity = 1; // Pale

        this.currentIntensity = targetIntensity;
        this.glowTicksRemaining = 10; // Glow for half a second after a transfer tick
    }

    public void tick(Level level, BlockPos pos, BlockState state) {
        if (level.isClientSide) return;

        if (glowTicksRemaining > 0) {
            glowTicksRemaining--;

            // Ensure the block state matches the current intensity
            if (state.getValue(WarpFluxConduitBlock.GLOW_INTENSITY) != currentIntensity) {
                level.setBlock(pos, state.setValue(WarpFluxConduitBlock.GLOW_INTENSITY, currentIntensity), 3);
            }
        } else if (state.getValue(WarpFluxConduitBlock.GLOW_INTENSITY) != 0) {
            // Turn off the glow
            this.currentIntensity = 0;
            level.setBlock(pos, state.setValue(WarpFluxConduitBlock.GLOW_INTENSITY, 0), 3);
        }
    }
}