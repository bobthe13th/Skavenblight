package org.ratden.skavenblight.item.custom;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import org.ratden.skavenblight.ai.pathing.SiegeNode;
import org.ratden.skavenblight.ai.pathing.StandardFlowField;
import org.ratden.skavenblight.block.entity.WarpstoneNexusEntity;
import org.ratden.skavenblight.debug.mode.server.DetailedServerMode;
import org.ratden.skavenblight.debug.mode.server.IServerDebugMode;
import org.ratden.skavenblight.debug.mode.server.MacroServerMode;
import org.ratden.skavenblight.debug.mode.server.WildernessServerMode;
import org.ratden.skavenblight.network.WarpFluxGridManager;
import org.ratden.skavenblight.network.WarpFluxNetwork;
import org.ratden.skavenblight.network.payload.SyncFlowFieldDebugPayload;

import java.util.HashMap;
import java.util.Map;

public class DebugFlowFieldReaderItem extends Item {

    // --- NEW: Expandable Mode Enum ---
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

    // --- NEW: Right-Click to cycle modes ---
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (!level.isClientSide()) {
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

                for (WarpFluxNetwork network : gridManager.getAllNetworks()) {
                    if (network.getTerritoryChunks().contains(serverPlayer.chunkPosition())) {

                        BlockPos activeNexus = null;
                        for (BlockPos endpoint : network.getEndpoints()) {
                            if (serverLevel.getBlockEntity(endpoint) instanceof WarpstoneNexusEntity) {
                                activeNexus = endpoint;
                                break;
                            }
                        }

                        if (activeNexus != null) {
                            StandardFlowField sharedField = network.getSharedFlowField(activeNexus);
                            sharedField.calculateMapIfNeeded(serverLevel);

                            // --- THE FIX: Initialize map and delegate data collection to the active Strategy ---
                            Map<BlockPos, SiegeNode> localNodes = new HashMap<>();
                            currentMode.getServerLogic().collectData(serverLevel, playerPos, sharedField, localNodes);

                            // --- TEMPORARY DEBUG LOG ---
                            System.out.println("[Skavenblight Debug] Active Mode: " + currentMode.name()
                                    + " | Nodes Collected: " + localNodes.size()
                                    + " | Sent to Client!");

                            // Send the updated payload mapping to the client
                            serverPlayer.connection.send(new SyncFlowFieldDebugPayload(
                                    network.getTerritoryChunks(),
                                    localNodes,
                                    sharedField.getMappedChunks(),
                                    currentMode.ordinal()
                            ));
                            return; // Exit early once the payload for the active network is sent
                        }
                    }
                }
            }
        }
    }
}