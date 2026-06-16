package org.ratden.skavenblight.event.skavenIncursion.action;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.entity.ModEntities;

public class SpawnWolfRats {

    public static int execute(ServerLevel level, BlockPos targetPos, int count) {
        int spawned = 0;

        for (int i = 0; i < count; i++) {
            var ratWolf = ModEntities.RAT_WOLF.get().create(level);

            if (ratWolf == null) {
                continue;
            }

            double x = targetPos.getX() + level.random.nextInt(11) - 5;
            double y = targetPos.getY();
            double z = targetPos.getZ() + level.random.nextInt(11) - 5;

            ratWolf.moveTo(x, y, z, level.random.nextFloat() * 360F, 0);
            level.addFreshEntity(ratWolf);

            spawned++;
        }

        return spawned;
    }
}