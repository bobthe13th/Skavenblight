package org.ratden.skavenblight.debug.mode.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.ratden.skavenblight.client.ClientDebugData;

import java.util.*;

import static org.ratden.skavenblight.client.ClientDebugData.*;

public class MacroClientMode implements IClientDebugMode {
    @Override
    public void updateData() {
        ClientDebugData.calculateMacroNavMesh();
    }

    @Override
    public void render(Matrix4f pose, BufferBuilder buffer, Player player, Vec3 camPos) {
        float yLevel = 310.0F;
        for (Map.Entry<ChunkPos, ChunkPos> entry : macroArrows.entrySet()) {
            if (player.chunkPosition().getChessboardDistance(entry.getKey()) <= 3) {
                drawMacroArrow(pose, buffer, entry.getKey(), entry.getValue(), camPos, yLevel);
            }
        }
    }

    private static void drawMacroArrow(org.joml.Matrix4f pose, BufferBuilder buffer, ChunkPos from, ChunkPos to, Vec3 camPos, float yLevel) {
        float startX = (float) (from.getMiddleBlockX() - camPos.x());
        float startZ = (float) (from.getMiddleBlockZ() - camPos.z());
        float endX = (float) (to.getMiddleBlockX() - camPos.x());
        float endZ = (float) (to.getMiddleBlockZ() - camPos.z());
        float y = (float) (yLevel - camPos.y());

        // Draw bright orange arrows
        int r = 255, g = 165, b = 0, a = 255;

        float dx = endX - startX;
        float dz = endZ - startZ;
        float lengthXZ = (float) Math.sqrt(dx * dx + dz * dz);
        float nx = dx / lengthXZ;
        float nz = dz / lengthXZ;

        // Pull the arrow back slightly so it doesn't clip into the exact center of the chunk
        float actualEndX = endX - (nx * 2.0f);
        float actualEndZ = endZ - (nz * 2.0f);

        buffer.addVertex(pose, startX, y, startZ).setColor(r, g, b, a);
        buffer.addVertex(pose, actualEndX, y, actualEndZ).setColor(r, g, b, a);

        float wingLength = 4.0f;
        float cos135 = -0.7071f;
        float sin135 = 0.7071f;

        float leftWingX = actualEndX + (nx * cos135 - nz * sin135) * wingLength;
        float leftWingZ = actualEndZ + (nx * sin135 + nz * cos135) * wingLength;

        buffer.addVertex(pose, actualEndX, y, actualEndZ).setColor(r, g, b, a);
        buffer.addVertex(pose, leftWingX, y, leftWingZ).setColor(r, g, b, a);

        float rightWingX = actualEndX + (nx * cos135 - nz * -sin135) * wingLength;
        float rightWingZ = actualEndZ + (nx * -sin135 + nz * cos135) * wingLength;

        buffer.addVertex(pose, actualEndX, y, actualEndZ).setColor(r, g, b, a);
        buffer.addVertex(pose, rightWingX, y, rightWingZ).setColor(r, g, b, a);
    }
    public static void calculateMacroNavMesh() {
        macroArrows.clear();

        // If we have no mapped chunks, there's nowhere to route to.
        if (mappedChunks.isEmpty()) return;

        Queue<ChunkPos> queue = new LinkedList<>(mappedChunks);
        Set<ChunkPos> visited = new HashSet<>(mappedChunks);

        while (!queue.isEmpty()) {
            ChunkPos current = queue.poll();

            // Check the 4 cardinal neighboring chunks (North, South, East, West)
            ChunkPos[] neighbors = new ChunkPos[] {
                    new ChunkPos(current.x + 1, current.z),
                    new ChunkPos(current.x - 1, current.z),
                    new ChunkPos(current.x, current.z + 1),
                    new ChunkPos(current.x, current.z - 1)
            };

            for (ChunkPos neighbor : neighbors) {
                // If the neighbor is part of the Nexus territory but hasn't been mapped yet
                if (territoryChunks.contains(neighbor) && !visited.contains(neighbor)) {
                    visited.add(neighbor);

                    // Point this wilderness chunk toward the current chunk (which is closer to the core)
                    macroArrows.put(neighbor, current);

                    // Add it to the queue to keep flooding outward
                    queue.add(neighbor);
                }
            }
        }
    }
}
