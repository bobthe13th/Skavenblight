package org.ratden.skavenblight.item.custom;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.ratden.skavenblight.block.entity.WarpFluxStorageBlockEntity;

public class WarpFluxStorageBlockItem extends BlockItem {
    public WarpFluxStorageBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    protected boolean updateCustomBlockEntityTag(BlockPos pos, Level level, @Nullable Player player, ItemStack stack, BlockState state) {
        boolean superResult = super.updateCustomBlockEntityTag(pos, level, player, stack, state);

        // Read from the 1.21 Custom Data Component and push it to our Block Entity
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof WarpFluxStorageBlockEntity storageBE) {
            CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
            if (customData != null && customData.contains("flux")) {
                int savedFlux = customData.getUnsafe().getInt("flux");
                storageBE.getFluxStorage().setFlux(savedFlux);
                storageBE.setChanged();
            }
        }
        return superResult;
    }
}