package org.ratden.skavenblight.screen;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import org.ratden.skavenblight.block.ModBlocks;
import org.ratden.skavenblight.block.entity.WarpFluxFurnaceBlockEntity;

public class WarpFluxFurnaceMenu extends AbstractContainerMenu {
    public final WarpFluxFurnaceBlockEntity blockEntity;
    private final ContainerData data;

    // 1. Client-Side Constructor (Called when the GUI opens on the player's screen)
    public WarpFluxFurnaceMenu(int containerId, Inventory playerInv, FriendlyByteBuf extraData) {
        this(containerId, playerInv, playerInv.player.level().getBlockEntity(extraData.readBlockPos()), new SimpleContainerData(2));
    }

    // 2. Server-Side Constructor (Called when the block is right-clicked)
    public WarpFluxFurnaceMenu(int containerId, Inventory playerInv, BlockEntity entity, ContainerData data) {
        super(ModMenus.WARP_FLUX_FURNACE_MENU.get(), containerId);

        this.blockEntity = (WarpFluxFurnaceBlockEntity) entity;
        this.data = data;

        IItemHandler itemHandler = this.blockEntity.getItemHandler(null);

        // 3. Add Machine Slots (Index 0 and 1)
        this.addSlot(new SlotItemHandler(itemHandler, 0, 41, 34)); // Input Slot
        this.addSlot(new SlotItemHandler(itemHandler, 1, 100, 35) { // Output Slot
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false; // Prevent players from manually placing items in the output!
            }
        });

        // 4. Add Player Inventory Slots
        addPlayerInventory(playerInv);
        addPlayerHotbar(playerInv);

        // 5. Tell the menu to track our data (Progress and Flux)
        addDataSlots(data);
    }

    // Helps draw the progress arrow
    public int getScaledProgress() {
        int progress = this.data.get(0);
        int maxProgress = org.ratden.skavenblight.config.WarpFluxFurnaceConfig.BASE_SMELT_TICKS.get();
        int arrowPixelSize = 14; // Updated to your custom width!
        return maxProgress != 0 && progress != 0 ? progress * arrowPixelSize / maxProgress : 0;
    }

    // Helps draw the energy bar
    public int getScaledFlux() {
        int flux = this.data.get(1);
        int maxFlux = 10000; // The capacity we set in the block entity
        int barPixelHeight = 56; // Updated to your custom height!
        return maxFlux != 0 && flux != 0 ? flux * barPixelHeight / maxFlux : 0;
    }

    // Ensures the player is close enough to interact with the block
    @Override
    public boolean stillValid(Player player) {
        return stillValid(ContainerLevelAccess.create(blockEntity.getLevel(), blockEntity.getBlockPos()),
                player, ModBlocks.WARP_FLUX_FURNACE.get());
    }

    // --- Standard Inventory Layout Helpers ---
    private void addPlayerInventory(Inventory playerInv) {
        for (int i = 0; i < 3; ++i) {
            for (int l = 0; l < 9; ++l) {
                this.addSlot(new Slot(playerInv, l + i * 9 + 9, 8 + l * 18, 84 + i * 18));
            }
        }
    }

    private void addPlayerHotbar(Inventory playerInv) {
        for (int i = 0; i < 9; ++i) {
            this.addSlot(new Slot(playerInv, i, 8 + i * 18, 142));
        }
    }

    // --- Shift-Clicking Logic (Mandatory for menus) ---
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot sourceSlot = this.slots.get(index);
        if (sourceSlot == null || !sourceSlot.hasItem()) return ItemStack.EMPTY;

        ItemStack sourceStack = sourceSlot.getItem();
        ItemStack copyOfSourceStack = sourceStack.copy();

        // If clicking machine slots (0 or 1), move to player inventory (2 to 37)
        if (index < 2) {
            if (!this.moveItemStackTo(sourceStack, 2, 38, false)) {
                return ItemStack.EMPTY;
            }
        }
        // If clicking player inventory, move to machine input slot (0)
        else {
            if (!this.moveItemStackTo(sourceStack, 0, 1, false)) {
                return ItemStack.EMPTY;
            }
        }

        if (sourceStack.getCount() == 0) sourceSlot.set(ItemStack.EMPTY);
        else sourceSlot.setChanged();

        sourceSlot.onTake(player, sourceStack);
        return copyOfSourceStack;
    }
    // Gets the exact current flux from our synced data
    public int getFlux() {
        return this.data.get(1);
    }

    // Hardcoded max capacity (matches what you set in the BlockEntity)
    public int getMaxFlux() {
        return 10000;
    }

    // Gets the exact current progress from our synced data
    public int getProgress() {
        return this.data.get(0);
    }

    // Gets the max progress configured
    public int getMaxProgress() {
        return org.ratden.skavenblight.config.WarpFluxFurnaceConfig.BASE_SMELT_TICKS.get();
    }
}