package org.ratden.skavenblight.network.payload;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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

public record SyncFlowFieldDebugPayload(Set<ChunkPos> chunks, Map<BlockPos, Direction> directions) implements CustomPacketPayload {

    public static final Type<SyncFlowFieldDebugPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Skavenblight.MODID, "sync_flow_field_debug"));

    public static final StreamCodec<FriendlyByteBuf, SyncFlowFieldDebugPayload> CODEC = CustomPacketPayload.codec(
            SyncFlowFieldDebugPayload::write, SyncFlowFieldDebugPayload::new
    );

    private SyncFlowFieldDebugPayload(FriendlyByteBuf buf) {
        this(readChunks(buf), readDirections(buf));
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeInt(chunks.size());
        for (ChunkPos chunk : chunks) {
            buf.writeInt(chunk.x);
            buf.writeInt(chunk.z);
        }
        buf.writeInt(directions.size());
        for (Map.Entry<BlockPos, Direction> entry : directions.entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeEnum(entry.getValue());
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

    private static Map<BlockPos, Direction> readDirections(FriendlyByteBuf buf) {
        Map<BlockPos, Direction> map = new HashMap<>();
        int size = buf.readInt();
        for (int i = 0; i < size; i++) {
            map.put(buf.readBlockPos(), buf.readEnum(Direction.class));
        }
        return map;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(final SyncFlowFieldDebugPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> ClientDebugData.update(payload.chunks(), payload.directions()));
    }
}