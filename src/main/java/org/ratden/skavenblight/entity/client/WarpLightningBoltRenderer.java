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
import org.ratden.skavenblight.entity.custom.WarpLightningBoltEntity;

public class WarpLightningBoltRenderer extends EntityRenderer<WarpLightningBoltEntity> {

    public WarpLightningBoltRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public boolean shouldRender(WarpLightningBoltEntity entity, Frustum frustum, double cameraX, double cameraY, double cameraZ) {
        return true; // Prevents frustum culling from clipping active bolts
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
        if (totalDist < 0.15) return;

        poseStack.pushPose();

        Matrix4f matrix = poseStack.last().pose();
        VertexConsumer consumer = buffer.getBuffer(RenderType.lightning());

        RandomSource random = RandomSource.create(entity.seed + entity.tickCount * 11L);

        int segments = Math.max(4, (int) (totalDist * 2.5));
        double maxJitter = Math.min(0.3, totalDist * 0.1);

        Vec3[] points = new Vec3[segments + 1];
        points[0] = Vec3.ZERO;
        points[segments] = new Vec3(xDiff, yDiff, zDiff);

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

        // Retrieve local camera position relative to the entity's start origin
        Vec3 cameraPos = this.entityRenderDispatcher.camera.getPosition();
        Vec3 localCamera = cameraPos.subtract(start);

        // Compute billboard offset vectors at each node for continuous connected joints
        Vec3[] nodeOffsets = new Vec3[segments + 1];
        float thickness = 0.05F;

        for (int i = 0; i <= segments; i++) {
            Vec3 dir;
            if (i == 0) {
                dir = points[1].subtract(points[0]);
            } else if (i == segments) {
                dir = points[segments].subtract(points[segments - 1]);
            } else {
                dir = points[i + 1].subtract(points[i - 1]);
            }

            Vec3 camDir = localCamera.subtract(points[i]);
            Vec3 normal = dir.cross(camDir);

            if (normal.lengthSqr() < 0.00001) {
                nodeOffsets[i] = new Vec3(thickness, 0, 0);
            } else {
                nodeOffsets[i] = normal.normalize().scale(thickness);
            }
        }

        // Render connected billboard strips with double-sided faces
        for (int i = 0; i < segments; i++) {
            Vec3 p1 = points[i];
            Vec3 p2 = points[i + 1];
            Vec3 o1 = nodeOffsets[i];
            Vec3 o2 = nodeOffsets[i + 1];

            // Outer green glow strip
            drawDoubleSidedQuad(matrix, consumer, p1, p2, o1.scale(1.8), o2.scale(1.8), 0.1F, 0.8F, 0.1F, 0.5F);
            // Core bright green beam
            drawDoubleSidedQuad(matrix, consumer, p1, p2, o1, o2, 0.5F, 1.0F, 0.5F, 0.9F);
        }

        poseStack.popPose();
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    private void drawDoubleSidedQuad(Matrix4f matrix, VertexConsumer consumer, Vec3 p1, Vec3 p2, Vec3 o1, Vec3 o2, float r, float g, float b, float a) {
        float x1 = (float) p1.x, y1 = (float) p1.y, z1 = (float) p1.z;
        float x2 = (float) p2.x, y2 = (float) p2.y, z2 = (float) p2.z;
        float ox1 = (float) o1.x, oy1 = (float) o1.y, oz1 = (float) o1.z;
        float ox2 = (float) o2.x, oy2 = (float) o2.y, oz2 = (float) o2.z;

        // Front Face
        consumer.addVertex(matrix, x1 - ox1, y1 - oy1, z1 - oz1).setColor(r, g, b, a);
        consumer.addVertex(matrix, x2 - ox2, y2 - oy2, z2 - oz2).setColor(r, g, b, a);
        consumer.addVertex(matrix, x2 + ox2, y2 + oy2, z2 + oz2).setColor(r, g, b, a);
        consumer.addVertex(matrix, x1 + ox1, y1 + oy1, z1 + oz1).setColor(r, g, b, a);

        // Back Face (Prevents backface culling when viewed from the opposite side)
        consumer.addVertex(matrix, x1 + ox1, y1 + oy1, z1 + oz1).setColor(r, g, b, a);
        consumer.addVertex(matrix, x2 + ox2, y2 + oy2, z2 + oz2).setColor(r, g, b, a);
        consumer.addVertex(matrix, x2 - ox2, y2 - oy2, z2 - oz2).setColor(r, g, b, a);
        consumer.addVertex(matrix, x1 - ox1, y1 - oy1, z1 - oz1).setColor(r, g, b, a);
    }

    @Override
    public ResourceLocation getTextureLocation(WarpLightningBoltEntity entity) {
        return null;
    }
}