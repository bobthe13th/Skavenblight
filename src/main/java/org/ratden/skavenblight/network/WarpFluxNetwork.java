package org.ratden.skavenblight.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.ratden.skavenblight.block.custom.WarpFluxConduitBlock;
import org.ratden.skavenblight.block.entity.WarpstoneNexusEntity;
import org.ratden.skavenblight.block.entity.WarpFluxStorageBlockEntity;
import org.ratden.skavenblight.capability.ModCapabilities;
import org.ratden.skavenblight.capability.custom.IWarpFluxStorage;

import java.util.*;

public class WarpFluxNetwork {
    private final UUID networkId;

    private final Set<BlockPos> conduits = new HashSet<>();
    private final Set<BlockPos> endpoints = new HashSet<>();

    public WarpFluxNetwork() {
        this.networkId = UUID.randomUUID();
    }

    public WarpFluxNetwork(UUID networkId) {
        this.networkId = networkId;
    }

    public UUID getId() { return this.networkId; }
    public Set<BlockPos> getConduits() { return this.conduits; }
    public Set<BlockPos> getEndpoints() { return this.endpoints; }

    public void addConduit(BlockPos pos) { this.conduits.add(pos); }
    public void addEndpoint(BlockPos pos) { this.endpoints.add(pos); }

    public void tick(ServerLevel level) {
        if (endpoints.isEmpty()) return;

        List<EndpointData> generators = new ArrayList<>();
        List<EndpointData> batteries = new ArrayList<>();
        List<EndpointData> consumers = new ArrayList<>();

        // 1. Categorize
        for (BlockPos pos : endpoints) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null) continue;

            IWarpFluxStorage storage = level.getCapability(ModCapabilities.WARP_FLUX, pos, null);
            if (storage == null) continue;

            EndpointData data = new EndpointData(pos, storage);

            if (be instanceof WarpstoneNexusEntity) {
                generators.add(data);
            } else if (be instanceof WarpFluxStorageBlockEntity) {
                batteries.add(data);
            } else {
                consumers.add(data);
            }
        }

        // 2. Sort Consumers (Emptiest machines first)
        consumers.sort(Comparator.comparingDouble(s ->
                s.storage().getMaxFlux() == 0 ? 1.0 : (double) s.storage().getFlux() / s.storage().getMaxFlux()
        ));

        // 3. Transfer Power
        transferPower(generators, consumers, level);
        transferPower(batteries, consumers, level);
        transferPower(generators, batteries, level);
    }
    public void scanForEndpoints(ServerLevel level) {
        this.endpoints.clear();
        for (BlockPos conduitPos : this.conduits) {
            for (Direction dir : Direction.values()) {
                BlockPos neighborPos = conduitPos.relative(dir);
                if (!this.conduits.contains(neighborPos)) {
                    // Check if the adjacent block has our custom capability
                    IWarpFluxStorage storage = level.getCapability(ModCapabilities.WARP_FLUX, neighborPos, dir.getOpposite());
                    if (storage != null) {
                        this.endpoints.add(neighborPos);
                    }
                }
            }
        }
    }

    private void transferPower(List<EndpointData> sources, List<EndpointData> destinations, ServerLevel level) {
        for (EndpointData dest : destinations) {
            int needed = dest.storage().getMaxFlux() - dest.storage().getFlux();
            if (needed <= 0) continue;

            for (EndpointData src : sources) {
                int available = src.storage().extractFlux(Integer.MAX_VALUE, true);
                if (available <= 0) continue;

                int accepted = dest.storage().receiveFlux(available, false);
                if (accepted > 0) {
                    src.storage().extractFlux(accepted, false);

                    // Power successfully moved! Find and light up the exact path.
                    triggerPathGlow(src.pos(), dest.pos(), accepted, level);
                }

                if (dest.storage().getFlux() >= dest.storage().getMaxFlux()) {
                    break;
                }
            }
        }
    }

    private void triggerPathGlow(BlockPos start, BlockPos end, int fluxAmount, ServerLevel level) {
        Queue<BlockPos> queue = new LinkedList<>();
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();

        for (Direction dir : Direction.values()) {
            BlockPos adj = start.relative(dir);
            if (this.conduits.contains(adj)) {
                queue.add(adj);
                cameFrom.put(adj, start);
            }
        }

        BlockPos endConduit = null;

        // BFS to find shortest path
        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();

            boolean touchesEnd = false;
            for (Direction dir : Direction.values()) {
                if (current.relative(dir).equals(end)) {
                    touchesEnd = true;
                    break;
                }
            }

            if (touchesEnd) {
                endConduit = current;
                break;
            }

            for (Direction dir : Direction.values()) {
                BlockPos neighbor = current.relative(dir);
                if (this.conduits.contains(neighbor) && !cameFrom.containsKey(neighbor)) {
                    cameFrom.put(neighbor, current);
                    queue.add(neighbor);
                }
            }
        }

        // Trace the path backwards and update the block states
        if (endConduit != null) {
            BlockPos current = endConduit;
            int maxNetworkCapacity = 1;

            BlockPos nextBlockInPath = end;

            while (current != null && !current.equals(start)) {
                BlockPos previousBlockInPath = cameFrom.get(current); // Where power comes FROM

                // 1. Tell BlockEntity to glow (Handles intensity)
                BlockEntity be = level.getBlockEntity(current);
                if (be instanceof org.ratden.skavenblight.block.entity.WarpFluxConduitBlockEntity conduitEntity) {
                    conduitEntity.triggerTransferGlow(fluxAmount, maxNetworkCapacity);
                }

                // 2. Set the block states for directional flow arms
                BlockState state = level.getBlockState(current);
                BlockState originalState = state; // Track state to prevent laggy block updates if already glowing

                // Activate arm pointing to the NEXT block (Consumer or next conduit)
                Direction dirToNext = getDirectionTo(current, nextBlockInPath);
                if (dirToNext != null) state = setDirectionActive(state, dirToNext, true);

                // Activate arm pointing to the PREVIOUS block (Generator or previous conduit)
                Direction dirToPrev = getDirectionTo(current, previousBlockInPath);
                if (dirToPrev != null) state = setDirectionActive(state, dirToPrev, true);

                // Only trigger a block update if the state actually changed!
                if (state != originalState) {
                    level.setBlock(current, state, 3);
                }



                // Move backwards up the chain
                nextBlockInPath = current;
                current = previousBlockInPath;
            }
        }
    }

    private Direction getDirectionTo(BlockPos from, BlockPos to) {
        for (Direction dir : Direction.values()) {
            if (from.relative(dir).equals(to)) return dir;
        }
        return null;
    }

    private BlockState setDirectionActive(BlockState state, Direction dir, boolean active) {
        if (!(state.getBlock() instanceof WarpFluxConduitBlock)) return state;
        return switch (dir) {
            case NORTH -> state.setValue(WarpFluxConduitBlock.NORTH_ACTIVE, active);
            case SOUTH -> state.setValue(WarpFluxConduitBlock.SOUTH_ACTIVE, active);
            case EAST -> state.setValue(WarpFluxConduitBlock.EAST_ACTIVE, active);
            case WEST -> state.setValue(WarpFluxConduitBlock.WEST_ACTIVE, active);
            case UP -> state.setValue(WarpFluxConduitBlock.UP_ACTIVE, active);
            case DOWN -> state.setValue(WarpFluxConduitBlock.DOWN_ACTIVE, active);
        };
    }

    private record EndpointData(BlockPos pos, IWarpFluxStorage storage) {}
}