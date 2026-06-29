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
                new SavedData.Factory<>(
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

            ListTag conduitList = new ListTag();
            for (BlockPos pos : network.getConduits()) {
                conduitList.add(NbtUtils.writeBlockPos(pos));
            }
            networkTag.put("conduits", conduitList);

            ListTag endpointList = new ListTag();
            for (BlockPos pos : network.getEndpoints()) {
                endpointList.add(NbtUtils.writeBlockPos(pos));
            }
            networkTag.put("endpoints", endpointList);

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

                // Delete the old, absorbed network
                networks.remove(networkToMerge.getId());
            }
        }

        // Add the newly placed conduit and newly discovered endpoints to our chosen network
        targetNetwork.addConduit(pos);
        positionToNetwork.put(pos, targetNetwork.getId());

        for (BlockPos endpoint : adjacentEndpoints) {
            targetNetwork.addEndpoint(endpoint);
        }

        this.setDirty(); // Tells Minecraft to save the GridManager to the world file
    }

    public static WarpFluxGridManager load(CompoundTag tag, HolderLookup.Provider registries) {
        WarpFluxGridManager manager = new WarpFluxGridManager();

        if (tag.contains("networks", Tag.TAG_LIST)) {
            ListTag networkList = tag.getList("networks", Tag.TAG_COMPOUND);
            for (int i = 0; i < networkList.size(); i++) {
                CompoundTag networkTag = networkList.getCompound(i);

                // We will need a constructor in WarpFluxNetwork that takes a UUID
                WarpFluxNetwork network = new WarpFluxNetwork(networkTag.getUUID("networkId"));

                ListTag conduitList = networkTag.getList("conduits", Tag.TAG_INT_ARRAY);
                for (int j = 0; j < conduitList.size(); j++) {
                    BlockPos pos = NbtUtils.readBlockPos(conduitList.getCompound(j), "pos").orElse(BlockPos.ZERO);
                    if (!pos.equals(BlockPos.ZERO)) {
                        network.addConduit(pos);
                        manager.positionToNetwork.put(pos, network.getId());
                    }
                }

                ListTag endpointList = networkTag.getList("endpoints", Tag.TAG_INT_ARRAY);
                for (int j = 0; j < endpointList.size(); j++) {
                    BlockPos pos = NbtUtils.readBlockPos(endpointList.getCompound(j), "pos").orElse(BlockPos.ZERO);
                    if (!pos.equals(BlockPos.ZERO)) {
                        network.addEndpoint(pos);
                    }
                }

                manager.networks.put(network.getId(), network);
            }
        }
        return manager;
    }
}