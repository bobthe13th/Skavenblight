package org.ratden.skavenblight.screen;

import com.klikli_dev.modonomicon.client.gui.BookGuiManager;
import com.klikli_dev.modonomicon.client.gui.book.BookAddress;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

public class NexusScreen extends Screen {

    // 1.21 uses fromNamespaceAndPath instead of the old constructor
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath("skavenblight", "textures/gui/nexus.png");
    private static final ResourceLocation BOOK_ID = ResourceLocation.fromNamespaceAndPath("skavenblight", "nexus_research");

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
        Button networkBtn = Button.builder(Component.translatable("gui.skavenblight.nexus.tab.network"), button -> {
            this.currentTab = Tab.NETWORK;
            this.rebuildWidgets();
        }).bounds(startX, startY - 20, 60, 20).build();
        networkBtn.setTooltip(Tooltip.create(Component.translatable("gui.skavenblight.nexus.tab.network.tooltip")));
        networkBtn.active = (this.currentTab != Tab.NETWORK);
        this.addRenderableWidget(networkBtn);

        // Scheme Tab Button
        Button schemesBtn = Button.builder(Component.translatable("gui.skavenblight.nexus.tab.schemes"), button -> {
            this.currentTab = Tab.SCHEMES;
            this.rebuildWidgets();
        }).bounds(startX + 62, startY - 20, 60, 20).build();
        schemesBtn.setTooltip(Tooltip.create(Component.translatable("gui.skavenblight.nexus.tab.schemes.tooltip")));
        schemesBtn.active = (this.currentTab != Tab.SCHEMES);
        this.addRenderableWidget(schemesBtn);

        // Research Tab Button (Opens Modonomicon)
        Button researchBtn = Button.builder(Component.translatable("gui.skavenblight.nexus.tab.research"), button -> {
            if (this.minecraft != null) {
                this.minecraft.setScreen(null); // Close the custom Nexus screen
                BookGuiManager.get().openBook(BookAddress.defaultFor(BOOK_ID));
            }
        }).bounds(startX + 124, startY - 20, 60, 20).build();
        researchBtn.setTooltip(Tooltip.create(Component.translatable("gui.skavenblight.nexus.tab.research.tooltip")));
        this.addRenderableWidget(researchBtn);

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
            graphics.drawString(this.font, Component.translatable("gui.skavenblight.nexus.network_status"), startX + 10, startY + 10, 0x44FF44, false);
        } else {
            graphics.drawString(this.font, Component.translatable("gui.skavenblight.nexus.active_schemes"), startX + 10, startY + 10, 0xFF4444, false);
        }
    }
}