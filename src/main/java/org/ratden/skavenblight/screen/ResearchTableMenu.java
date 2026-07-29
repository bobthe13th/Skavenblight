package org.ratden.skavenblight.screen;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import org.ratden.skavenblight.Config;
import org.ratden.skavenblight.block.ModBlocks;
import org.ratden.skavenblight.block.entity.ResearchTableBlockEntity;
import org.ratden.skavenblight.magic.Wind;

import java.util.List;
import java.util.Optional;

public class ResearchTableMenu extends AbstractContainerMenu {

    public final ResearchTableBlockEntity blockEntity;
    private final ContainerData data;

    // Client-side constructor (called when the GUI opens on the player's screen)
    public ResearchTableMenu(int containerId, Inventory playerInv, FriendlyByteBuf extraData) {
        this(containerId, playerInv, playerInv.player.level().getBlockEntity(extraData.readBlockPos()),
                new SimpleContainerData(2 + Wind.values().length));
    }

    // Server-side constructor (called when the block is right-clicked)
    public ResearchTableMenu(int containerId, Inventory playerInv, BlockEntity entity, ContainerData data) {
        super(ModMenus.RESEARCH_TABLE_MENU.get(), containerId);

        this.blockEntity = (ResearchTableBlockEntity) entity;
        this.data = data;

        IItemHandler catalystHandler = this.blockEntity.getCatalystHandler();
        this.addSlot(new SlotItemHandler(catalystHandler, 0, 133, 89));

        addPlayerInventory(playerInv);
        addPlayerHotbar(playerInv);

        addDataSlots(data);
    }

    public int getProgress() {
        return data.get(0);
    }

    public int getScaledProgress(int pixelWidth) {
        int max = Config.researchTicksBase;
        int progress = data.get(0);
        return max != 0 ? Math.min(pixelWidth, progress * pixelWidth / max) : 0;
    }

    /** Current Wind level in the table's own chunk, as last synced from the server. */
    public int getWindLevel(Wind wind) {
        return data.get(2 + wind.ordinal());
    }

    /** The spell currently being actively researched, if any - resolved from the synced sorted
     *  index rather than a raw id, since ResourceLocation can't ride in ContainerData's ints. */
    public Optional<ResourceLocation> getActiveSpellId() {
        int index = data.get(1);
        List<ResourceLocation> sorted = ResearchTableBlockEntity.sortedSpellIds();
        if (index < 0 || index >= sorted.size()) {
            return Optional.empty();
        }
        return Optional.of(sorted.get(index));
    }

    // Invoked server-side by the client's "Begin Research" button via
    // Minecraft.gameMode.handleInventoryButtonClick - the standard vanilla mechanism for a menu
    // action button (same one the enchanting table/stonecutter/loom use), rather than a bespoke
    // network payload.
    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        List<ResourceLocation> sorted = ResearchTableBlockEntity.sortedSpellIds();
        if (id < 0 || id >= sorted.size()) {
            return false;
        }
        return blockEntity.tryStartResearch(serverPlayer, sorted.get(id));
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(ContainerLevelAccess.create(blockEntity.getLevel(), blockEntity.getBlockPos()),
                player, ModBlocks.RESEARCH_TABLE.get());
    }

    // --- Standard Inventory Layout Helpers (matches the taller panel: rows start further down
    // than WarpFluxFurnaceMenu's, to leave room for the spell browser above) ---
    private void addPlayerInventory(Inventory playerInv) {
        for (int i = 0; i < 3; ++i) {
            for (int l = 0; l < 9; ++l) {
                this.addSlot(new Slot(playerInv, l + i * 9 + 9, 8 + l * 18, 146 + i * 18));
            }
        }
    }

    private void addPlayerHotbar(Inventory playerInv) {
        for (int i = 0; i < 9; ++i) {
            this.addSlot(new Slot(playerInv, i, 8 + i * 18, 204));
        }
    }

    // --- Shift-Clicking Logic (mandatory for menus) ---
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot sourceSlot = this.slots.get(index);
        if (sourceSlot == null || !sourceSlot.hasItem()) {
            return ItemStack.EMPTY;
        }

        ItemStack sourceStack = sourceSlot.getItem();
        ItemStack copyOfSourceStack = sourceStack.copy();

        // Slot 0 is the catalyst slot; slots 1-37 are player inventory + hotbar.
        if (index == 0) {
            if (!this.moveItemStackTo(sourceStack, 1, 37, false)) {
                return ItemStack.EMPTY;
            }
        } else {
            if (!this.moveItemStackTo(sourceStack, 0, 1, false)) {
                return ItemStack.EMPTY;
            }
        }

        if (sourceStack.getCount() == 0) {
            sourceSlot.set(ItemStack.EMPTY);
        } else {
            sourceSlot.setChanged();
        }

        sourceSlot.onTake(player, sourceStack);
        return copyOfSourceStack;
    }
}
