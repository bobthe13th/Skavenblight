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
import org.ratden.skavenblight.block.entity.ModBlockEntities;
import org.ratden.skavenblight.block.entity.WarpFluxStorageBlockEntity;

public class WarpFluxStorageBlock extends Block implements EntityBlock {
    private final int capacity;
    private final int maxReceive;
    private final int maxExtract;

    public WarpFluxStorageBlock(Properties properties, int capacity, int maxReceive, int maxExtract) {
        super(properties);
        this.capacity = capacity;
        this.maxReceive = maxReceive;
        this.maxExtract = maxExtract;
    }

    public int getCapacity() { return capacity; }
    public int getMaxReceive() { return maxReceive; }
    public int getMaxExtract() { return maxExtract; }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WarpFluxStorageBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return null;
    }

    //we might want to replace this with a more advanced GUI eventually.
    @Override
    protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state, net.minecraft.world.level.Level level, BlockPos pos, net.minecraft.world.entity.player.Player player, net.minecraft.world.phys.BlockHitResult hitResult) {

        // We only want to calculate and send the message on the server side to prevent double-firing
        if (!level.isClientSide()) {

            // Grab the block entity at the clicked position
            net.minecraft.world.level.block.entity.BlockEntity blockEntity = level.getBlockEntity(pos);

            // Check to make sure it's actually our storage block entity
            if (blockEntity instanceof WarpFluxStorageBlockEntity storageEntity) {

                // Fetch the current and max flux
                int currentFlux = storageEntity.getFluxStorage().getFlux();
                int maxFlux = storageEntity.getFluxStorage().getMaxFlux();

                // Create the text message. (You can color this or translate it later!)
                net.minecraft.network.chat.Component message = net.minecraft.network.chat.Component.literal(
                        "Warp Flux: " + currentFlux + " / " + maxFlux
                );

                // Send the message to the player.
                // The 'true' boolean puts it in the Action Bar (above the hotbar) instead of clogging the chat.
                player.displayClientMessage(message, true);
            }
        }

        // Return a successful interaction so the hand swings
        return net.minecraft.world.InteractionResult.sidedSuccess(level.isClientSide());
    }

}