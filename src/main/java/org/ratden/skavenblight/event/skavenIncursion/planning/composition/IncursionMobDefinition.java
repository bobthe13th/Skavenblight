package org.ratden.skavenblight.event.skavenIncursion.planning.composition;

import org.ratden.skavenblight.event.skavenIncursion.planning.budget.IncursionBudgetCosts;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceSize;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Planning definition for one mob type that may be purchased by the
 * CompositionPlanner.
 *
 * This class does not spawn entities or perform composition planning.
 * It only exposes the information needed to:
 *
 * - spend threat;
 * - measure source-capacity use;
 * - calculate roster threat density;
 * - select mobs through authored characteristics;
 * - determine the minimum compatible source size.
 */
public class IncursionMobDefinition {

    private final String mobId;
    private final IncursionBudgetCosts.MobCost cost;
    private final SourceSize minimumSourceSize;

    private final MobSize mobSize;
    private final Set<MobProfile> profiles;
    private final Set<MobBehaviour> behaviours;

    public IncursionMobDefinition(
            String mobId,
            IncursionBudgetCosts.MobCost cost,
            SourceSize minimumSourceSize,
            MobSize mobSize,
            Set<MobProfile> profiles,
            Set<MobBehaviour> behaviours
    ) {
        if (mobId == null || mobId.isBlank()) {
            throw new IllegalArgumentException(
                    "Mob composition ID cannot be blank."
            );
        }

        this.cost = Objects.requireNonNull(
                cost,
                "Mob composition cost cannot be null."
        );

        this.minimumSourceSize = Objects.requireNonNull(
                minimumSourceSize,
                "Minimum source size cannot be null."
        );

        this.mobSize = Objects.requireNonNull(
                mobSize,
                "Mob size cannot be null."
        );

        if (profiles == null) {
            throw new IllegalArgumentException(
                    "Mob profiles cannot be null."
            );
        }

        if (behaviours == null) {
            throw new IllegalArgumentException(
                    "Mob behaviours cannot be null."
            );
        }

        this.mobId = mobId;

        this.profiles = profiles.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(
                EnumSet.copyOf(profiles)
        );

        this.behaviours = behaviours.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(
                EnumSet.copyOf(behaviours)
        );
    }

    public String getMobId() {
        return mobId;
    }

    public IncursionBudgetCosts.MobCost getCost() {
        return cost;
    }

    public int getThreatCost() {
        return cost.threatCost();
    }

    public int getCapacityCost() {
        return cost.capacityCost();
    }

    /**
     * Threat represented by one unit of source capacity for this mob.
     *
     * The ThreatDensityCalculator will combine this value with the mob's
     * effective planning weight to calculate RosterThreatDensity.
     */
    public double getThreatPerCapacity() {
        return cost.threatPerCapacity();
    }

    public SourceSize getMinimumSourceSize() {
        return minimumSourceSize;
    }

    public MobSize getMobSize() {
        return mobSize;
    }

    public Set<MobProfile> getProfiles() {
        return profiles;
    }

    public Set<MobBehaviour> getBehaviours() {
        return behaviours;
    }

    public boolean hasProfile(MobProfile profile) {
        return profile != null && profiles.contains(profile);
    }

    public boolean hasBehaviour(MobBehaviour behaviour) {
        return behaviour != null && behaviours.contains(behaviour);
    }

    /**
     * Broad identity and equipment characteristics.
     *
     * These describe what the mob is rather than the exact action its AI
     * performs during combat.
     */
    public enum MobProfile {
        FAST,
        SLOW,
        ARMOURED,
        FODDER,
        ELITE,
        MAGIC,
        MECHANICAL
    }

    /**
     * Characteristics describing how the mob contributes during combat.
     */
    public enum MobBehaviour {
        MELEE,
        RANGED,
        SIEGE,
        SWARM,
        HUNTER,
        SUPPORT,
        CASTER
    }

    /**
     * Every mob has exactly one planning size.
     *
     * This remains separate from SourceSize: MobSize describes the mob,
     * while SourceSize describes the physical source required to contain it.
     */
    public enum MobSize {
        SMALL,
        MEDIUM,
        LARGE,
        HUGE
    }
}