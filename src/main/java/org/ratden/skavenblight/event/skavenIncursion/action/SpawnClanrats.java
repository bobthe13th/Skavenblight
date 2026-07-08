package org.ratden.skavenblight.event.skavenIncursion.action;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.entity.ModEntities;
import org.ratden.skavenblight.entity.custom.ClanratEntity;
import org.ratden.skavenblight.ai.pathing.StandardFlowField;

public class SpawnClanrats {

    public static int execute(ServerLevel level, BlockPos targetPos, int count, StandardFlowField flowField) {
        int spawned = 0;

        for (int i = 0; i < count; i++) {
            // Assuming your ModEntities has CLANRAT defined
            var clanrat = ModEntities.CLANRAT.get().create(level);

            if (clanrat == null) {
                continue;
            }

            double x = targetPos.getX() + level.random.nextInt(11) - 5;
            double y = targetPos.getY();
            double z = targetPos.getZ() + level.random.nextInt(11) - 5;

            clanrat.moveTo(x, y, z, level.random.nextFloat() * 360F, 0);

            // --- NEW: ASSIGN THE MAP TO THE RAT ---
            if (clanrat instanceof ClanratEntity rat) {
                rat.assignFlowField(flowField);
            }

            level.addFreshEntity(clanrat);
            spawned++;
        }

        return spawned;
    }
}