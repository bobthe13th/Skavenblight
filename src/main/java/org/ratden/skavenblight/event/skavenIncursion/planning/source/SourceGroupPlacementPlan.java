package org.ratden.skavenblight.event.skavenIncursion.planning.source;

import net.minecraft.core.BlockPos;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.SourceGroupComposition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Physical placement result for one planned source group.
 *
 * This object binds one SourceGroupComposition to the physical sources that
 * will execute it.
 */
public class SourceGroupPlacementPlan {

    private final UUID sourceGroupPlacementId;
    private final UUID frontId;
    private final UUID sourceGroupCompositionId;

    private final SourceRole sourceRole;
    private final BlockPos anchorPos;

    private final List<SourcePlacementPlan> sourcePlacementPlans;

    public SourceGroupPlacementPlan(
            UUID frontId,
            SourceGroupComposition sourceGroupComposition,
            SourceRole sourceRole,
            BlockPos anchorPos
    ) {
        if (frontId == null) {
            throw new IllegalArgumentException(
                    "Front ID cannot be null."
            );
        }

        if (sourceGroupComposition == null) {
            throw new IllegalArgumentException(
                    "Source-group composition cannot be null."
            );
        }

        if (sourceRole == null) {
            throw new IllegalArgumentException(
                    "Source role cannot be null."
            );
        }

        if (anchorPos == null) {
            throw new IllegalArgumentException(
                    "Source-group anchor position cannot be null."
            );
        }

        this.sourceGroupPlacementId = UUID.randomUUID();
        this.frontId = frontId;
        this.sourceGroupCompositionId =
                sourceGroupComposition.getSourceGroupCompositionId();
        this.sourceRole = sourceRole;
        this.anchorPos = anchorPos.immutable();
        this.sourcePlacementPlans = new ArrayList<>();
    }

    public UUID getSourceGroupPlacementId() {
        return sourceGroupPlacementId;
    }

    public UUID getFrontId() {
        return frontId;
    }

    public UUID getSourceGroupCompositionId() {
        return sourceGroupCompositionId;
    }

    public SourceRole getSourceRole() {
        return sourceRole;
    }

    public BlockPos getAnchorPos() {
        return anchorPos;
    }

    public void addSourcePlacementPlan(
            SourcePlacementPlan sourcePlacementPlan
    ) {
        if (sourcePlacementPlan == null) {
            throw new IllegalArgumentException(
                    "Source placement plan cannot be null."
            );
        }

        if (!sourceGroupPlacementId.equals(
                sourcePlacementPlan.getSourceGroupPlacementId()
        )) {
            throw new IllegalArgumentException(
                    "Source placement belongs to a different source group."
            );
        }

        sourcePlacementPlans.add(sourcePlacementPlan);
    }

    public SourcePlacementPlan createSourcePlacementPlan(
            SourceGroupComposition.SourceComposition sourceComposition,
            BlockPos sourceAnchorPos
    ) {
        if (sourceComposition == null) {
            throw new IllegalArgumentException(
                    "Source composition cannot be null."
            );
        }

        SourcePlacementPlan sourcePlacementPlan =
                new SourcePlacementPlan(
                        sourceGroupPlacementId,
                        sourceComposition.getSourceCompositionId(),
                        sourceComposition.getRequiredSourceType(),
                        sourceComposition.getRequiredSourceSize(),
                        sourceComposition.getSourceRole(),
                        sourceAnchorPos
                );

        sourcePlacementPlans.add(sourcePlacementPlan);
        return sourcePlacementPlan;
    }

    public List<SourcePlacementPlan> getSourcePlacementPlans() {
        return Collections.unmodifiableList(sourcePlacementPlans);
    }

    public boolean isEmpty() {
        return sourcePlacementPlans.isEmpty();
    }

    public int getSourceCount() {
        return sourcePlacementPlans.size();
    }

    public int getTotalCapacityUnits() {
        int total = 0;

        for (SourcePlacementPlan sourcePlacementPlan
                : sourcePlacementPlans) {
            total += sourcePlacementPlan.getCapacityUnits();
        }

        return total;
    }
}