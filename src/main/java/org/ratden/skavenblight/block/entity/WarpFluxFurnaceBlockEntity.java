package org.ratden.skavenblight.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;

import org.ratden.skavenblight.block.custom.WarpFluxFurnaceBlock;
import org.ratden.skavenblight.capability.custom.WarpFluxStorage;
import org.ratden.skavenblight.config.WarpFluxFurnaceConfig;
import net.minecraft.world.inventory.ContainerData;

import java.util.Optional;

public class WarpFluxFurnaceBlockEntity extends BlockEntity implements net.minecraft.world.MenuProvider {

    // 1. Inventory Setup: Slot 0 = Input, Slot 1 = Output
    private final ItemStackHandler itemHandler = new ItemStackHandler(2) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }

        // Removed @Override here to prevent strict compilation errors across different NeoForge versions
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            return slot != 1; // Prevent hoppers/pipes from inserting into the output slot
        }
    };

    // 2. Warp Flux Storage Setup
    private final WarpFluxStorage fluxStorage = new WarpFluxStorage(10000, 256, 256);
    private int progress = 0;

    public WarpFluxFurnaceBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.WARP_FLUX_FURNACE.get(), pos, state);
    }

    // 3. The Core Tick Loop
    public static void tick(Level level, BlockPos pos, BlockState state, WarpFluxFurnaceBlockEntity blockEntity) {
        if (level.isClientSide) return;

        boolean isLit = state.getValue(WarpFluxFurnaceBlock.LIT);
        boolean shouldBeLit = false;

        ItemStack input = blockEntity.itemHandler.getStackInSlot(0);

        if (!input.isEmpty()) {
            // Modern NeoForge uses SingleRecipeInput instead of SimpleContainer
            Optional<RecipeHolder<SmeltingRecipe>> recipeOpt = level.getRecipeManager()
                    .getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(input), level);

            if (recipeOpt.isPresent()) {
                SmeltingRecipe recipe = recipeOpt.get().value();
                ItemStack result = recipe.getResultItem(level.registryAccess());

                // Check if we can safely place the output into Slot 1
                if (blockEntity.canOutput(result)) {
                    int fluxCost = WarpFluxFurnaceConfig.FLUX_PER_TICK.get();

                    // Uses your actual custom getFlux() and setFlux() methods!
                    if (blockEntity.fluxStorage.getFlux() >= fluxCost) {
                        blockEntity.fluxStorage.setFlux(blockEntity.fluxStorage.getFlux() - fluxCost);
                        blockEntity.progress++;
                        shouldBeLit = true;

                        // Check if smelting is complete
                        if (blockEntity.progress >= WarpFluxFurnaceConfig.BASE_SMELT_TICKS.get()) {
                            blockEntity.smeltItem(result);
                            blockEntity.progress = 0;
                        }
                        blockEntity.setChanged();
                    }
                }
            }
        }

        // Reset progress smoothly if processing stops (out of power or items)
        if (!shouldBeLit && blockEntity.progress > 0) {
            blockEntity.progress = Math.max(0, blockEntity.progress - 2);
            blockEntity.setChanged();
        }

        // Update the blockstate dynamically so it lights up / changes textures
        if (isLit != shouldBeLit) {
            level.setBlock(pos, state.setValue(WarpFluxFurnaceBlock.LIT, shouldBeLit), 3);
        }
    }

    private boolean canOutput(ItemStack result) {
        ItemStack outputSlot = itemHandler.getStackInSlot(1);
        if (outputSlot.isEmpty()) return true;
        if (!ItemStack.isSameItemSameComponents(outputSlot, result)) return false;

        int configCount = (int) Math.floor(result.getCount() * WarpFluxFurnaceConfig.OUTPUT_MULTIPLIER.get());
        int potentialResultCount = outputSlot.getCount() + Math.max(1, configCount);

        return potentialResultCount <= outputSlot.getMaxStackSize();
    }

    private void smeltItem(ItemStack result) {
        ItemStack inputSlot = itemHandler.getStackInSlot(0);
        ItemStack outputSlot = itemHandler.getStackInSlot(1);

        double multiplier = WarpFluxFurnaceConfig.OUTPUT_MULTIPLIER.get();
        int finalCount = (int) Math.floor(result.getCount() * multiplier);
        finalCount = Math.max(1, finalCount);

        if (outputSlot.isEmpty()) {
            itemHandler.setStackInSlot(1, new ItemStack(result.getItem(), finalCount));
        } else {
            outputSlot.grow(finalCount);
        }

        inputSlot.shrink(1);
    }

    // 4. Save/Load Data (NBT)
    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Inventory", itemHandler.serializeNBT(registries));
        tag.putInt("Flux", fluxStorage.getFlux());
        tag.putInt("Progress", progress);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Inventory")) {
            itemHandler.deserializeNBT(registries, tag.getCompound("Inventory"));
        }
        fluxStorage.setFlux(tag.getInt("Flux"));
        progress = tag.getInt("Progress");
    }

    // Getters for capabilities
    public ItemStackHandler getItemHandler() { return itemHandler; }
    public WarpFluxStorage getFluxStorage() { return fluxStorage; }
    protected final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case 0 -> WarpFluxFurnaceBlockEntity.this.progress;
                case 1 -> WarpFluxFurnaceBlockEntity.this.fluxStorage.getFlux();
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            switch (index) {
                case 0 -> WarpFluxFurnaceBlockEntity.this.progress = value;
                case 1 -> WarpFluxFurnaceBlockEntity.this.fluxStorage.setFlux(value);
            }
        }

        @Override
        public int getCount() {
            return 2; // We are tracking 2 variables: Progress and Flux
        }
    };
    @Override
    public net.minecraft.network.chat.Component getDisplayName() {
        return net.minecraft.network.chat.Component.translatable("block.skavenblight.warp_flux_furnace");
    }

    @org.jetbrains.annotations.Nullable
    @Override
    public net.minecraft.world.inventory.AbstractContainerMenu createMenu(int id, net.minecraft.world.entity.player.Inventory inventory, net.minecraft.world.entity.player.Player player) {
        return new org.ratden.skavenblight.screen.WarpFluxFurnaceMenu(id, inventory, this, this.data);
    }
}