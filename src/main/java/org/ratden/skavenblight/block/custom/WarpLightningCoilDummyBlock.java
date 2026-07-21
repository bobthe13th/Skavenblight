package org.ratden.skavenblight.block.custom;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.ratden.skavenblight.block.ModBlocks;

public class WarpLightningCoilDummyBlock extends Block {

    // Standard 1-block hitbox for the upper sections
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 16, 16);

    public WarpLightningCoilDummyBlock(Properties properties) {
        super(properties);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    // Ensures the block is invisible (your GeoModel handles the rendering of the whole 3-block structure)
    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    // When the dummy is broken, find the base block and break it
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide()) {
            // Check up to 2 blocks down for the base
            for (int i = 1; i <= 2; i++) {
                BlockPos lowerPos = pos.below(i);
                BlockState lowerState = level.getBlockState(lowerPos);
                if (lowerState.is(ModBlocks.WARP_LIGHTNING_COIL.get())) {
                    level.destroyBlock(lowerPos, !player.isCreative());
                    break;
                }
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
