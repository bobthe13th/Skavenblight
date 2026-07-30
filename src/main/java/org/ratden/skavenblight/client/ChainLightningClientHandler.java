package org.ratden.skavenblight.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.ratden.skavenblight.network.payload.ChainLightningPayload;

/**
 * Client-only particle rendering for ChainLightningPayload.
 *
 * Kept out of ChainLightningPayload itself so that class stays free of
 * direct client-only type references (Minecraft, ClientLevel) - otherwise
 * RuntimeDistCleaner blocks it from loading on a dedicated server, since
 * the payload class is referenced by ModNetwork's common registration code.
 */
public final class ChainLightningClientHandler {

    public static void spawnParticles(ChainLightningPayload payload) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return;

        for (int i = 0; i < payload.hitEntityIds().size() - 1; i++) {
            Entity source = level.getEntity(payload.hitEntityIds().get(i));
            Entity target = level.getEntity(payload.hitEntityIds().get(i + 1));

            if (source != null && target != null) {
                net.minecraft.world.phys.Vec3 startPos = source.position().add(0, source.getBbHeight() / 2.0, 0);
                net.minecraft.world.phys.Vec3 endPos = target.position().add(0, target.getBbHeight() / 2.0, 0);

                double distance = startPos.distanceTo(endPos);
                int particleCount = (int) (distance * 5);

                for (int j = 0; j <= particleCount; j++) {
                    double t = (double) j / particleCount;

                    double x = net.minecraft.util.Mth.lerp(t, startPos.x, endPos.x);
                    double y = net.minecraft.util.Mth.lerp(t, startPos.y, endPos.y);
                    double z = net.minecraft.util.Mth.lerp(t, startPos.z, endPos.z);

                    double jitterX = (level.random.nextDouble() - 0.5) * 0.3;
                    double jitterY = (level.random.nextDouble() - 0.5) * 0.3;
                    double jitterZ = (level.random.nextDouble() - 0.5) * 0.3;

                    level.addParticle(
                            net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                            x + jitterX, y + jitterY, z + jitterZ,
                            0.0, 0.0, 0.0
                    );
                }
            }
        }
    }

    private ChainLightningClientHandler() {
    }
}
