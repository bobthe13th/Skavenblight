package org.ratden.skavenblight.event.skavenIncursion.planning.composition;

import org.ratden.skavenblight.event.skavenIncursion.action.mob.MobModifier;
import org.ratden.skavenblight.event.skavenIncursion.action.mob.MobOrder;
import org.ratden.skavenblight.event.skavenIncursion.planning.budget.complexity.ComplexityOptionCategory;
import org.ratden.skavenblight.event.skavenIncursion.planning.budget.complexity.ComplexityOptionDefinition;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourcePlacementProfileCatalogue;
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
 * Planned composition for one source group in one front and wave.
 *
 * The group owns group-scoped planning data, including its load rules and its
 * optional Pack assignment. Child SourceComposition objects describe the
 * individual source-sized mob packages inside the group.
 *
 * Source-group load is recorded here but is not yet used to split groups.
 * CompositionPlanner will become responsible for creating another group
 * before this group's configured maximum is exceeded.
 */
public class SourceGroupComposition {

    private final UUID sourceGroupCompositionId;
    private final SourceGroupRules sourceGroupRules;
    private final List<SourceComposition> sourceCompositions;

    private PackAssignment packAssignment;

    /**
     * Creates a new source-group composition with standard rules and a fresh
     * structural ID.
     */
    public SourceGroupComposition() {
        this(
                UUID.randomUUID(),
                SourceGroupRules.STANDARD
        );
    }

    /**
     * Creates a new source-group composition with authored rules and a fresh
     * structural ID.
     */
    public SourceGroupComposition(
            SourceGroupRules sourceGroupRules
    ) {
        this(
                UUID.randomUUID(),
                sourceGroupRules
        );
    }

    /**
     * Creates an empty source-group composition using an existing structural ID.
     *
     * This constructor is intended for immutable plan restoration. Child source
     * compositions and the optional Pack assignment must still be added through
     * the normal validated methods.
     */
    public SourceGroupComposition(
            UUID sourceGroupCompositionId,
            SourceGroupRules sourceGroupRules
    ) {
        if (sourceGroupCompositionId == null) {
            throw new IllegalArgumentException(
                    "Source-group composition ID cannot be null."
            );
        }

        if (sourceGroupRules == null) {
            throw new IllegalArgumentException(
                    "Source-group rules cannot be null."
            );
        }

        this.sourceGroupCompositionId =
                sourceGroupCompositionId;

        this.sourceGroupRules =
                sourceGroupRules;

        this.sourceCompositions =
                new ArrayList<>();

        this.packAssignment =
                null;
    }

    public UUID getSourceGroupCompositionId() {
        return sourceGroupCompositionId;
    }

    public SourceGroupRules getSourceGroupRules() {
        return sourceGroupRules;
    }

    public int getMaximumSourceGroupLoad() {
        return sourceGroupRules.maximumLoad();
    }

    /**
     * Adds one source-sized composition to this group.
     *
     * All sources in one group currently require the same SourceRole. Load
     * overflow is intentionally not rejected in this data-model step because
     * the existing CompositionPlanner has not yet been changed to split an
     * overflowing group. Callers can use canFitSourceComposition before
     * adding; the planner will do so in the next implementation step.
     */
    public void addSourceComposition(
            SourceComposition sourceComposition
    ) {
        if (sourceComposition == null) {
            throw new IllegalArgumentException(
                    "Source composition cannot be null."
            );
        }

        if (getSourceComposition(
                sourceComposition.getSourceCompositionId()
        ) != null) {
            throw new IllegalArgumentException(
                    "Source group already contains source composition ID "
                            + sourceComposition.getSourceCompositionId()
                            + "."
            );
        }

        SourceRole existingRole = getSourceRole();

        if (existingRole != null
                && existingRole != sourceComposition.getSourceRole()) {
            throw new IllegalArgumentException(
                    "One source group cannot contain mixed source roles."
            );
        }

        if (sourceComposition.getCalculatedThreatSpent()
                != sourceComposition.getThreatSpent()) {

            throw new IllegalArgumentException(
                    "Source composition "
                            + sourceComposition.getSourceCompositionId()
                            + " records "
                            + sourceComposition.getThreatSpent()
                            + " threat spent, but its mob entries represent "
                            + sourceComposition.getCalculatedThreatSpent()
                            + " threat."
            );
        }

        if (sourceComposition.getSourceGroupLoadCost()
                > getMaximumSourceGroupLoad()) {
            throw new IllegalArgumentException(
                    "Source composition "
                            + sourceComposition.getSourceCompositionId()
                            + " has group load "
                            + sourceComposition.getSourceGroupLoadCost()
                            + ", exceeding the group's maximum load of "
                            + getMaximumSourceGroupLoad()
                            + "."
            );
        }

        sourceCompositions.add(sourceComposition);
    }

    public List<SourceComposition> getSourceCompositions() {
        return Collections.unmodifiableList(sourceCompositions);
    }

    public SourceComposition getSourceComposition(
            UUID sourceCompositionId
    ) {
        if (sourceCompositionId == null) {
            return null;
        }

        for (SourceComposition sourceComposition
                : sourceCompositions) {
            if (sourceComposition
                    .getSourceCompositionId()
                    .equals(sourceCompositionId)) {
                return sourceComposition;
            }
        }

        return null;
    }

    /**
     * Returns the common role used by this group, or null while it is empty.
     */
    public SourceRole getSourceRole() {
        if (sourceCompositions.isEmpty()) {
            return null;
        }

        return sourceCompositions
                .getFirst()
                .getSourceRole();
    }

    public boolean isEmpty() {
        return sourceCompositions.isEmpty();
    }

    public int getSourceCount() {
        return sourceCompositions.size();
    }

    public int getTotalThreatSpent() {
        int total = 0;

        for (SourceComposition sourceComposition
                : sourceCompositions) {
            total += sourceComposition.getThreatSpent();
        }

        return total;
    }

    public int getTotalComplexitySpent() {
        int total = 0;

        for (SourceComposition sourceComposition
                : sourceCompositions) {
            total += sourceComposition.getComplexitySpent();
        }

        return total;
    }

    public int getTotalCapacityUsed() {
        int total = 0;

        for (SourceComposition sourceComposition
                : sourceCompositions) {
            total += sourceComposition.getUsedCapacityUnits();
        }

        return total;
    }

    public int getTotalSourceGroupLoad() {
        int total = 0;

        for (SourceComposition sourceComposition
                : sourceCompositions) {
            total += sourceComposition.getSourceGroupLoadCost();
        }

        return total;
    }

    public int getRemainingSourceGroupLoad() {
        return sourceGroupRules.getRemainingLoad(
                getTotalSourceGroupLoad()
        );
    }

    public boolean canFitSourceComposition(
            SourceComposition sourceComposition
    ) {
        if (sourceComposition == null) {
            return false;
        }

        SourceRole existingRole = getSourceRole();

        if (existingRole != null
                && existingRole != sourceComposition.getSourceRole()) {
            return false;
        }

        return sourceGroupRules.canFit(
                getTotalSourceGroupLoad(),
                sourceComposition.getSourceGroupLoadCost()
        );
    }

    public boolean isWithinSourceGroupLoadLimit() {
        return getTotalSourceGroupLoad()
                <= getMaximumSourceGroupLoad();
    }

    public boolean hasPackAssignment() {
        return packAssignment != null;
    }

    public PackAssignment getPackAssignment() {
        return packAssignment;
    }

    public UUID getPackId() {
        return packAssignment == null
                ? null
                : packAssignment.packId();
    }

    /**
     * Creates the one Pack permitted within this source-group composition.
     *
     * The Pack remains group-owned, while the supplied source-composition and
     * attached-mob IDs identify the particular source queue and promoted mob
     * that contain its leader.
     */
    public PackAssignment assignPack(
            UUID packId,
            UUID sourceCompositionId,
            UUID attachedMobAssignmentId
    ) {
        if (packId == null) {
            throw new IllegalArgumentException(
                    "Pack ID cannot be null."
            );
        }

        SourceComposition sourceComposition =
                getSourceComposition(
                        sourceCompositionId
                );

        if (sourceComposition == null) {
            throw new IllegalArgumentException(
                    "Pack source composition does not belong to source group "
                            + sourceGroupCompositionId
                            + "."
            );
        }

        AttachedMobAssignment attachedMobAssignment =
                sourceComposition.getAttachedMobAssignment(
                        attachedMobAssignmentId
                );

        if (attachedMobAssignment == null) {
            throw new IllegalArgumentException(
                    "Pack leader attachment does not belong to source "
                            + "composition "
                            + sourceCompositionId
                            + "."
            );
        }

        PackAssignment requestedAssignment =
                new PackAssignment(
                        packId,
                        sourceCompositionId,
                        attachedMobAssignmentId
                );

        if (packAssignment != null) {
            if (packAssignment.equals(requestedAssignment)) {
                return packAssignment;
            }

            throw new IllegalStateException(
                    "Source-group composition "
                            + sourceGroupCompositionId
                            + " already has a different Pack assignment."
            );
        }

        packAssignment = requestedAssignment;

        return packAssignment;
    }

    /**
     * Composition package intended for one eventual physical source.
     *
     * It records what should spawn from the source and what kind of source is
     * required, but it contains no world position.
     *
     * Attached mob assignments promote particular already-budgeted mobs.
     * They do not add another mob, change threat spent or consume additional
     * source capacity.
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
        private final List<AttachedMobAssignment> attachedMobAssignments;

        /**
         * Creates a new source-sized composition with a fresh structural ID.
         */
        public SourceComposition(
                SourceType requiredSourceType,
                SourceSize requiredSourceSize,
                SourceRole sourceRole,
                int threatSpent
        ) {
            this(
                    UUID.randomUUID(),
                    requiredSourceType,
                    requiredSourceSize,
                    sourceRole,
                    threatSpent
            );
        }

        /**
         * Creates an empty source-sized composition using an existing structural ID.
         *
         * This constructor is intended for immutable plan restoration. Mobs,
         * modifiers, orders and attached assignments must still be added through the
         * normal validated methods.
         */
        public SourceComposition(
                UUID sourceCompositionId,
                SourceType requiredSourceType,
                SourceSize requiredSourceSize,
                SourceRole sourceRole,
                int threatSpent
        ) {
            if (sourceCompositionId == null) {
                throw new IllegalArgumentException(
                        "Source composition ID cannot be null."
                );
            }

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

            this.sourceCompositionId =
                    sourceCompositionId;

            this.requiredSourceType =
                    requiredSourceType;

            this.requiredSourceSize =
                    requiredSourceSize;

            this.sourceRole =
                    sourceRole;

            this.threatSpent =
                    threatSpent;

            this.mobEntries =
                    new ArrayList<>();

            this.modifiers =
                    EnumSet.noneOf(
                            MobModifier.class
                    );

            this.orders =
                    EnumSet.noneOf(
                            MobOrder.class
                    );

            this.attachedMobAssignments =
                    new ArrayList<>();
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

        public int getComplexitySpent() {
            int total = 0;

            for (AttachedMobAssignment assignment
                    : attachedMobAssignments) {
                total += assignment
                        .complexityOption()
                        .complexityCost();
            }

            return total;
        }

        public int getSourceGroupLoadCost() {
            return SourcePlacementProfileCatalogue
                    .require(
                            requiredSourceType,
                            requiredSourceSize
                    )
                    .sourceGroupLoadCost();
        }

        /**
         * Compatibility bridge for immutable plan snapshots created before
         * represented threat was stored per mob entry.
         *
         * New planning code must use the five-argument overload and provide
         * the exact represented threat directly. This overload exists only
         * until the composition snapshot schema is upgraded in the next step.
         */
        @Deprecated
        public void addMob(
                String mobId,
                int count,
                int capacityCostPerMob,
                SourceSize minimumSourceSize
        ) {
            IncursionMobDefinition mobDefinition =
                    IncursionMobCatalogue.getById(
                            mobId
                    );

            if (mobDefinition == null) {
                throw new IllegalArgumentException(
                        "Cannot restore legacy mob entry '"
                                + mobId
                                + "' because no current incursion mob "
                                + "definition exists for that ID."
                );
            }

            addMob(
                    mobId,
                    count,
                    mobDefinition.getThreatCost(),
                    capacityCostPerMob,
                    minimumSourceSize
            );
        }

        /**
         * Adds one already-purchased mob entry with its exact represented
         * threat.
         *
         * Represented threat is copied from the admitted plan rather than
         * recalculated later by runtime. Future compression or authored
         * substitution may therefore assign a value that differs from the
         * mob catalogue's ordinary cost.
         */
        public void addMob(
                String mobId,
                int count,
                int representedThreatPerMob,
                int capacityCostPerMob,
                SourceSize minimumSourceSize
        ) {
            if (mobId == null
                    || mobId.isBlank()) {

                throw new IllegalArgumentException(
                        "Mob ID cannot be blank."
                );
            }

            if (count <= 0) {
                throw new IllegalArgumentException(
                        "Mob count must be greater than zero."
                );
            }

            if (representedThreatPerMob <= 0) {
                throw new IllegalArgumentException(
                        "Represented threat per mob must be greater than zero."
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

            if (!requiredSourceSize.canFit(
                    minimumSourceSize
            )) {
                throw new IllegalArgumentException(
                        "Required source size "
                                + requiredSourceSize
                                + " cannot fit a mob requiring "
                                + minimumSourceSize
                                + "."
                );
            }

            int addedThreat =
                    Math.multiplyExact(
                            count,
                            representedThreatPerMob
                    );

            int resultingThreat =
                    Math.addExact(
                            getCalculatedThreatSpent(),
                            addedThreat
                    );

            if (resultingThreat > threatSpent) {
                throw new IllegalArgumentException(
                        "Mob entry would exceed the source composition's "
                                + "planned threat expenditure."
                );
            }

            int addedCapacity =
                    Math.multiplyExact(
                            count,
                            capacityCostPerMob
                    );

            int resultingCapacity =
                    Math.addExact(
                            getUsedCapacityUnits(),
                            addedCapacity
                    );

            if (resultingCapacity
                    > requiredSourceSize.getCapacityUnits()) {

                throw new IllegalArgumentException(
                        "Mob entry would exceed the planned source capacity."
                );
            }

            mobEntries.add(
                    new MobEntry(
                            mobId,
                            count,
                            representedThreatPerMob,
                            capacityCostPerMob,
                            minimumSourceSize
                    )
            );
        }

        public List<MobEntry> getMobEntries() {
            return Collections.unmodifiableList(mobEntries);
        }

        public int getMobCount(
                String mobId
        ) {
            if (mobId == null || mobId.isBlank()) {
                return 0;
            }

            int total = 0;

            for (MobEntry mobEntry : mobEntries) {
                if (mobId.equals(mobEntry.getMobId())) {
                    total += mobEntry.getCount();
                }
            }

            return total;
        }

        public int getTotalMobCount() {
            int total = 0;

            for (MobEntry mobEntry : mobEntries) {
                total += mobEntry.getCount();
            }

            return total;
        }

        /**
         * Calculates the exact represented threat contained in the immutable
         * mob entries.
         */
        public int getCalculatedThreatSpent() {
            int total =
                    0;

            for (MobEntry mobEntry
                    : mobEntries) {

                total =
                        Math.addExact(
                                total,
                                mobEntry.getTotalRepresentedThreat()
                        );
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

        public void addModifier(
                MobModifier modifier
        ) {
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

        public void addOrder(
                MobOrder order
        ) {
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

        /**
         * Promotes one already-budgeted mob into a distinct attached planned
         * assignment using a fresh attachment ID.
         *
         * The assignment reserves one unit from the existing mob count. It does not
         * alter threat spent, total mob count or source capacity.
         */
        public AttachedMobAssignment addAttachedMobAssignment(
                String mobId,
                ComplexityOptionDefinition complexityOption,
                AttachedMobSpawnPriority spawnPriority
        ) {
            return addAttachedMobAssignment(
                    UUID.randomUUID(),
                    mobId,
                    complexityOption,
                    spawnPriority
            );
        }

        /**
         * Restores one attached planned assignment using its existing stable ID.
         *
         * The same validation used by ordinary planning remains in force: the
         * promoted mob must already exist in the threat-budgeted composition and one
         * unreserved instance must remain.
         */
        public AttachedMobAssignment addAttachedMobAssignment(
                UUID attachedMobAssignmentId,
                String mobId,
                ComplexityOptionDefinition complexityOption,
                AttachedMobSpawnPriority spawnPriority
        ) {
            if (attachedMobAssignmentId == null) {
                throw new IllegalArgumentException(
                        "Attached mob assignment ID cannot be null."
                );
            }

            if (mobId == null
                    || mobId.isBlank()) {

                throw new IllegalArgumentException(
                        "Attached mob ID cannot be blank."
                );
            }

            if (complexityOption == null) {
                throw new IllegalArgumentException(
                        "Attached complexity option cannot be null."
                );
            }

            if (complexityOption.category()
                    != ComplexityOptionCategory.ATTACHED) {

                throw new IllegalArgumentException(
                        "Attached mob assignments require an ATTACHED "
                                + "complexity option."
                );
            }

            if (spawnPriority == null) {
                throw new IllegalArgumentException(
                        "Attached mob spawn priority cannot be null."
                );
            }

            if (getAttachedMobAssignment(
                    attachedMobAssignmentId
            ) != null) {

                throw new IllegalArgumentException(
                        "Source composition "
                                + sourceCompositionId
                                + " already contains attached mob assignment ID "
                                + attachedMobAssignmentId
                                + "."
                );
            }

            int plannedMobCount =
                    getMobCount(
                            mobId
                    );

            int reservedMobCount =
                    getReservedAttachedMobCount(
                            mobId
                    );

            if (plannedMobCount <= reservedMobCount) {
                throw new IllegalArgumentException(
                        "No unreserved "
                                + mobId
                                + " remains in this source composition for another "
                                + "attached assignment."
                );
            }

            AttachedMobAssignment assignment =
                    new AttachedMobAssignment(
                            attachedMobAssignmentId,
                            mobId,
                            complexityOption,
                            spawnPriority
                    );

            attachedMobAssignments.add(
                    assignment
            );

            return assignment;
        }

        public List<AttachedMobAssignment>
        getAttachedMobAssignments() {
            return Collections.unmodifiableList(
                    attachedMobAssignments
            );
        }

        public AttachedMobAssignment getAttachedMobAssignment(
                UUID attachedMobAssignmentId
        ) {
            if (attachedMobAssignmentId == null) {
                return null;
            }

            for (AttachedMobAssignment assignment
                    : attachedMobAssignments) {
                if (assignment
                        .attachedMobAssignmentId()
                        .equals(attachedMobAssignmentId)) {
                    return assignment;
                }
            }

            return null;
        }

        public int getReservedAttachedMobCount(
                String mobId
        ) {
            if (mobId == null || mobId.isBlank()) {
                return 0;
            }

            int total = 0;

            for (AttachedMobAssignment assignment
                    : attachedMobAssignments) {
                if (mobId.equals(assignment.mobId())) {
                    total++;
                }
            }

            return total;
        }

        public int getTotalReservedAttachedMobCount() {
            return attachedMobAssignments.size();
        }

        public int getOrdinaryMobCount(
                String mobId
        ) {
            return getMobCount(mobId)
                    - getReservedAttachedMobCount(mobId);
        }

        public boolean hasAttachedMobAssignments() {
            return !attachedMobAssignments.isEmpty();
        }

        public boolean isEmpty() {
            return mobEntries.isEmpty();
        }
    }

    /**
     * One purchased mob type and count within a source-sized composition.
     *
     * representedThreatPerMob records how much of the admitted plan each
     * individual mob represents. It is not recalculated from the live mob
     * catalogue during runtime.
     */
    public static class MobEntry {

        private final String mobId;
        private final int count;
        private final int representedThreatPerMob;
        private final int capacityCostPerMob;
        private final SourceSize minimumSourceSize;

        public MobEntry(
                String mobId,
                int count,
                int representedThreatPerMob,
                int capacityCostPerMob,
                SourceSize minimumSourceSize
        ) {
            if (mobId == null
                    || mobId.isBlank()) {

                throw new IllegalArgumentException(
                        "Mob entry ID cannot be blank."
                );
            }

            if (count <= 0) {
                throw new IllegalArgumentException(
                        "Mob entry count must be greater than zero."
                );
            }

            if (representedThreatPerMob <= 0) {
                throw new IllegalArgumentException(
                        "Mob entry represented threat must be greater than "
                                + "zero."
                );
            }

            if (capacityCostPerMob <= 0) {
                throw new IllegalArgumentException(
                        "Mob entry capacity cost must be greater than zero."
                );
            }

            if (minimumSourceSize == null) {
                throw new IllegalArgumentException(
                        "Mob entry minimum source size cannot be null."
                );
            }

            this.mobId =
                    mobId;

            this.count =
                    count;

            this.representedThreatPerMob =
                    representedThreatPerMob;

            this.capacityCostPerMob =
                    capacityCostPerMob;

            this.minimumSourceSize =
                    minimumSourceSize;
        }

        public String getMobId() {
            return mobId;
        }

        public int getCount() {
            return count;
        }

        public int getRepresentedThreatPerMob() {
            return representedThreatPerMob;
        }

        public int getTotalRepresentedThreat() {
            return Math.multiplyExact(
                    count,
                    representedThreatPerMob
            );
        }

        public int getCapacityCostPerMob() {
            return capacityCostPerMob;
        }

        public int getTotalCapacityCost() {
            return Math.multiplyExact(
                    count,
                    capacityCostPerMob
            );
        }

        public SourceSize getMinimumSourceSize() {
            return minimumSourceSize;
        }
    }

    /**
     * Spawn-order relationship between an attached promoted mob and the
     * ordinary queue copied from the same source composition.
     */
    public enum AttachedMobSpawnPriority {
        BEFORE_ORDINARY,
        WITH_ORDINARY,
        AFTER_ORDINARY
    }

    /**
     * Planned identity for one promoted mob inside a source composition.
     *
     * The assignment reserves exactly one mob matching mobId from the
     * composition's existing mob entries. Its stable ID can later be mapped
     * to the UUID of the successfully spawned entity.
     */
    public record AttachedMobAssignment(
            UUID attachedMobAssignmentId,
            String mobId,
            ComplexityOptionDefinition complexityOption,
            AttachedMobSpawnPriority spawnPriority
    ) {
        public AttachedMobAssignment {
            if (attachedMobAssignmentId == null) {
                throw new IllegalArgumentException(
                        "Attached mob assignment ID cannot be null."
                );
            }

            if (mobId == null || mobId.isBlank()) {
                throw new IllegalArgumentException(
                        "Attached mob ID cannot be blank."
                );
            }

            if (complexityOption == null) {
                throw new IllegalArgumentException(
                        "Attached complexity option cannot be null."
                );
            }

            if (complexityOption.category()
                    != ComplexityOptionCategory.ATTACHED) {
                throw new IllegalArgumentException(
                        "Attached mob assignment requires an ATTACHED "
                                + "complexity option."
                );
            }

            if (spawnPriority == null) {
                throw new IllegalArgumentException(
                        "Attached mob spawn priority cannot be null."
                );
            }
        }

        public int reservedMobCount() {
            return 1;
        }
    }

    /**
     * The one Pack permitted within a source-group composition.
     *
     * Pack ownership is group-scoped. The referenced child source composition
     * and attached assignment identify where the promoted Pack leader is
     * queued.
     */
    public record PackAssignment(
            UUID packId,
            UUID sourceCompositionId,
            UUID attachedMobAssignmentId
    ) {
        public PackAssignment {
            if (packId == null) {
                throw new IllegalArgumentException(
                        "Pack ID cannot be null."
                );
            }

            if (sourceCompositionId == null) {
                throw new IllegalArgumentException(
                        "Pack source composition ID cannot be null."
                );
            }

            if (attachedMobAssignmentId == null) {
                throw new IllegalArgumentException(
                        "Pack leader attachment ID cannot be null."
                );
            }
        }
    }
}