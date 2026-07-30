package org.ratden.skavenblight.network.payload;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.client.ChainLightningClientHandler;
import java.util.List;

public record ChainLightningPayload(List<Integer> hitEntityIds) implements CustomPacketPayload {

    public static final Type<ChainLightningPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Skavenblight.MODID, "chain_lightning"));

    // Codec to serialize/deserialize the list of integers (Entity IDs)
    public static final StreamCodec<ByteBuf, ChainLightningPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.INT.apply(ByteBufCodecs.list()),
            ChainLightningPayload::hitEntityIds,
            ChainLightningPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleChainLightning(final ChainLightningPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> ChainLightningClientHandler.spawnParticles(payload));
    }
}
