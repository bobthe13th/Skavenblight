package org.ratden.skavenblight.capability.custom;

public interface IWarpFluxStorage {

    /** Returns the current amount of flux stored. */
    int getFlux();

    /** Returns the maximum capacity of flux this storage can hold. */
    int getMaxFlux();

    /**
     * Attempts to add flux to the storage.
     * @param maxReceive The maximum amount to insert.
     * @param simulate If true, the insertion will only be calculated, not actually applied.
     * @return The actual amount of flux that was (or would be) accepted.
     */
    int receiveFlux(int maxReceive, boolean simulate);

    /**
     * Attempts to remove flux from the storage.
     * @param maxExtract The maximum amount to extract.
     * @param simulate If true, the extraction will only be calculated, not actually applied.
     * @return The actual amount of flux that was (or would be) extracted.
     */
    int extractFlux(int maxExtract, boolean simulate);

    /** Forcefully sets the flux amount, bypassing transfer limits. Used for network balancing. */
    void setFlux(int amount);
}
