package org.ratden.skavenblight.event.skavenIncursion.scenario;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.event.skavenIncursion.IncursionTargetType;
import org.ratden.skavenblight.event.skavenIncursion.scenario.generic.WolfRatAssault;

public class ScenarioRegistry {

    public static SkavenScenario createScenario(
            String scenarioId,
            ServerLevel level,
            BlockPos targetPos,
            IncursionTargetType targetType
    ) {
        if (scenarioId.equals("wolf_rat_assault")) {
            return new WolfRatAssault(level, targetPos, targetType);
        }

        return null;
    }

    private ScenarioRegistry() {
    }
}