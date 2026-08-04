package org.ratden.skavenblight.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.ratden.skavenblight.Config;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.block.entity.ResearchFormulas;
import org.ratden.skavenblight.block.entity.ResearchTableBlockEntity;
import org.ratden.skavenblight.magic.Wind;
import org.ratden.skavenblight.magic.player.ModAttachments;
import org.ratden.skavenblight.magic.player.PlayerMagicData;
import org.ratden.skavenblight.magic.spell.Spell;
import org.ratden.skavenblight.magic.spell.SpellManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * See docs/design/research-table-ux.md for the full UX spec this implements.
 *
 * Known Phase 1 limitation, stated deliberately rather than silently: this screen reads
 * {@link SpellManager#getAll()} directly on the client. That is correct in single-player /
 * integrated-server play (this whole magic system has only ever been tested that way so far),
 * because the spell catalog is loaded once per JVM as a plain static field with no dedicated
 * network sync. On a true dedicated multiplayer server, a remote client's JVM never runs the
 * datapack reload listener that populates it, so this list would render empty there until a
 * future sync packet exists. {@link PlayerMagicData}, by contrast, IS synced (see
 * {@code ModAttachments.PLAYER_MAGIC}'s {@code .sync(...)}), so tier/known-spell state is correct
 * on a real client either way.
 */
public class ResearchTableScreen extends AbstractContainerScreen<ResearchTableMenu> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Skavenblight.MODID, "textures/gui/research_table_gui.png");

    // Spell list
    private static final int LIST_X = 6;
    private static final int LIST_Y = 16;
    private static final int LIST_W = 90;
    private static final int LIST_H = 122;

    // Detail pane
    private static final int DETAIL_X = 98;
    private static final int ICON_Y = 17;
    private static final int NAME_Y = 19;
    private static final int WIND_TIER_Y = 36;
    private static final int DESC_Y = 46;
    private static final int DESC_LINE_HEIGHT = 9;
    private static final int DESC_MAX_LINES = 2;
    private static final int METER_X = 100, METER_Y = 74, METER_W = 60, METER_H = 8;
    private static final int PROGRESS_X = 100, PROGRESS_Y = 110, PROGRESS_W = 60, PROGRESS_H = 6;
    private static final int BUTTON_X = 100, BUTTON_Y = 120, BUTTON_W = 72, BUTTON_H = 14;

    private SpellListWidget spellList;
    private Button beginButton;

    public ResearchTableScreen(ResearchTableMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = 176;
        this.imageHeight = 222;
        this.inventoryLabelY = 10000; // hide the default "Inventory" label, matches the taller custom layout
    }

    @Override
    protected void init() {
        super.init();

        this.spellList = new SpellListWidget(this.minecraft, LIST_W, LIST_H, this.topPos + LIST_Y, 14);
        this.spellList.setX(this.leftPos + LIST_X);
        rebuildList();
        this.addRenderableWidget(this.spellList);

        // If research is already in progress (GUI reopened mid-study), pre-select that spell so
        // the detail pane and progress bar make sense immediately, without needing another click.
        this.menu.getActiveSpellId().ifPresent(activeId ->
                this.spellList.children().stream()
                        .filter(entry -> entry.id.equals(activeId))
                        .findFirst()
                        .ifPresent(this.spellList::setSelected));

        this.beginButton = Button.builder(Component.translatable("gui.skavenblight.research_table.begin"),
                        button -> onBeginResearch())
                .bounds(this.leftPos + BUTTON_X, this.topPos + BUTTON_Y, BUTTON_W, BUTTON_H)
                .build();
        this.addRenderableWidget(this.beginButton);
    }

    private void rebuildList() {
        List<Map.Entry<ResourceLocation, Spell>> entries = new ArrayList<>(SpellManager.getAll().entrySet());
        entries.removeIf(e -> e.getValue().wind() != this.menu.blockEntity.wind);
        entries.sort(Comparator.comparing(Map.Entry::getKey));

        SpellListWidget.Entry previouslySelected = this.spellList.getSelected();
        this.spellList.replaceAllEntries(entries);
        if (previouslySelected != null) {
            this.spellList.children().stream()
                    .filter(entry -> entry.id.equals(previouslySelected.id))
                    .findFirst()
                    .ifPresent(this.spellList::setSelected);
        }
    }

    private void onBeginResearch() {
        SpellListWidget.Entry selected = this.spellList.getSelected();
        if (selected == null || this.minecraft.player == null) {
            return;
        }
        int index = ResearchTableBlockEntity.sortedSpellIds().indexOf(selected.id);
        if (index < 0) {
            return;
        }
        this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, index);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        updateBeginButton();
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        renderDetailPane(guiGraphics);
        renderTooltip(guiGraphics, mouseX, mouseY);

        renderWindMeterTooltip(guiGraphics, mouseX, mouseY, this.leftPos, this.topPos);
        renderProgressAreaTooltip(guiGraphics, mouseX, mouseY, this.leftPos, this.topPos);
    }

    private void renderWindMeterTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY, int x, int y) {
        if (isMouseAboveArea(mouseX, mouseY, x, y, METER_X, METER_Y, METER_W, METER_H)) {
            PreviewState preview = computePreviewState();
            if (preview.spell != null && !preview.known) {
                Spell spell = preview.spell;
                int current = Math.max(0, preview.windLevel);
                int required = Math.max(1, Math.round(ResearchFormulas.requiredWindLevel(spell.tier(), Config.researchWindThreshold)));

                List<Component> tooltipLines = new ArrayList<>();

                // Line 1: Wind Level (current / required)
                tooltipLines.add(Component.translatable("gui.skavenblight.research_table.tooltip.wind", current, required)
                        .withStyle(net.minecraft.ChatFormatting.GOLD));

                // Line 2: Status
                if (preview.windSufficient) {
                    tooltipLines.add(Component.translatable("gui.skavenblight.research_table.tooltip.wind_sufficient")
                            .withStyle(net.minecraft.ChatFormatting.GREEN));

                    // Line 3: Multiplier
                    float multiplier = ResearchFormulas.speedMultiplier(current, required,
                            Config.researchWindBonusReference, Config.researchWindMaxBonusMultiplier);
                    tooltipLines.add(Component.translatable("gui.skavenblight.research_table.tooltip.speed", String.format("%.2f", multiplier))
                            .withStyle(net.minecraft.ChatFormatting.AQUA));
                } else {
                    tooltipLines.add(Component.translatable("gui.skavenblight.research_table.tooltip.wind_insufficient")
                            .withStyle(net.minecraft.ChatFormatting.RED));
                }

                guiGraphics.renderComponentTooltip(this.font, tooltipLines, mouseX, mouseY);
            }
        }
    }

    private void renderProgressAreaTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY, int x, int y) {
        if (isMouseAboveArea(mouseX, mouseY, x, y, PROGRESS_X, PROGRESS_Y, PROGRESS_W, PROGRESS_H)) {
            PreviewState preview = computePreviewState();
            if (preview.spell != null && !preview.known) {
                List<Component> tooltipLines = new ArrayList<>();

                int progress = menu.getProgress();
                int maxProgress = Config.researchTicksBase * ResearchFormulas.PROGRESS_SCALE;

                if (preview.isActiveResearch && progress > 0 && maxProgress > 0) {
                    int percent = (int) Math.min(100, Math.max(0, (long) progress * 100 / maxProgress));
                    tooltipLines.add(Component.translatable("gui.skavenblight.research_table.tooltip.progress", percent)
                            .withStyle(net.minecraft.ChatFormatting.GREEN));

                    if (preview.windSufficient) {
                        tooltipLines.add(Component.translatable("gui.skavenblight.research_table.tooltip.progress_status_active")
                                .withStyle(net.minecraft.ChatFormatting.GREEN));
                    } else {
                        tooltipLines.add(Component.translatable("gui.skavenblight.research_table.tooltip.progress_status_paused")
                                .withStyle(net.minecraft.ChatFormatting.YELLOW));
                    }
                } else {
                    tooltipLines.add(Component.translatable("gui.skavenblight.research_table.tooltip.progress_idle")
                            .withStyle(net.minecraft.ChatFormatting.GRAY));
                    tooltipLines.add(Component.translatable("gui.skavenblight.research_table.tooltip.progress_status_idle")
                            .withStyle(net.minecraft.ChatFormatting.GRAY));
                }

                guiGraphics.renderComponentTooltip(this.font, tooltipLines, mouseX, mouseY);
            }
        }
    }

    private boolean isMouseAboveArea(int mouseX, int mouseY, int x, int y, int offsetX, int offsetY, int width, int height) {
        return mouseX >= (x + offsetX) && mouseX <= (x + offsetX) + width &&
                mouseY >= (y + offsetY) && mouseY <= (y + offsetY) + height;
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        com.mojang.blaze3d.systems.RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexShader);
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        int x = (width - imageWidth) / 2;
        int y = (height - imageHeight) / 2;
        guiGraphics.blit(TEXTURE, x, y, 0, 0, imageWidth, imageHeight);

        // Progress bar fill - purely server-synced data, independent of what's previewed client-side
        int filled = menu.getScaledProgress(PROGRESS_W - 2);
        if (filled > 0) {
            guiGraphics.fill(x + PROGRESS_X + 1, y + PROGRESS_Y + 1,
                    x + PROGRESS_X + 1 + filled, y + PROGRESS_Y + PROGRESS_H - 1, 0xFF9B6BC2);
        }
    }

    private void updateBeginButton() {
        PreviewState preview = computePreviewState();
        boolean showButton = preview.spell != null && !preview.known;
        this.beginButton.visible = showButton;
        this.beginButton.active = showButton && preview.canBegin;

        if (showButton) {
            this.beginButton.setMessage(Component.translatable(preview.isActiveResearch
                    ? "gui.skavenblight.research_table.studying"
                    : "gui.skavenblight.research_table.begin"));
            this.beginButton.setTooltip(preview.blockReason != null
                    ? Tooltip.create(Component.translatable(preview.blockReason))
                    : null);
        }
    }

    private void renderDetailPane(GuiGraphics guiGraphics) {
        int x = (width - imageWidth) / 2;
        int y = (height - imageHeight) / 2;
        PreviewState preview = computePreviewState();

        if (preview.spell == null) {
            guiGraphics.drawWordWrap(this.font,
                    Component.translatable("gui.skavenblight.research_table.choose_prompt"),
                    x + DETAIL_X, y + DESC_Y, 70, 0xA0A0A0);
            return;
        }

        Spell spell = preview.spell;
        Wind wind = spell.wind();

        guiGraphics.fill(x + DETAIL_X + 2, y + ICON_Y, x + DETAIL_X + 18, y + ICON_Y + 16, 0xFF000000 | wind.getColor());
        guiGraphics.drawString(this.font, Component.translatable(preview.nameKey),
                x + DETAIL_X + 21, y + NAME_Y, preview.known ? 0xFF55FF55 : (preview.tierLocked ? 0xFF808080 : 0xFFFFFFFF), false);

        guiGraphics.drawString(this.font,
                Component.literal(wind.getLoreName() + " · Tier " + spell.tier()),
                x + DETAIL_X, y + WIND_TIER_Y, 0xFFA0A0A0, false);

        List<net.minecraft.util.FormattedCharSequence> lines =
                this.font.split(Component.translatable(preview.descriptionKey), 72);
        for (int i = 0; i < Math.min(lines.size(), DESC_MAX_LINES); i++) {
            guiGraphics.drawString(this.font, lines.get(i), x + DETAIL_X, y + DESC_Y + i * DESC_LINE_HEIGHT, 0xFFCCCCCC, false);
        }

        if (preview.known) {
            return; // Mastered - no meter/catalyst/progress to show, the button is hidden too.
        }

        // Wind meter
        int current = Math.max(0, preview.windLevel);
        int required = Math.max(1, Math.round(ResearchFormulas.requiredWindLevel(spell.tier(), Config.researchWindThreshold)));
        int meterFill = Math.min(METER_W - 2, current * (METER_W - 2) / required);
        int meterColor = preview.windSufficient ? (0xFF000000 | wind.getColor()) : 0xFFAA3333;
        if (meterFill > 0) {
            guiGraphics.fill(x + METER_X + 1, y + METER_Y + 1, x + METER_X + 1 + meterFill, y + METER_Y + METER_H - 1, meterColor);
        }
    }

    private PreviewState computePreviewState() {
        SpellListWidget.Entry selected = this.spellList == null ? null : this.spellList.getSelected();
        if (selected == null) {
            return PreviewState.EMPTY;
        }

        Spell spell = selected.spell;
        PlayerMagicData data = this.minecraft.player == null
                ? PlayerMagicData.EMPTY
                : this.minecraft.player.getData(ModAttachments.PLAYER_MAGIC.get());

        boolean known = data.knownSpells().contains(selected.id);
        boolean tierLocked = spell.tier() > data.getTier(spell.wind());
        int windLevel = this.menu.getWindLevel(spell.wind());
        boolean windSufficient = windLevel >= ResearchFormulas.requiredWindLevel(spell.tier(), Config.researchWindThreshold);

        Optional<Ingredient> component = spell.componentItem();
        ItemStack catalyst = this.menu.slots.get(0).getItem();
        boolean needsCatalyst = component.isPresent();
        boolean catalystOk = component.isEmpty() || component.get().test(catalyst);

        Optional<ResourceLocation> activeId = this.menu.getActiveSpellId();
        boolean isActiveResearch = activeId.isPresent() && activeId.get().equals(selected.id);
        boolean somethingElseActive = activeId.isPresent() && !activeId.get().equals(selected.id);

        String blockReason = null;
        if (tierLocked) {
            blockReason = "gui.skavenblight.research_table.reason.tier";
        } else if (somethingElseActive) {
            blockReason = "gui.skavenblight.research_table.reason.busy";
        } else if (!windSufficient) {
            blockReason = "gui.skavenblight.research_table.reason.wind";
        } else if (!catalystOk) {
            blockReason = "gui.skavenblight.research_table.reason.catalyst";
        }

        boolean canBegin = !known && !tierLocked && !somethingElseActive && !isActiveResearch && windSufficient && catalystOk;

        return new PreviewState(spell, selected.nameKey, selected.descriptionKey, known, tierLocked,
                windSufficient, windLevel, needsCatalyst, catalystOk, isActiveResearch, canBegin, blockReason);
    }

    private record PreviewState(
            Spell spell, String nameKey, String descriptionKey, boolean known, boolean tierLocked,
            boolean windSufficient, int windLevel, boolean needsCatalyst, boolean catalystOk,
            boolean isActiveResearch, boolean canBegin, String blockReason
    ) {
        static final PreviewState EMPTY = new PreviewState(null, null, null, false, false,
                false, 0, false, false, false, false, null);
    }

    /** Vanilla-native scrollable spell browser, same widget class used by vanilla's resource-pack
     *  and world-select screens. */
    private static class SpellListWidget extends ObjectSelectionList<SpellListWidget.Entry> {

        SpellListWidget(net.minecraft.client.Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
        }

        @Override
        public int getRowWidth() {
            return this.width - 8;
        }

        void replaceAllEntries(List<Map.Entry<ResourceLocation, Spell>> spells) {
            this.clearEntries();
            for (Map.Entry<ResourceLocation, Spell> e : spells) {
                this.addEntry(new Entry(this, e.getKey(), e.getValue()));
            }
        }

        static final class Entry extends ObjectSelectionList.Entry<Entry> {
            final SpellListWidget owner;
            final ResourceLocation id;
            final Spell spell;
            final String nameKey;
            final String descriptionKey;

            Entry(SpellListWidget owner, ResourceLocation id, Spell spell) {
                this.owner = owner;
                this.id = id;
                this.spell = spell;
                String base = "spell.skavenblight." + id.getPath().substring(id.getPath().lastIndexOf('/') + 1);
                this.nameKey = base + ".name";
                this.descriptionKey = spell.descriptionKey();
            }

            @Override
            public void render(GuiGraphics guiGraphics, int index, int top, int left, int width, int height,
                                int mouseX, int mouseY, boolean hovered, float partialTick) {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                PlayerMagicData data = mc.player == null ? PlayerMagicData.EMPTY
                        : mc.player.getData(ModAttachments.PLAYER_MAGIC.get());
                boolean known = data.knownSpells().contains(id);
                boolean locked = spell.tier() > data.getTier(spell.wind());

                guiGraphics.fill(left, top, left + 10, top + 10, 0xFF000000 | spell.wind().getColor());

                int color = known ? 0xFF55FF55 : (locked ? 0xFF808080 : 0xFFE0E0E0);
                guiGraphics.drawString(mc.font, Component.translatable(nameKey), left + 13, top + 1, color, false);
            }

            @Override
            public Component getNarration() {
                return Component.translatable(nameKey);
            }
        }
    }
}
