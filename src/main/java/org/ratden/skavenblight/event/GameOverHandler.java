package org.ratden.skavenblight.event;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class GameOverHandler {

    private static final List<GameOver> GAME_OVER = new ArrayList<>();

        public static void start(ServerLevel level, BlockPos pos) {
            GAME_OVER.add(new GameOver(level, pos.immutable(), 0));
        }

        public static void onServerTick(ServerTickEvent.Post event) {
            Iterator<GameOver> iterator = GAME_OVER.iterator();


            while (iterator.hasNext()) {
                GameOver gameOver = iterator.next();
                gameOver.tick++;

                ServerLevel level = gameOver.level;
                BlockPos pos = gameOver.pos;

                if (gameOver.tick == 1) {
                    level.sendParticles(
                            ParticleTypes.END_ROD,
                            pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                            100,
                            1.5, 1.5, 1.8,
                            0.1
                    );
                }

                if (gameOver.tick == 20) {
                    level.sendParticles(
                            ParticleTypes.END_ROD,
                            pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                            150,
                            1.0, 1.0, 1.2,
                            0.06
                    );
                }

                if (gameOver.tick == 40) {
                    level.sendParticles(
                            ParticleTypes.END_ROD,
                            pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                            200,
                            0.2, 0.2, 0.2,
                            0.04
                    );
                }

                if (gameOver.tick >= 60) {
                    level.explode(
                            null,
                            pos.getX() + 0.5,
                            pos.getY() + 0.5,
                            pos.getZ() + 0.5,
                            10.0f,
                            Level.ExplosionInteraction.BLOCK
                    );
                    level.sendParticles(
                            ParticleTypes.EXPLOSION_EMITTER,
                            pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                            300,
                            0.2, 0.2, 0.2,
                            4
                    );
                    level.sendParticles(
                            ParticleTypes.TOTEM_OF_UNDYING,
                            pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                            300,
                            0.2, 0.2, 0.2,
                            2.5
                    );

                    iterator.remove();
                }
            }
        }

        private static class GameOver {
            private final ServerLevel level;
            private final BlockPos pos;
            private int tick;

            private GameOver(ServerLevel level, BlockPos pos, int tick) {
                this.level = level;
                this.pos = pos;
                this.tick = tick;
            }
        }
    }


