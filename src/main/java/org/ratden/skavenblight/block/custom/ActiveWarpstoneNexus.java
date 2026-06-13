package org.ratden.skavenblight.block.custom;

import org.ratden.skavenblight.event.GameOverHandler;
import org.ratden.skavenblight.sound.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

public class ActiveWarpstoneNexus extends Block {
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
        if (!level.isClientSide() && state.getBlock() != newState.getBlock()) {
            for (Player player : level.players()) {
                if (player instanceof ServerPlayer serverPlayer) {
                    serverPlayer.playNotifySound(
                            ModSounds.GAME_OVER.get(),
                            SoundSource.MASTER,
                            1.5f,
                            1.0f
                    );
                    serverPlayer.connection.send(
                            new ClientboundSetTitleTextPacket(Component.literal("GAME OVER"))
                    );
                    serverPlayer.connection.send(
                            new ClientboundSetSubtitleTextPacket(Component.literal("The Warpstone Nexus has fallen."))
                    );
                };
                if (level instanceof ServerLevel serverLevel) {
                    GameOverHandler.start(serverLevel, pos);
                }

               /* level.playSound(
                        null,
                        player.blockPosition(),
                        ModSounds.GAME_OVER.get(),
                        SoundSource.MASTER,
                        1.0f,
                        1.0f
                );

                */
            }
            /*if (level instanceof ServerLevel serverLevel) {
                serverLevel.sendParticles(
                        ParticleTypes.END_ROD,
                        pos.getX() + 0.5,
                        pos.getY() + 1.0,
                        pos.getZ() + 0.5,
                        200,
                        0.2,
                        0.2,
                        0.2,
                        0.2
                );
            }


            level.explode(
                    null,
                    pos.getX() + 0.5,
                    pos.getY() + 0.5,
                    pos.getZ() + 0.5,
                    3.0f,
                    Level.ExplosionInteraction.BLOCK
            );

             */
        }

        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}




