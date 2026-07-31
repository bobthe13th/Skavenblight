package org.ratden.skavenblight.block.custom;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.ratden.skavenblight.block.entity.ModBlockEntities;
import org.ratden.skavenblight.block.entity.PistonSpikeTrapBlockEntity;

public class PistonSpikeTrapBlock extends BaseEntityBlock {
    // Allows the trap to face any of the 6 directions
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    // The required Codec for modern Minecraft versions
    public static final com.mojang.serialization.MapCodec<PistonSpikeTrapBlock> CODEC = simpleCodec(PistonSpikeTrapBlock::new);

    @Override
    protected com.mojang.serialization.MapCodec<? extends net.minecraft.world.level.block.BaseEntityBlock> codec() {
        return CODEC;
    }
    // A standard 16x16x16 cube for the physical block collision
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 16, 16);

    public PistonSpikeTrapBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    // --- HITBOX ---
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE; // The physical block never changes size
    }

    // --- PLACEMENT ---
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // Places the trap facing the player (like a vanilla piston)
        return this.defaultBlockState().setValue(FACING, context.getClickedFace());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    // --- RENDERING ---
    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED; // Crucial for GeckoLib!
    }

    // --- BLOCK ENTITY ---
    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PistonSpikeTrapBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, ModBlockEntities.PISTON_SPIKE_TRAP.get(),
                PistonSpikeTrapBlockEntity::tick);
    }
}