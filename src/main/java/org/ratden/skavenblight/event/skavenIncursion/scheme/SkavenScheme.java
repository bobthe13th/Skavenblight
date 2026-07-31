package org.ratden.skavenblight.event.skavenIncursion.scheme;

import org.ratden.skavenblight.event.skavenIncursion.scenario.ScenarioDefinition;

import java.util.List;

public interface SkavenScheme {

    // 1. Identity
    String getId();

    String getDisplayName();

    int getSchemeTier();

    // 2. Progress structure
    int getDefaultDeadlineDays();

    int getFinalStageThreshold();

    // 3. Progress rules
    int getPassiveDailyProgress();

    int getProgressFromSkavenSuccess();

    int getProgressLostFromSkavenFailure();

    // 4. Available scenarios
    List<String> getAvailableScenarioIds(int schemeProgress, int complexity);

    /**
     * Scheme-specific multiplier for scenario selection.
     *
     * Return 100 for normal weight.
     * Return 150 to make a scenario 50% more likely.
     * Return 50 to make a scenario half as likely.
     * Return 0 to effectively disable it for this scheme.
     */
    int getScenarioWeightModifierPercent(ScenarioDefinition scenarioDefinition);

    // 5. Available events
    List<String> getAvailableEventIds(int schemeProgress, int complexity);

    // 6. Finale rules
    boolean isFinalStageReady(int schemeProgress);

    String getFinalScenarioId();
}