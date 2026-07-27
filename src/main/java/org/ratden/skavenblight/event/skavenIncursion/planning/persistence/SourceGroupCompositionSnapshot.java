package org.ratden.skavenblight.event.skavenIncursion.planning.persistence;

import org.ratden.skavenblight.event.skavenIncursion.action.mob.MobModifier;
import org.ratden.skavenblight.event.skavenIncursion.action.mob.MobOrder;
import org.ratden.skavenblight.event.skavenIncursion.planning.budget.complexity.ComplexityOptionCategory;
import org.ratden.skavenblight.event.skavenIncursion.planning.budget.complexity.ComplexityOptionDefinition;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.SourceGroupComposition;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.SourceGroupRules;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceRole;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceSize;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable persistence-facing snapshot of one SourceGroupComposition.
 *
 * The snapshot preserves:
 *
 * - the source-group composition ID;
 * - source-group load rules;
 * - child source-composition IDs;
 * - mob entries and their capacity information;
 * - modifiers and orders;
 * - attached promoted-mob assignments;
 * - complexity-option definitions;
 * - the optional group-owned Pack assignment.
 *
 * Every UUID is retained during restoration. No new planning identity is
 * generated.
 *
 * This class contains immutable planning data only.
 * SourceGroupCompositionSnapshotNbtCodec serialises it as part of the
 * complete persisted IncursionPlan snapshot tree.
 */
public record SourceGroupCompositionSnapshot(
        UUID sourceGroupCompositionId,
        int maximumLoad,
        List<SourceCompositionSnapshot> sourceCompositionSnapshots,
        PackAssignmentSnapshot packAssignmentSnapshot
) {

    public SourceGroupCompositionSnapshot {
        if (sourceGroupCompositionId == null) {
            throw new IllegalArgumentException(
                    "Source-group composition snapshot ID cannot be null."
            );
        }

        if (maximumLoad <= 0) {
            throw new IllegalArgumentException(
                    "Source-group composition maximum load must be greater "
                            + "than zero."
            );
        }

        if (sourceCompositionSnapshots == null
                || sourceCompositionSnapshots.isEmpty()) {

            throw new IllegalArgumentException(
                    "Source-group composition snapshot requires at least one "
                            + "source composition."
            );
        }

        sourceCompositionSnapshots =
                List.copyOf(
                        sourceCompositionSnapshots
                );

        validateSourceCompositions(
                maximumLoad,
                sourceCompositionSnapshots
        );

        if (packAssignmentSnapshot != null) {
            validatePackAssignment(
                    packAssignmentSnapshot,
                    sourceCompositionSnapshots
            );
        }
    }

    /**
     * Captures one complete source-group composition.
     */
    public static SourceGroupCompositionSnapshot capture(
            SourceGroupComposition sourceGroupComposition
    ) {
        if (sourceGroupComposition == null) {
            throw new IllegalArgumentException(
                    "Source-group composition cannot be null."
            );
        }

        if (sourceGroupComposition.isEmpty()) {
            throw new IllegalArgumentException(
                    "Source-group composition "
                            + sourceGroupComposition
                            .getSourceGroupCompositionId()
                            + " is empty."
            );
        }

        List<SourceCompositionSnapshot> capturedSources =
                new ArrayList<>();

        for (SourceGroupComposition.SourceComposition sourceComposition
                : sourceGroupComposition.getSourceCompositions()) {

            capturedSources.add(
                    SourceCompositionSnapshot.capture(
                            sourceComposition
                    )
            );
        }

        PackAssignmentSnapshot capturedPackAssignment =
                sourceGroupComposition.hasPackAssignment()
                        ? PackAssignmentSnapshot.capture(
                        sourceGroupComposition.getPackAssignment()
                )
                        : null;

        return new SourceGroupCompositionSnapshot(
                sourceGroupComposition.getSourceGroupCompositionId(),
                sourceGroupComposition.getMaximumSourceGroupLoad(),
                capturedSources,
                capturedPackAssignment
        );
    }

    /**
     * Restores one complete source-group composition using all saved IDs.
     *
     * The current authored source-profile catalogue must still report the
     * same source-group load for every source composition. A mismatch means
     * that the saved plan no longer describes the same physical
     * infrastructure and is rejected rather than silently changed.
     */
    public SourceGroupComposition restore() {
        SourceGroupComposition restoredGroup =
                new SourceGroupComposition(
                        sourceGroupCompositionId,
                        new SourceGroupRules(
                                maximumLoad
                        )
                );

        for (SourceCompositionSnapshot sourceSnapshot
                : sourceCompositionSnapshots) {

            restoredGroup.addSourceComposition(
                    sourceSnapshot.restore()
            );
        }

        if (packAssignmentSnapshot != null) {
            restoredGroup.assignPack(
                    packAssignmentSnapshot.packId(),
                    packAssignmentSnapshot.sourceCompositionId(),
                    packAssignmentSnapshot.attachedMobAssignmentId()
            );
        }

        SourceGroupCompositionSnapshot reconstructedSnapshot =
                capture(
                        restoredGroup
                );

        if (!equals(
                reconstructedSnapshot
        )) {
            throw new IllegalArgumentException(
                    "Restored source-group composition "
                            + sourceGroupCompositionId
                            + " does not exactly match its saved snapshot."
            );
        }

        return restoredGroup;
    }

    public SourceRole getSourceRole() {
        return sourceCompositionSnapshots
                .getFirst()
                .sourceRole();
    }

    public int getSourceCount() {
        return sourceCompositionSnapshots.size();
    }

    public int getTotalThreatSpent() {
        int totalThreatSpent =
                0;

        for (SourceCompositionSnapshot sourceSnapshot
                : sourceCompositionSnapshots) {

            totalThreatSpent +=
                    sourceSnapshot.threatSpent();
        }

        return totalThreatSpent;
    }

    public int getTotalComplexitySpent() {
        int totalComplexitySpent =
                0;

        for (SourceCompositionSnapshot sourceSnapshot
                : sourceCompositionSnapshots) {

            totalComplexitySpent +=
                    sourceSnapshot.getComplexitySpent();
        }

        return totalComplexitySpent;
    }

    public int getTotalSourceGroupLoad() {
        int totalLoad =
                0;

        for (SourceCompositionSnapshot sourceSnapshot
                : sourceCompositionSnapshots) {

            totalLoad +=
                    sourceSnapshot.sourceGroupLoadCost();
        }

        return totalLoad;
    }

    public SourceCompositionSnapshot getSourceCompositionSnapshot(
            UUID sourceCompositionId
    ) {
        if (sourceCompositionId == null) {
            return null;
        }

        for (SourceCompositionSnapshot sourceSnapshot
                : sourceCompositionSnapshots) {

            if (sourceCompositionId.equals(
                    sourceSnapshot.sourceCompositionId()
            )) {
                return sourceSnapshot;
            }
        }

        return null;
    }

    private static void validateSourceCompositions(
            int maximumLoad,
            List<SourceCompositionSnapshot> sourceCompositionSnapshots
    ) {
        Set<UUID> sourceCompositionIds =
                new HashSet<>();

        SourceRole commonRole =
                null;

        int totalLoad =
                0;

        for (SourceCompositionSnapshot sourceSnapshot
                : sourceCompositionSnapshots) {

            if (sourceSnapshot == null) {
                throw new IllegalArgumentException(
                        "Source-group composition snapshot cannot contain a "
                                + "null source composition."
                );
            }

            if (!sourceCompositionIds.add(
                    sourceSnapshot.sourceCompositionId()
            )) {
                throw new IllegalArgumentException(
                        "Source-group composition snapshot contains duplicate "
                                + "source-composition ID "
                                + sourceSnapshot.sourceCompositionId()
                                + "."
                );
            }

            if (commonRole == null) {
                commonRole =
                        sourceSnapshot.sourceRole();
            } else if (commonRole
                    != sourceSnapshot.sourceRole()) {

                throw new IllegalArgumentException(
                        "One source-group composition cannot contain mixed "
                                + "source roles."
                );
            }

            totalLoad +=
                    sourceSnapshot.sourceGroupLoadCost();
        }

        if (totalLoad > maximumLoad) {
            throw new IllegalArgumentException(
                    "Source-group composition load "
                            + totalLoad
                            + " exceeds maximum load "
                            + maximumLoad
                            + "."
            );
        }
    }

    private static void validatePackAssignment(
            PackAssignmentSnapshot packAssignmentSnapshot,
            List<SourceCompositionSnapshot> sourceCompositionSnapshots
    ) {
        SourceCompositionSnapshot selectedSource =
                null;

        for (SourceCompositionSnapshot sourceSnapshot
                : sourceCompositionSnapshots) {

            if (sourceSnapshot.sourceCompositionId().equals(
                    packAssignmentSnapshot.sourceCompositionId()
            )) {
                selectedSource =
                        sourceSnapshot;

                break;
            }
        }

        if (selectedSource == null) {
            throw new IllegalArgumentException(
                    "Pack snapshot refers to source composition "
                            + packAssignmentSnapshot.sourceCompositionId()
                            + " outside its source group."
            );
        }

        AttachedMobAssignmentSnapshot selectedAssignment =
                selectedSource.getAttachedMobAssignmentSnapshot(
                        packAssignmentSnapshot
                                .attachedMobAssignmentId()
                );

        if (selectedAssignment == null) {
            throw new IllegalArgumentException(
                    "Pack snapshot refers to attached-mob assignment "
                            + packAssignmentSnapshot
                            .attachedMobAssignmentId()
                            + " outside source composition "
                            + selectedSource.sourceCompositionId()
                            + "."
            );
        }
    }

    /**
     * Immutable snapshot of one source-sized composition.
     */
    public record SourceCompositionSnapshot(
            UUID sourceCompositionId,
            SourceType requiredSourceType,
            SourceSize requiredSourceSize,
            SourceRole sourceRole,
            int threatSpent,
            int sourceGroupLoadCost,
            List<MobEntrySnapshot> mobEntrySnapshots,
            List<MobModifier> modifiers,
            List<MobOrder> orders,
            List<AttachedMobAssignmentSnapshot>
            attachedMobAssignmentSnapshots
    ) {

        public SourceCompositionSnapshot {
            if (sourceCompositionId == null) {
                throw new IllegalArgumentException(
                        "Source-composition snapshot ID cannot be null."
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
                        "Source-composition threat spent cannot be negative."
                );
            }

            if (sourceGroupLoadCost <= 0) {
                throw new IllegalArgumentException(
                        "Source-composition group load must be greater than "
                                + "zero."
                );
            }

            if (mobEntrySnapshots == null
                    || mobEntrySnapshots.isEmpty()) {

                throw new IllegalArgumentException(
                        "Source-composition snapshot requires at least one "
                                + "mob entry."
                );
            }

            if (modifiers == null) {
                throw new IllegalArgumentException(
                        "Source modifiers cannot be null."
                );
            }

            if (orders == null) {
                throw new IllegalArgumentException(
                        "Source orders cannot be null."
                );
            }

            if (attachedMobAssignmentSnapshots == null) {
                throw new IllegalArgumentException(
                        "Attached mob assignments cannot be null."
                );
            }

            mobEntrySnapshots =
                    List.copyOf(
                            mobEntrySnapshots
                    );

            modifiers =
                    List.copyOf(
                            modifiers
                    );

            orders =
                    List.copyOf(
                            orders
                    );

            attachedMobAssignmentSnapshots =
                    List.copyOf(
                            attachedMobAssignmentSnapshots
                    );

            validateContents(
                    requiredSourceSize,
                    mobEntrySnapshots,
                    modifiers,
                    orders,
                    attachedMobAssignmentSnapshots
            );
        }

        private static SourceCompositionSnapshot capture(
                SourceGroupComposition.SourceComposition sourceComposition
        ) {
            if (sourceComposition == null) {
                throw new IllegalArgumentException(
                        "Source composition cannot be null."
                );
            }

            if (sourceComposition.isEmpty()) {
                throw new IllegalArgumentException(
                        "Source composition "
                                + sourceComposition.getSourceCompositionId()
                                + " is empty."
                );
            }

            List<MobEntrySnapshot> capturedMobs =
                    new ArrayList<>();

            for (SourceGroupComposition.MobEntry mobEntry
                    : sourceComposition.getMobEntries()) {

                capturedMobs.add(
                        MobEntrySnapshot.capture(
                                mobEntry
                        )
                );
            }

            List<AttachedMobAssignmentSnapshot> capturedAttachments =
                    new ArrayList<>();

            for (SourceGroupComposition.AttachedMobAssignment assignment
                    : sourceComposition.getAttachedMobAssignments()) {

                capturedAttachments.add(
                        AttachedMobAssignmentSnapshot.capture(
                                assignment
                        )
                );
            }

            return new SourceCompositionSnapshot(
                    sourceComposition.getSourceCompositionId(),
                    sourceComposition.getRequiredSourceType(),
                    sourceComposition.getRequiredSourceSize(),
                    sourceComposition.getSourceRole(),
                    sourceComposition.getThreatSpent(),
                    sourceComposition.getSourceGroupLoadCost(),
                    capturedMobs,
                    List.copyOf(
                            sourceComposition.getModifiers()
                    ),
                    List.copyOf(
                            sourceComposition.getOrders()
                    ),
                    capturedAttachments
            );
        }

        private SourceGroupComposition.SourceComposition restore() {
            SourceGroupComposition.SourceComposition restoredSource =
                    new SourceGroupComposition.SourceComposition(
                            sourceCompositionId,
                            requiredSourceType,
                            requiredSourceSize,
                            sourceRole,
                            threatSpent
                    );

            for (MobEntrySnapshot mobEntrySnapshot
                    : mobEntrySnapshots) {

                restoredSource.addMob(
                        mobEntrySnapshot.mobId(),
                        mobEntrySnapshot.count(),
                        mobEntrySnapshot.capacityCostPerMob(),
                        mobEntrySnapshot.minimumSourceSize()
                );
            }

            for (MobModifier modifier
                    : modifiers) {

                restoredSource.addModifier(
                        modifier
                );
            }

            for (MobOrder order
                    : orders) {

                restoredSource.addOrder(
                        order
                );
            }

            for (AttachedMobAssignmentSnapshot assignmentSnapshot
                    : attachedMobAssignmentSnapshots) {

                restoredSource.addAttachedMobAssignment(
                        assignmentSnapshot.attachedMobAssignmentId(),
                        assignmentSnapshot.mobId(),
                        assignmentSnapshot
                                .complexityOptionSnapshot()
                                .restore(),
                        assignmentSnapshot.spawnPriority()
                );
            }

            int currentAuthoredLoad =
                    restoredSource.getSourceGroupLoadCost();

            if (currentAuthoredLoad
                    != sourceGroupLoadCost) {

                throw new IllegalArgumentException(
                        "Restored source composition "
                                + sourceCompositionId
                                + " has current authored group load "
                                + currentAuthoredLoad
                                + " but the persisted plan requires "
                                + sourceGroupLoadCost
                                + "."
                );
            }

            return restoredSource;
        }

        public int getComplexitySpent() {
            int totalComplexitySpent =
                    0;

            for (AttachedMobAssignmentSnapshot assignmentSnapshot
                    : attachedMobAssignmentSnapshots) {

                totalComplexitySpent +=
                        assignmentSnapshot
                                .complexityOptionSnapshot()
                                .complexityCost();
            }

            return totalComplexitySpent;
        }

        public int getTotalMobCount() {
            int totalMobCount =
                    0;

            for (MobEntrySnapshot mobEntrySnapshot
                    : mobEntrySnapshots) {

                totalMobCount +=
                        mobEntrySnapshot.count();
            }

            return totalMobCount;
        }

        public int getUsedCapacityUnits() {
            int usedCapacityUnits =
                    0;

            for (MobEntrySnapshot mobEntrySnapshot
                    : mobEntrySnapshots) {

                usedCapacityUnits +=
                        mobEntrySnapshot.getTotalCapacityCost();
            }

            return usedCapacityUnits;
        }

        public AttachedMobAssignmentSnapshot
        getAttachedMobAssignmentSnapshot(
                UUID attachedMobAssignmentId
        ) {
            if (attachedMobAssignmentId == null) {
                return null;
            }

            for (AttachedMobAssignmentSnapshot assignmentSnapshot
                    : attachedMobAssignmentSnapshots) {

                if (attachedMobAssignmentId.equals(
                        assignmentSnapshot.attachedMobAssignmentId()
                )) {
                    return assignmentSnapshot;
                }
            }

            return null;
        }

        private static void validateContents(
                SourceSize requiredSourceSize,
                List<MobEntrySnapshot> mobEntrySnapshots,
                List<MobModifier> modifiers,
                List<MobOrder> orders,
                List<AttachedMobAssignmentSnapshot>
                        attachedMobAssignmentSnapshots
        ) {
            int usedCapacity =
                    0;

            Map<String, Integer> plannedMobCounts =
                    new HashMap<>();

            for (MobEntrySnapshot mobEntrySnapshot
                    : mobEntrySnapshots) {

                if (mobEntrySnapshot == null) {
                    throw new IllegalArgumentException(
                            "Source composition cannot contain a null mob "
                                    + "entry."
                    );
                }

                if (!requiredSourceSize.canFit(
                        mobEntrySnapshot.minimumSourceSize()
                )) {
                    throw new IllegalArgumentException(
                            "Source size "
                                    + requiredSourceSize
                                    + " cannot fit mob entry "
                                    + mobEntrySnapshot.mobId()
                                    + " requiring "
                                    + mobEntrySnapshot.minimumSourceSize()
                                    + "."
                    );
                }

                usedCapacity +=
                        mobEntrySnapshot.getTotalCapacityCost();

                plannedMobCounts.merge(
                        mobEntrySnapshot.mobId(),
                        mobEntrySnapshot.count(),
                        Integer::sum
                );
            }

            if (usedCapacity
                    > requiredSourceSize.getCapacityUnits()) {

                throw new IllegalArgumentException(
                        "Source-composition snapshot uses "
                                + usedCapacity
                                + " capacity from a source with capacity "
                                + requiredSourceSize.getCapacityUnits()
                                + "."
                );
            }

            requireUniqueNonNullValues(
                    modifiers,
                    "mob modifier"
            );

            requireUniqueNonNullValues(
                    orders,
                    "mob order"
            );

            Set<UUID> attachedAssignmentIds =
                    new HashSet<>();

            Map<String, Integer> attachedMobCounts =
                    new HashMap<>();

            for (AttachedMobAssignmentSnapshot assignmentSnapshot
                    : attachedMobAssignmentSnapshots) {

                if (assignmentSnapshot == null) {
                    throw new IllegalArgumentException(
                            "Source composition cannot contain a null "
                                    + "attached-mob assignment."
                    );
                }

                if (!attachedAssignmentIds.add(
                        assignmentSnapshot.attachedMobAssignmentId()
                )) {
                    throw new IllegalArgumentException(
                            "Source composition contains duplicate "
                                    + "attached-mob assignment ID "
                                    + assignmentSnapshot
                                    .attachedMobAssignmentId()
                                    + "."
                    );
                }

                int attachedCount =
                        attachedMobCounts.merge(
                                assignmentSnapshot.mobId(),
                                1,
                                Integer::sum
                        );

                int plannedCount =
                        plannedMobCounts.getOrDefault(
                                assignmentSnapshot.mobId(),
                                0
                        );

                if (attachedCount > plannedCount) {
                    throw new IllegalArgumentException(
                            "Source composition reserves more attached "
                                    + assignmentSnapshot.mobId()
                                    + " mobs than it plans."
                    );
                }
            }
        }
    }

    /**
     * Immutable snapshot of one mob entry.
     */
    public record MobEntrySnapshot(
            String mobId,
            int count,
            int capacityCostPerMob,
            SourceSize minimumSourceSize
    ) {

        public MobEntrySnapshot {
            if (mobId == null
                    || mobId.isBlank()) {

                throw new IllegalArgumentException(
                        "Mob-entry snapshot ID cannot be blank."
                );
            }

            if (count <= 0) {
                throw new IllegalArgumentException(
                        "Mob-entry snapshot count must be greater than zero."
                );
            }

            if (capacityCostPerMob <= 0) {
                throw new IllegalArgumentException(
                        "Mob-entry capacity cost must be greater than zero."
                );
            }

            if (minimumSourceSize == null) {
                throw new IllegalArgumentException(
                        "Mob-entry minimum source size cannot be null."
                );
            }
        }

        private static MobEntrySnapshot capture(
                SourceGroupComposition.MobEntry mobEntry
        ) {
            if (mobEntry == null) {
                throw new IllegalArgumentException(
                        "Mob entry cannot be null."
                );
            }

            return new MobEntrySnapshot(
                    mobEntry.getMobId(),
                    mobEntry.getCount(),
                    mobEntry.getCapacityCostPerMob(),
                    mobEntry.getMinimumSourceSize()
            );
        }

        public int getTotalCapacityCost() {
            return count * capacityCostPerMob;
        }
    }

    /**
     * Immutable snapshot of one promoted, already-budgeted mob.
     */
    public record AttachedMobAssignmentSnapshot(
            UUID attachedMobAssignmentId,
            String mobId,
            ComplexityOptionSnapshot complexityOptionSnapshot,
            SourceGroupComposition.AttachedMobSpawnPriority spawnPriority
    ) {

        public AttachedMobAssignmentSnapshot {
            if (attachedMobAssignmentId == null) {
                throw new IllegalArgumentException(
                        "Attached-mob snapshot ID cannot be null."
                );
            }

            if (mobId == null
                    || mobId.isBlank()) {

                throw new IllegalArgumentException(
                        "Attached-mob snapshot mob ID cannot be blank."
                );
            }

            if (complexityOptionSnapshot == null) {
                throw new IllegalArgumentException(
                        "Attached-mob complexity snapshot cannot be null."
                );
            }

            if (complexityOptionSnapshot.category()
                    != ComplexityOptionCategory.ATTACHED) {

                throw new IllegalArgumentException(
                        "Attached-mob snapshot requires an ATTACHED complexity "
                                + "option."
                );
            }

            if (spawnPriority == null) {
                throw new IllegalArgumentException(
                        "Attached-mob spawn priority cannot be null."
                );
            }
        }

        private static AttachedMobAssignmentSnapshot capture(
                SourceGroupComposition.AttachedMobAssignment assignment
        ) {
            if (assignment == null) {
                throw new IllegalArgumentException(
                        "Attached mob assignment cannot be null."
                );
            }

            return new AttachedMobAssignmentSnapshot(
                    assignment.attachedMobAssignmentId(),
                    assignment.mobId(),
                    ComplexityOptionSnapshot.capture(
                            assignment.complexityOption()
                    ),
                    assignment.spawnPriority()
            );
        }
    }

    /**
     * Saved value of one complexity-option definition.
     *
     * The complete definition is stored rather than only its ID so an active
     * incursion retains the exact complexity cost and category with which it
     * was planned.
     */
    public record ComplexityOptionSnapshot(
            String id,
            int complexityCost,
            ComplexityOptionCategory category
    ) {

        public ComplexityOptionSnapshot {
            if (id == null
                    || id.isBlank()) {

                throw new IllegalArgumentException(
                        "Complexity-option snapshot ID cannot be blank."
                );
            }

            if (complexityCost <= 0) {
                throw new IllegalArgumentException(
                        "Complexity-option snapshot cost must be greater than "
                                + "zero."
                );
            }

            if (category == null) {
                throw new IllegalArgumentException(
                        "Complexity-option snapshot category cannot be null."
                );
            }
        }

        private static ComplexityOptionSnapshot capture(
                ComplexityOptionDefinition complexityOption
        ) {
            if (complexityOption == null) {
                throw new IllegalArgumentException(
                        "Complexity option cannot be null."
                );
            }

            return new ComplexityOptionSnapshot(
                    complexityOption.id(),
                    complexityOption.complexityCost(),
                    complexityOption.category()
            );
        }

        private ComplexityOptionDefinition restore() {
            return new ComplexityOptionDefinition(
                    id,
                    complexityCost,
                    category
            );
        }
    }

    /**
     * Immutable snapshot of the optional group-owned Pack identity.
     */
    public record PackAssignmentSnapshot(
            UUID packId,
            UUID sourceCompositionId,
            UUID attachedMobAssignmentId
    ) {

        public PackAssignmentSnapshot {
            if (packId == null) {
                throw new IllegalArgumentException(
                        "Pack snapshot ID cannot be null."
                );
            }

            if (sourceCompositionId == null) {
                throw new IllegalArgumentException(
                        "Pack source-composition ID cannot be null."
                );
            }

            if (attachedMobAssignmentId == null) {
                throw new IllegalArgumentException(
                        "Pack attachment ID cannot be null."
                );
            }
        }

        private static PackAssignmentSnapshot capture(
                SourceGroupComposition.PackAssignment packAssignment
        ) {
            if (packAssignment == null) {
                throw new IllegalArgumentException(
                        "Pack assignment cannot be null."
                );
            }

            return new PackAssignmentSnapshot(
                    packAssignment.packId(),
                    packAssignment.sourceCompositionId(),
                    packAssignment.attachedMobAssignmentId()
            );
        }
    }

    private static <T> void requireUniqueNonNullValues(
            List<T> values,
            String valueName
    ) {
        Set<T> uniqueValues =
                new HashSet<>();

        for (T value
                : values) {

            if (value == null) {
                throw new IllegalArgumentException(
                        valueName
                                + " cannot be null."
                );
            }

            if (!uniqueValues.add(
                    value
            )) {
                throw new IllegalArgumentException(
                        "Duplicate "
                                + valueName
                                + " "
                                + value
                                + "."
                );
            }
        }
    }
}