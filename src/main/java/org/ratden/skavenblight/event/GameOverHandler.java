package org.ratden.skavenblight.event;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.sound.ModSounds;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Runs the delayed game-over sequence after the Warpstone Nexus falls.
 */
@EventBusSubscriber(modid = Skavenblight.MODID)
public final class GameOverHandler {

    private static final List<GameOver> GAME_OVER_EVENTS =
            new ArrayList<>();

    public static void start(
            ServerLevel level,
            BlockPos pos
    ) {
        if (!GAME_OVER_EVENTS.isEmpty()) {
            return;
        }

        GAME_OVER_EVENTS.add(
                new GameOver(
                        level,
                        pos.immutable(),
                        0
                )
        );
    }

    @SubscribeEvent
    public static void onServerTick(
            ServerTickEvent.Post event
    ) {
        Iterator<GameOver> iterator =
                GAME_OVER_EVENTS.iterator();

        while (iterator.hasNext()) {
            GameOver gameOver = iterator.next();

            gameOver.tick++;

            ServerLevel level = gameOver.level;
            BlockPos pos = gameOver.pos;

            if (gameOver.tick == 1) {
                level.sendParticles(
                        ParticleTypes.END_ROD,
                        pos.getX() + 0.5D,
                        pos.getY() + 1.0D,
                        pos.getZ() + 0.5D,
                        100,
                        1.5D,
                        1.5D,
                        1.8D,
                        0.1D
                );

                for (Player player : level.players()) {
                    if (!(player instanceof ServerPlayer serverPlayer)) {
                        continue;
                    }

                    serverPlayer.playNotifySound(
                            ModSounds.GAME_OVER.get(),
                            SoundSource.MASTER,
                            1.5F,
                            1.0F
                    );

                    serverPlayer.connection.send(
                            new ClientboundSetTitleTextPacket(
                                    Component.literal("GAME OVER")
                            )
                    );

                    serverPlayer.connection.send(
                            new ClientboundSetSubtitleTextPacket(
                                    Component.literal(
                                            "The Warpstone Nexus has fallen."
                                    )
                            )
                    );
                }
            }

            if (gameOver.tick == 20) {
                level.sendParticles(
                        ParticleTypes.END_ROD,
                        pos.getX() + 0.5D,
                        pos.getY() + 1.0D,
                        pos.getZ() + 0.5D,
                        150,
                        1.0D,
                        1.0D,
                        1.2D,
                        0.06D
                );
            }

            if (gameOver.tick == 40) {
                level.sendParticles(
                        ParticleTypes.END_ROD,
                        pos.getX() + 0.5D,
                        pos.getY() + 1.0D,
                        pos.getZ() + 0.5D,
                        200,
                        0.2D,
                        0.2D,
                        0.2D,
                        0.04D
                );
            }

            if (gameOver.tick < 60) {
                continue;
            }

            level.explode(
                    null,
                    pos.getX() + 0.5D,
                    pos.getY() + 0.5D,
                    pos.getZ() + 0.5D,
                    10.0F,
                    Level.ExplosionInteraction.BLOCK
            );

            level.sendParticles(
                    ParticleTypes.EXPLOSION_EMITTER,
                    pos.getX() + 0.5D,
                    pos.getY() + 1.0D,
                    pos.getZ() + 0.5D,
                    300,
                    0.2D,
                    0.2D,
                    0.2D,
                    4.0D
            );

            level.sendParticles(
                    ParticleTypes.TOTEM_OF_UNDYING,
                    pos.getX() + 0.5D,
                    pos.getY() + 1.0D,
                    pos.getZ() + 0.5D,
                    300,
                    0.2D,
                    0.2D,
                    0.2D,
                    2.5D
            );

            iterator.remove();
        }
    }

    private static final class GameOver {

        private final ServerLevel level;
        private final BlockPos pos;
        private int tick;

        private GameOver(
                ServerLevel level,
                BlockPos pos,
                int tick
        ) {
            this.level = level;
            this.pos = pos;
            this.tick = tick;
        }
    }

    private GameOverHandler() {
    }
}