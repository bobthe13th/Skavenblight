package org.ratden.skavenblight.event.skavenIncursion;

import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.ratden.skavenblight.event.skavenIncursion.scenario.SkavenScenario;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class ActiveIncursionManager {
    private static final List<SkavenScenario> ACTIVE_INCURSIONS = new ArrayList<>();

    public static void addIncursion(SkavenScenario incursion) {
        ACTIVE_INCURSIONS.add(incursion);
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        Iterator<SkavenScenario> iterator = ACTIVE_INCURSIONS.iterator();

        while (iterator.hasNext()) {
            SkavenScenario incursion = iterator.next();

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

    public static int getActiveIncursionCount() {
        return ACTIVE_INCURSIONS.size();
    }
}