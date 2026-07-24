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

    // When either dummy segment is broken, find the base and the *other* dummy segment
    // and clean up both directly. We can't rely on the base's own playerWillDestroy to
    // cascade this for us: level.destroyBlock() never calls playerWillDestroy (that hook
    // only fires for the block the player actually clicked), so if we only destroyed the
    // base here, the remaining dummy segment would be orphaned.
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide()) {
            BlockPos basePos = null;
            BlockPos otherDummyPos = null;

            if (level.getBlockState(pos.below(1)).is(ModBlocks.WARP_LIGHTNING_COIL.get())) {
                // This is the middle segment: base is directly below, other dummy is above
                basePos = pos.below(1);
                otherDummyPos = pos.above(1);
            } else if (level.getBlockState(pos.below(2)).is(ModBlocks.WARP_LIGHTNING_COIL.get())) {
                // This is the top segment: base is two below, other dummy is directly below
                basePos = pos.below(2);
                otherDummyPos = pos.below(1);
            }

            if (otherDummyPos != null && level.getBlockState(otherDummyPos).is(ModBlocks.WARP_LIGHTNING_COIL_DUMMY.get())) {
                level.destroyBlock(otherDummyPos, false); // false = don't drop items for dummy blocks
            }
            if (basePos != null && level.getBlockState(basePos).is(ModBlocks.WARP_LIGHTNING_COIL.get())) {
                level.destroyBlock(basePos, !player.isCreative());
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
