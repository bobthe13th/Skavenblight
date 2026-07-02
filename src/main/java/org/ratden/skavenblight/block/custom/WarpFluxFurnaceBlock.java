package org.ratden.skavenblight.block.custom;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import org.jetbrains.annotations.Nullable;
import org.ratden.skavenblight.block.entity.WarpFluxFurnaceBlockEntity;

public class WarpFluxFurnaceBlock extends Block implements EntityBlock {

    // 1. Block Properties: Facing direction and Active/Lit state
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    public WarpFluxFurnaceBlock(Properties properties) {
        super(properties);
        // Set the default state when placed: Facing North and turned OFF
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(LIT, false));
    }

    // 2. Make the block face the player when placed
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    // 3. Register our properties with the game engine
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LIT);
    }

    // 4. Link this Block to our Block Entity
    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WarpFluxFurnaceBlockEntity(pos, state);
    }

    // 5. Tell the game to run our tick() method every single tick
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        // We only want the server to handle the actual smelting math and flux drain
        if (level.isClientSide()) {
            return null;
        }

        return (lvl, pos, blockState, entity) -> {
            if (entity instanceof WarpFluxFurnaceBlockEntity furnaceEntity) {
                WarpFluxFurnaceBlockEntity.tick(lvl, pos, blockState, furnaceEntity);
            }
        };
    }

    @Override
    protected net.minecraft.world.InteractionResult useWithoutItem(net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos, net.minecraft.world.entity.player.Player player, net.minecraft.world.phys.BlockHitResult hit) {
        if (!level.isClientSide()) {
            net.minecraft.world.level.block.entity.BlockEntity entity = level.getBlockEntity(pos);
            if (entity instanceof org.ratden.skavenblight.block.entity.WarpFluxFurnaceBlockEntity furnace) {
                player.openMenu(furnace, pos);
            }
        }
        return net.minecraft.world.InteractionResult.sidedSuccess(level.isClientSide());
    }
}