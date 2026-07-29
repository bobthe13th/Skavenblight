package org.ratden.skavenblight.block.custom;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
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
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;
import org.ratden.skavenblight.block.entity.ResearchTableBlockEntity;

/**
 * A "Foundation/Tutorial" magic-research workbench: the player selects a spell they don't yet
 * know from the full catalog, and (given enough local Wind and, if required, a catalyst) the
 * table studies it over time until it's added to the player's PlayerMagicData.knownSpells.
 * See docs/design/research-table-ux.md for the full UX spec.
 */
public class ResearchTableBlock extends Block implements EntityBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** True while actively researching a spell - reuses the vanilla LIT property/datagen convention,
     *  same as WarpFluxFurnaceBlock, even though "lit" isn't literally what this block does. */
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    public ResearchTableBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(LIT, false));
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LIT);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ResearchTableBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        if (level.isClientSide()) {
            return null;
        }

        return (lvl, pos, blockState, entity) -> {
            if (entity instanceof ResearchTableBlockEntity researchTable) {
                ResearchTableBlockEntity.tick(lvl, pos, blockState, researchTable);
            }
        };
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide()) {
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity instanceof ResearchTableBlockEntity researchTable) {
                player.openMenu(researchTable, pos);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}
