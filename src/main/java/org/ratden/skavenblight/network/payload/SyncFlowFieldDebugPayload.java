package org.ratden.skavenblight.network.payload;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.ratden.skavenblight.ai.pathing.SiegeNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public record SyncFlowFieldDebugPayload(Set<ChunkPos> territoryChunks, Map<BlockPos, SiegeNode> flowFieldMap) implements CustomPacketPayload {

    // 1. Declare the Payload Type
    public static final CustomPacketPayload.Type<SyncFlowFieldDebugPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("skavenblight", "sync_flow_field_debug"));

    // 2. Declare the CODEC
    public static final StreamCodec<FriendlyByteBuf, SyncFlowFieldDebugPayload> CODEC =
            StreamCodec.ofMember(SyncFlowFieldDebugPayload::write, SyncFlowFieldDebugPayload::new);

    public SyncFlowFieldDebugPayload(FriendlyByteBuf buffer) {
        this(readChunks(buffer), readMap(buffer));
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeInt(this.territoryChunks.size());
        for (ChunkPos chunkPos : this.territoryChunks) {
            buffer.writeLong(chunkPos.toLong());
        }

        buffer.writeInt(this.flowFieldMap.size());
        for (Map.Entry<BlockPos, SiegeNode> entry : this.flowFieldMap.entrySet()) {
            buffer.writeBlockPos(entry.getKey());
            buffer.writeBlockPos(entry.getValue().pos());
            buffer.writeEnum(entry.getValue().action());
        }
    }

    private static Set<ChunkPos> readChunks(FriendlyByteBuf buffer) {
        int size = buffer.readInt();
        Set<ChunkPos> chunks = new HashSet<>();
        for (int i = 0; i < size; i++) {
            chunks.add(new ChunkPos(buffer.readLong()));
        }
        return chunks;
    }

    private static Map<BlockPos, SiegeNode> readMap(FriendlyByteBuf buffer) {
        int size = buffer.readInt();
        Map<BlockPos, SiegeNode> map = new HashMap<>();
        for (int i = 0; i < size; i++) {
            BlockPos currentPos = buffer.readBlockPos();
            BlockPos targetPos = buffer.readBlockPos();
            SiegeNode.SiegeAction action = buffer.readEnum(SiegeNode.SiegeAction.class);

            map.put(currentPos, new SiegeNode(targetPos, action));
        }
        return map;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // 3. THIS is where the handle method lives!
    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> {
            org.ratden.skavenblight.client.ClientDebugData.update(this.territoryChunks, this.flowFieldMap);
        });
    }
}