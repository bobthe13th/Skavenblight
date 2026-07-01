package org.ratden.skavenblight.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.ratden.skavenblight.block.entity.WarpstoneNexusEntity;
import org.ratden.skavenblight.capability.ModCapabilities;
import org.ratden.skavenblight.block.custom.WarpFluxConduitBlock;

import java.util.*;

public class WarpFluxNetwork {
    private final UUID networkId;

    private final Set<BlockPos> conduits = new HashSet<>();
    private final Set<BlockPos> endpoints = new HashSet<>();

    // Tracks the remaining glow time (0 to 60 ticks) for individual conduits
    private final Map<BlockPos, Integer> conduitTimers = new HashMap<>();

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

    public boolean isValid(ServerLevel level) {
        for (BlockPos pos : endpoints) {
            if (level.getBlockEntity(pos) instanceof WarpstoneNexusEntity) {
                return true;
            }
        }
        return false;
    }

    public void tick(ServerLevel level) {
        // --- 1. INDIVIDUAL CONDUIT DECAY LOGIC ---
        if (!conduitTimers.isEmpty()) {
            Iterator<Map.Entry<BlockPos, Integer>> iterator = conduitTimers.entrySet().iterator();

            while (iterator.hasNext()) {
                Map.Entry<BlockPos, Integer> entry = iterator.next();
                BlockPos pos = entry.getKey();
                int timeRemaining = entry.getValue() - 1;

                BlockState state = level.getBlockState(pos);

                // SAFETY CHECK: If the pipe was the one you just broke, stop tracking it!
                if (!(state.getBlock() instanceof WarpFluxConduitBlock)) {
                    iterator.remove();
                    continue;
                }

                int targetIntensity = 0; // Off
                if (timeRemaining > 40) targetIntensity = 3;      // 3 to 2 seconds: Strong
                else if (timeRemaining > 20) targetIntensity = 2; // 2 to 1 seconds: Medium
                else if (timeRemaining > 0) targetIntensity = 1;  // 1 to 0 seconds: Pale

                int currentIntensity = state.getValue(WarpFluxConduitBlock.GLOW_INTENSITY);
                if (currentIntensity != targetIntensity) {
                    level.setBlock(pos, state.setValue(WarpFluxConduitBlock.GLOW_INTENSITY, targetIntensity), 3);
                }

                if (timeRemaining <= 0) {
                    iterator.remove();

                    // The pipe is completely cold. Shut off all routing arms!
                    state = state.setValue(WarpFluxConduitBlock.NORTH_ACTIVE, false)
                            .setValue(WarpFluxConduitBlock.SOUTH_ACTIVE, false)
                            .setValue(WarpFluxConduitBlock.EAST_ACTIVE, false)
                            .setValue(WarpFluxConduitBlock.WEST_ACTIVE, false)
                            .setValue(WarpFluxConduitBlock.UP_ACTIVE, false)
                            .setValue(WarpFluxConduitBlock.DOWN_ACTIVE, false);

                    level.setBlock(pos, state.setValue(WarpFluxConduitBlock.GLOW_INTENSITY, 0), 3);
                } else {
                    if (currentIntensity != targetIntensity) {
                        level.setBlock(pos, state.setValue(WarpFluxConduitBlock.GLOW_INTENSITY, targetIntensity), 3);
                    }
                    entry.setValue(timeRemaining);
                }
            }
        }

        // --- 2. BATTERY BALANCING LOGIC ---
        if (endpoints.isEmpty()) return;

        long totalFlux = 0;
        long totalCapacity = 0;

        List<org.ratden.skavenblight.capability.custom.IWarpFluxStorage> connectedStorages = new ArrayList<>();

        for (BlockPos pos : endpoints) {
            var storage = level.getCapability(ModCapabilities.WARP_FLUX, pos, null);
            if (storage != null && storage.getMaxFlux() > 0) {
                connectedStorages.add(storage);
                totalFlux += storage.getFlux();
                totalCapacity += storage.getMaxFlux();
            }
        }

        if (connectedStorages.isEmpty() || totalCapacity == 0) return;

        double fillPercentage = (double) totalFlux / totalCapacity;
        long distributedFlux = 0;

        for (int i = 0; i < connectedStorages.size(); i++) {
            var storage = connectedStorages.get(i);
            if (i == connectedStorages.size() - 1) {
                storage.setFlux((int) (totalFlux - distributedFlux));
            } else {
                int targetFlux = (int) (storage.getMaxFlux() * fillPercentage);
                storage.setFlux(targetFlux);
                distributedFlux += targetFlux;
            }
        }
    }

    public int pushFlux(ServerLevel level, int maxAmount, BlockPos sourcePos) {
        if (maxAmount <= 0) return 0;

        List<org.ratden.skavenblight.capability.custom.IWarpFluxStorage> validStorages = new ArrayList<>();
        List<BlockPos> validPositions = new ArrayList<>();

        for (BlockPos endpointPos : endpoints) {
            if (endpointPos.equals(sourcePos)) continue;

            var fluxStorage = level.getCapability(ModCapabilities.WARP_FLUX, endpointPos, null);
            if (fluxStorage != null && fluxStorage.receiveFlux(1, true) > 0) {
                validStorages.add(fluxStorage);
                validPositions.add(endpointPos);
            }
        }

        if (validStorages.isEmpty()) return 0;

        int splitAmount = maxAmount / validStorages.size();
        int remainder = maxAmount % validStorages.size();

        int totalPushed = 0;

        // Tracks exactly which arms are actively routing power this tick
        Map<BlockPos, Set<Direction>> activeDirsThisPulse = new HashMap<>();

        for (int i = 0; i < validStorages.size(); i++) {
            var storage = validStorages.get(i);
            BlockPos receiverPos = validPositions.get(i);

            int amountToPush = splitAmount;
            if (i < remainder) amountToPush += 1;

            if (amountToPush > 0) {
                int accepted = storage.receiveFlux(amountToPush, false);
                if (accepted > 0) {
                    totalPushed += accepted;

                    // --- 3. TRACE PATHS ---
                    List<BlockPos> path = findShortestPath(sourcePos, receiverPos);
                    if (!path.isEmpty()) {
                        List<BlockPos> fullChain = new ArrayList<>();
                        fullChain.add(sourcePos);
                        fullChain.addAll(path);
                        fullChain.add(receiverPos);

                        for (int j = 1; j < fullChain.size() - 1; j++) {
                            BlockPos current = fullChain.get(j);
                            BlockPos prev = fullChain.get(j - 1);
                            BlockPos next = fullChain.get(j + 1);

                            Direction dirToPrev = getDirectionTo(current, prev);
                            Direction dirToNext = getDirectionTo(current, next);

                            // Add the entry and exit directions for this specific conduit block
                            activeDirsThisPulse.computeIfAbsent(current, k -> new HashSet<>())
                                    .addAll(Arrays.asList(dirToPrev, dirToNext));
                        }
                    }
                }
            }
        }

        // --- 4. APPLY EXACT VISUALS ---
        // This ensures the active paths turn ON, and any "Ghost" branches turn OFF instantly!
        for (Map.Entry<BlockPos, Set<Direction>> entry : activeDirsThisPulse.entrySet()) {
            BlockPos current = entry.getKey();
            Set<Direction> activeDirs = entry.getValue();

            BlockState state = level.getBlockState(current);
            if (state.getBlock() instanceof WarpFluxConduitBlock) {
                state = state.setValue(WarpFluxConduitBlock.NORTH_ACTIVE, activeDirs.contains(Direction.NORTH))
                        .setValue(WarpFluxConduitBlock.SOUTH_ACTIVE, activeDirs.contains(Direction.SOUTH))
                        .setValue(WarpFluxConduitBlock.EAST_ACTIVE, activeDirs.contains(Direction.EAST))
                        .setValue(WarpFluxConduitBlock.WEST_ACTIVE, activeDirs.contains(Direction.WEST))
                        .setValue(WarpFluxConduitBlock.UP_ACTIVE, activeDirs.contains(Direction.UP))
                        .setValue(WarpFluxConduitBlock.DOWN_ACTIVE, activeDirs.contains(Direction.DOWN));

                level.setBlock(current, state, 3);
            }

            // Refresh the cooldown timer
            conduitTimers.put(current, 60);
        }

        return totalPushed;
    }

    // --- BREADTH-FIRST SEARCH PATHFINDING ---
    private List<BlockPos> findShortestPath(BlockPos start, BlockPos end) {
        Queue<BlockPos> queue = new LinkedList<>();
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();

        queue.add(start);
        cameFrom.put(start, null);

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();

            // If we reached the receiver, trace our steps backwards
            if (current.equals(end)) {
                List<BlockPos> path = new ArrayList<>();
                BlockPos step = end;
                while (step != null) {
                    // Only add the actual cables to our glow list
                    if (conduits.contains(step)) {
                        path.add(step);
                    }
                    step = cameFrom.get(step);
                }
                return path;
            }

            // Check neighbors
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = current.relative(dir);
                if (!cameFrom.containsKey(neighbor)) {
                    // Only explore paths that are part of our conduits OR the final destination
                    if (conduits.contains(neighbor) || neighbor.equals(end)) {
                        cameFrom.put(neighbor, current);
                        queue.add(neighbor);
                    }
                }
            }
        }
        return Collections.emptyList(); // No path found
    }

    public void scanForEndpoints(ServerLevel level) {
        this.endpoints.clear();
        for (BlockPos conduitPos : this.conduits) {

            // 1. Scan for machines attached to the pipes
            for (Direction dir : Direction.values()) {
                BlockPos neighborPos = conduitPos.relative(dir);
                if (level.getCapability(ModCapabilities.WARP_FLUX, neighborPos, dir.getOpposite()) != null) {
                    this.endpoints.add(neighborPos);
                }
            }

            // --- 2. COOLDOWN RECOVERY ---
            // If the network was just split/rebuilt, inherit the glow states from the physical blocks!
            BlockState state = level.getBlockState(conduitPos);
            if (state.getBlock() instanceof WarpFluxConduitBlock) {
                int currentIntensity = state.getValue(WarpFluxConduitBlock.GLOW_INTENSITY);

                // If it's glowing but not in our timer map yet, adopt it!
                if (currentIntensity > 0 && !conduitTimers.containsKey(conduitPos)) {
                    int timeToRecover = 0;
                    if (currentIntensity == 3) timeToRecover = 60;
                    else if (currentIntensity == 2) timeToRecover = 40;
                    else if (currentIntensity == 1) timeToRecover = 20;

                    conduitTimers.put(conduitPos, timeToRecover);
                }
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
}