package org.ratden.skavenblight.event.skavenIncursion.scenario;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.event.skavenIncursion.director.IncursionTargetType;
import org.ratden.skavenblight.event.skavenIncursion.scenario.generic.WolfRatAssault;
import org.ratden.skavenblight.event.skavenIncursion.scenario.tutorial.TutorialCampAttack;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class ScenarioRegistry {
    private static final Map<String, ScenarioDefinition> SCENARIO_DEFINITIONS = new HashMap<>();

    static {
        registerDefinition(WolfRatAssault.DEFINITION);
        registerDefinition(TutorialCampAttack.DEFINITION);
    }

    public static SkavenScenario createScenario(
            String scenarioId,
            ServerLevel level,
            BlockPos targetPos,
            IncursionTargetType targetType
    ) {
        if (scenarioId.equals(WolfRatAssault.id())) {
            return new WolfRatAssault(level, targetPos, targetType);
        }

        if (scenarioId.equals(TutorialCampAttack.id())) {
            return new TutorialCampAttack(level, targetPos, targetType);
        }

        return null;
    }

    public static ScenarioDefinition getDefinition(String scenarioId) {
        return SCENARIO_DEFINITIONS.get(scenarioId);
    }

    public static Collection<ScenarioDefinition> getDefinitions() {
        return SCENARIO_DEFINITIONS.values();
    }

    public static Collection<ScenarioDefinition> getDefinitionsForIds(Collection<String> scenarioIds) {
        return scenarioIds.stream()
                .map(ScenarioRegistry::getDefinition)
                .filter(definition -> definition != null)
                .toList();
    }

    public static boolean hasDefinition(String scenarioId) {
        return SCENARIO_DEFINITIONS.containsKey(scenarioId);
    }

    private static void registerDefinition(ScenarioDefinition scenarioDefinition) {
        SCENARIO_DEFINITIONS.put(scenarioDefinition.getId(), scenarioDefinition);
    }

    private ScenarioRegistry() {
    }
}