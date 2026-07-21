package org.ratden.skavenblight.entity.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.ratden.skavenblight.entity.custom.WarpLightningBoltEntity;

public class WarpLightningBoltRenderer extends EntityRenderer<WarpLightningBoltEntity> {

    public WarpLightningBoltRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public boolean shouldRender(WarpLightningBoltEntity entity, Frustum frustum, double cameraX, double cameraY, double cameraZ) {
        return true; // Prevents camera culling from clipping long arcs
    }

    @Override
    public void render(WarpLightningBoltEntity entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        Vec3 start = entity.position();
        Vec3 target = entity.getTargetPosition();
        if (target.equals(Vec3.ZERO)) return;

        double xDiff = target.x - start.x;
        double yDiff = target.y - start.y;
        double zDiff = target.z - start.z;

        double totalDist = Math.sqrt(xDiff * xDiff + yDiff * yDiff + zDiff * zDiff);

        // Skip rendering zero-length segments between mobs crammed together
        if (totalDist < 0.15) return;

        poseStack.pushPose();

        Matrix4f matrix = poseStack.last().pose();
        VertexConsumer consumer = buffer.getBuffer(RenderType.lightning());

        // Animate the random seed slightly with tickCount so the bolt crackles
        RandomSource random = RandomSource.create(entity.seed + entity.tickCount * 11L);

        // Calculate dynamic segment count and scale jitter based on distance
        int segments = Math.max(3, (int) (totalDist * 2));
        double maxJitter = Math.min(0.35, totalDist * 0.12);

        Vec3[] points = new Vec3[segments + 1];
        points[0] = Vec3.ZERO;
        points[segments] = new Vec3(xDiff, yDiff, zDiff);

        // Generate clean zigzag points along the vector
        for (int i = 1; i < segments; i++) {
            double progress = (double) i / segments;
            double baseX = xDiff * progress;
            double baseY = yDiff * progress;
            double baseZ = zDiff * progress;

            double jitterX = (random.nextDouble() - 0.5) * 2.0 * maxJitter;
            double jitterY = (random.nextDouble() - 0.5) * 2.0 * maxJitter;
            double jitterZ = (random.nextDouble() - 0.5) * 2.0 * maxJitter;

            points[i] = new Vec3(baseX + jitterX, baseY + jitterY, baseZ + jitterZ);
        }

        // Draw 3D cross-beam quads (+ shape) for each segment
        float thickness = 0.05F;
        for (int i = 0; i < segments; i++) {
            drawLightningSegment(matrix, consumer, points[i], points[i + 1], thickness);
        }

        poseStack.popPose();
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    private void drawLightningSegment(Matrix4f matrix, VertexConsumer consumer, Vec3 p1, Vec3 p2, float thickness) {
        Vector3f dir = new Vector3f((float)(p2.x - p1.x), (float)(p2.y - p1.y), (float)(p2.z - p1.z));
        if (dir.lengthSquared() < 0.0001f) return;

        // Calculate perpendicular vectors for full 3D beam thickness
        Vector3f perp1;
        if (Math.abs(dir.x) > Math.abs(dir.z)) {
            perp1 = new Vector3f(-dir.y, dir.x, 0.0f);
        } else {
            perp1 = new Vector3f(0.0f, -dir.z, dir.y);
        }
        perp1.normalize().mul(thickness);

        Vector3f perp2 = new Vector3f(dir).cross(perp1).normalize().mul(thickness);

        // Core bright green beam
        drawQuad(matrix, consumer, p1, p2, perp1, 0.2F, 1.0F, 0.2F, 0.8F);
        // Secondary cross plane (+ shape) for 3D depth
        drawQuad(matrix, consumer, p1, p2, perp2, 0.4F, 1.0F, 0.4F, 0.8F);
    }

    private void drawQuad(Matrix4f matrix, VertexConsumer consumer, Vec3 p1, Vec3 p2, Vector3f offset, float r, float g, float b, float a) {
        float x1 = (float) p1.x, y1 = (float) p1.y, z1 = (float) p1.z;
        float x2 = (float) p2.x, y2 = (float) p2.y, z2 = (float) p2.z;

        consumer.addVertex(matrix, x1 - offset.x, y1 - offset.y, z1 - offset.z).setColor(r, g, b, a);
        consumer.addVertex(matrix, x2 - offset.x, y2 - offset.y, z2 - offset.z).setColor(r, g, b, a);
        consumer.addVertex(matrix, x2 + offset.x, y2 + offset.y, z2 + offset.z).setColor(r, g, b, a);
        consumer.addVertex(matrix, x1 + offset.x, y1 + offset.y, z1 + offset.z).setColor(r, g, b, a);
    }

    @Override
    public ResourceLocation getTextureLocation(WarpLightningBoltEntity entity) {
        return null;
    }
}