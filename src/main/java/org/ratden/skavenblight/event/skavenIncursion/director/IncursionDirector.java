package org.ratden.skavenblight.event.skavenIncursion.director;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.ratden.skavenblight.event.skavenIncursion.ActiveIncursionManager;
import org.ratden.skavenblight.event.skavenIncursion.IncursionTargetType;
import org.ratden.skavenblight.event.skavenIncursion.scenario.ScenarioDefinition;
import org.ratden.skavenblight.event.skavenIncursion.scenario.ScenarioRegistry;
import org.ratden.skavenblight.event.skavenIncursion.scenario.SkavenScenario;
import org.ratden.skavenblight.event.skavenIncursion.scheme.SchemeRegistry;
import org.ratden.skavenblight.event.skavenIncursion.scheme.SkavenScheme;
import org.ratden.skavenblight.world.NexusTracker;
import org.ratden.skavenblight.world.SkavenblightWorldData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class IncursionDirector {

    public static boolean startDebugIncursionFromCurrentScheme(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        SkavenblightWorldData worldData = SkavenblightWorldData.get(level);

        SkavenScheme currentScheme = SchemeRegistry.getScheme(
                worldData.getCurrentSchemeId()
        );

        if (currentScheme == null) {
            return false;
        }

        List<String> availableScenarioIds = currentScheme.getAvailableScenarioIds(
                worldData.getSchemeProgress(),
                worldData.getSchemeComplexity()
        );

        if (availableScenarioIds.isEmpty()) {
            return false;
        }

        Collection<ScenarioDefinition> availableDefinitions =
                ScenarioRegistry.getDefinitionsForIds(availableScenarioIds);

        List<ScenarioDefinition> validDefinitions = filterValidDefinitions(
                level,
                worldData,
                availableDefinitions
        );

        if (validDefinitions.isEmpty()) {
            return false;
        }

        ScenarioDefinition chosenDefinition = chooseScenarioDefinition(
                level,
                currentScheme,
                validDefinitions
        );

        if (chosenDefinition == null) {
            return false;
        }

        IncursionTargetType targetType = chooseTargetType(
                level,
                chosenDefinition
        );

        if (targetType == null) {
            return false;
        }

        BlockPos targetPos = chooseTargetPos(
                level,
                player.blockPosition(),
                targetType
        );

        SkavenScenario incursion = ScenarioRegistry.createScenario(
                chosenDefinition.getId(),
                level,
                targetPos,
                targetType
        );

        if (incursion == null) {
            return false;
        }

        ActiveIncursionManager.addIncursion(incursion);

        worldData.recordScenarioStarted(
                chosenDefinition.getId(),
                chosenDefinition.getPattern(),
                level.getGameTime()
        );

        return true;
    }

    private static List<ScenarioDefinition> filterValidDefinitions(
            ServerLevel level,
            SkavenblightWorldData worldData,
            Collection<ScenarioDefinition> definitions
    ) {
        List<ScenarioDefinition> validDefinitions = new ArrayList<>();
        long currentGameTime = level.getGameTime();

        for (ScenarioDefinition definition : definitions) {
            if (!definition.isComplexityAllowed(worldData.getSchemeComplexity())) {
                continue;
            }

            if (definition.requiresActiveNexus() && !NexusTracker.hasActiveNexus(level)) {
                continue;
            }

            if (!hasValidTargetType(level, definition)) {
                continue;
            }

            if (!isScenarioCooldownReady(worldData, definition, currentGameTime)) {
                continue;
            }

            validDefinitions.add(definition);
        }

        return validDefinitions;
    }

    private static boolean isScenarioCooldownReady(
            SkavenblightWorldData worldData,
            ScenarioDefinition definition,
            long currentGameTime
    ) {
        if (definition.getCooldownTicks() <= 0) {
            return true;
        }

        String lastScenarioId = worldData.getLastScenarioId();

        if (lastScenarioId == null || !lastScenarioId.equals(definition.getId())) {
            return true;
        }

        long ticksSinceLastScenario =
                currentGameTime - worldData.getLastGlobalIncursionGameTime();

        return ticksSinceLastScenario >= definition.getCooldownTicks();
    }



    private static boolean hasValidTargetType(
            ServerLevel level,
            ScenarioDefinition definition
    ) {
        if (definition.allowsTargetType(IncursionTargetType.PLAYER)) {
            return true;
        }

        if (definition.allowsTargetType(IncursionTargetType.NEXUS)
                && NexusTracker.hasActiveNexus(level)) {
            return true;
        }

        return false;
    }

    private static ScenarioDefinition chooseScenarioDefinition(
            ServerLevel level,
            SkavenScheme currentScheme,
            List<ScenarioDefinition> validDefinitions
    ) {
        int totalWeight = 0;

        for (ScenarioDefinition definition : validDefinitions) {
            totalWeight += getModifiedWeight(currentScheme, definition);
        }

        if (totalWeight <= 0) {
            return null;
        }

        int roll = level.random.nextInt(totalWeight);

        for (ScenarioDefinition definition : validDefinitions) {
            int weight = getModifiedWeight(currentScheme, definition);

            if (weight <= 0) {
                continue;
            }

            if (roll < weight) {
                return definition;
            }

            roll -= weight;
        }

        return null;
    }

    private static int getModifiedWeight(
            SkavenScheme currentScheme,
            ScenarioDefinition definition
    ) {
        int baseWeight = definition.getBaseWeight();
        int modifierPercent = currentScheme.getScenarioWeightModifierPercent(definition);

        return Math.max(0, (baseWeight * modifierPercent) / 100);
    }

    private static IncursionTargetType chooseTargetType(
            ServerLevel level,
            ScenarioDefinition definition
    ) {
        boolean canTargetPlayer = definition.allowsTargetType(IncursionTargetType.PLAYER);
        boolean canTargetNexus = definition.allowsTargetType(IncursionTargetType.NEXUS)
                && NexusTracker.hasActiveNexus(level);

        if (canTargetPlayer && canTargetNexus) {
            if (level.random.nextBoolean()) {
                return IncursionTargetType.NEXUS;
            }

            return IncursionTargetType.PLAYER;
        }

        if (canTargetNexus) {
            return IncursionTargetType.NEXUS;
        }

        if (canTargetPlayer) {
            return IncursionTargetType.PLAYER;
        }

        return null;
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

    private IncursionDirector() {
    }
}