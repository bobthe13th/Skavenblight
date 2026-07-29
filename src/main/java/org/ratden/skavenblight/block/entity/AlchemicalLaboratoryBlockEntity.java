package org.ratden.skavenblight.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A BlockEntity skeleton for the Alchemical Laboratory, storing brewing progress and state.
 */
public class AlchemicalLaboratoryBlockEntity extends BlockEntity {

    private int brewTime = 0;

    public AlchemicalLaboratoryBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ALCHEMICAL_LABORATORY.get(), pos, state);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, AlchemicalLaboratoryBlockEntity blockEntity) {
        if (level.isClientSide) return;
        // Simple ticking logic placeholder for boiling liquids
        blockEntity.brewTime++;
    }

    public int getBrewTime() {
        return brewTime;
    }

    public void setBrewTime(int brewTime) {
        this.brewTime = brewTime;
        setChanged();
    }
}
