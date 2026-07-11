package org.ratden.skavenblight.item.custom;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.ratden.skavenblight.ai.pathing.SiegeNode;
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
                            // Grab the shared instance from the network
                            StandardFlowField sharedField = network.getSharedFlowField(activeNexus);
                            sharedField.calculateMapIfNeeded(serverLevel);

                            // --- CHANGED: Map now tracks <BlockPos, SiegeNode> to include actions for tinting ---
                            Map<BlockPos, SiegeNode> localNodes = new HashMap<>();

                            // Iterate over the new instruction map
                            for (BlockPos pos : sharedField.getInstructionMap().keySet()) {
                                // Keep the 16-block radius to prevent payload overflow
                                if (pos.closerThan(playerPos, 16)) {
                                    SiegeNode nextNode = sharedField.getNextSiegeNode(serverLevel, pos);
                                    if (nextNode != null) {
                                        localNodes.put(pos, nextNode);
                                    }
                                }
                            }

                            // Send the upgraded map containing full instructions to the client
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