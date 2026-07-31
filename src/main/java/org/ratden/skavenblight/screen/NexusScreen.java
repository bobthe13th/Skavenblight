package org.ratden.skavenblight.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

public class NexusScreen extends Screen {

    // 1.21 uses fromNamespaceAndPath instead of the old constructor
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath("skavenblight", "textures/gui/nexus.png");

    private enum Tab { NETWORK, SCHEMES }
    private Tab currentTab = Tab.NETWORK;

    public NexusScreen() {
        super(Component.translatable("gui.skavenblight.nexus"));
    }

    @Override
    protected void init() {
        super.init();
        int startX = (this.width - 256) / 2;
        int startY = (this.height - 256) / 2;

        // Network Tab Button
        this.addRenderableWidget(Button.builder(Component.literal("Network"), button -> {
            this.currentTab = Tab.NETWORK;
            this.rebuildWidgets();
        }).bounds(startX, startY - 20, 60, 20).build());

        // Scheme Tab Button
        this.addRenderableWidget(Button.builder(Component.literal("Schemes"), button -> {
            this.currentTab = Tab.SCHEMES;
            this.rebuildWidgets();
        }).bounds(startX + 62, startY - 20, 60, 20).build());

        // Research Tab Button (Opens Modonomicon)
        this.addRenderableWidget(Button.builder(Component.literal("Research"), button -> {
            if (this.minecraft != null) {
                this.minecraft.setScreen(null); // Close the custom Nexus screen

                // TODO: Send a network packet to the server to open the Modonomicon book.
                // PacketHandler.sendToServer(new OpenResearchPacket());
            }
        }).bounds(startX + 124, startY - 20, 60, 20).build());

        // Initialize specific widgets based on the active tab
        if (this.currentTab == Tab.NETWORK) {
            initNetworkTab(startX, startY);
        } else if (this.currentTab == Tab.SCHEMES) {
            initSchemeTab(startX, startY);
        }
    }

    private void initNetworkTab(int x, int y) {
        // Add network-specific buttons/widgets here
    }

    private void initSchemeTab(int x, int y) {
        // Add scheme-specific buttons/widgets here
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);

        int startX = (this.width - 256) / 2;
        int startY = (this.height - 256) / 2;

        // Draw main GUI texture
        graphics.blit(TEXTURE, startX, startY, 0, 0, 256, 256);

        super.render(graphics, mouseX, mouseY, partialTick);

        // Render tab-specific text/overlays
        if (this.currentTab == Tab.NETWORK) {
            graphics.drawString(this.font, "Warp-Network Status: Active", startX + 10, startY + 10, 0x44FF44, false);
        } else {
            graphics.drawString(this.font, "Active Schemes", startX + 10, startY + 10, 0xFF4444, false);
        }
    }
}