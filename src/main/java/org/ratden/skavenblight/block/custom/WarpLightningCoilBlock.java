package org.ratden.skavenblight.block.custom;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.ratden.skavenblight.block.ModBlocks;
import org.ratden.skavenblight.block.entity.ModBlockEntities;
import org.ratden.skavenblight.block.entity.WarpLightningCoilBlockEntity;

public class WarpLightningCoilBlock extends Block implements EntityBlock {

    public WarpLightningCoilBlock(Properties properties) {
        super(properties);
    }

    // 1. Cancel placement if there aren't 2 open spaces above
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        Level level = context.getLevel();

        if (pos.getY() < level.getMaxBuildHeight() - 2
                && level.getBlockState(pos.above(1)).canBeReplaced(context)
                && level.getBlockState(pos.above(2)).canBeReplaced(context)) {
            return super.getStateForPlacement(context);
        }

        return null; // Returning null prevents the item from being placed/consumed
    }

    // 2. Spawn dummy blocks above when placed
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);

        if (!level.isClientSide) {
            level.setBlock(pos.above(1), ModBlocks.WARP_LIGHTNING_COIL_DUMMY.get().defaultBlockState(), 3);
            level.setBlock(pos.above(2), ModBlocks.WARP_LIGHTNING_COIL_DUMMY.get().defaultBlockState(), 3);
        }
    }

    // 3. Clean up the dummy blocks when the base block is broken
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide) {
            for (int i = 1; i <= 2; i++) {
                BlockPos offsetPos = pos.above(i);
                if (level.getBlockState(offsetPos).is(ModBlocks.WARP_LIGHTNING_COIL_DUMMY.get())) {
                    level.destroyBlock(offsetPos, false); // false = don't drop items for dummy blocks
                }
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WarpLightningCoilBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;

        return (lvl, p, st, be) -> {
            if (be instanceof WarpLightningCoilBlockEntity coil) {
                coil.tick(lvl, p, st);
            }
        };
    }
}