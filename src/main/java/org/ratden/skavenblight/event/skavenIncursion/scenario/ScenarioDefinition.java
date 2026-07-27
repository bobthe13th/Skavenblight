package org.ratden.skavenblight.event.skavenIncursion.scenario;

import org.ratden.skavenblight.event.skavenIncursion.director.IncursionTargetType;
import org.ratden.skavenblight.event.skavenIncursion.director.OverlapType;
import org.ratden.skavenblight.event.skavenIncursion.director.PressureProfile;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.AttachedMobComplexityOption;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.IncursionMobDefinition;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceDistanceProfile;
import org.ratden.skavenblight.event.skavenIncursion.planning.stratagem.StratagemDefinition;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Authoritative shared metadata for one Scenario.
 *
 * A Scenario determines which content and Stratagems are legal and under
 * what broad conditions it may run.
 *
 * The source-distance profile defines the Scenario's baseline tactical
 * distance identity. A future Stratagem rule may modify that baseline, but
 * planning entry points must not independently invent or hardcode a profile.
 *
 * Planning and execution remain the responsibility of the planning pipeline
 * and the runtime Scenario respectively.
 */
public record ScenarioDefinition(
        String id,
        ScenarioPattern pattern,
        SourceDistanceProfile sourceDistanceProfile,
        ScenarioGoal goal,
        OverlapType overlapType,
        PressureProfile pressureProfile,
        Set<IncursionTargetType> allowedTargetTypes,
        List<MobRosterEntry> mobRoster,
        List<StratagemDefinition> allowedStratagems,
        List<AttachedMobComplexityOption>
        allowedAttachedMobComplexityOptions,
        int minComplexity,
        int maxComplexity,
        int baseWeight,
        long cooldownTicks,
        boolean canRepeat,
        boolean canRunWithoutScheme,
        boolean requiresActiveNexus
) {

    /**
     * Compatibility constructor for Scenarios that do not yet permit any
     * attached-mob complexity options.
     */
    public ScenarioDefinition(
            String id,
            ScenarioPattern pattern,
            SourceDistanceProfile sourceDistanceProfile,
            ScenarioGoal goal,
            OverlapType overlapType,
            PressureProfile pressureProfile,
            Set<IncursionTargetType> allowedTargetTypes,
            List<MobRosterEntry> mobRoster,
            List<StratagemDefinition> allowedStratagems,
            int minComplexity,
            int maxComplexity,
            int baseWeight,
            long cooldownTicks,
            boolean canRepeat,
            boolean canRunWithoutScheme,
            boolean requiresActiveNexus
    ) {
        this(
                id,
                pattern,
                sourceDistanceProfile,
                goal,
                overlapType,
                pressureProfile,
                allowedTargetTypes,
                mobRoster,
                allowedStratagems,
                List.of(),
                minComplexity,
                maxComplexity,
                baseWeight,
                cooldownTicks,
                canRepeat,
                canRunWithoutScheme,
                requiresActiveNexus
        );
    }

    public ScenarioDefinition(
            String id,
            ScenarioPattern pattern,
            SourceDistanceProfile sourceDistanceProfile,
            ScenarioGoal goal,
            OverlapType overlapType,
            PressureProfile pressureProfile,
            Set<IncursionTargetType> allowedTargetTypes,
            List<MobRosterEntry> mobRoster,
            List<StratagemDefinition> allowedStratagems,
            List<AttachedMobComplexityOption>
                    allowedAttachedMobComplexityOptions,
            int minComplexity,
            int maxComplexity,
            int baseWeight,
            long cooldownTicks,
            boolean canRepeat,
            boolean canRunWithoutScheme,
            boolean requiresActiveNexus
    ) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                    "Scenario ID cannot be blank."
            );
        }

        if (pattern == null) {
            throw new IllegalArgumentException(
                    "Scenario pattern cannot be null."
            );
        }

        if (sourceDistanceProfile == null) {
            throw new IllegalArgumentException(
                    "Scenario source-distance profile cannot be null."
            );
        }

        if (goal == null) {
            throw new IllegalArgumentException(
                    "Scenario goal cannot be null."
            );
        }

        if (overlapType == null) {
            throw new IllegalArgumentException(
                    "Scenario overlap type cannot be null."
            );
        }

        if (pressureProfile == null) {
            throw new IllegalArgumentException(
                    "Scenario pressure profile cannot be null."
            );
        }

        if (allowedTargetTypes == null
                || allowedTargetTypes.isEmpty()) {
            throw new IllegalArgumentException(
                    "Scenario must allow at least one target type."
            );
        }

        if (mobRoster == null || mobRoster.isEmpty()) {
            throw new IllegalArgumentException(
                    "Scenario must contain at least one mob roster entry."
            );
        }

        validateMobRoster(
                mobRoster
        );

        if (allowedStratagems == null
                || allowedStratagems.isEmpty()) {
            throw new IllegalArgumentException(
                    "Scenario must allow at least one Stratagem."
            );
        }

        validateAllowedStratagems(
                allowedStratagems
        );

        if (allowedAttachedMobComplexityOptions == null) {
            throw new IllegalArgumentException(
                    "Allowed attached complexity options cannot be null."
            );
        }

        validateAllowedAttachedMobComplexityOptions(
                allowedAttachedMobComplexityOptions
        );

        if (minComplexity < 0) {
            throw new IllegalArgumentException(
                    "Minimum complexity cannot be negative."
            );
        }

        if (maxComplexity >= 0
                && maxComplexity < minComplexity) {
            throw new IllegalArgumentException(
                    "Maximum complexity cannot be below minimum complexity."
            );
        }

        if (baseWeight < 0) {
            throw new IllegalArgumentException(
                    "Scenario base weight cannot be negative."
            );
        }

        if (cooldownTicks < 0L) {
            throw new IllegalArgumentException(
                    "Scenario cooldown cannot be negative."
            );
        }

        this.id =
                id;

        this.pattern =
                pattern;

        this.sourceDistanceProfile =
                sourceDistanceProfile;

        this.goal =
                goal;

        this.overlapType =
                overlapType;

        this.pressureProfile =
                pressureProfile;

        this.allowedTargetTypes =
                Collections.unmodifiableSet(
                        EnumSet.copyOf(
                                allowedTargetTypes
                        )
                );

        this.mobRoster =
                List.copyOf(
                        mobRoster
                );

        this.allowedStratagems =
                List.copyOf(
                        allowedStratagems
                );

        this.allowedAttachedMobComplexityOptions =
                List.copyOf(
                        allowedAttachedMobComplexityOptions
                );

        this.minComplexity =
                minComplexity;

        this.maxComplexity =
                maxComplexity;

        this.baseWeight =
                baseWeight;

        this.cooldownTicks =
                cooldownTicks;

        this.canRepeat =
                canRepeat;

        this.canRunWithoutScheme =
                canRunWithoutScheme;

        this.requiresActiveNexus =
                requiresActiveNexus;
    }

    public boolean allowsTargetType(
            IncursionTargetType targetType
    ) {
        return targetType != null
                && allowedTargetTypes.contains(
                targetType
        );
    }

    public boolean allowsStratagem(
            StratagemDefinition stratagemDefinition
    ) {
        if (stratagemDefinition == null) {
            return false;
        }

        return allowsStratagemId(
                stratagemDefinition.getId()
        );
    }

    public boolean allowsStratagemId(
            String stratagemId
    ) {
        if (stratagemId == null
                || stratagemId.isBlank()) {
            return false;
        }

        for (StratagemDefinition allowedStratagem
                : allowedStratagems) {

            if (allowedStratagem
                    .getId()
                    .equals(
                            stratagemId
                    )) {
                return true;
            }
        }

        return false;
    }

    public boolean allowsAttachedMobComplexityOption(
            AttachedMobComplexityOption complexityOption
    ) {
        if (complexityOption == null) {
            return false;
        }

        return allowsAttachedMobComplexityOptionId(
                complexityOption.getId()
        );
    }

    public boolean allowsAttachedMobComplexityOptionId(
            String complexityOptionId
    ) {
        if (complexityOptionId == null
                || complexityOptionId.isBlank()) {
            return false;
        }

        for (AttachedMobComplexityOption allowedOption
                : allowedAttachedMobComplexityOptions) {

            if (allowedOption
                    .getId()
                    .equals(
                            complexityOptionId
                    )) {
                return true;
            }
        }

        return false;
    }

    public boolean hasMaxComplexity() {
        return maxComplexity >= 0;
    }

    public boolean isComplexityAllowed(
            int complexity
    ) {
        if (complexity < minComplexity) {
            return false;
        }

        return !hasMaxComplexity()
                || complexity <= maxComplexity;
    }

    private static void validateMobRoster(
            List<MobRosterEntry> mobRoster
    ) {
        for (int index = 0;
             index < mobRoster.size();
             index++) {

            MobRosterEntry entry =
                    mobRoster.get(
                            index
                    );

            if (entry == null) {
                throw new IllegalArgumentException(
                        "Mob roster entry at index "
                                + index
                                + " cannot be null."
                );
            }

            for (int previousIndex = 0;
                 previousIndex < index;
                 previousIndex++) {

                MobRosterEntry previousEntry =
                        mobRoster.get(
                                previousIndex
                        );

                if (previousEntry
                        .mobDefinition()
                        .getMobId()
                        .equals(
                                entry
                                        .mobDefinition()
                                        .getMobId()
                        )) {
                    throw new IllegalArgumentException(
                            "Scenario mob roster contains duplicate mob ID: "
                                    + entry
                                    .mobDefinition()
                                    .getMobId()
                                    + "."
                    );
                }
            }
        }
    }

    private static void validateAllowedStratagems(
            List<StratagemDefinition> allowedStratagems
    ) {
        for (int index = 0;
             index < allowedStratagems.size();
             index++) {

            StratagemDefinition stratagem =
                    allowedStratagems.get(
                            index
                    );

            if (stratagem == null) {
                throw new IllegalArgumentException(
                        "Allowed Stratagem at index "
                                + index
                                + " cannot be null."
                );
            }

            for (int previousIndex = 0;
                 previousIndex < index;
                 previousIndex++) {

                StratagemDefinition previousStratagem =
                        allowedStratagems.get(
                                previousIndex
                        );

                if (previousStratagem
                        .getId()
                        .equals(
                                stratagem.getId()
                        )) {
                    throw new IllegalArgumentException(
                            "Scenario contains duplicate Stratagem ID: "
                                    + stratagem.getId()
                                    + "."
                    );
                }
            }
        }
    }

    private static void validateAllowedAttachedMobComplexityOptions(
            List<AttachedMobComplexityOption> allowedOptions
    ) {
        for (int index = 0;
             index < allowedOptions.size();
             index++) {

            AttachedMobComplexityOption option =
                    allowedOptions.get(
                            index
                    );

            if (option == null) {
                throw new IllegalArgumentException(
                        "Allowed attached complexity option at index "
                                + index
                                + " cannot be null."
                );
            }

            for (int previousIndex = 0;
                 previousIndex < index;
                 previousIndex++) {

                AttachedMobComplexityOption previousOption =
                        allowedOptions.get(
                                previousIndex
                        );

                if (previousOption
                        .getId()
                        .equals(
                                option.getId()
                        )) {
                    throw new IllegalArgumentException(
                            "Scenario contains duplicate attached complexity "
                                    + "option ID: "
                                    + option.getId()
                                    + "."
                    );
                }
            }
        }
    }

    /**
     * One mob that is legal for this Scenario, paired with its baseline
     * composition-selection weight.
     *
     * A weight of zero keeps the mob legal but prevents ordinary baseline
     * selection until another authored rule raises its effective weight.
     */
    public record MobRosterEntry(
            IncursionMobDefinition mobDefinition,
            double baseWeight
    ) {

        public MobRosterEntry {
            if (mobDefinition == null) {
                throw new IllegalArgumentException(
                        "Roster mob definition cannot be null."
                );
            }

            if (!Double.isFinite(
                    baseWeight
            )) {
                throw new IllegalArgumentException(
                        "Roster mob weight must be finite."
                );
            }

            if (baseWeight < 0.0D) {
                throw new IllegalArgumentException(
                        "Roster mob weight cannot be negative."
                );
            }
        }
    }
}