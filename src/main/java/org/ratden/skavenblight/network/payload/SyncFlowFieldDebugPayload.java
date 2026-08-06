package org.ratden.skavenblight.network.payload;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.ratden.skavenblight.ai.pathing.FlowStep;
import org.ratden.skavenblight.ai.pathing.PathAction;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class SyncFlowFieldDebugPayload implements CustomPacketPayload {

    public static final Type<SyncFlowFieldDebugPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("skavenblight", "sync_flow_field_debug"));

    public static final StreamCodec<FriendlyByteBuf, SyncFlowFieldDebugPayload> CODEC = CustomPacketPayload.codec(
            SyncFlowFieldDebugPayload::write,
            SyncFlowFieldDebugPayload::new
    );

    private final Set<ChunkPos> territoryChunks;
    private final Map<BlockPos, FlowStep> flowFieldNodes;
    private final Set<ChunkPos> mappedChunks;
    private final int currentMode;

    public SyncFlowFieldDebugPayload(Set<ChunkPos> territoryChunks, Map<BlockPos, FlowStep> flowFieldNodes, Set<ChunkPos> mappedChunks, int currentMode) {
        this.territoryChunks = territoryChunks;
        this.flowFieldNodes = flowFieldNodes;
        this.mappedChunks = mappedChunks;
        this.currentMode = currentMode;
    }

    public SyncFlowFieldDebugPayload(FriendlyByteBuf buf) {
        // 1. Read and initialize territoryChunks
        int territorySize = buf.readInt();
        this.territoryChunks = new HashSet<>();
        for (int i = 0; i < territorySize; i++) {
            this.territoryChunks.add(buf.readChunkPos());
        }

        // 2. Read and initialize flowFieldNodes
        int nodeSize = buf.readInt();
        this.flowFieldNodes = new HashMap<>();
        for (int i = 0; i < nodeSize; i++) {
            BlockPos pos = buf.readBlockPos();
            BlockPos targetPos = buf.readBlockPos();
            PathAction action = buf.readEnum(PathAction.class);
            BlockPos predecessorPos = buf.readBlockPos();
            this.flowFieldNodes.put(pos, new FlowStep(targetPos, action, predecessorPos));
        }

        // 3. Read and initialize mappedChunks
        int mappedSize = buf.readInt();
        this.mappedChunks = new HashSet<>();
        for (int i = 0; i < mappedSize; i++) {
            this.mappedChunks.add(buf.readChunkPos());
        }

        // 4. Read and initialize currentMode
        this.currentMode = buf.readInt();
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeInt(this.territoryChunks.size());
        for (ChunkPos pos : this.territoryChunks) {
            buf.writeChunkPos(pos);
        }

        buf.writeInt(this.flowFieldNodes.size());
        for (Map.Entry<BlockPos, FlowStep> entry : this.flowFieldNodes.entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeBlockPos(entry.getValue().pos());
            buf.writeEnum(entry.getValue().action());
            // Self-referencing filler (see Task 25's decision): this debug sync has no real
            // predecessor to send - every reconstructed FlowStep just points at its own pos(),
            // matching the established "position pointing at itself" idiom for "no real
            // predecessor" (FlowStepTest/FlowFieldCalculatorTest already use it).
            buf.writeBlockPos(entry.getValue().pos());
        }

        buf.writeInt(this.mappedChunks.size());
        for (ChunkPos pos : this.mappedChunks) {
            buf.writeChunkPos(pos);
        }

        buf.writeInt(this.currentMode);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // --- FIX: The handler method sits INSIDE the payload class ---
    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> {
            org.ratden.skavenblight.client.ClientDebugData.update(
                    this.territoryChunks,
                    this.flowFieldNodes,
                    this.mappedChunks,
                    this.currentMode
            );
        });
    }

    public Set<ChunkPos> territoryChunks() {
        return territoryChunks;
    }

    public Map<BlockPos, FlowStep> flowFieldNodes() {
        return flowFieldNodes;
    }

    public Set<ChunkPos> mappedChunks() {
        return mappedChunks;
    }

    public int currentMode() {
        return currentMode;
    }
}