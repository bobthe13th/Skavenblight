package org.ratden.skavenblight.event.skavenIncursion.scenario;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.event.skavenIncursion.director.IncursionTargetType;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlan;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.PersistableSkavenScenario;
import org.ratden.skavenblight.event.skavenIncursion.runtime.persistence.PlannedScenarioRuntimeSnapshot;
import org.ratden.skavenblight.event.skavenIncursion.scenario.generic.CatDogRaid;
import org.ratden.skavenblight.event.skavenIncursion.scenario.generic.WolfRatAssault;
import org.ratden.skavenblight.event.skavenIncursion.scenario.tutorial.TutorialCampAttack;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Central catalogue and runtime factory for all registered Scenarios.
 *
 * Legacy Scenarios are still created directly from a target position and
 * target type.
 *
 * Ordinary planning-aware Scenarios are created from a completed
 * IncursionPlan and must expose the common PlannedScenarioRuntimeSnapshot
 * required by persistent-incursion ownership.
 *
 * Planning-aware Scenarios also provide a restoration factory. This restores
 * their exact mutable runtime state against an immutable IncursionPlan that
 * has already been reconstructed from persistence.
 *
 * Creation and restoration routes temporarily coexist with the legacy route
 * while older Scenarios are migrated onto planning-aware runtime.
 */
public final class ScenarioRegistry {

    private static final Map<String, ScenarioDefinition>
            SCENARIO_DEFINITIONS =
            new LinkedHashMap<>();

    private static final Map<String, LegacyScenarioFactory>
            LEGACY_SCENARIO_FACTORIES =
            new LinkedHashMap<>();

    private static final Map<String, PlannedScenarioFactory>
            PLANNED_SCENARIO_FACTORIES =
            new LinkedHashMap<>();

    private static final Map<String, PlannedScenarioRestorer>
            PLANNED_SCENARIO_RESTORERS =
            new LinkedHashMap<>();

    static {
        registerLegacyScenario(
                WolfRatAssault.DEFINITION,
                WolfRatAssault::new
        );

        registerLegacyScenario(
                TutorialCampAttack.DEFINITION,
                TutorialCampAttack::new
        );

        registerPlannedScenario(
                CatDogRaid.DEFINITION,
                CatDogRaid::new,
                CatDogRaid::restore
        );
    }

    /**
     * Creates a legacy Scenario directly from target information.
     *
     * Returns null when the Scenario ID is unknown or the registered Scenario
     * does not support legacy construction.
     */
    public static SkavenScenario createScenario(
            String scenarioId,
            ServerLevel level,
            BlockPos targetPos,
            IncursionTargetType targetType
    ) {
        LegacyScenarioFactory scenarioFactory =
                LEGACY_SCENARIO_FACTORIES.get(
                        scenarioId
                );

        if (scenarioFactory == null) {
            return null;
        }

        return scenarioFactory.create(
                level,
                targetPos,
                targetType
        );
    }

    /**
     * Creates an ordinary planning-aware Scenario from a completed
     * IncursionPlan.
     *
     * Every Scenario registered through the planned route must be capable of
     * producing the common PlannedScenarioRuntimeSnapshot used by
     * LivePersistentIncursion.
     *
     * Returns null when the Scenario ID is unknown or the registered Scenario
     * does not support planned construction.
     */
    public static PersistableSkavenScenario<
            PlannedScenarioRuntimeSnapshot
            > createPlannedScenario(
            String scenarioId,
            ServerLevel level,
            IncursionPlan incursionPlan
    ) {
        PlannedScenarioFactory scenarioFactory =
                PLANNED_SCENARIO_FACTORIES.get(
                        scenarioId
                );

        if (scenarioFactory == null) {
            return null;
        }

        return scenarioFactory.create(
                level,
                incursionPlan
        );
    }

    /**
     * Restores an ordinary planning-aware Scenario from its immutable plan
     * and exact mutable runtime snapshot.
     *
     * The immutable IncursionPlan must already have been reconstructed from
     * the IncursionPlanSnapshot stored in the same persistent-incursion
     * record.
     *
     * Returns null when the Scenario ID is unknown or has no registered
     * restoration route.
     */
    public static PersistableSkavenScenario<
            PlannedScenarioRuntimeSnapshot
            > restorePlannedScenario(
            String scenarioId,
            ServerLevel level,
            IncursionPlan incursionPlan,
            PlannedScenarioRuntimeSnapshot runtimeSnapshot
    ) {
        if (scenarioId == null
                || scenarioId.isBlank()) {

            throw new IllegalArgumentException(
                    "Restored planned Scenario ID cannot be blank."
            );
        }

        if (level == null) {
            throw new IllegalArgumentException(
                    "Restored planned Scenario level cannot be null."
            );
        }

        if (incursionPlan == null) {
            throw new IllegalArgumentException(
                    "Restored planned Scenario IncursionPlan cannot be null."
            );
        }

        if (runtimeSnapshot == null) {
            throw new IllegalArgumentException(
                    "Restored planned Scenario runtime snapshot cannot be "
                            + "null."
            );
        }

        if (!scenarioId.equals(
                runtimeSnapshot.scenarioId()
        )) {
            throw new IllegalArgumentException(
                    "Requested restoration of Scenario '"
                            + scenarioId
                            + "', but the runtime snapshot belongs to '"
                            + runtimeSnapshot.scenarioId()
                            + "'."
            );
        }

        if (!incursionPlan
                .getIncursionId()
                .equals(
                        runtimeSnapshot.instanceId()
                )) {

            throw new IllegalArgumentException(
                    "Restored IncursionPlan ID "
                            + incursionPlan.getIncursionId()
                            + " does not match runtime snapshot instance ID "
                            + runtimeSnapshot.instanceId()
                            + "."
            );
        }

        PlannedScenarioRestorer scenarioRestorer =
                PLANNED_SCENARIO_RESTORERS.get(
                        scenarioId
                );

        if (scenarioRestorer == null) {
            return null;
        }

        PersistableSkavenScenario<
                PlannedScenarioRuntimeSnapshot
                > restoredScenario =
                scenarioRestorer.restore(
                        level,
                        incursionPlan,
                        runtimeSnapshot
                );

        if (restoredScenario == null) {
            throw new IllegalStateException(
                    "Planned Scenario restorer for '"
                            + scenarioId
                            + "' returned null."
            );
        }

        if (!scenarioId.equals(
                restoredScenario.getId()
        )) {
            throw new IllegalStateException(
                    "Planned Scenario restorer for '"
                            + scenarioId
                            + "' produced Scenario '"
                            + restoredScenario.getId()
                            + "'."
            );
        }

        if (!incursionPlan
                .getIncursionId()
                .equals(
                        restoredScenario.getInstanceId()
                )) {

            throw new IllegalStateException(
                    "Restored Scenario instance ID "
                            + restoredScenario.getInstanceId()
                            + " does not match IncursionPlan ID "
                            + incursionPlan.getIncursionId()
                            + "."
            );
        }

        PlannedScenarioRuntimeSnapshot
                reconstructedRuntimeSnapshot =
                restoredScenario.createSnapshot();

        if (!runtimeSnapshot.equals(
                reconstructedRuntimeSnapshot
        )) {
            throw new IllegalStateException(
                    "Restored Scenario '"
                            + scenarioId
                            + "' does not exactly reproduce its saved runtime "
                            + "snapshot for incursion "
                            + runtimeSnapshot.instanceId()
                            + "."
            );
        }

        return restoredScenario;
    }

    public static ScenarioDefinition getDefinition(
            String scenarioId
    ) {
        return SCENARIO_DEFINITIONS.get(
                scenarioId
        );
    }

    /**
     * Returns registered Scenario definitions in registration order.
     *
     * Stable ordering makes command suggestions and debug output predictable.
     */
    public static Collection<ScenarioDefinition> getDefinitions() {
        return List.copyOf(
                SCENARIO_DEFINITIONS.values()
        );
    }

    public static Collection<ScenarioDefinition> getDefinitionsForIds(
            Collection<String> scenarioIds
    ) {
        if (scenarioIds == null) {
            throw new IllegalArgumentException(
                    "Scenario ID collection cannot be null."
            );
        }

        return scenarioIds.stream()
                .map(
                        ScenarioRegistry::getDefinition
                )
                .filter(
                        definition -> definition != null
                )
                .toList();
    }

    public static boolean hasDefinition(
            String scenarioId
    ) {
        return SCENARIO_DEFINITIONS.containsKey(
                scenarioId
        );
    }

    public static boolean supportsLegacyCreation(
            String scenarioId
    ) {
        return LEGACY_SCENARIO_FACTORIES.containsKey(
                scenarioId
        );
    }

    public static boolean supportsPlannedCreation(
            String scenarioId
    ) {
        return PLANNED_SCENARIO_FACTORIES.containsKey(
                scenarioId
        );
    }

    public static boolean supportsPlannedRestoration(
            String scenarioId
    ) {
        return PLANNED_SCENARIO_RESTORERS.containsKey(
                scenarioId
        );
    }

    private static void registerLegacyScenario(
            ScenarioDefinition scenarioDefinition,
            LegacyScenarioFactory scenarioFactory
    ) {
        if (scenarioFactory == null) {
            throw new IllegalArgumentException(
                    "Legacy Scenario factory cannot be null."
            );
        }

        registerDefinition(
                scenarioDefinition
        );

        LEGACY_SCENARIO_FACTORIES.put(
                scenarioDefinition.id(),
                scenarioFactory
        );
    }

    /**
     * Registers both halves of one planning-aware Scenario lifecycle.
     *
     * Requiring creation and restoration together prevents a Scenario from
     * entering persistent runtime while lacking a way to reconstruct that
     * runtime after restart.
     */
    private static void registerPlannedScenario(
            ScenarioDefinition scenarioDefinition,
            PlannedScenarioFactory scenarioFactory,
            PlannedScenarioRestorer scenarioRestorer
    ) {
        if (scenarioFactory == null) {
            throw new IllegalArgumentException(
                    "Planned Scenario factory cannot be null."
            );
        }

        if (scenarioRestorer == null) {
            throw new IllegalArgumentException(
                    "Planned Scenario restorer cannot be null."
            );
        }

        registerDefinition(
                scenarioDefinition
        );

        PLANNED_SCENARIO_FACTORIES.put(
                scenarioDefinition.id(),
                scenarioFactory
        );

        PLANNED_SCENARIO_RESTORERS.put(
                scenarioDefinition.id(),
                scenarioRestorer
        );
    }

    private static void registerDefinition(
            ScenarioDefinition scenarioDefinition
    ) {
        if (scenarioDefinition == null) {
            throw new IllegalArgumentException(
                    "Scenario definition cannot be null."
            );
        }

        ScenarioDefinition previousDefinition =
                SCENARIO_DEFINITIONS.putIfAbsent(
                        scenarioDefinition.id(),
                        scenarioDefinition
                );

        if (previousDefinition != null) {
            throw new IllegalStateException(
                    "Duplicate Scenario ID registered: "
                            + scenarioDefinition.id()
                            + "."
            );
        }
    }

    @FunctionalInterface
    private interface LegacyScenarioFactory {

        SkavenScenario create(
                ServerLevel level,
                BlockPos targetPos,
                IncursionTargetType targetType
        );
    }

    @FunctionalInterface
    private interface PlannedScenarioFactory {

        PersistableSkavenScenario<
                PlannedScenarioRuntimeSnapshot
                > create(
                ServerLevel level,
                IncursionPlan incursionPlan
        );
    }

    @FunctionalInterface
    private interface PlannedScenarioRestorer {

        PersistableSkavenScenario<
                PlannedScenarioRuntimeSnapshot
                > restore(
                ServerLevel level,
                IncursionPlan incursionPlan,
                PlannedScenarioRuntimeSnapshot runtimeSnapshot
        );
    }

    private ScenarioRegistry() {
    }
}