package org.ratden.skavenblight.item.custom;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.ratden.skavenblight.ai.pathing.SiegeNode;
import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;
import org.ratden.skavenblight.ai.pathing.region.TerritoryRegionMap;
import org.ratden.skavenblight.debug.PathingDebugFileWriter;
import org.ratden.skavenblight.debug.mode.server.DetailedServerMode;
import org.ratden.skavenblight.debug.mode.server.IServerDebugMode;
import org.ratden.skavenblight.debug.mode.server.MacroServerMode;
import org.ratden.skavenblight.debug.mode.server.WildernessServerMode;
import org.ratden.skavenblight.network.WarpFluxGridManager;
import org.ratden.skavenblight.network.WarpFluxNetwork;
import org.ratden.skavenblight.network.payload.SyncFlowFieldDebugPayload;
import org.ratden.skavenblight.world.NexusTracker;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class DebugFlowFieldReaderItem extends Item {

    // --- Expandable Mode Enum ---
    public enum DebugMode {
        DETAILED_NODES("Detailed Block Paths", new DetailedServerMode()),
        MACRO_NAVMESH("Macro NavMesh (Chunk Routing)", new MacroServerMode()),
        WILDERNESS_PATH("Wilderness Dynamic Routing", new WildernessServerMode());

        private final String displayName;
        private final IServerDebugMode serverLogic;

        DebugMode(String displayName, IServerDebugMode serverLogic) {
            this.displayName = displayName;
            this.serverLogic = serverLogic;
        }

        public String getDisplayName() { return displayName; }
        public IServerDebugMode getServerLogic() { return serverLogic; }
        public DebugMode next() { return values()[(this.ordinal() + 1) % values().length]; }
    }

    public DebugFlowFieldReaderItem(Properties properties) {
        super(properties);
    }

    // --- Right-Click Logic (Normal = Cycle Mode, Shift = Export Topology) ---
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            ServerLevel serverLevel = (ServerLevel) level;

            // --- SHIFT + RIGHT CLICK: Export Topology ---
            if (player.isSecondaryUseActive() || player.isShiftKeyDown()) {
                BlockPos playerPos = serverPlayer.blockPosition();

                // 1. Check if the world has a globally active Nexus
                if (!NexusTracker.hasActiveNexus(serverLevel)) {
                    serverPlayer.sendSystemMessage(Component.literal("§c[Skavenblight] §fFailed! No active Nexus found in the world."));
                    return InteractionResultHolder.fail(player.getItemInHand(hand));
                }

                // 2. Fetch the active Nexus position
                BlockPos nexusPos = NexusTracker.getActiveNexusPos(serverLevel);

                // 3. Retrieve the active Region Flow Field using Chunk lookup, then resolve it to
                // the specific region containing the nexus via the network's TerritoryRegionMap
                // (StandardFlowField's single-territory getSharedFlowField no longer exists).
                WarpFluxGridManager gridManager = WarpFluxGridManager.get(serverLevel);
                RegionFlowField activeField = null;
                TerritoryRegionMap activeRegionMap = null;
                ChunkPos nexusChunk = new ChunkPos(nexusPos);

                for (WarpFluxNetwork network : gridManager.getAllNetworks()) {
                    // Skip a network with no live nexus - its region map never bootstraps.
                    if (!network.isValid(serverLevel)) continue;
                    if (!network.getTerritoryChunks().contains(nexusChunk)) continue;

                    activeRegionMap = network.getRegionMap();

                    // The nexus block itself is solid (see WARPSTONE_NEXUS/ACTIVE_WARPSTONE_NEXUS
                    // in ModBlocks), so it's never a member of any region and a direct
                    // getRegionFlowFieldFor(nexusPos) lookup always misses - check its orthogonal
                    // neighbors too, same fix as TerritoryRegionMap's own root-region resolution.
                    for (BlockPos candidate : new BlockPos[]{
                            nexusPos, nexusPos.above(), nexusPos.below(),
                            nexusPos.north(), nexusPos.south(), nexusPos.east(), nexusPos.west()}) {
                        activeField = activeRegionMap.getRegionFlowFieldFor(candidate);
                        if (activeField != null) break;
                    }

                    if (activeField != null) break;

                    // This network's territory geometrically contains the nexus's chunk, but
                    // yielded no field (region map still bootstrapping, or - since territory
                    // bubbles are flat 2D chunk radii - a different network's territory overlaps
                    // this exact spot at another height). Keep checking other networks.
                    activeRegionMap = null;
                }

                // 4. Export the data - PathingDebugFileWriter handles empty/calculating/ready
                // states itself (it renders the live in-progress map while a calculation is
                // running), so the only real failure case here is not finding a network/region at all.
                if (activeField != null) {
                    String filePath = PathingDebugFileWriter.exportDeepDump(serverLevel, activeField, activeRegionMap, playerPos, 32, 10, 32);

                    if (filePath != null) {
                        serverPlayer.sendSystemMessage(Component.literal("§a[Skavenblight] §fDeep dump saved to: §e" + filePath));
                    } else {
                        serverPlayer.sendSystemMessage(Component.literal("§c[Skavenblight] §fFailed to write dump file. Check server console."));
                    }
                } else {
                    serverPlayer.sendSystemMessage(Component.literal("§c[Skavenblight] §fFailed! Could not find a Network claiming the Nexus at " + nexusPos.toShortString()));
                }

                return InteractionResultHolder.success(player.getItemInHand(hand));
            }

            // --- NORMAL RIGHT CLICK: Cycle Modes ---
            CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            CompoundTag tag = customData.copyTag();

            int currentModeIndex = tag.getInt("DebugMode");
            DebugMode currentMode = DebugMode.values()[currentModeIndex % DebugMode.values().length];
            DebugMode nextMode = currentMode.next();

            tag.putInt("DebugMode", nextMode.ordinal());
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

            // Inform the player
            player.displayClientMessage(Component.literal("§a[Skavenblight] §fVisualizer Mode: §e" + nextMode.getDisplayName()), true);
        }

        return InteractionResultHolder.success(stack);
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        if (!level.isClientSide() && isSelected && entity instanceof ServerPlayer serverPlayer) {

            // Throttle updates to once per second (20 ticks)
            if (level.getGameTime() % 20 == 0) {
                ServerLevel serverLevel = (ServerLevel) level;
                WarpFluxGridManager gridManager = WarpFluxGridManager.get(serverLevel);
                BlockPos playerPos = serverPlayer.blockPosition();

                // Read the item's current active mode
                CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
                int currentModeIndex = customData.copyTag().getInt("DebugMode");
                DebugMode currentMode = DebugMode.values()[currentModeIndex % DebugMode.values().length];

                // Territory of the last network whose chunk geometrically matched the player,
                // even if it had no usable field for the current mode/position - used below to
                // send a clearing update if every match falls through empty-handed.
                Set<ChunkPos> lastMatchedTerritory = null;

                for (WarpFluxNetwork network : gridManager.getAllNetworks()) {
                    // Skip a network with no live nexus - its region map never bootstraps (see
                    // WarpFluxNetwork.tick), so it can never yield a real field here.
                    if (!network.isValid(serverLevel)) continue;

                    if (network.getTerritoryChunks().contains(serverPlayer.chunkPosition())) {
                        lastMatchedTerritory = network.getTerritoryChunks();

                        // Position-based lookup against the player's current region - replaces
                        // the old nexus-lookup + network-wide getSharedFlowField call, since
                        // pathing is now region-scoped rather than one field per whole territory.
                        TerritoryRegionMap regionMap = network.getRegionMap();
                        RegionFlowField sharedField = regionMap.getRegionFlowFieldFor(playerPos);
                        boolean usingBorrowedWildernessField = false;

                        if (sharedField == null && currentMode == DebugMode.WILDERNESS_PATH) {
                            // Wilderness mode's whole purpose is showing a heading FROM outside
                            // every scanned region - getWildernessHeadingTarget delegates
                            // network-wide via TerritoryRegionMap regardless of which region's
                            // RegionFlowField instance issues the call (see RegionFlowField and
                            // FollowFlowFieldGoal's identical use of this for wandering mobs), so
                            // grab any available region's field as a proxy instead of skipping
                            // visualization entirely for the one mode that needs this most.
                            sharedField = regionMap.getRegionIndex().getRegions().stream()
                                    .map(r -> regionMap.getRegionFlowFieldFor(r.getMin()))
                                    .filter(Objects::nonNull)
                                    .findFirst().orElse(null);
                            usingBorrowedWildernessField = sharedField != null;
                        }

                        if (sharedField == null) {
                            // This network's territory geometrically contains the player's
                            // current chunk, but it has no usable field here - its region map may
                            // still be bootstrapping, or (since territory bubbles are flat 2D
                            // chunk radii with no Y-awareness) a DIFFERENT network's territory
                            // happens to overlap this exact spot at another height. Keep checking
                            // other networks rather than giving up on the first geometric match.
                            continue;
                        }

                        Map<BlockPos, SiegeNode> localNodes = new HashMap<>();
                        currentMode.getServerLogic().collectData(serverLevel, playerPos, sharedField, regionMap, localNodes);

                        // The borrowed field above is picked arbitrarily (whichever region's
                        // field happened to exist first) purely so getWildernessHeadingTarget's
                        // network-wide lookup can be made - it has nothing to do with which
                        // region the computed heading actually points toward (that's resolved
                        // independently, by nearest-distance, inside TerritoryRegionMap). Chunk
                        // highlighting that region would mislead the client into lighting up an
                        // unrelated, possibly-distant region, so skip highlighting entirely in
                        // that case - a missing highlight is better than a wrong one. The normal
                        // (non-wilderness-fallback) case is untouched: sharedField there really is
                        // the region the player is standing in, so highlighting it is correct.
                        Set<ChunkPos> highlightedChunks;
                        if (usingBorrowedWildernessField) {
                            highlightedChunks = Set.of();
                        } else {
                            RegionFlowField highlightField = sharedField;
                            highlightedChunks = regionMap.getRegionIndex().getRegions().stream()
                                    .filter(r -> r.getId() == highlightField.getRegionId())
                                    .findFirst()
                                    .map(r -> r.getChunkCells().keySet())
                                    .orElse(Set.of());
                        }

                        serverPlayer.connection.send(new SyncFlowFieldDebugPayload(
                                network.getTerritoryChunks(),
                                localNodes,
                                highlightedChunks,
                                currentMode.ordinal()
                        ));
                        return;
                    }
                }

                if (lastMatchedTerritory != null) {
                    // Every matching network fell through empty-handed (e.g. Detailed/Macro mode
                    // while standing in an unmapped or route-unreachable region). Send an
                    // empty-but-current-mode update so the client clears whatever it last
                    // rendered instead of leaving stale data on screen - previously this case sent
                    // nothing at all, which left the display frozen on the last mode that DID
                    // produce data (almost always Wilderness, since its borrow-fallback above
                    // rarely fails), making the tool look permanently stuck in that mode.
                    serverPlayer.connection.send(new SyncFlowFieldDebugPayload(
                            lastMatchedTerritory, Map.of(), Set.of(), currentMode.ordinal()
                    ));
                }
            }
        }
    }
}