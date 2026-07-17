package org.ratden.skavenblight.event.skavenIncursion.planning.budget;

import net.minecraft.core.BlockPos;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceGroupPlan;
import org.ratden.skavenblight.event.skavenIncursion.action.source.SourcePlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceRole;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceSize;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceType;

import java.util.List;

public class SourceCapacityAllocator {

    public static boolean allocateMobSpawnGroupAcrossSourceGroups(
            List<SourceGroupPlan> sourceGroupPlans,
            MobSpawnGroup mobSpawnGroup,
            SourceType sourceType,
            SourceSize preferredSourceSize,
            SourceRole sourceRole
    ) {
        if (sourceGroupPlans == null || sourceGroupPlans.isEmpty()) {
            return false;
        }

        if (mobSpawnGroup == null || mobSpawnGroup.isEmpty()) {
            return false;
        }

        SourceSize requiredSourceSize = getRequiredSourceSize(
                preferredSourceSize,
                mobSpawnGroup
        );

        int remainingCapacityCost = mobSpawnGroup.getTotalCapacityCost();
        int sourceCapacity = requiredSourceSize.getCapacityUnits();

        if (sourceCapacity <= 0) {
            return false;
        }

        int requiredSourceCount = divideRoundUp(
                remainingCapacityCost,
                sourceCapacity
        );

        if (requiredSourceCount <= 0) {
            return false;
        }

        for (int i = 0; i < requiredSourceCount; i++) {
            SourceGroupPlan sourceGroupPlan = chooseSourceGroupForNewSource(
                    sourceGroupPlans,
                    i
            );

            BlockPos sourceAnchor = getOffsetAnchorForSource(
                    sourceGroupPlan,
                    sourceGroupPlan.getSourceCount()
            );

            sourceGroupPlan.createSourcePlan(
                    sourceType,
                    requiredSourceSize,
                    sourceRole,
                    sourceAnchor
            );
        }

        return distributeMobSpawnGroupAcrossSources(
                sourceGroupPlans,
                mobSpawnGroup,
                sourceType,
                requiredSourceSize,
                sourceRole
        );
    }

    private static boolean distributeMobSpawnGroupAcrossSources(
            List<SourceGroupPlan> sourceGroupPlans,
            MobSpawnGroup mobSpawnGroup,
            SourceType sourceType,
            SourceSize sourceSize,
            SourceRole sourceRole
    ) {
        for (MobSpawnGroup.MobEntry mobEntry : mobSpawnGroup.getMobEntries()) {
            int remainingCount = mobEntry.getCount();

            while (remainingCount > 0) {
                SourcePlan sourcePlan = findSourceWithRemainingCapacity(
                        sourceGroupPlans,
                        mobEntry.getCapacityCostPerMob(),
                        mobEntry.getMinimumSourceSize()
                );

                if (sourcePlan == null) {
                    SourceGroupPlan sourceGroupPlan = chooseSourceGroupWithLowestSourceCount(sourceGroupPlans);

                    sourcePlan = sourceGroupPlan.createSourcePlan(
                            sourceType,
                            sourceSize,
                            sourceRole,
                            getOffsetAnchorForSource(
                                    sourceGroupPlan,
                                    sourceGroupPlan.getSourceCount()
                            )
                    );
                }

                int capacityAvailableForThisMob = sourcePlan.getRemainingCapacityUnits()
                        / mobEntry.getCapacityCostPerMob();

                if (capacityAvailableForThisMob <= 0) {
                    return false;
                }

                int countToAssign = Math.min(
                        remainingCount,
                        capacityAvailableForThisMob
                );

                MobSpawnGroup splitGroup = new MobSpawnGroup();
                splitGroup.addMob(
                        mobEntry.getMobId(),
                        countToAssign,
                        mobEntry.getCapacityCostPerMob(),
                        mobEntry.getMinimumSourceSize()
                );

                for (var modifier : mobSpawnGroup.getModifiers()) {
                    splitGroup.addModifier(modifier);
                }

                for (var order : mobSpawnGroup.getOrders()) {
                    splitGroup.addOrder(order);
                }

                boolean added = sourcePlan.addMobSpawnGroup(splitGroup);

                if (!added) {
                    return false;
                }

                remainingCount -= countToAssign;
            }
        }

        return true;
    }

    private static SourcePlan findSourceWithRemainingCapacity(
            List<SourceGroupPlan> sourceGroupPlans,
            int capacityCostPerMob,
            SourceSize minimumSourceSize
    ) {
        for (SourceGroupPlan sourceGroupPlan : sourceGroupPlans) {
            for (SourcePlan sourcePlan : sourceGroupPlan.getSourcePlans()) {
                if (!sourcePlan.getSourceSize().canFit(minimumSourceSize)) {
                    continue;
                }

                if (sourcePlan.getRemainingCapacityUnits() >= capacityCostPerMob) {
                    return sourcePlan;
                }
            }
        }

        return null;
    }

    private static SourceGroupPlan chooseSourceGroupForNewSource(
            List<SourceGroupPlan> sourceGroupPlans,
            int sourceIndex
    ) {
        int groupIndex = sourceIndex % sourceGroupPlans.size();
        return sourceGroupPlans.get(groupIndex);
    }

    private static SourceGroupPlan chooseSourceGroupWithLowestSourceCount(
            List<SourceGroupPlan> sourceGroupPlans
    ) {
        SourceGroupPlan chosenGroup = sourceGroupPlans.get(0);

        for (SourceGroupPlan sourceGroupPlan : sourceGroupPlans) {
            if (sourceGroupPlan.getSourceCount() < chosenGroup.getSourceCount()) {
                chosenGroup = sourceGroupPlan;
            }
        }

        return chosenGroup;
    }

    private static SourceSize getRequiredSourceSize(
            SourceSize preferredSourceSize,
            MobSpawnGroup mobSpawnGroup
    ) {
        SourceSize minimumRequiredSize = mobSpawnGroup.getMinimumRequiredSourceSize();

        if (preferredSourceSize.canFit(minimumRequiredSize)) {
            return preferredSourceSize;
        }

        return minimumRequiredSize;
    }

    private static BlockPos getOffsetAnchorForSource(
            SourceGroupPlan sourceGroupPlan,
            int sourceIndexWithinGroup
    ) {
        int spacing = 5;

        int offsetX = (sourceIndexWithinGroup % 3 - 1) * spacing;
        int offsetZ = (sourceIndexWithinGroup / 3) * spacing;

        return sourceGroupPlan.getAnchorPos().offset(
                offsetX,
                0,
                offsetZ
        );
    }

    private static int divideRoundUp(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private SourceCapacityAllocator() {
    }
}