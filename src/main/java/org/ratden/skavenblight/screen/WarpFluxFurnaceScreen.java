package org.ratden.skavenblight.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import org.ratden.skavenblight.Skavenblight;

public class WarpFluxFurnaceScreen extends AbstractContainerScreen<WarpFluxFurnaceMenu> {
    // Texture locations
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Skavenblight.MODID, "textures/gui/warp_flux_furnace_gui.png");
    private static final ResourceLocation PROGRESS_BAR =
            ResourceLocation.fromNamespaceAndPath(Skavenblight.MODID, "textures/gui/sprites/lit_progress.png");
    private static final ResourceLocation ENERGY_BAR =
            ResourceLocation.fromNamespaceAndPath(Skavenblight.MODID, "textures/gui/sprites/energybar.png");

    public WarpFluxFurnaceScreen(WarpFluxFurnaceMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
    }

    @Override
    protected void init() {
        super.init();
        this.inventoryLabelY = 10000; // Hides the default "Inventory" text to keep it clean
        this.titleLabelY = 10000;     // Hides the default title text
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick); // Dims the world behind the GUI
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        renderTooltip(guiGraphics, mouseX, mouseY); // Shows item names when hovered over slots

        // Draw our custom Warp Flux tooltip
        renderEnergyAreaTooltip(guiGraphics, mouseX, mouseY, this.leftPos, this.topPos);
        renderProgressAreaTooltip(guiGraphics, mouseX, mouseY, this.leftPos, this.topPos);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        com.mojang.blaze3d.systems.RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexShader);
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        int x = (width - imageWidth) / 2;
        int y = (height - imageHeight) / 2;

        // 1. Draw the main background
        guiGraphics.blit(TEXTURE, x, y, 0, 0, imageWidth, imageHeight);

        // 2. Draw the Progress Arrow (14x14)
        int progress = menu.getScaledProgress();
        if (progress > 0) {
            int ARROW_X = 65;
            int ARROW_Y = 34;

            // Draws (progress) width, and 14 height. The texture itself is 14x14.
            guiGraphics.blit(PROGRESS_BAR, x + ARROW_X, y + ARROW_Y, 0, 0, progress, 14, 14, 14);
        }

        // 3. Draw the Energy Bar (23x56)
        int flux = menu.getScaledFlux();
        if (flux > 0) {
            int TANK_X = 139;
            int TANK_Y = 11;

            int barWidth = 23;
            int barHeight = 56;
            int emptySpace = barHeight - flux;

            // Draws bottom-up
            guiGraphics.blit(ENERGY_BAR, x + TANK_X, y + TANK_Y + emptySpace, 0, emptySpace, barWidth, flux, barWidth, barHeight);
        }
    }

    private void renderEnergyAreaTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY, int x, int y) {
        // IMPORTANT: Match these numbers to the TANK_X, TANK_Y, barWidth, and barHeight from your renderBg method!
        int TANK_X = 139;
        int TANK_Y = 11;
        int barWidth = 23;
        int barHeight = 56;

        if (isMouseAboveArea(mouseX, mouseY, x, y, TANK_X, TANK_Y, barWidth, barHeight)) {
            int flux = menu.getFlux();
            int maxFlux = menu.getMaxFlux();

            // Format: "5000 / 10000 Warp Flux"
            Component text = Component.literal(flux + " / " + maxFlux + " Warp Flux");
            guiGraphics.renderTooltip(this.font, text, mouseX, mouseY);
        }
    }

    private void renderProgressAreaTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY, int x, int y) {
        // IMPORTANT: Match these numbers to the ARROW_X, ARROW_Y, and 14x14 dimensions!
        int ARROW_X = 65;
        int ARROW_Y = 34;
        int arrowWidth = 14;
        int arrowHeight = 14;

        if (isMouseAboveArea(mouseX, mouseY, x, y, ARROW_X, ARROW_Y, arrowWidth, arrowHeight)) {
            int progress = menu.getProgress();
            int maxProgress = menu.getMaxProgress();

            Component text;
            if (progress > 0 && maxProgress > 0) {
                int percent = (int) Math.min(100, Math.max(0, (long) progress * 100 / maxProgress));
                text = Component.literal("Progress: " + percent + "%").withStyle(net.minecraft.ChatFormatting.GREEN);
            } else {
                text = Component.literal("Progress: Idle").withStyle(net.minecraft.ChatFormatting.GRAY);
            }
            guiGraphics.renderTooltip(this.font, text, mouseX, mouseY);
        }
    }

    private boolean isMouseAboveArea(int mouseX, int mouseY, int x, int y, int offsetX, int offsetY, int width, int height) {
        return mouseX >= (x + offsetX) && mouseX <= (x + offsetX) + width &&
                mouseY >= (y + offsetY) && mouseY <= (y + offsetY) + height;
    }
}