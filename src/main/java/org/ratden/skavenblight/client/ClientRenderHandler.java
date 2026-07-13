package org.ratden.skavenblight.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.ratden.skavenblight.ai.pathing.SiegeNode;
import com.mojang.blaze3d.vertex.BufferUploader;

import java.util.Map;

public class ClientRenderHandler {

    // Note: Call this method from your Forge/NeoForge RenderLevelStageEvent subscriber
    public static void renderFlowFieldDebug(Matrix4f pose, Vec3 camPos) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;

        Tesselator tesselator = Tesselator.getInstance();

        // Setup RenderSystem for drawing translucent debug lines through terrain
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
        RenderSystem.lineWidth(2.0f);

        // Tesselator.begin() initializes and returns the BufferBuilder directly
        BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        // --- LAYER 2: Mode Specific Data ---
        if (ClientDebugData.currentMode == 0) {
            // DETAILED MODE: Draw block-level arrows
            for (Map.Entry<BlockPos, SiegeNode> entry : ClientDebugData.flowFieldNodes.entrySet()) {
                if (entry.getKey().closerThan(player.blockPosition(), 24)) {
                    drawFloorArrow(pose, buffer, entry.getKey(), entry.getValue(), camPos);
                }
            }
        } else if (ClientDebugData.currentMode == 1) {
            // MACRO NAVMESH MODE: Draw massive chunk-to-chunk arrows sky-high
            float yLevel = 310.0F;
            for (Map.Entry<ChunkPos, ChunkPos> entry : ClientDebugData.macroArrows.entrySet()) {
                if (player.chunkPosition().getChessboardDistance(entry.getKey()) <= 3) {
                    drawMacroArrow(pose, buffer, entry.getKey(), entry.getValue(), camPos, yLevel);
                }
            }
        } else if (ClientDebugData.currentMode == 2) {
            // WILDERNESS VIEW MODE: Render the dynamic hybrid evaluation near the player
            for (Map.Entry<BlockPos, SiegeNode> entry : ClientDebugData.flowFieldNodes.entrySet()) {
                drawWildernessArrow(pose, buffer, entry.getKey(), entry.getValue(), camPos);
            }
        }

        // Build the mesh data and upload it via BufferUploader
        BufferUploader.drawWithShader(buffer.buildOrThrow());

        // Restore RenderSystem state
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    // --------------------------------------------------------
    // 1. DETAILED MODE (Block-Level Navigation)
    // --------------------------------------------------------
    private static void drawFloorArrow(Matrix4f pose, BufferBuilder buffer, BlockPos pos, SiegeNode node, Vec3 camPos) {
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
            case BUILD_LANDING -> { r = 255; g = 215; b = 0; }
            case BUILD_PILLAR -> { r = 255; g = 140; b = 0; }
            case LEAP -> { r = 255; g = 255; b = 0; }
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
        float rightWingX = endX + (nx * cos135 - nz * -sin135) * wingLength;
        float rightWingZ = endZ + (nx * -sin135 + nz * cos135) * wingLength;

        buffer.addVertex(pose, endX, y, endZ).setColor(r, g, b, a);
        buffer.addVertex(pose, leftWingX, y, leftWingZ).setColor(r, g, b, a);
        buffer.addVertex(pose, endX, y, endZ).setColor(r, g, b, a);
        buffer.addVertex(pose, rightWingX, y, rightWingZ).setColor(r, g, b, a);
    }

    // --------------------------------------------------------
    // 2. MACRO NAVMESH MODE (Chunk-to-Chunk Overhead Radar)
    // --------------------------------------------------------
    private static void drawMacroArrow(Matrix4f pose, BufferBuilder buffer, ChunkPos from, ChunkPos to, Vec3 camPos, float yLevel) {
        float startX = (float) (from.getMiddleBlockX() - camPos.x());
        float startZ = (float) (from.getMiddleBlockZ() - camPos.z());
        float endX = (float) (to.getMiddleBlockX() - camPos.x());
        float endZ = (float) (to.getMiddleBlockZ() - camPos.z());
        float y = (float) (yLevel - camPos.y());

        int r = 255, g = 128, b = 0, a = 255; // Big Orange Arrow

        // Main chunk connector line
        buffer.addVertex(pose, startX, y, startZ).setColor(r, g, b, a);
        buffer.addVertex(pose, endX, y, endZ).setColor(r, g, b, a);

        float dx = endX - startX;
        float dz = endZ - startZ;
        float length = (float) Math.sqrt(dx * dx + dz * dz);

        if (length > 0) {
            float nx = dx / length;
            float nz = dz / length;
            float wingLength = 4.0f; // Scale wings up drastically for chunk size
            float cos135 = -0.7071f;
            float sin135 = 0.7071f;

            float leftX = endX + (nx * cos135 - nz * sin135) * wingLength;
            float leftZ = endZ + (nx * sin135 + nz * cos135) * wingLength;
            float rightX = endX + (nx * cos135 - nz * -sin135) * wingLength;
            float rightZ = endZ + (nx * -sin135 + nz * cos135) * wingLength;

            buffer.addVertex(pose, endX, y, endZ).setColor(r, g, b, a);
            buffer.addVertex(pose, leftX, y, leftZ).setColor(r, g, b, a);
            buffer.addVertex(pose, endX, y, endZ).setColor(r, g, b, a);
            buffer.addVertex(pose, rightX, y, rightZ).setColor(r, g, b, a);
        }
    }

    // --------------------------------------------------------
    // 3. WILDERNESS MODE (Dynamic Hybrid Evaluation)
    // --------------------------------------------------------
    private static void drawWildernessArrow(Matrix4f pose, BufferBuilder buffer, BlockPos pos, SiegeNode node, Vec3 camPos) {
        BlockPos targetNode = node.pos();

        float dx = targetNode.getX() - pos.getX();
        float dy = targetNode.getY() - pos.getY();
        float dz = targetNode.getZ() - pos.getZ();

        float x = (float) ((pos.getX() + 0.5) - camPos.x());
        float z = (float) ((pos.getZ() + 0.5) - camPos.z());
        float y = (float) ((pos.getY() + 0.05) - camPos.y());

        int r = 255, g = 255, b = 255, a = 255;

        switch (node.action()) {
            case MINE -> { r = 255; g = 0; b = 128; }
            case BUILD_BRIDGE, BUILD_STAIR -> { r = 0; g = 191; b = 255; }
            case BUILD_LANDING -> { r = 255; g = 215; b = 0; }
            case BUILD_PILLAR -> { r = 255; g = 69; b = 0; }
            case LEAP -> { r = 124; g = 252; b = 0; }
        }

        float minX = (float) (pos.getX() - camPos.x());
        float minY = (float) (pos.getY() - camPos.y());
        float minZ = (float) (pos.getZ() - camPos.z());
        float maxX = minX + 1.0f;
        float maxZ = minZ + 1.0f;

        // Thin white bounding box showing evaluated wilderness grid slots
        buffer.addVertex(pose, minX, minY, minZ).setColor(255, 255, 255, 60);
        buffer.addVertex(pose, maxX, minY, minZ).setColor(255, 255, 255, 60);
        buffer.addVertex(pose, minX, minY, minZ).setColor(255, 255, 255, 60);
        buffer.addVertex(pose, minX, minY, maxZ).setColor(255, 255, 255, 60);

        if (dx == 0 && dz == 0 && dy == 0) return;

        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length > 0) {
            float nx = dx / length;
            float ny = dy / length;
            float nz = dz / length;

            // Vector ray out to target
            buffer.addVertex(pose, x, y, z).setColor(r, g, b, a);
            buffer.addVertex(pose, x + nx * 0.5f, y + ny * 0.5f, z + nz * 0.5f).setColor(r, g, b, a);
        }
    }
}