package org.ratden.skavenblight.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class WarpstoneNexusEntity extends BlockEntity {

    private int nexusTier;
    private int stability;
    private int energy;

    public WarpstoneNexusEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.WARPSTONE_NEXUS.get(), pos, blockState);

        this.nexusTier = 0;
        this.stability = 100;
        this.energy = 0;
    }

    public int getNexusTier() {
        return nexusTier;
    }

    public int getStability() {
        return stability;
    }

    public int getEnergy() {
        return energy;
    }

    public void setNexusTier(int nexusTier) {
        this.nexusTier = Math.max(0, nexusTier);
        setChanged();
    }

    public void setStability(int stability) {
        this.stability = Math.max(0, stability);
        setChanged();
    }

    public void setEnergy(int energy) {
        this.energy = Math.max(0, energy);
        setChanged();
    }
}