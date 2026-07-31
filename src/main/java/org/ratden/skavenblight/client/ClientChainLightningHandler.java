package org.ratden.skavenblight.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.ratden.skavenblight.network.payload.ChainLightningPayload;

/**
 * Holds ChainLightningPayload's client-only particle logic. Kept out of ChainLightningPayload
 * itself (which is loaded on both sides for its TYPE/STREAM_CODEC/registration) so referencing
 * Minecraft/ClientLevel doesn't trip NeoForge's RuntimeDistCleaner on a dedicated server - the
 * same reason SyncFlowFieldDebugPayload delegates to ClientDebugData instead of touching
 * client-only classes directly in its own handle() method.
 */
public final class ClientChainLightningHandler {

    private ClientChainLightningHandler() {
    }

    public static void handle(ChainLightningPayload payload) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return;

        for (int i = 0; i < payload.hitEntityIds().size() - 1; i++) {
            Entity source = level.getEntity(payload.hitEntityIds().get(i));
            Entity target = level.getEntity(payload.hitEntityIds().get(i + 1));

            if (source != null && target != null) {
                Vec3 startPos = source.position().add(0, source.getBbHeight() / 2.0, 0);
                Vec3 endPos = target.position().add(0, target.getBbHeight() / 2.0, 0);

                double distance = startPos.distanceTo(endPos);
                int particleCount = (int) (distance * 5);

                for (int j = 0; j <= particleCount; j++) {
                    double t = (double) j / particleCount;

                    double x = Mth.lerp(t, startPos.x, endPos.x);
                    double y = Mth.lerp(t, startPos.y, endPos.y);
                    double z = Mth.lerp(t, startPos.z, endPos.z);

                    double jitterX = (level.random.nextDouble() - 0.5) * 0.3;
                    double jitterY = (level.random.nextDouble() - 0.5) * 0.3;
                    double jitterZ = (level.random.nextDouble() - 0.5) * 0.3;

                    level.addParticle(
                            ParticleTypes.HAPPY_VILLAGER,
                            x + jitterX, y + jitterY, z + jitterZ,
                            0.0, 0.0, 0.0
                    );
                }
            }
        }
    }
}
