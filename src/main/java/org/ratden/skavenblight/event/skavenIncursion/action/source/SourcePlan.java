package org.ratden.skavenblight.event.skavenIncursion.action.source;

import net.minecraft.core.BlockPos;
import org.ratden.skavenblight.event.skavenIncursion.planning.budget.MobSpawnGroup;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceRole;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceSize;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class SourcePlan {
    private final UUID plannedSourceId;
    private final UUID sourceGroupId;

    private final SourceType sourceType;
    private final SourceSize sourceSize;
    private final SourceRole sourceRole;

    private final BlockPos anchorPos;
    private final int capacityUnits;

    private final List<MobSpawnGroup> mobSpawnGroups;

    private BlockPos placedPos;

    public SourcePlan(
            UUID sourceGroupId,
            SourceType sourceType,
            SourceSize sourceSize,
            SourceRole sourceRole,
            BlockPos anchorPos
    ) {
        this.plannedSourceId = UUID.randomUUID();
        this.sourceGroupId = sourceGroupId;
        this.sourceType = sourceType;
        this.sourceSize = sourceSize;
        this.sourceRole = sourceRole;
        this.anchorPos = anchorPos.immutable();
        this.capacityUnits = sourceSize.getCapacityUnits();
        this.mobSpawnGroups = new ArrayList<>();
        this.placedPos = null;
    }

    public UUID getPlannedSourceId() {
        return plannedSourceId;
    }

    public UUID getSourceGroupId() {
        return sourceGroupId;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public SourceSize getSourceSize() {
        return sourceSize;
    }

    public SourceRole getSourceRole() {
        return sourceRole;
    }

    public BlockPos getAnchorPos() {
        return anchorPos;
    }

    public int getCapacityUnits() {
        return capacityUnits;
    }

    public int getUsedCapacityUnits() {
        int total = 0;

        for (MobSpawnGroup mobSpawnGroup : mobSpawnGroups) {
            total += mobSpawnGroup.getTotalCapacityCost();
        }

        return total;
    }

    public int getRemainingCapacityUnits() {
        return capacityUnits - getUsedCapacityUnits();
    }

    public boolean canFit(MobSpawnGroup mobSpawnGroup) {
        if (mobSpawnGroup == null || mobSpawnGroup.isEmpty()) {
            return false;
        }

        if (!sourceSize.canFit(mobSpawnGroup.getMinimumRequiredSourceSize())) {
            return false;
        }

        return getRemainingCapacityUnits() >= mobSpawnGroup.getTotalCapacityCost();
    }

    public boolean addMobSpawnGroup(MobSpawnGroup mobSpawnGroup) {
        if (!canFit(mobSpawnGroup)) {
            return false;
        }

        mobSpawnGroups.add(mobSpawnGroup);
        return true;
    }

    public List<MobSpawnGroup> getMobSpawnGroups() {
        return Collections.unmodifiableList(mobSpawnGroups);
    }

    public boolean hasPlacedPos() {
        return placedPos != null;
    }

    public BlockPos getPlacedPos() {
        return placedPos;
    }

    public void setPlacedPos(BlockPos placedPos) {
        if (placedPos == null) {
            this.placedPos = null;
            return;
        }

        this.placedPos = placedPos.immutable();
    }
}