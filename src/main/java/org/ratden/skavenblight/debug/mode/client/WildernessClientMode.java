package org.ratden.skavenblight.debug.mode.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.ratden.skavenblight.ai.pathing.SiegeNode;
import org.ratden.skavenblight.client.ClientDebugData;

import java.util.Map;

public class WildernessClientMode implements IClientDebugMode {
    @Override
    public void updateData() {
        ClientDebugData.trafficMap.clear(); // Wilderness mode doesn't track traffic
    }

    @Override
    public void render(Matrix4f pose, BufferBuilder buffer, Player player, Vec3 camPos) {
        for (Map.Entry<BlockPos, SiegeNode> entry : ClientDebugData.flowFieldNodes.entrySet()) {
            drawWildernessArrow(pose, buffer, entry.getKey(), entry.getValue(), camPos);
        }
    }

    // Paste drawWildernessArrow() here...
    private static void drawWildernessArrow(org.joml.Matrix4f pose, BufferBuilder buffer, BlockPos pos, SiegeNode node, Vec3 camPos) {
        BlockPos targetNode = node.pos();

        float dx = targetNode.getX() - pos.getX();
        float dy = targetNode.getY() - pos.getY();
        float dz = targetNode.getZ() - pos.getZ();

        float x = (float) ((pos.getX() + 0.5) - camPos.x());
        float z = (float) ((pos.getZ() + 0.5) - camPos.z());
        float y = (float) ((pos.getY() + 0.05) - camPos.y()); // Slightly higher to prevent floor clipping

        // Wilderness color palette uses high-contrast bright indicators
        int r = 255, g = 255, b = 255, a = 255; // Default white for wilderness WALK

        switch (node.action()) {
            case MINE -> { r = 255; g = 0; b = 128; }         // Hot Pink
            case BUILD_BRIDGE, BUILD_STAIR -> { r = 0; g = 191; b = 255; } // Deep Sky Blue
            case BUILD_LANDING -> { r = 255; g = 215; b = 0; } // Gold
            case BUILD_PILLAR -> { r = 255; g = 69; b = 0; }   // Red-Orange
            case LEAP -> { r = 124; g = 252; b = 0; }          // Lawn Green
        }

        // Draw an outer wireframe box around the block being evaluated
        float minX = (float) (pos.getX() - camPos.x());
        float minY = (float) (pos.getY() - camPos.y());
        float minZ = (float) (pos.getZ() - camPos.z());
        float maxX = minX + 1.0f;
        float maxY = minY + 1.0f;
        float maxZ = minZ + 1.0f;

        // Thin white bounding box showing evaluated wilderness grid slots
        buffer.addVertex(pose, minX, minY, minZ).setColor(255, 255, 255, 60);
        buffer.addVertex(pose, maxX, minY, minZ).setColor(255, 255, 255, 60);
        buffer.addVertex(pose, minX, minY, minZ).setColor(255, 255, 255, 60);
        buffer.addVertex(pose, minX, minY, maxZ).setColor(255, 255, 255, 60);

        if (dx == 0 && dz == 0 && dy == 0) return;

        // Draw directional vector line pointing to the wilderness target heading
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
