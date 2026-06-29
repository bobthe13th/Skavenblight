package org.ratden.skavenblight.network;

import net.minecraft.core.BlockPos;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class WarpFluxNetwork {
    private final UUID networkId;

    // The stateless cables that make up this specific network
    private final Set<BlockPos> conduits = new HashSet<>();

    // The machines (Nexuses, consumers) attached to this network
    private final Set<BlockPos> endpoints = new HashSet<>();

    public WarpFluxNetwork() {
        this.networkId = UUID.randomUUID();
    }

    public WarpFluxNetwork(UUID networkId) {
        this.networkId = networkId;
    }

    public UUID getId() {
        return this.networkId;
    }

    public Set<BlockPos> getConduits() {
        return this.conduits;
    }

    public Set<BlockPos> getEndpoints() {
        return this.endpoints;
    }

    public void addConduit(BlockPos pos) {
        this.conduits.add(pos);
    }

    public void addEndpoint(BlockPos pos) {
        this.endpoints.add(pos);
    }

    // We will add methods for merging networks and routing power later!

}