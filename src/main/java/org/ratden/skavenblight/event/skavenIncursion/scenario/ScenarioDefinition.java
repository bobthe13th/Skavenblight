package org.ratden.skavenblight.event.skavenIncursion.scenario;

import org.ratden.skavenblight.event.skavenIncursion.IncursionTargetType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public class ScenarioDefinition {
    private final String id;
    private final ScenarioPattern pattern;
    private final ScenarioGoal goal;
    private final Set<IncursionTargetType> allowedTargetTypes;

    private final int minComplexity;
    private final int maxComplexity;

    private final int baseWeight;
    private final long cooldownTicks;

    private final boolean canRepeat;
    private final boolean canRunWithoutScheme;
    private final boolean requiresActiveNexus;

    public ScenarioDefinition(
            String id,
            ScenarioPattern pattern,
            ScenarioGoal goal,
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

    public String getId() {
        return id;
    }

    public ScenarioPattern getPattern() {
        return pattern;
    }

    public ScenarioGoal getGoal() {
        return goal;
    }

    public Set<IncursionTargetType> getAllowedTargetTypes() {
        return allowedTargetTypes;
    }

    public boolean allowsTargetType(IncursionTargetType targetType) {
        return allowedTargetTypes.contains(targetType);
    }

    public int getMinComplexity() {
        return minComplexity;
    }

    public int getMaxComplexity() {
        return maxComplexity;
    }

    public boolean hasMaxComplexity() {
        return maxComplexity >= 0;
    }

    public boolean isComplexityAllowed(int complexity) {
        if (complexity < minComplexity) {
            return false;
        }

        if (hasMaxComplexity() && complexity > maxComplexity) {
            return false;
        }

        return true;
    }

    public int getBaseWeight() {
        return baseWeight;
    }

    public long getCooldownTicks() {
        return cooldownTicks;
    }

    public boolean canRepeat() {
        return canRepeat;
    }

    public boolean canRunWithoutScheme() {
        return canRunWithoutScheme;
    }

    public boolean requiresActiveNexus() {
        return requiresActiveNexus;
    }
}