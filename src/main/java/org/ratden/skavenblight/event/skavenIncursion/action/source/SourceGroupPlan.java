package org.ratden.skavenblight.event.skavenIncursion.action.source;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class SourceGroupPlan {
    private final UUID sourceGroupId;

    private final SourcePlacementPattern placementPattern;
    private final SourceRole sourceRole;
    private final BlockPos anchorPos;

    private final List<SourcePlan> sourcePlans;

    public SourceGroupPlan(
            SourcePlacementPattern placementPattern,
            SourceRole sourceRole,
            BlockPos anchorPos
    ) {
        this.sourceGroupId = UUID.randomUUID();
        this.placementPattern = placementPattern;
        this.sourceRole = sourceRole;
        this.anchorPos = anchorPos.immutable();
        this.sourcePlans = new ArrayList<>();
    }

    public UUID getSourceGroupId() {
        return sourceGroupId;
    }

    public SourcePlacementPattern getPlacementPattern() {
        return placementPattern;
    }

    public SourceRole getSourceRole() {
        return sourceRole;
    }

    public BlockPos getAnchorPos() {
        return anchorPos;
    }

    public SourcePlan createSourcePlan(
            SourceType sourceType,
            SourceSize sourceSize,
            SourceRole sourceRole,
            BlockPos anchorPos
    ) {
        SourcePlan sourcePlan = new SourcePlan(
                sourceGroupId,
                sourceType,
                sourceSize,
                sourceRole,
                anchorPos
        );

        sourcePlans.add(sourcePlan);
        return sourcePlan;
    }

    public SourcePlan createSourcePlan(
            SourceType sourceType,
            SourceSize sourceSize
    ) {
        return createSourcePlan(
                sourceType,
                sourceSize,
                sourceRole,
                anchorPos
        );
    }

    public List<SourcePlan> getSourcePlans() {
        return Collections.unmodifiableList(sourcePlans);
    }

    public int getSourceCount() {
        return sourcePlans.size();
    }

    public int getTotalCapacityUnits() {
        int total = 0;

        for (SourcePlan sourcePlan : sourcePlans) {
            total += sourcePlan.getCapacityUnits();
        }

        return total;
    }

    public int getUsedCapacityUnits() {
        int total = 0;

        for (SourcePlan sourcePlan : sourcePlans) {
            total += sourcePlan.getUsedCapacityUnits();
        }

        return total;
    }

    public int getRemainingCapacityUnits() {
        return getTotalCapacityUnits() - getUsedCapacityUnits();
    }

    public SourcePlan findSourceThatCanFit(MobSpawnGroup mobSpawnGroup) {
        for (SourcePlan sourcePlan : sourcePlans) {
            if (sourcePlan.canFit(mobSpawnGroup)) {
                return sourcePlan;
            }
        }

        return null;
    }

    public boolean addMobSpawnGroupToFirstAvailableSource(MobSpawnGroup mobSpawnGroup) {
        SourcePlan sourcePlan = findSourceThatCanFit(mobSpawnGroup);

        if (sourcePlan == null) {
            return false;
        }

        return sourcePlan.addMobSpawnGroup(mobSpawnGroup);
    }
}