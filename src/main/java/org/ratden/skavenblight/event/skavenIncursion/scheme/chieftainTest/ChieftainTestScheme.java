package org.ratden.skavenblight.event.skavenIncursion.scheme.chieftainTest;

import org.ratden.skavenblight.event.skavenIncursion.scheme.SkavenScheme;

import java.util.List;

public class ChieftainTestScheme implements SkavenScheme {

    // 1. Identity

    @Override
    public String getId() {
        return "chieftain_test";
    }

    @Override
    public String getDisplayName() {
        return "Warmongering Chieftain (Test)";
    }

    @Override
    public int getSchemeTier() {
        return 0;
    }

    // 2. Progress structure

    @Override
    public int getDefaultDeadlineDays() {
        return 14;
    }

    @Override
    public int getFinalStageThreshold() {
        return 100;
    }

    // 3. Progress rules

    @Override
    public int getPassiveDailyProgress() {
        return 8;
    }

    @Override
    public int getProgressFromSkavenSuccess() {
        return 10;
    }

    @Override
    public int getProgressLostFromSkavenFailure() {
        return 5;
    }

    // 4. Available scenarios

    @Override
    public List<String> getAvailableScenarioIds(int schemeProgress, int complexity) {
        if (schemeProgress >= 100) {
            return List.of(getFinalScenarioId());
        }

        if (schemeProgress >= 70) {
            return List.of(
                    "chieftain_raid_late"
            );
        }

        if (schemeProgress >= 46) {
            return List.of(
                    "chieftain_assault_early",
                    "chieftain_raid_early"
            );
        }

        if (schemeProgress >= 23) {
            return List.of(
                    "wolf_rat_assault",
                    "chieftain_assault_early"
            );
        }

        return List.of(
                "wolf_rat_assault"
        );
    }

    // 5. Available events

    @Override
    public List<String> getAvailableEventIds(int schemeProgress, int complexity) {
        if (schemeProgress >= 70) {
            return List.of(
                    "red_eyes_at_night",
                    "distant_bell"
            );
        }

        return List.of(
                "red_eyes_at_night"
        );
    }

    // 6. Finale rules

    @Override
    public boolean isFinalStageReady(int schemeProgress) {
        return schemeProgress >= getFinalStageThreshold();
    }

    @Override
    public String getFinalScenarioId() {
        return "chieftain_finale";
    }
}