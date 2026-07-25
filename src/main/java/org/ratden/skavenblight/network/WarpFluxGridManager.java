package org.ratden.skavenblight.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.ratden.skavenblight.capability.ModCapabilities;

import java.util.*;

public class WarpFluxGridManager extends SavedData {

    private final Map<UUID, WarpFluxNetwork> networks = new HashMap<>();
    private final Map<BlockPos, UUID> positionToNetwork = new HashMap<>();

    public WarpFluxGridManager() {}

    public WarpFluxNetwork getNetworkAt(BlockPos pos) {
        UUID id = positionToNetwork.get(pos);
        if (id != null) {
            return networks.get(id);
        }
        return null;
    }

    public static WarpFluxGridManager get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new Factory<>(
                        WarpFluxGridManager::new,
                        WarpFluxGridManager::load,
                        null
                ),
                "skavenblight_warp_flux_grids"
        );
    }

    // --- SavedData NBT Handling ---

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag networkList = new ListTag();

        for (WarpFluxNetwork network : networks.values()) {
            CompoundTag networkTag = new CompoundTag();
            networkTag.putUUID("networkId", network.getId());

            // Convert our HashSets of BlockPos into flat arrays of Longs
            long[] conduitArr = network.getConduits().stream().mapToLong(BlockPos::asLong).toArray();
            networkTag.putLongArray("conduits", conduitArr);

            long[] endpointArr = network.getEndpoints().stream().mapToLong(BlockPos::asLong).toArray();
            networkTag.putLongArray("endpoints", endpointArr);

            networkList.add(networkTag);
        }

        tag.put("networks", networkList);
        return tag;
    }

    // --- Network Discovery and Merging ---

    public void addConduit(ServerLevel level, BlockPos pos) {
        if (positionToNetwork.containsKey(pos)) return; // Already in a network

        Set<WarpFluxNetwork> adjacentNetworks = new HashSet<>();
        Set<BlockPos> adjacentEndpoints = new HashSet<>();

        // Check all 6 directions
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = pos.relative(dir);

            // 1. Is it another conduit?
            WarpFluxNetwork neighborNetwork = getNetworkAt(neighborPos);
            if (neighborNetwork != null) {
                adjacentNetworks.add(neighborNetwork);
            }
            // 2. Is it a machine with Warp Flux storage?
            else if (level.getCapability(ModCapabilities.WARP_FLUX, neighborPos, dir.getOpposite()) != null) {
                adjacentEndpoints.add(neighborPos);
            }
        }

        WarpFluxNetwork targetNetwork;
        boolean anyRegionMapCleaned = false;

        if (adjacentNetworks.isEmpty()) {
            // Case A: No nearby networks. Create a brand new one!
            targetNetwork = new WarpFluxNetwork();
            networks.put(targetNetwork.getId(), targetNetwork);
        } else {
            // Case B & C: Found 1 or more networks. Merge them all into the first one we found.
            Iterator<WarpFluxNetwork> iterator = adjacentNetworks.iterator();
            targetNetwork = iterator.next(); // Pick the first one to be our "Main" network

            while (iterator.hasNext()) {
                WarpFluxNetwork networkToMerge = iterator.next();

                // Move all conduits to the main network
                for (BlockPos oldConduitPos : networkToMerge.getConduits()) {
                    targetNetwork.addConduit(oldConduitPos);
                    positionToNetwork.put(oldConduitPos, targetNetwork.getId());
                }

                // Move all endpoints to the main network
                for (BlockPos oldEndpointPos : networkToMerge.getEndpoints()) {
                    targetNetwork.addEndpoint(oldEndpointPos);
                }

                // Delete the old, absorbed network. Release its region map's forced-chunk
                // tickets first - nothing else ever calls TerritoryRegionMap.cleanup, so the
                // tickets it took out would leak for the rest of the session.
                networkToMerge.getRegionMap().cleanup(level);
                networks.remove(networkToMerge.getId());
                anyRegionMapCleaned = true;
            }
        }

        // setChunkForced is one level-wide set: if the absorbed network's territory overlapped the
        // surviving one's, the cleanup above just dropped a ticket the survivor still believes it
        // holds (so syncTerritoryChunkTickets would never re-issue it). Re-assert its own tickets.
        if (anyRegionMapCleaned) {
            targetNetwork.getRegionMap().reassertChunkTickets(level);
        }

        // Add the newly placed conduit and newly discovered endpoints to our chosen network
        targetNetwork.addConduit(pos);
        positionToNetwork.put(pos, targetNetwork.getId());

        for (BlockPos endpoint : adjacentEndpoints) {
            targetNetwork.addEndpoint(endpoint);
        }
        // Update the territory bounding box
        targetNetwork.updateTerritory(org.ratden.skavenblight.Config.territoryChunkRadius);
        this.setDirty(); // Tells Minecraft to save the GridManager to the world file
    }

    // --- Network Splitting and Rebuilding ---

    public void removeConduit(ServerLevel level, BlockPos pos) {
        UUID networkId = positionToNetwork.remove(pos); // Remove the broken conduit
        if (networkId == null) return;

        WarpFluxNetwork oldNetwork = networks.remove(networkId);
        if (oldNetwork == null) return;

        // This network is gone (its survivors get rebuilt into brand-new networks below, each with
        // its own fresh region map) - hand its forced-chunk tickets back, or they leak for the rest
        // of the session. Any chunk a rebuilt network still needs is re-forced by that network's
        // own first region-map rebuild.
        oldNetwork.getRegionMap().cleanup(level);

        // 1. Unmap all remaining conduits that were part of this old network
        for (BlockPos cPos : oldNetwork.getConduits()) {
            if (!cPos.equals(pos)) {
                positionToNetwork.remove(cPos);
            }
        }

        // 2. Look at the 6 blocks surrounding the broken conduit.
        // If there are surviving conduits, rebuild a new network from them.
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            if (level.getBlockState(neighbor).getBlock() instanceof org.ratden.skavenblight.block.custom.WarpFluxConduitBlock) {

                // If this neighbor hasn't been scooped up by a rebuild yet, start a flood fill!
                if (!positionToNetwork.containsKey(neighbor)) {
                    rebuildNetwork(level, neighbor);
                }
            }
        }

        this.setDirty();
    }

    private void rebuildNetwork(ServerLevel level, BlockPos startPos) {
        WarpFluxNetwork newNetwork = new WarpFluxNetwork();
        networks.put(newNetwork.getId(), newNetwork);

        Queue<BlockPos> queue = new LinkedList<>();
        queue.add(startPos);

        // Standard Breadth-First Search (Flood Fill)
        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();

            // Skip if we already visited this block
            if (positionToNetwork.containsKey(current)) continue;

            // Add to new network
            newNetwork.addConduit(current);
            positionToNetwork.put(current, newNetwork.getId());

            // Check neighbors to continue the flood fill
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = current.relative(dir);

                if (level.getBlockState(neighbor).getBlock() instanceof org.ratden.skavenblight.block.custom.WarpFluxConduitBlock) {
                    if (!positionToNetwork.containsKey(neighbor)) {
                        queue.add(neighbor);
                    }
                }
                // Re-discover endpoints (Nexuses and consumers)
                else if (level.getCapability(ModCapabilities.WARP_FLUX, neighbor, dir.getOpposite()) != null) {
                    newNetwork.addEndpoint(neighbor);
                }
            }
        }
        // Call this after the grid manager finishes identifying the cables for a rebuilt network
        newNetwork.scanForEndpoints(level);

        // Update the territory bounding box based on the newly rebuilt network
        newNetwork.updateTerritory(org.ratden.skavenblight.Config.territoryChunkRadius);

        this.setDirty();
    }


    public static WarpFluxGridManager load(CompoundTag tag, HolderLookup.Provider registries) {
        WarpFluxGridManager manager = new WarpFluxGridManager();

        if (tag.contains("networks", Tag.TAG_LIST)) {
            ListTag networkList = tag.getList("networks", Tag.TAG_COMPOUND);

            for (int i = 0; i < networkList.size(); i++) {
                CompoundTag networkTag = networkList.getCompound(i);
                WarpFluxNetwork network = new WarpFluxNetwork(networkTag.getUUID("networkId"));

                // Unpack the longs right back into BlockPos!
                // No messy NbtUtils or Tag casting required.
                long[] conduitsLongs = networkTag.getLongArray("conduits");
                for (long posLong : conduitsLongs) {
                    BlockPos pos = BlockPos.of(posLong);
                    network.addConduit(pos);
                    manager.positionToNetwork.put(pos, network.getId());
                }

                long[] endpointsLongs = networkTag.getLongArray("endpoints");
                for (long posLong : endpointsLongs) {
                    BlockPos pos = BlockPos.of(posLong);
                    network.addEndpoint(pos);
                }

                manager.networks.put(network.getId(), network);
                network.updateTerritory(org.ratden.skavenblight.Config.territoryChunkRadius);
            }
        }
        return manager;
    }
    public Collection<WarpFluxNetwork> getAllNetworks() {
        return this.networks.values();
    }
    public void tickNetworks(ServerLevel level) {
        for (WarpFluxNetwork network : networks.values()) {
            network.tick(level);
        }
    }
}