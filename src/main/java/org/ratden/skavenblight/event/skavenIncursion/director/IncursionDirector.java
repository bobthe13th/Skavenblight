package org.ratden.skavenblight.event.skavenIncursion.director;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.ratden.skavenblight.event.skavenIncursion.ActiveIncursionManager;
import org.ratden.skavenblight.event.skavenIncursion.IncursionTargetType;
import org.ratden.skavenblight.event.skavenIncursion.scenario.SkavenScenario;
import org.ratden.skavenblight.event.skavenIncursion.scenario.ScenarioRegistry;
import org.ratden.skavenblight.event.skavenIncursion.scheme.SchemeRegistry;
import org.ratden.skavenblight.event.skavenIncursion.scheme.SkavenScheme;
import org.ratden.skavenblight.world.NexusTracker;
import org.ratden.skavenblight.world.SkavenblightWorldData;

import java.util.List;

public class IncursionDirector {

    public static boolean startDebugIncursionFromCurrentScheme(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        SkavenblightWorldData worldData = SkavenblightWorldData.get(level);

        SkavenScheme currentScheme = SchemeRegistry.getScheme(
                worldData.getCurrentSchemeId()
        );

        List<String> availableScenarioIds =
                currentScheme.getAvailableScenarioIds(
                        worldData.getSchemeProgress(),
                        worldData.getSchemeComplexity()
                );

        if (availableScenarioIds.isEmpty()) {
            return false;
        }

        String scenarioId = chooseScenarioId(level, availableScenarioIds);
        IncursionTargetType targetType = chooseTargetType(level);
        BlockPos targetPos = chooseTargetPos(level, player.blockPosition(), targetType);

        SkavenScenario incursion = ScenarioRegistry.createScenario(
                scenarioId,
                level,
                targetPos,
                targetType
        );

        if (incursion == null) {
            return false;
        }

        ActiveIncursionManager.addIncursion(incursion);
        return true;
    }

    private static String chooseScenarioId(ServerLevel level, List<String> availableScenarioIds) {
        return availableScenarioIds.get(
                level.random.nextInt(availableScenarioIds.size())
        );
    }

    private static IncursionTargetType chooseTargetType(ServerLevel level) {
        if (NexusTracker.hasActiveNexus(level) && level.random.nextBoolean()) {
            return IncursionTargetType.NEXUS;
        }

        return IncursionTargetType.PLAYER;
    }

    private static BlockPos chooseTargetPos(
            ServerLevel level,
            BlockPos fallbackPlayerPos,
            IncursionTargetType targetType
    ) {
        if (targetType == IncursionTargetType.NEXUS
                && NexusTracker.hasActiveNexus(level)) {
            return NexusTracker.getActiveNexusPos(level);
        }

        return fallbackPlayerPos;
    }
}