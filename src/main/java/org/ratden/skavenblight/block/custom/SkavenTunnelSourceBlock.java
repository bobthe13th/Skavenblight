package org.ratden.skavenblight.block.custom;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import org.jetbrains.annotations.Nullable;
import org.ratden.skavenblight.block.entity.SkavenTunnelSourceEntity;
import org.ratden.skavenblight.block.entity.state.SourceState;

public class SkavenTunnelSourceBlock extends Block implements EntityBlock {

    public static final EnumProperty<SourceState> SOURCE_STATE =
            EnumProperty.create("source_state", SourceState.class);

    public SkavenTunnelSourceBlock(Properties properties) {
        super(properties);

        this.registerDefaultState(
                this.stateDefinition.any()
                        .setValue(SOURCE_STATE, SourceState.DORMANT)
        );
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SkavenTunnelSourceEntity(pos, state);
    }

    @Override
    protected void createBlockStateDefinition(
            StateDefinition.Builder<Block, BlockState> builder
    ) {
        builder.add(SOURCE_STATE);
    }
}