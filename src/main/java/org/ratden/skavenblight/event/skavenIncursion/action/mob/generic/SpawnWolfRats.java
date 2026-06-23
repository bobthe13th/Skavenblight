package org.ratden.skavenblight.event.skavenIncursion.action.mob.generic;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.entity.ModEntities;

public class SpawnWolfRats {

    public static int execute(ServerLevel level, BlockPos sourcePos, int count) {
        int spawned = 0;

        for (int i = 0; i < count; i++) {
            var ratWolf = ModEntities.RAT_WOLF.get().create(level);

            if (ratWolf == null) {
                continue;
            }

            BlockPos spawnPos = findSafeSpawnPos(level, sourcePos);

            if (spawnPos == null) {
                continue;
            }

            ratWolf.moveTo(
                    spawnPos.getX() + 0.5,
                    spawnPos.getY(),
                    spawnPos.getZ() + 0.5,
                    level.random.nextFloat() * 360F,
                    0
            );

            level.addFreshEntity(ratWolf);
            spawned++;
        }

        return spawned;
    }

    private static BlockPos findSafeSpawnPos(ServerLevel level, BlockPos sourcePos) {
        int attempts = 12;

        for (int attempt = 0; attempt < attempts; attempt++) {
            int offsetX = level.random.nextInt(7) - 3;
            int offsetZ = level.random.nextInt(7) - 3;

            BlockPos spawnPos = sourcePos.offset(offsetX, 0, offsetZ);

            if (level.getBlockState(spawnPos).isAir()
                    && level.getBlockState(spawnPos.above()).isAir()
                    && level.getBlockState(spawnPos.below()).isSolidRender(level, spawnPos.below())) {
                return spawnPos;
            }
        }

        if (level.getBlockState(sourcePos).isAir()
                && level.getBlockState(sourcePos.above()).isAir()
                && level.getBlockState(sourcePos.below()).isSolidRender(level, sourcePos.below())) {
            return sourcePos;
        }

        return null;
    }
}