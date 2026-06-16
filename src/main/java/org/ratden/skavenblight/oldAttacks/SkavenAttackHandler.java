package org.ratden.skavenblight.oldAttacks;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/*public class SkavenAttackHandler {
    private static final List<SkavenAttack> ACTIVE_ATTACKS = new ArrayList<>();

    public static void startWolfRatAttack(ServerLevel level, BlockPos targetPos) {
        ACTIVE_ATTACKS.add(new WolfRatAttack(level, targetPos));
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        Iterator<SkavenAttack> iterator = ACTIVE_ATTACKS.iterator();

        while (iterator.hasNext()) {
            SkavenAttack attack = iterator.next();

            attack.tick();

            if (attack.isFinished()) {
                iterator.remove();
            }
        }
    }
}

 */

