package org.ratden.skavenblight.network.payload;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.ratden.skavenblight.Skavenblight;
import net.minecraft.world.entity.Entity;
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
        context.enqueueWork(() -> {
            // Client-side logic for particles
            Level level = Minecraft.getInstance().level;
            if (level == null) return;

            // Loop through the hit entities and draw your lightning!
            for (int i = 0; i < payload.hitEntityIds().size() - 1; i++) {
                Entity source = level.getEntity(payload.hitEntityIds().get(i));
                Entity target = level.getEntity(payload.hitEntityIds().get(i + 1));

                if (source != null && target != null) {

                    // 1. Get the center of the source and target bodies
                    net.minecraft.world.phys.Vec3 startPos = source.position().add(0, source.getBbHeight() / 2.0, 0);
                    net.minecraft.world.phys.Vec3 endPos = target.position().add(0, target.getBbHeight() / 2.0, 0);

                    // 2. Calculate distance to figure out how many particles to spawn
                    double distance = startPos.distanceTo(endPos);
                    int particleCount = (int) (distance * 5); // Spawns 5 particles per block of distance

                    // 3. Draw the line
                    for (int j = 0; j <= particleCount; j++) {
                        // 't' is the percentage of the way between the start and end (from 0.0 to 1.0)
                        double t = (double) j / particleCount;

                        // Lerp calculates the exact coordinate at that percentage
                        double x = net.minecraft.util.Mth.lerp(t, startPos.x, endPos.x);
                        double y = net.minecraft.util.Mth.lerp(t, startPos.y, endPos.y);
                        double z = net.minecraft.util.Mth.lerp(t, startPos.z, endPos.z);

                        // Add a tiny bit of random chaos so it looks like electricity, not a laser
                        double jitterX = (level.random.nextDouble() - 0.5) * 0.3;
                        double jitterY = (level.random.nextDouble() - 0.5) * 0.3;
                        double jitterZ = (level.random.nextDouble() - 0.5) * 0.3;

                        // 4. Spawn the particle (Happy Villager is a great built-in green spark)
                        level.addParticle(
                                net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                                x + jitterX, y + jitterY, z + jitterZ,
                                0.0, 0.0, 0.0
                        );
                    }
                }
            }
        });
    }
}
