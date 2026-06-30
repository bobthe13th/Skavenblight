package org.ratden.skavenblight.network;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.block.entity.WarpstoneNexusEntity;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class WarpFluxNetwork {
    private final UUID networkId;

    // The stateless cables that make up this specific network
    private final Set<BlockPos> conduits = new HashSet<>();

    // The machines (Nexuses, consumers) attached to this network
    private final Set<BlockPos> endpoints = new HashSet<>();

    // This checks if the network actually contains a Nexus
    public boolean isValid(ServerLevel level) {
        for (BlockPos pos : endpoints) {
            if (level.getBlockEntity(pos) instanceof WarpstoneNexusEntity) {
                return true;
            }
        }
        return false;
    }

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

    public int pushFlux(ServerLevel level, int maxAmount, BlockPos sourcePos) {
        //if (maxAmount <= 0 || !isValid(level)) return 0;

        int remainingFlux = maxAmount;

        for (BlockPos endpointPos : endpoints) {
            // 1. Don't feed power back into the machine that sent it!
            if (endpointPos.equals(sourcePos)) continue;

            // 2. Grab the capability of the receiving machine
            // (We pass 'null' for the side, assuming your machines accept power globally.
            // If they are strictly sided, you'll need to check the 6 adjacent blocks to find the conduit).
            var fluxStorage = level.getCapability(org.ratden.skavenblight.capability.ModCapabilities.WARP_FLUX, endpointPos, null);

            if (fluxStorage != null) {
                // 3. Push as much as we can into the machine
                int accepted = fluxStorage.receiveFlux(remainingFlux, false);
                remainingFlux -= accepted;

                // 4. If we run out of power to give, stop checking endpoints
                if (remainingFlux <= 0) {
                    break;
                }
            }
        }

        // Calculate exactly how much power actually left the network
        int fluxTransferred = maxAmount - remainingFlux;

        // Optional: Make the network look active!
        if (fluxTransferred > 0) {
            triggerActiveEffects(level);
        }

        return fluxTransferred;
    }

    private void triggerActiveEffects(ServerLevel level) {
        // To prevent TPS lag from updating 100 blockstates every single tick,
        // a highly optimized way to show activity is to spawn a particle at a random conduit!
        if (conduits.isEmpty()) return;

        // Pick a random cable in the network and spawn a warp lightning/green flame particle
        int randomIndex = level.random.nextInt(conduits.size());
        BlockPos randomConduit = (BlockPos) conduits.toArray()[randomIndex];

        // Replace with whatever your mod's warp particle is!
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.WAX_ON,
                randomConduit.getX() + 0.5, randomConduit.getY() + 0.5, randomConduit.getZ() + 0.5,
                1, 0.2, 0.2, 0.2, 0.0);
    }

    public void scanForEndpoints(ServerLevel level) {
        this.endpoints.clear();
        for (BlockPos conduitPos : this.conduits) {
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                BlockPos neighborPos = conduitPos.relative(dir);
                // If a machine is touching this cable, add it to our endpoints!
                if (level.getCapability(org.ratden.skavenblight.capability.ModCapabilities.WARP_FLUX, neighborPos, dir.getOpposite()) != null) {
                    this.endpoints.add(neighborPos);
                }
            }
        }
    }

}