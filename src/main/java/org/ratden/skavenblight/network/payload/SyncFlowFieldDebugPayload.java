package org.ratden.skavenblight.network.payload;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.client.ClientDebugData;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

// --- CHANGED: directions Map is now nodes Map<BlockPos, BlockPos> ---
public record SyncFlowFieldDebugPayload(Set<ChunkPos> chunks, Map<BlockPos, BlockPos> nodes) implements CustomPacketPayload {

    public static final Type<SyncFlowFieldDebugPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Skavenblight.MODID, "sync_flow_field_debug"));

    public static final StreamCodec<FriendlyByteBuf, SyncFlowFieldDebugPayload> CODEC = CustomPacketPayload.codec(
            SyncFlowFieldDebugPayload::write, SyncFlowFieldDebugPayload::new
    );

    private SyncFlowFieldDebugPayload(FriendlyByteBuf buf) {
        this(readChunks(buf), readNodes(buf));
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeInt(chunks.size());
        for (ChunkPos chunk : chunks) {
            buf.writeInt(chunk.x);
            buf.writeInt(chunk.z);
        }
        buf.writeInt(nodes.size());
        // --- CHANGED: Write the target BlockPos instead of the Direction enum ---
        for (Map.Entry<BlockPos, BlockPos> entry : nodes.entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeBlockPos(entry.getValue());
        }
    }

    private static Set<ChunkPos> readChunks(FriendlyByteBuf buf) {
        Set<ChunkPos> set = new HashSet<>();
        int size = buf.readInt();
        for (int i = 0; i < size; i++) {
            set.add(new ChunkPos(buf.readInt(), buf.readInt()));
        }
        return set;
    }

    // --- CHANGED: Read BlockPos from the buffer ---
    private static Map<BlockPos, BlockPos> readNodes(FriendlyByteBuf buf) {
        Map<BlockPos, BlockPos> map = new HashMap<>();
        int size = buf.readInt();
        for (int i = 0; i < size; i++) {
            map.put(buf.readBlockPos(), buf.readBlockPos());
        }
        return map;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(final SyncFlowFieldDebugPayload payload, final IPayloadContext context) {
        // Pass the updated payload.nodes() into ClientDebugData
        context.enqueueWork(() -> ClientDebugData.update(payload.chunks(), payload.nodes()));
    }
}