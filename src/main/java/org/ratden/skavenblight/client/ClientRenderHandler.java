package org.ratden.skavenblight.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.item.ModItems;
import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.ai.pathing.SiegeNode;

import java.util.Map;

@EventBusSubscriber(modid = Skavenblight.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class ClientRenderHandler {

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        var camera = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera();
        net.minecraft.world.phys.Vec3 camPos = camera.getPosition();
        org.joml.Matrix4f pose = event.getPoseStack().last().pose();

        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;

        boolean holdingReader = player.getMainHandItem().is(ModItems.DEBUG_FLOW_FIELD_READER.get())
                || player.getOffhandItem().is(ModItems.DEBUG_FLOW_FIELD_READER.get());
        if (!holdingReader) return;

        Vec3 cameraPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();

        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.lineWidth(3.0F);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        RenderSystem.disableDepthTest();
        for (ChunkPos chunk : ClientDebugData.territoryChunks) {
            if (player.chunkPosition().getChessboardDistance(chunk) <= 3) {
                drawChunkColumns(pose, buffer, chunk, ClientDebugData.territoryChunks, camPos);
            }
        }
        var meshData = buffer.build();
        if (meshData != null) {
            BufferUploader.drawWithShader(meshData);
        }

        RenderSystem.enableDepthTest();
        buffer = tesselator.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        for (Map.Entry<BlockPos, SiegeNode> entry : ClientDebugData.flowFieldNodes.entrySet()) {
            BlockPos pos = entry.getKey();
            SiegeNode node = entry.getValue();

            // Render ALL nodes now, including the pre-planned Cyan BUILD arrows and Magenta MINE arrows
            if (pos.closerThan(player.blockPosition(), 24)) {
                drawFloorArrow(pose, buffer, pos, node, camPos);
            }
        }

        var arrowMeshData = buffer.build();
        if (arrowMeshData != null) {
            BufferUploader.drawWithShader(arrowMeshData);
        }

        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    private static void drawChunkColumns(org.joml.Matrix4f pose, com.mojang.blaze3d.vertex.BufferBuilder buffer, net.minecraft.world.level.ChunkPos chunk, java.util.Set<net.minecraft.world.level.ChunkPos> territoryChunks, net.minecraft.world.phys.Vec3 camPos) {
        int rB = 150, gB = 0, bB = 255, aB = 255;

        float minY = (float) (-64 - camPos.y());
        float maxY = (float) (320 - camPos.y());

        float startX = (float) ((chunk.x * 16) - camPos.x());
        float startZ = (float) ((chunk.z * 16) - camPos.z());
        float endX = startX + 16f;
        float endZ = startZ + 16f;

        if (!territoryChunks.contains(new net.minecraft.world.level.ChunkPos(chunk.x, chunk.z - 1))) {
            buffer.addVertex(pose, startX, minY, startZ).setColor(rB, gB, bB, aB);
            buffer.addVertex(pose, startX, maxY, startZ).setColor(rB, gB, bB, aB);
            buffer.addVertex(pose, endX, minY, startZ).setColor(rB, gB, bB, aB);
            buffer.addVertex(pose, endX, maxY, startZ).setColor(rB, gB, bB, aB);
            buffer.addVertex(pose, startX, maxY, startZ).setColor(rB, gB, bB, aB);
            buffer.addVertex(pose, endX, maxY, startZ).setColor(rB, gB, bB, aB);
            buffer.addVertex(pose, startX, minY, startZ).setColor(rB, gB, bB, aB);
            buffer.addVertex(pose, endX, minY, startZ).setColor(rB, gB, bB, aB);
        }

        if (!territoryChunks.contains(new net.minecraft.world.level.ChunkPos(chunk.x, chunk.z + 1))) {
            buffer.addVertex(pose, startX, minY, endZ).setColor(rB, gB, bB, aB);
            buffer.addVertex(pose, startX, maxY, endZ).setColor(rB, gB, bB, aB);
            buffer.addVertex(pose, endX, minY, endZ).setColor(rB, gB, bB, aB);
            buffer.addVertex(pose, endX, maxY, endZ).setColor(rB, gB, bB, aB);
            buffer.addVertex(pose, startX, maxY, endZ).setColor(rB, gB, bB, aB);
            buffer.addVertex(pose, endX, maxY, endZ).setColor(rB, gB, bB, aB);
            buffer.addVertex(pose, startX, minY, endZ).setColor(rB, gB, bB, aB);
            buffer.addVertex(pose, endX, minY, endZ).setColor(rB, gB, bB, aB);
        }

        if (!territoryChunks.contains(new net.minecraft.world.level.ChunkPos(chunk.x - 1, chunk.z))) {
            buffer.addVertex(pose, startX, minY, startZ).setColor(rB, gB, bB, aB);
            buffer.addVertex(pose, startX, maxY, startZ).setColor(rB, gB, bB, aB);
            buffer.addVertex(pose, startX, minY, endZ).setColor(rB, gB, bB, aB);
            buffer.addVertex(pose, startX, maxY, endZ).setColor(rB, gB, bB, aB);
            buffer.addVertex(pose, startX, maxY, startZ).setColor(rB, gB, bB, aB);
            buffer.addVertex(pose, startX, maxY, endZ).setColor(rB, gB, bB, aB);
            buffer.addVertex(pose, startX, minY, startZ).setColor(rB, gB, bB, aB);
            buffer.addVertex(pose, startX, minY, endZ).setColor(rB, gB, bB, aB);
        }

        if (!territoryChunks.contains(new net.minecraft.world.level.ChunkPos(chunk.x + 1, chunk.z))) {
            buffer.addVertex(pose, endX, minY, startZ).setColor(rB, gB, bB, aB);
            buffer.addVertex(pose, endX, maxY, startZ).setColor(rB, gB, bB, aB);
            buffer.addVertex(pose, endX, minY, endZ).setColor(rB, gB, bB, aB);
            buffer.addVertex(pose, endX, maxY, endZ).setColor(rB, gB, bB, aB);
            buffer.addVertex(pose, endX, maxY, startZ).setColor(rB, gB, bB, aB);
            buffer.addVertex(pose, endX, maxY, endZ).setColor(rB, gB, bB, aB);
            buffer.addVertex(pose, endX, minY, startZ).setColor(rB, gB, bB, aB);
            buffer.addVertex(pose, endX, minY, endZ).setColor(rB, gB, bB, aB);
        }
    }

    private static void drawFloorArrow(org.joml.Matrix4f pose, BufferBuilder buffer, BlockPos pos, SiegeNode node, net.minecraft.world.phys.Vec3 camPos) {
        BlockPos targetNode = node.pos();

        float dx = targetNode.getX() - pos.getX();
        float dy = targetNode.getY() - pos.getY();
        float dz = targetNode.getZ() - pos.getZ();

        float x = (float) ((pos.getX() + 0.5) - camPos.x());
        float z = (float) ((pos.getZ() + 0.5) - camPos.z());
        float y = (float) ((pos.getY() + 0.02) - camPos.y());

        int r = 0, g = 255, b = 0, a = 255;

        switch (node.action()) {
            case MINE -> { r = 255; g = 0; b = 255; }
            case BUILD_BRIDGE, BUILD_STAIR -> { r = 0; g = 255; b = 255; }
            case WALK -> {
                int traffic = ClientDebugData.trafficMap.getOrDefault(pos, 1);

                float ratio = (float) (Math.log(traffic) / Math.log(Math.max(2, ClientDebugData.maxTraffic)));
                ratio = Math.min(1.0f, Math.max(0.0f, ratio));

                if (ratio < 0.5f) {
                    float normalized = ratio * 2.0f;
                    r = (int) (255 * normalized);
                    g = 255;
                    b = 0;
                } else {
                    float normalized = (ratio - 0.5f) * 2.0f;
                    r = 255;
                    g = (int) (255 * (1.0f - normalized));
                    b = 0;
                }
            }
        }

        // Draw bounding box highlights for terraforming actions
        if (node.action() != SiegeNode.SiegeAction.WALK) {
            float minX = (float) (targetNode.getX() + 0.1 - camPos.x());
            float minY = (float) (targetNode.getY() + 0.1 - camPos.y());
            float minZ = (float) (targetNode.getZ() + 0.1 - camPos.z());
            float maxX = (float) (targetNode.getX() + 0.9 - camPos.x());
            float maxY = (float) (targetNode.getY() + 0.9 - camPos.y());
            float maxZ = (float) (targetNode.getZ() + 0.9 - camPos.z());

            buffer.addVertex(pose, minX, minY, minZ).setColor(r, g, b, 150);
            buffer.addVertex(pose, maxX, minY, minZ).setColor(r, g, b, 150);
            buffer.addVertex(pose, minX, maxY, minZ).setColor(r, g, b, 150);
            buffer.addVertex(pose, maxX, maxY, minZ).setColor(r, g, b, 150);
            buffer.addVertex(pose, minX, minY, maxZ).setColor(r, g, b, 150);
            buffer.addVertex(pose, maxX, minY, maxZ).setColor(r, g, b, 150);
            buffer.addVertex(pose, minX, maxY, maxZ).setColor(r, g, b, 150);
            buffer.addVertex(pose, maxX, maxY, maxZ).setColor(r, g, b, 150);
        }

        // Draw a static cross if it's an action taking place on the same block
        if (dx == 0 && dz == 0) {
            float s = 0.2f;
            if (dy > 0) {
                buffer.addVertex(pose, x - s, y, z).setColor(r, g, b, a);
                buffer.addVertex(pose, x + s, y, z).setColor(r, g, b, a);
                buffer.addVertex(pose, x, y, z - s).setColor(r, g, b, a);
                buffer.addVertex(pose, x, y, z + s).setColor(r, g, b, a);
            } else {
                buffer.addVertex(pose, x - s, y, z - s).setColor(r, g, b, a);
                buffer.addVertex(pose, x + s, y, z + s).setColor(r, g, b, a);
                buffer.addVertex(pose, x - s, y, z + s).setColor(r, g, b, a);
                buffer.addVertex(pose, x + s, y, z - s).setColor(r, g, b, a);
            }
            return;
        }

        // Draw directional pointer for paths
        float lengthXZ = (float) Math.sqrt(dx * dx + dz * dz);
        float nx = dx / lengthXZ;
        float nz = dz / lengthXZ;

        float startX = x - (nx * 0.3f);
        float startZ = z - (nz * 0.3f);
        float endX = x + (nx * 0.4f);
        float endZ = z + (nz * 0.4f);

        buffer.addVertex(pose, startX, y, startZ).setColor(r, g, b, a);
        buffer.addVertex(pose, endX, y, endZ).setColor(r, g, b, a);

        float wingLength = 0.25f;
        float cos135 = -0.7071f;
        float sin135 = 0.7071f;

        float leftWingX = endX + (nx * cos135 - nz * sin135) * wingLength;
        float leftWingZ = endZ + (nx * sin135 + nz * cos135) * wingLength;

        buffer.addVertex(pose, endX, y, endZ).setColor(r, g, b, a);
        buffer.addVertex(pose, leftWingX, y, leftWingZ).setColor(r, g, b, a);

        float rightWingX = endX + (nx * cos135 - nz * -sin135) * wingLength;
        float rightWingZ = endZ + (nx * -sin135 + nz * cos135) * wingLength;

        buffer.addVertex(pose, endX, y, endZ).setColor(r, g, b, a);
        buffer.addVertex(pose, rightWingX, y, rightWingZ).setColor(r, g, b, a);
    }
}