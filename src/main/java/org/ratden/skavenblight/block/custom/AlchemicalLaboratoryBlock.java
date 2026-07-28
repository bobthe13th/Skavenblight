package org.ratden.skavenblight.block.custom;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.ratden.skavenblight.block.entity.AlchemicalLaboratoryBlockEntity;

/**
 * A block skeleton for the Alchemical Laboratory, where players brew WFRP potions.
 */
public class AlchemicalLaboratoryBlock extends Block implements EntityBlock {

    public AlchemicalLaboratoryBlock(Properties properties) {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AlchemicalLaboratoryBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        if (level.isClientSide()) {
            return null;
        }
        return (lvl, pos, blockState, entity) -> {
            if (entity instanceof AlchemicalLaboratoryBlockEntity labEntity) {
                AlchemicalLaboratoryBlockEntity.tick(lvl, pos, blockState, labEntity);
            }
        };
    }
}
