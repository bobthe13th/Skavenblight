package org.ratden.skavenblight.block.custom;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
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
import org.ratden.skavenblight.event.skavenIncursion.director.ActiveIncursionManager;
import org.ratden.skavenblight.event.skavenIncursion.runtime.event.SourceDestroyedEvent;

import java.util.UUID;

public class SkavenTunnelSourceBlock extends Block
        implements EntityBlock {

    public static final EnumProperty<SourceState> SOURCE_STATE =
            EnumProperty.create(
                    "source_state",
                    SourceState.class
            );

    public SkavenTunnelSourceBlock(
            Properties properties
    ) {
        super(properties);

        registerDefaultState(
                stateDefinition
                        .any()
                        .setValue(
                                SOURCE_STATE,
                                SourceState.DORMANT
                        )
        );
    }

    @Override
    public RenderShape getRenderShape(
            BlockState state
    ) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(
            BlockPos pos,
            BlockState state
    ) {
        return new SkavenTunnelSourceEntity(
                pos,
                state
        );
    }

    /**
     * Reports genuine removal of a physical tunnel source.
     *
     * Block-state changes such as ACTIVE to DORMANT do not count as
     * destruction because the block itself remains a tunnel source.
     *
     * The block entity is inspected before super.onRemove removes it from the
     * world.
     */
    @Override
    protected void onRemove(
            BlockState state,
            Level level,
            BlockPos pos,
            BlockState newState,
            boolean movedByPiston
    ) {
        boolean sourceWasRemoved =
                state.getBlock()
                        != newState.getBlock();

        if (sourceWasRemoved
                && !level.isClientSide()
                && level instanceof ServerLevel serverLevel) {

            BlockEntity blockEntity =
                    level.getBlockEntity(
                            pos
                    );

            if (blockEntity
                    instanceof SkavenTunnelSourceEntity tunnelSource) {

                reportSourceDestroyed(
                        serverLevel,
                        pos,
                        tunnelSource
                );
            }
        }

        super.onRemove(
                state,
                level,
                pos,
                newState,
                movedByPiston
        );
    }

    private void reportSourceDestroyed(
            ServerLevel level,
            BlockPos pos,
            SkavenTunnelSourceEntity tunnelSource
    ) {
        UUID runtimeSourceId =
                tunnelSource.getSourceId();

        UUID scenarioInstanceId =
                tunnelSource.getScenarioId();

        /*
         * Manually placed, legacy or incompletely initialised tunnels may not
         * belong to an active Scenario. Their removal is not an incursion
         * source-destruction event.
         */
        if (runtimeSourceId == null
                || scenarioInstanceId == null) {
            return;
        }

        ActiveIncursionManager.reportSourceDestroyed(
                new SourceDestroyedEvent(
                        runtimeSourceId,
                        scenarioInstanceId,
                        pos,
                        level.getGameTime()
                )
        );
    }

    @Override
    protected void createBlockStateDefinition(
            StateDefinition.Builder<Block, BlockState> builder
    ) {
        builder.add(
                SOURCE_STATE
        );
    }
}