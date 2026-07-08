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
import com.mojang.blaze3d.vertex.BufferUploader;

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

        // 1. DRAW SUPERIMPOSED CHUNK BORDERS (Purple)
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

        // 2. DRAW FLOW FIELD ARROWS ON THE FLOOR (Green)
        RenderSystem.enableDepthTest();
        buffer = tesselator.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        // --- CHANGED: Now iterates over a Map of <BlockPos, BlockPos> ---
        for (Map.Entry<BlockPos, BlockPos> entry : ClientDebugData.flowFieldNodes.entrySet()) {
            BlockPos pos = entry.getKey();
            if (pos.closerThan(player.blockPosition(), 16)) {
                drawFloorArrow(pose, buffer, pos, entry.getValue(), camPos);
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

    private static void drawFloorArrow(org.joml.Matrix4f pose, BufferBuilder buffer, BlockPos pos, BlockPos targetNode, net.minecraft.world.phys.Vec3 camPos) {
        // Find the difference vector between blocks
        float dx = targetNode.getX() - pos.getX();
        float dy = targetNode.getY() - pos.getY();
        float dz = targetNode.getZ() - pos.getZ();

        float x = (float) ((pos.getX() + 0.5) - camPos.x());
        float z = (float) ((pos.getZ() + 0.5) - camPos.z());
        float y = (float) ((pos.getY() + 0.02) - camPos.y());

        int r = 0, g = 255, b = 100, a = 255;

        // If it's a purely vertical drop/climb, draw an indicator
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

        // Normalize the vector so the arrow is a consistent length regardless of distance
        float lengthXZ = (float) Math.sqrt(dx * dx + dz * dz);
        float nx = dx / lengthXZ;
        float nz = dz / lengthXZ;

        float startX = x - (nx * 0.3f);
        float startZ = z - (nz * 0.3f);
        float endX = x + (nx * 0.4f);
        float endZ = z + (nz * 0.4f);

        // Draw the main shaft of the arrow
        buffer.addVertex(pose, startX, y, startZ).setColor(r, g, b, a);
        buffer.addVertex(pose, endX, y, endZ).setColor(r, g, b, a);

        // Calculate wings using a 2D rotation matrix (swept back 135 degrees)
        float wingLength = 0.25f;
        float cos135 = -0.7071f;
        float sin135 = 0.7071f;

        // Left Wing
        float leftWingX = endX + (nx * cos135 - nz * sin135) * wingLength;
        float leftWingZ = endZ + (nx * sin135 + nz * cos135) * wingLength;

        buffer.addVertex(pose, endX, y, endZ).setColor(r, g, b, a);
        buffer.addVertex(pose, leftWingX, y, leftWingZ).setColor(r, g, b, a);

        // Right Wing
        float rightWingX = endX + (nx * cos135 - nz * -sin135) * wingLength;
        float rightWingZ = endZ + (nx * -sin135 + nz * cos135) * wingLength;

        buffer.addVertex(pose, endX, y, endZ).setColor(r, g, b, a);
        buffer.addVertex(pose, rightWingX, y, rightWingZ).setColor(r, g, b, a);
    }
}