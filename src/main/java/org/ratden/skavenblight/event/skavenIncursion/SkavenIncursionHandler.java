package org.ratden.skavenblight.event.skavenIncursion;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.ratden.skavenblight.event.skavenIncursion.scenario.WolfRatAssault;
import org.ratden.skavenblight.world.NexusTracker;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class SkavenIncursionHandler {
    private static final List<SkavenIncursion> ACTIVE_INCURSIONS = new ArrayList<>();

    public static IncursionTargetType startWolfRatAssault(ServerLevel level, BlockPos fallbackPlayerPos) {
        IncursionTargetType targetType;
        BlockPos targetPos;

        if (NexusTracker.hasActiveNexus(level) && level.random.nextBoolean()) {
            targetType = IncursionTargetType.NEXUS;
            targetPos = NexusTracker.getActiveNexusPos(level);
        } else {
            targetType = IncursionTargetType.PLAYER;
            targetPos = fallbackPlayerPos;
        }

        ACTIVE_INCURSIONS.add(new WolfRatAssault(level, targetPos, targetType));

        return targetType;
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        Iterator<SkavenIncursion> iterator = ACTIVE_INCURSIONS.iterator();

        while (iterator.hasNext()) {
            SkavenIncursion incursion = iterator.next();

            incursion.tick();

            if (incursion.isFinished()) {
                iterator.remove();
            }
        }
    }

    public static int clearIncursions() {
        int count = ACTIVE_INCURSIONS.size();
        ACTIVE_INCURSIONS.clear();
        return count;
    }
}