package org.ratden.skavenblight.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.ratden.skavenblight.Config; // Added this import!
import org.ratden.skavenblight.capability.custom.WarpFluxStorage;

public class WarpstoneNexusEntity extends BlockEntity {

    private int nexusTier;
    private int stability;
    private final WarpFluxStorage fluxStorage;

    public WarpstoneNexusEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.WARPSTONE_NEXUS.get(), pos, blockState);
        this.nexusTier = 0;
        this.stability = 100;

        // Initialize with dummy values, then immediately sync with the Config
        this.fluxStorage = new WarpFluxStorage(0, 0, 0);
        updateTierStats();
    }

    public WarpFluxStorage getFluxStorage() {
        return fluxStorage;
    }

    public void setNexusTier(int nexusTier) {
        this.nexusTier = Math.max(0, nexusTier);
        updateTierStats();
        setChanged();
    }

    // Updates the capability limits when the tier changes, reading from Config
    private void updateTierStats() {
        switch (this.nexusTier) {
            case 0 -> this.fluxStorage.setStats(Config.tier0Capacity, 0, Config.tier0Generation);
            case 1 -> this.fluxStorage.setStats(Config.tier1Capacity, 0, Config.tier1Generation);
            case 2 -> this.fluxStorage.setStats(Config.tier2Capacity, 0, Config.tier2Generation);
            default -> this.fluxStorage.setStats(Config.tier0Capacity, 0, Config.tier0Generation);
        }
    }

    // Called every tick by the block
    public void tick(Level level, BlockPos pos, BlockState state) {
        if (level.isClientSide()) return; // Only generate on the server

        int generationRate = switch (this.nexusTier) {
            case 0 -> Config.tier0Generation;
            case 1 -> Config.tier1Generation;
            case 2 -> Config.tier2Generation;
            default -> Config.tier0Generation;
        };

        // Bypass 'receiveFlux' limits using setFlux because this is internal generation
        int current = this.fluxStorage.getFlux();
        int max = this.fluxStorage.getMaxFlux();

        if (current < max) {
            this.fluxStorage.setFlux(Math.min(max, current + generationRate));
            setChanged(); // Tells Minecraft this block needs to be saved
        }
    }

    // --- Save and Load Data ---

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("nexusTier", this.nexusTier);
        tag.putInt("stability", this.stability);
        this.fluxStorage.saveNBTData(tag, registries);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.nexusTier = tag.getInt("nexusTier");
        this.stability = tag.getInt("stability");
        updateTierStats(); // Ensure storage limits match the loaded tier
        this.fluxStorage.loadNBTData(tag, registries);
    }

    // Standard Getters & Setters
    public int getNexusTier() { return nexusTier; }
    public int getStability() { return stability; }
    public void setStability(int stability) {
        this.stability = Math.max(0, stability);
        setChanged();
    }
}