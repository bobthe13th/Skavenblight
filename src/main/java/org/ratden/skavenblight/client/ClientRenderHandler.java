package org.ratden.skavenblight.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.item.ModItems; // Adjust to your item registry path
import net.minecraft.world.level.ChunkPos;

import java.util.Map;

@EventBusSubscriber(modid = Skavenblight.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class ClientRenderHandler {

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        // We render after all standard block outlines are drawn
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;

        // Only render if the player is holding our debug tool
        boolean holdingReader = player.getMainHandItem().is(ModItems.DEBUG_FLOW_FIELD_READER.get())
                || player.getOffhandItem().is(ModItems.DEBUG_FLOW_FIELD_READER.get());
        if (!holdingReader) return;

        Vec3 cameraPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();

        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        // Prepare our line rendering configurations
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.lineWidth(3.0F); // Nice thick lines

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        // 1. DRAW SUPERIMPOSED CHUNK BORDERS (Purple)
        RenderSystem.disableDepthTest(); // This makes lines see-through/superimposed!
        for (ChunkPos chunk : ClientDebugData.territoryChunks) {
            if (player.chunkPosition().getChessboardDistance(chunk) <= 3) {
                drawChunkColumns(buffer, chunk);
            }
        }
        tesselator.clear(); // Flush buffer

        // 2. DRAW FLOW FIELD ARROWS ON THE FLOOR (Green)
        RenderSystem.enableDepthTest(); // Re-enable depth testing so arrows hide under blocks naturally
        buffer = tesselator.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        for (Map.Entry<BlockPos, Direction> entry : ClientDebugData.flowFieldDirections.entrySet()) {
            BlockPos pos = entry.getKey();
            if (pos.closerThan(player.blockPosition(), 16)) {
                drawFloorArrow(buffer, pos, entry.getValue());
            }
        }
        tesselator.clear();

        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    private static void drawChunkColumns(BufferBuilder buffer, ChunkPos chunk) {
        int minX = chunk.getMinBlockX();
        int minZ = chunk.getMinBlockZ();
        int maxX = chunk.getMaxBlockX() + 1;
        int maxZ = chunk.getMaxBlockZ() + 1;

        // Draw a transparent 3D bounding frame spanning from void to sky limit
        int r = 150, g = 0, b = 255, a = 255; // Dark Magenta/Purple

        for (int y = -64; y <= 320; y += 32) {
            // Horizontal square rings every 32 blocks up
            buffer.addVertex(minX, y, minZ).setColor(r, g, b, a); buffer.addVertex(maxX, y, minZ).setColor(r, g, b, a);
            buffer.addVertex(maxX, y, minZ).setColor(r, g, b, maxZ).setColor(r, g, b, a);
            buffer.addVertex(maxX, y, maxZ).setColor(r, g, b, a); buffer.addVertex(minX, y, maxZ).setColor(r, g, b, a);
            buffer.addVertex(minX, y, maxZ).setColor(r, g, b, a); buffer.addVertex(minX, y, minZ).setColor(r, g, b, a);
        }
        // Vertical corner columns
        buffer.addVertex(minX, -64, minZ).setColor(r, g, b, a); buffer.addVertex(minX, 320, minZ).setColor(r, g, b, a);
        buffer.addVertex(maxX, -64, minZ).setColor(r, g, b, a); buffer.addVertex(maxX, 320, minZ).setColor(r, g, b, a);
        buffer.addVertex(maxX, -64, maxZ).setColor(r, g, b, a); buffer.addVertex(maxX, 320, maxZ).setColor(r, g, b, a);
        buffer.addVertex(minX, -64, maxZ).setColor(r, g, b, a); buffer.addVertex(minX, 320, maxZ).setColor(r, g, b, a);
    }

    private static void drawFloorArrow(BufferBuilder buffer, BlockPos pos, Direction dir) {
        double x = pos.getX() + 0.5;
        double z = pos.getZ() + 0.5;
        double y = pos.getY() + 1.02; // Elevated slightly above the block face to prevent z-fighting textures

        int r = 0, g = 255, b = 100, a = 255; // Bright Warpstone Green

        // Draw the main pointer stem line
        double startX = x - (dir.getStepX() * 0.3);
        double startZ = z - (dir.getStepZ() * 0.3);
        double endX = x + (dir.getStepX() * 0.4);
        double endZ = z + (dir.getStepZ() * 0.4);

        buffer.addVertex((float) startX, (float) y, (float) startZ).setColor(r, g, b, a);
        buffer.addVertex((float) endX, (float) y, (float) endZ).setColor(r, g, b, a);

        // Draw arrow wings pointing backwards from the tip
        Direction leftWing = dir.getCounterClockWise();
        double wingLeftX = endX - (dir.getStepX() * 0.2) + (leftWing.getStepX() * 0.2);
        double wingLeftZ = endZ - (dir.getStepZ() * 0.2) + (leftWing.getStepZ() * 0.2);

        buffer.addVertex((float) endX, (float) y, (float) endZ).setColor(r, g, b, a);
        buffer.addVertex((float) wingLeftX, (float) y, (float) wingLeftZ).setColor(r, g, b, a);

        Direction rightWing = dir.getClockWise();
        double wingRightX = endX - (dir.getStepX() * 0.2) + (rightWing.getStepX() * 0.2);
        double wingRightZ = endZ - (dir.getStepZ() * 0.2) + (rightWing.getStepZ() * 0.2);

        buffer.addVertex((float) endX, (float) y, (float) endZ).setColor(r, g, b, a);
        buffer.addVertex((float) wingRightX, (float) y, (float) wingRightZ).setColor(r, g, b, a);
    }
}