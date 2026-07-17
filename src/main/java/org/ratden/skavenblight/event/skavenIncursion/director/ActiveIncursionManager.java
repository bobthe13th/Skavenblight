package org.ratden.skavenblight.event.skavenIncursion.director;

import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.ratden.skavenblight.event.skavenIncursion.scenario.ScenarioGoal;
import org.ratden.skavenblight.event.skavenIncursion.scenario.ScenarioPattern;
import org.ratden.skavenblight.event.skavenIncursion.scenario.SkavenScenario;

import java.util.ArrayList;
import java.util.Collections;
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

    public static List<SkavenScenario> getActiveIncursions() {
        return Collections.unmodifiableList(ACTIVE_INCURSIONS);
    }

    public static int getActiveIncursionCount() {
        return ACTIVE_INCURSIONS.size();
    }

    public static boolean hasActiveIncursions() {
        return !ACTIVE_INCURSIONS.isEmpty();
    }

    public static boolean hasActiveScenarioId(String scenarioId) {
        for (SkavenScenario incursion : ACTIVE_INCURSIONS) {
            if (incursion.getId().equals(scenarioId)) {
                return true;
            }
        }

        return false;
    }

    public static boolean hasActivePattern(ScenarioPattern pattern) {
        for (SkavenScenario incursion : ACTIVE_INCURSIONS) {
            if (incursion.getDefinition().pattern() == pattern) {
                return true;
            }
        }

        return false;
    }

    public static boolean hasActiveGoal(ScenarioGoal goal) {
        for (SkavenScenario incursion : ACTIVE_INCURSIONS) {
            if (incursion.getDefinition().goal() == goal) {
                return true;
            }
        }

        return false;
    }

    public static boolean hasActiveOverlapType(OverlapType overlapType) {
        for (SkavenScenario incursion : ACTIVE_INCURSIONS) {
            if (incursion.getDefinition().overlapType() == overlapType) {
                return true;
            }
        }

        return false;
    }

    public static boolean hasActivePressureProfile(PressureProfile pressureProfile) {
        for (SkavenScenario incursion : ACTIVE_INCURSIONS) {
            if (incursion.getDefinition().pressureProfile() == pressureProfile) {
                return true;
            }
        }

        return false;
    }

    public static int countActiveOverlapType(OverlapType overlapType) {
        int count = 0;

        for (SkavenScenario incursion : ACTIVE_INCURSIONS) {
            if (incursion.getDefinition().overlapType() == overlapType) {
                count++;
            }
        }

        return count;
    }

    public static int countActivePressureProfile(PressureProfile pressureProfile) {
        int count = 0;

        for (SkavenScenario incursion : ACTIVE_INCURSIONS) {
            if (incursion.getDefinition().pressureProfile() == pressureProfile) {
                count++;
            }
        }

        return count;
    }

    public static int getActiveMajorIncursionCount() {
        return countActiveOverlapType(OverlapType.MAJOR);
    }

    public static int getActiveMinorIncursionCount() {
        return countActiveOverlapType(OverlapType.MINOR);
    }

    public static boolean hasActiveCombatPressure() {
        return hasActivePressureProfile(PressureProfile.COMBAT)
                || hasActivePressureProfile(PressureProfile.SET_PIECE);
    }

    public static boolean hasActiveSetPiece() {
        return hasActivePressureProfile(PressureProfile.SET_PIECE)
                || hasActiveOverlapType(OverlapType.EXCLUSIVE);
    }

    private ActiveIncursionManager() {
    }
}