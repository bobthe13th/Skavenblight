package org.ratden.skavenblight.item.custom;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.ratden.skavenblight.ai.pathing.StandardFlowField;
import org.ratden.skavenblight.block.entity.WarpstoneNexusEntity;
import org.ratden.skavenblight.network.WarpFluxGridManager;
import org.ratden.skavenblight.network.WarpFluxNetwork;
import org.ratden.skavenblight.network.payload.SyncFlowFieldDebugPayload;

import java.util.HashMap;
import java.util.Map;

public class DebugFlowFieldReaderItem extends Item {

    public DebugFlowFieldReaderItem(Properties properties) {
        super(properties);
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        if (!level.isClientSide() && isSelected && entity instanceof ServerPlayer serverPlayer) {

            // Sync information once a second (every 20 ticks) to reduce server/network load
            if (level.getGameTime() % 20 == 0) {
                ServerLevel serverLevel = (ServerLevel) level;
                WarpFluxGridManager gridManager = WarpFluxGridManager.get(serverLevel);
                BlockPos playerPos = serverPlayer.blockPosition();

                for (WarpFluxNetwork network : gridManager.getAllNetworks()) {
                    // Find the network local to where the player is looking/standing
                    if (network.getTerritoryChunks().contains(serverPlayer.chunkPosition())) {

                        BlockPos activeNexus = null;
                        for (BlockPos endpoint : network.getEndpoints()) {
                            if (serverLevel.getBlockEntity(endpoint) instanceof WarpstoneNexusEntity) {
                                activeNexus = endpoint;
                                break;
                            }
                        }

                        if (activeNexus != null) {
                            // Grab the shared instance from the network instead of creating a new one
                            StandardFlowField sharedField = network.getSharedFlowField(activeNexus);

                            // Ask it to update (it will only do so if it hasn't updated recently)
                            sharedField.calculateMapIfNeeded(serverLevel);

                            // --- CHANGED: Map now tracks <BlockPos, BlockPos> instead of <BlockPos, Direction> ---
                            Map<BlockPos, BlockPos> localNodes = new HashMap<>();
                            for (BlockPos pos : sharedField.getCostMap().keySet()) {
                                // Filter vectors strictly to a 16-block box around the player to keep payloads small
                                if (pos.closerThan(playerPos, 16)) {
                                    // --- CHANGED: Grab the next 3D pathing coordinate block ---
                                    BlockPos nextNode = sharedField.getBestNextNode(pos);
                                    if (nextNode != null) {
                                        localNodes.put(pos, nextNode);
                                    }
                                }
                            }

                            // Dispatched straight to our client renderer with the upgraded map!
                            serverPlayer.connection.send(new SyncFlowFieldDebugPayload(
                                    network.getTerritoryChunks(),
                                    localNodes
                            ));
                            return;
                        }
                    }
                }
            }
        }
    }
}