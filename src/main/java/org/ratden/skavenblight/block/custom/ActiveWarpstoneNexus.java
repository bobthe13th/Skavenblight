package org.ratden.skavenblight.block.custom;

import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.ratden.skavenblight.block.ModBlocks;
import org.ratden.skavenblight.block.entity.WarpstoneNexusEntity;
import org.ratden.skavenblight.event.GameOverHandler;
import org.ratden.skavenblight.sound.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.jetbrains.annotations.Nullable;
import org.ratden.skavenblight.world.NexusTracker;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;

public class ActiveWarpstoneNexus extends Block implements EntityBlock {
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    public ActiveWarpstoneNexus(Properties properties) {
        super(properties);
        this.registerDefaultState(this.defaultBlockState().setValue(LIT, false));
    }

    @Override
    public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
        if (entity instanceof Player player) {
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 200, 1));
            level.playSound(player, pos, ModSounds.NEXUS_SPEED.get(), SoundSource.BLOCKS, 2f, 1f);
            if (!level.isClientSide()) {
                level.setBlock(pos, state.setValue(LIT, true), 3);
            }

            if (level instanceof ServerLevel serverLevel) {
                serverLevel.sendParticles(
                        ParticleTypes.WITCH,
                        pos.getX() + 0.5,
                        pos.getY() + 1.1,
                        pos.getZ() + 0.5,
                        12,
                        0.35,
                        0.35,
                        0.35,
                        0.05
                );
                serverLevel.sendParticles(
                        ParticleTypes.COMPOSTER,
                        pos.getX() + 0.5,
                        pos.getY() + 1.1,
                        pos.getZ() + 0.5,
                        8,
                        0.35,
                        0.35,
                        0.35,
                        0.08
                );
            }


        }
        super.stepOn(level, pos, state, entity);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LIT);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide()
                && level instanceof ServerLevel serverLevel
                && state.getBlock() != newState.getBlock()) {

            boolean wasTrackedNexus = NexusTracker.isActiveNexus(serverLevel, pos);

            if (wasTrackedNexus) {
                NexusTracker.clearActiveNexus(serverLevel);
                GameOverHandler.start(serverLevel, pos);
            }
        }

        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WarpstoneNexusEntity(pos, state);
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);

        if (level.isClientSide()) {
            return;
        }

        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        if (oldState.is(this)) {
            return;
        }

        if (NexusTracker.hasActiveNexus(serverLevel)
                && !NexusTracker.isActiveNexus(serverLevel, pos)) {

            level.setBlock(
                    pos,
                    ModBlocks.WARPSTONE_NEXUS.get().defaultBlockState(),
                    3
            );

            return;
        }

        NexusTracker.setActiveNexus(serverLevel, pos);
    }

   //
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null; // Don't tick on the client
        }

        // Checks if the block entity type matches our Warpstone Nexus type
        return type == org.ratden.skavenblight.block.entity.ModBlockEntities.WARPSTONE_NEXUS.get() ?
                (lvl, pos, st, blockEntity) -> ((WarpstoneNexusEntity) blockEntity).tick(lvl, pos, st) : null;
    }
}




