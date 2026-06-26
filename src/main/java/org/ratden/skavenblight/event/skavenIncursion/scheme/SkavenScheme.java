package org.ratden.skavenblight.event.skavenIncursion.scheme;

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

    // 5. Available events
    List<String> getAvailableEventIds(int schemeProgress, int complexity);

    // 6. Finale rules
    boolean isFinalStageReady(int schemeProgress);

    String getFinalScenarioId();
}