package org.ratden.skavenblight.capability.custom;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

public class WarpFluxStorage implements IWarpFluxStorage {

    private int flux;
    private int capacity;
    private int maxReceive;
    private int maxExtract;

    public WarpFluxStorage(int capacity, int maxReceive, int maxExtract) {
        this.capacity = capacity;
        this.maxReceive = maxReceive;
        this.maxExtract = maxExtract;
        this.flux = 0; // Starts empty by default
    }

    // Inside WarpFluxStorage.java
    public void setStats(int newCapacity, int newMaxReceive, int newMaxExtract) {
        this.capacity = newCapacity;
        this.maxReceive = newMaxReceive;
        this.maxExtract = newMaxExtract;
        // Ensure current flux doesn't exceed the new capacity if down-tiered
        if (this.flux > this.capacity) {
            this.flux = this.capacity;
        }
    }

    @Override
    public int getFlux() {
        return this.flux;
    }

    @Override
    public int getMaxFlux() {
        return this.capacity;
    }

    @Override
    public int receiveFlux(int maxReceive, boolean simulate) {
        // Calculate how much space is left, and take the smallest value between space, maxReceive, and max transfer rate
        int fluxReceived = Math.min(this.capacity - this.flux, Math.min(this.maxReceive, maxReceive));

        if (!simulate) {
            this.flux += fluxReceived;
        }
        return fluxReceived;
    }

    @Override
    public int extractFlux(int maxExtract, boolean simulate) {
        // Calculate how much we can actually take out
        int fluxExtracted = Math.min(this.flux, Math.min(this.maxExtract, maxExtract));

        if (!simulate) {
            this.flux -= fluxExtracted;
        }
        return fluxExtracted;
    }

    // --- Utility Methods for BlockEntities ---

    public void setFlux(int flux) {
        this.flux = Math.max(0, Math.min(flux, this.capacity));
    }

    public void saveNBTData(CompoundTag nbt, HolderLookup.Provider provider) {
        nbt.putInt("WarpFlux", this.flux);
    }

    public void loadNBTData(CompoundTag nbt, HolderLookup.Provider provider) {
        this.flux = nbt.getInt("WarpFlux");
    }
}
