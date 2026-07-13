package org.ratden.skavenblight.event.skavenIncursion.scenario;

import org.ratden.skavenblight.event.skavenIncursion.director.IncursionTargetType;
import org.ratden.skavenblight.event.skavenIncursion.director.OverlapType;
import org.ratden.skavenblight.event.skavenIncursion.director.PressureProfile;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public record ScenarioDefinition(String id, ScenarioPattern pattern, ScenarioGoal goal, OverlapType overlapType,
                                 PressureProfile pressureProfile, Set<IncursionTargetType> allowedTargetTypes,
                                 int minComplexity, int maxComplexity, int baseWeight, long cooldownTicks,
                                 boolean canRepeat, boolean canRunWithoutScheme, boolean requiresActiveNexus) {
    public ScenarioDefinition(
            String id,
            ScenarioPattern pattern,
            ScenarioGoal goal,
            OverlapType overlapType,
            PressureProfile pressureProfile,
            Set<IncursionTargetType> allowedTargetTypes,
            int minComplexity,
            int maxComplexity,
            int baseWeight,
            long cooldownTicks,
            boolean canRepeat,
            boolean canRunWithoutScheme,
            boolean requiresActiveNexus
    ) {
        this.id = id;
        this.pattern = pattern;
        this.goal = goal;
        this.overlapType = overlapType;
        this.pressureProfile = pressureProfile;
        this.allowedTargetTypes = Collections.unmodifiableSet(
                EnumSet.copyOf(allowedTargetTypes)
        );
        this.minComplexity = minComplexity;
        this.maxComplexity = maxComplexity;
        this.baseWeight = baseWeight;
        this.cooldownTicks = cooldownTicks;
        this.canRepeat = canRepeat;
        this.canRunWithoutScheme = canRunWithoutScheme;
        this.requiresActiveNexus = requiresActiveNexus;
    }

    public boolean allowsTargetType(IncursionTargetType targetType) {
        return allowedTargetTypes.contains(targetType);
    }

    public boolean hasMaxComplexity() {
        return maxComplexity >= 0;
    }

    public boolean isComplexityAllowed(int complexity) {
        if (complexity < minComplexity) {
            return false;
        }

        return !hasMaxComplexity() || complexity <= maxComplexity;
    }
}