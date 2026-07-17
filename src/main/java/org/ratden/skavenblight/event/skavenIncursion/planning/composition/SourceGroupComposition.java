package org.ratden.skavenblight.event.skavenIncursion.planning.composition;

import org.ratden.skavenblight.event.skavenIncursion.action.mob.MobModifier;
import org.ratden.skavenblight.event.skavenIncursion.action.mob.MobOrder;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceRole;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceSize;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Planned composition for one source group.
 *
 * This object describes what the group contains before any physical sources
 * are placed in the world.
 *
 * Group-level leadership and complexity information will be added later.
 */
public class SourceGroupComposition {

    private final UUID sourceGroupCompositionId;
    private final List<SourceComposition> sourceCompositions;

    public SourceGroupComposition() {
        this.sourceGroupCompositionId = UUID.randomUUID();
        this.sourceCompositions = new ArrayList<>();
    }

    public UUID getSourceGroupCompositionId() {
        return sourceGroupCompositionId;
    }

    public void addSourceComposition(SourceComposition sourceComposition) {
        if (sourceComposition == null) {
            throw new IllegalArgumentException(
                    "Source composition cannot be null."
            );
        }

        sourceCompositions.add(sourceComposition);
    }

    public List<SourceComposition> getSourceCompositions() {
        return Collections.unmodifiableList(sourceCompositions);
    }

    public boolean isEmpty() {
        return sourceCompositions.isEmpty();
    }

    public int getSourceCount() {
        return sourceCompositions.size();
    }

    public int getTotalThreatSpent() {
        int total = 0;

        for (SourceComposition sourceComposition : sourceCompositions) {
            total += sourceComposition.getThreatSpent();
        }

        return total;
    }

    public int getTotalCapacityUsed() {
        int total = 0;

        for (SourceComposition sourceComposition : sourceCompositions) {
            total += sourceComposition.getUsedCapacityUnits();
        }

        return total;
    }

    /**
     * Composition package intended for one eventual physical source.
     *
     * It records what should spawn from the source and what kind of source is
     * required, but it contains no world position.
     */
    public static class SourceComposition {

        private final UUID sourceCompositionId;

        private final SourceType requiredSourceType;
        private final SourceSize requiredSourceSize;
        private final SourceRole sourceRole;

        private final int threatSpent;

        private final List<MobEntry> mobEntries;
        private final Set<MobModifier> modifiers;
        private final Set<MobOrder> orders;

        public SourceComposition(
                SourceType requiredSourceType,
                SourceSize requiredSourceSize,
                SourceRole sourceRole,
                int threatSpent
        ) {
            if (requiredSourceType == null) {
                throw new IllegalArgumentException(
                        "Required source type cannot be null."
                );
            }

            if (requiredSourceSize == null) {
                throw new IllegalArgumentException(
                        "Required source size cannot be null."
                );
            }

            if (sourceRole == null) {
                throw new IllegalArgumentException(
                        "Source role cannot be null."
                );
            }

            if (threatSpent < 0) {
                throw new IllegalArgumentException(
                        "Threat spent cannot be negative."
                );
            }

            this.sourceCompositionId = UUID.randomUUID();
            this.requiredSourceType = requiredSourceType;
            this.requiredSourceSize = requiredSourceSize;
            this.sourceRole = sourceRole;
            this.threatSpent = threatSpent;

            this.mobEntries = new ArrayList<>();
            this.modifiers = EnumSet.noneOf(MobModifier.class);
            this.orders = EnumSet.noneOf(MobOrder.class);
        }

        public UUID getSourceCompositionId() {
            return sourceCompositionId;
        }

        public SourceType getRequiredSourceType() {
            return requiredSourceType;
        }

        public SourceSize getRequiredSourceSize() {
            return requiredSourceSize;
        }

        public SourceRole getSourceRole() {
            return sourceRole;
        }

        public int getThreatSpent() {
            return threatSpent;
        }

        public void addMob(
                String mobId,
                int count,
                int capacityCostPerMob,
                SourceSize minimumSourceSize
        ) {
            if (mobId == null || mobId.isBlank()) {
                throw new IllegalArgumentException(
                        "Mob ID cannot be blank."
                );
            }

            if (count <= 0) {
                throw new IllegalArgumentException(
                        "Mob count must be greater than zero."
                );
            }

            if (capacityCostPerMob <= 0) {
                throw new IllegalArgumentException(
                        "Capacity cost per mob must be greater than zero."
                );
            }

            if (minimumSourceSize == null) {
                throw new IllegalArgumentException(
                        "Minimum source size cannot be null."
                );
            }

            if (!requiredSourceSize.canFit(minimumSourceSize)) {
                throw new IllegalArgumentException(
                        "Required source size "
                                + requiredSourceSize
                                + " cannot fit a mob requiring "
                                + minimumSourceSize
                                + "."
                );
            }

            int addedCapacity = count * capacityCostPerMob;

            if (getUsedCapacityUnits() + addedCapacity
                    > requiredSourceSize.getCapacityUnits()) {
                throw new IllegalArgumentException(
                        "Mob entry would exceed the planned source capacity."
                );
            }

            mobEntries.add(new MobEntry(
                    mobId,
                    count,
                    capacityCostPerMob,
                    minimumSourceSize
            ));
        }

        public List<MobEntry> getMobEntries() {
            return Collections.unmodifiableList(mobEntries);
        }

        public int getTotalMobCount() {
            int total = 0;

            for (MobEntry mobEntry : mobEntries) {
                total += mobEntry.getCount();
            }

            return total;
        }

        public int getUsedCapacityUnits() {
            int total = 0;

            for (MobEntry mobEntry : mobEntries) {
                total += mobEntry.getTotalCapacityCost();
            }

            return total;
        }

        public int getRemainingCapacityUnits() {
            return requiredSourceSize.getCapacityUnits()
                    - getUsedCapacityUnits();
        }

        public void addModifier(MobModifier modifier) {
            if (modifier == null) {
                throw new IllegalArgumentException(
                        "Mob modifier cannot be null."
                );
            }

            modifiers.add(modifier);
        }

        public Set<MobModifier> getModifiers() {
            return Collections.unmodifiableSet(modifiers);
        }

        public void addOrder(MobOrder order) {
            if (order == null) {
                throw new IllegalArgumentException(
                        "Mob order cannot be null."
                );
            }

            orders.add(order);
        }

        public Set<MobOrder> getOrders() {
            return Collections.unmodifiableSet(orders);
        }

        public boolean isEmpty() {
            return mobEntries.isEmpty();
        }
    }

    /**
     * One mob type and count within a source-sized composition package.
     */
    public static class MobEntry {

        private final String mobId;
        private final int count;
        private final int capacityCostPerMob;
        private final SourceSize minimumSourceSize;

        public MobEntry(
                String mobId,
                int count,
                int capacityCostPerMob,
                SourceSize minimumSourceSize
        ) {
            this.mobId = mobId;
            this.count = count;
            this.capacityCostPerMob = capacityCostPerMob;
            this.minimumSourceSize = minimumSourceSize;
        }

        public String getMobId() {
            return mobId;
        }

        public int getCount() {
            return count;
        }

        public int getCapacityCostPerMob() {
            return capacityCostPerMob;
        }

        public int getTotalCapacityCost() {
            return count * capacityCostPerMob;
        }

        public SourceSize getMinimumSourceSize() {
            return minimumSourceSize;
        }
    }
}