package org.ratden.skavenblight.debug.mode.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.ratden.skavenblight.ai.pathing.SiegeNode;
import org.ratden.skavenblight.client.ClientDebugData;

import java.util.Map;

public class DetailedClientMode implements IClientDebugMode {
    @Override
    public void updateData() {
        ClientDebugData.calculateTrafficMap();
    }

    @Override
    public void render(Matrix4f pose, BufferBuilder buffer, Player player, Vec3 camPos) {
        for (Map.Entry<BlockPos, SiegeNode> entry : ClientDebugData.flowFieldNodes.entrySet()) {
            if (entry.getKey().closerThan(player.blockPosition(), 24)) {
                drawFloorArrow(pose, buffer, entry.getKey(), entry.getValue(), camPos);
            }
        }
    }

    // Paste drawFloorArrow() here...
    private static void drawFloorArrow(org.joml.Matrix4f pose, BufferBuilder buffer, BlockPos pos, SiegeNode node, Vec3 camPos) {
        BlockPos targetNode = node.pos();

        float dx = targetNode.getX() - pos.getX();
        float dy = targetNode.getY() - pos.getY();
        float dz = targetNode.getZ() - pos.getZ();

        float x = (float) ((pos.getX() + 0.5) - camPos.x());
        float z = (float) ((pos.getZ() + 0.5) - camPos.z());
        float y = (float) ((pos.getY() + 0.02) - camPos.y());

        int r = 0, g = 255, b = 0, a = 255;

        switch (node.action()) {
            case MINE -> { r = 255; g = 0; b = 255; } // Magenta
            case BUILD_BRIDGE, BUILD_STAIR -> { r = 0; g = 255; b = 255; } // Cyan

            // --- NEW: Visual representations for the added actions ---
            case BUILD_LANDING -> { r = 255; g = 215; b = 0; } // Gold staging areas
            case BUILD_PILLAR -> { r = 255; g = 140; b = 0; }  // Dark Orange vertical shafts
            case LEAP -> { r = 255; g = 255; b = 0; }          // Bright Yellow gap jumps

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

        // Draws the 3D target block outline box (Handles all non-WALK items automatically)
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

        // Draw node direction indicators
        if (dx == 0 && dz == 0) {
            float s = 0.2f;
            if (dy > 0) {
                // Upward movement (Like BUILD_PILLAR) gets a plus crosshair indicator
                buffer.addVertex(pose, x - s, y, z).setColor(r, g, b, a);
                buffer.addVertex(pose, x + s, y, z).setColor(r, g, b, a);
                buffer.addVertex(pose, x, y, z - s).setColor(r, g, b, a);
                buffer.addVertex(pose, x, y, z + s).setColor(r, g, b, a);
            } else {
                // Downward movement gets a diagonal cross
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

        buffer.addVertex(pose, endX, y, endZ).setColor(r, g, b, a);
        buffer.addVertex(pose, leftWingX, y, leftWingZ).setColor(r, g, b, a);

        float rightWingX = endX + (nx * cos135 - nz * -sin135) * wingLength;
        float rightWingZ = endZ + (nx * -sin135 + nz * cos135) * wingLength;

        buffer.addVertex(pose, endX, y, endZ).setColor(r, g, b, a);
        buffer.addVertex(pose, rightWingX, y, rightWingZ).setColor(r, g, b, a);
    }
}