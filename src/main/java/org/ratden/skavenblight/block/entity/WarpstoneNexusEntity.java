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
        if (level.isClientSide()) return; // Only process power on the server

        net.minecraft.server.level.ServerLevel serverLevel = (net.minecraft.server.level.ServerLevel) level;

        // ==========================================
        // GENERATION LOGIC
        // ==========================================
        int generationRate = switch (this.nexusTier) {
            case 1 -> org.ratden.skavenblight.Config.tier1Generation;
            case 2 -> org.ratden.skavenblight.Config.tier2Generation;
            default -> org.ratden.skavenblight.Config.tier0Generation;
        };

        int current = this.fluxStorage.getFlux();
        int max = this.fluxStorage.getMaxFlux();

        if (current < max) {
            this.fluxStorage.setFlux(Math.min(max, current + generationRate));
            setChanged(); // Tells Minecraft this block needs to be saved
        }

        // ==========================================
        // NETWORK TRANSFER LOGIC
        // ==========================================
        int currentFlux = this.fluxStorage.getFlux();
        if (currentFlux <= 0) return;

        // Simulate an extraction to find out exactly how much energy this Nexus tier
        // is allowed to output this tick based on its internal 'maxExtract' value.
        int amountToPush = this.fluxStorage.extractFlux(currentFlux, true);
        if (amountToPush <= 0) return;

        // Grab the Grid Manager to locate nearby networks
        org.ratden.skavenblight.network.WarpFluxGridManager manager =
                org.ratden.skavenblight.network.WarpFluxGridManager.get(serverLevel);

        // Look at all 6 sides of the Nexus to find a connected Conduit Network
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
            BlockPos neighborPos = pos.relative(dir);
            org.ratden.skavenblight.network.WarpFluxNetwork network = manager.getNetworkAt(neighborPos);

            if (network != null) {
                // Push the flux directly onto the network!
                int transferred = network.pushFlux(serverLevel, amountToPush, pos);

                if (transferred > 0) {
                    // Deduct the successfully transferred power from the Nexus's storage (non-simulated)
                    this.fluxStorage.extractFlux(transferred, false);
                    this.setChanged();

                    // Subtract what we sent from our remaining allowance this tick
                    amountToPush -= transferred;

                    // If we've hit our max transfer limit for this tick, stop looking at other sides
                    if (amountToPush <= 0) {
                        break;
                    }
                }
            }
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