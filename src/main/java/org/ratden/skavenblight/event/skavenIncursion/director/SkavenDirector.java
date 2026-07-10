package org.ratden.skavenblight.event.skavenIncursion.director;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
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

public class SkavenDirector {
    private static final int DIRECTOR_CHECK_INTERVAL_TICKS = 200;
    private static final long INITIAL_GRACE_PERIOD_TICKS = 72000L;

    public static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel level = event.getServer().overworld();

        if (level.getGameTime() % DIRECTOR_CHECK_INTERVAL_TICKS != 0) {
            return;
        }

        if (level.players().isEmpty()) {
            return;
        }

        ServerPlayer player = level.players().get(0);

        tryStartScheduledIncursionFromCurrentScheme(player);
    }

    public static boolean tryStartScheduledIncursionFromCurrentScheme(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        SkavenblightWorldData worldData = SkavenblightWorldData.get(level);

        ensureSkavenblightStarted(worldData, level);

        if (!isInitialGracePeriodOver(worldData, level.getGameTime())) {
            return false;
        }

        TempoBracketRules tempoRules = TempoBracketRules.forTempo(
                worldData.getIncursionTempo()
        );

        if (!isGlobalLockoutReady(worldData, tempoRules, level.getGameTime())) {
            return false;
        }

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
                tempoRules,
                availableDefinitions,
                DirectorStartReason.SCHEDULED
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

        return startScenario(
                player,
                chosenDefinition,
                DirectorStartReason.SCHEDULED
        );
    }

    public static boolean tryStartSpecificScenario(
            ServerPlayer player,
            String scenarioId,
            DirectorStartReason startReason
    ) {
        ServerLevel level = player.serverLevel();
        SkavenblightWorldData worldData = SkavenblightWorldData.get(level);

        ensureSkavenblightStarted(worldData, level);

        ScenarioDefinition definition = ScenarioRegistry.getDefinition(scenarioId);

        if (definition == null) {
            return false;
        }

        TempoBracketRules tempoRules = TempoBracketRules.forTempo(
                worldData.getIncursionTempo()
        );

        List<ScenarioDefinition> validDefinitions = filterValidDefinitions(
                level,
                worldData,
                tempoRules,
                List.of(definition),
                startReason
        );

        if (validDefinitions.isEmpty()) {
            return false;
        }

        return startScenario(
                player,
                definition,
                startReason
        );
    }

    public static boolean startDebugIncursionFromCurrentScheme(ServerPlayer player) {
        return tryStartScheduledIncursionFromCurrentScheme(player);
    }

    private static boolean startScenario(
            ServerPlayer player,
            ScenarioDefinition definition,
            DirectorStartReason startReason
    ) {
        ServerLevel level = player.serverLevel();

        IncursionTargetType targetType = chooseTargetType(
                level,
                definition
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
                definition.getId(),
                level,
                targetPos,
                targetType
        );

        if (incursion == null) {
            return false;
        }

        ActiveIncursionManager.addIncursion(incursion);

        SkavenblightWorldData.get(level).recordScenarioStarted(
                definition.getId(),
                definition.getPattern(),
                level.getGameTime()
        );

        return true;
    }

    private static List<ScenarioDefinition> filterValidDefinitions(
            ServerLevel level,
            SkavenblightWorldData worldData,
            TempoBracketRules tempoRules,
            Collection<ScenarioDefinition> definitions,
            DirectorStartReason startReason
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

            if (!definition.canRunWithoutScheme()
                    && SchemeRegistry.getScheme(worldData.getCurrentSchemeId()) == null) {
                continue;
            }

            if (!hasValidTargetType(level, definition)) {
                continue;
            }

            if (shouldCheckScenarioCooldown(startReason)
                    && !isScenarioCooldownReady(worldData, definition, currentGameTime)) {
                continue;
            }

            if (shouldCheckOverlap(startReason)
                    && !isOverlapAllowed(definition, tempoRules)) {
                continue;
            }

            validDefinitions.add(definition);
        }

        return validDefinitions;
    }

    private static boolean shouldCheckScenarioCooldown(DirectorStartReason startReason) {
        return startReason == DirectorStartReason.SCHEDULED
                || startReason == DirectorStartReason.SCHEME_TRIGGERED;
    }

    private static boolean shouldCheckOverlap(DirectorStartReason startReason) {
        return startReason == DirectorStartReason.SCHEDULED
                || startReason == DirectorStartReason.SCHEME_TRIGGERED;
    }

    private static void ensureSkavenblightStarted(
            SkavenblightWorldData worldData,
            ServerLevel level
    ) {
        if (!worldData.isSkavenblightStarted()) {
            worldData.startSkavenblight(level.getGameTime());
        }
    }

    private static boolean isInitialGracePeriodOver(
            SkavenblightWorldData worldData,
            long currentGameTime
    ) {
        long ticksSinceSkavenblightStarted =
                currentGameTime - worldData.getSkavenblightStartGameTime();

        return ticksSinceSkavenblightStarted >= INITIAL_GRACE_PERIOD_TICKS;
    }

    private static boolean isGlobalLockoutReady(
            SkavenblightWorldData worldData,
            TempoBracketRules tempoRules,
            long currentGameTime
    ) {
        long lastGlobalIncursionGameTime = worldData.getLastGlobalIncursionGameTime();

        if (lastGlobalIncursionGameTime <= 0L) {
            return true;
        }

        long ticksSinceLastGlobalIncursion =
                currentGameTime - lastGlobalIncursionGameTime;

        return ticksSinceLastGlobalIncursion >= tempoRules.getGlobalLockoutTicks();
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

    private static boolean isOverlapAllowed(
            ScenarioDefinition definition,
            TempoBracketRules tempoRules
    ) {
        if (ActiveIncursionManager.hasActiveSetPiece()) {
            return definition.getOverlapType() == OverlapType.BACKGROUND
                    && definition.getPressureProfile() == PressureProfile.AMBIENT;
        }

        if (definition.getOverlapType() == OverlapType.EXCLUSIVE) {
            return !ActiveIncursionManager.hasActiveIncursions();
        }

        if (definition.getOverlapType() == OverlapType.MAJOR) {
            return ActiveIncursionManager.getActiveMajorIncursionCount()
                    < tempoRules.getMaxActiveMajorIncursions();
        }

        if (definition.getOverlapType() == OverlapType.MINOR) {
            if (!tempoRules.allowsMinorIncursionOverlap()) {
                return !ActiveIncursionManager.hasActiveIncursions();
            }

            return ActiveIncursionManager.getActiveMinorIncursionCount()
                    < tempoRules.getMaxActiveMinorIncursions();
        }

        if (definition.getOverlapType() == OverlapType.BACKGROUND) {
            if (definition.getPressureProfile() == PressureProfile.SUBTLE
                    && ActiveIncursionManager.hasActiveCombatPressure()
                    && !tempoRules.allowsSubtleEffectsDuringCombat()) {
                return false;
            }

            if (definition.getPressureProfile() == PressureProfile.AMBIENT
                    && ActiveIncursionManager.hasActiveCombatPressure()
                    && !tempoRules.allowsBackgroundEffectsDuringCombat()) {
                return false;
            }

            return true;
        }

        return false;
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

    private SkavenDirector() {
    }
}