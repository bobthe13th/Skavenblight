package org.ratden.skavenblight.item.custom;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
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

            // Sync information twice a second (every 10 ticks)
            if (level.getGameTime() % 10 == 0) {
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
                            StandardFlowField debugField = new StandardFlowField(activeNexus, network.getTerritoryChunks());
                            debugField.calculateMap(serverLevel);

                            Map<BlockPos, Direction> localDirections = new HashMap<>();
                            for (BlockPos pos : debugField.getCostMap().keySet()) {
                                // Filter vectors strictly to a 16-block box around the player to keep payloads small
                                if (pos.closerThan(playerPos, 16)) {
                                    Direction bestDir = debugField.getBestDirection(pos);
                                    if (bestDir != null) {
                                        localDirections.put(pos, bestDir);
                                    }
                                }
                            }

                            // Dispatched straight to our client renderer!
                            serverPlayer.connection.send(new SyncFlowFieldDebugPayload(
                                    network.getTerritoryChunks(),
                                    localDirections
                            ));
                            return;
                        }
                    }
                }
            }
        }
    }
}