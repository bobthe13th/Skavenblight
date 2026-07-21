package org.ratden.skavenblight.event.skavenIncursion.planning.source;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.SourceGroupComposition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Physical placement result for one planned source group.
 *
 * One physical source group may support compositions from several waves.
 * The contained SourcePlacementPlans represent the persistent physical
 * sources that execute those compositions.
 */
public class SourceGroupPlacementPlan {

    private final UUID sourceGroupPlacementId;
    private final UUID frontId;

    private final List<UUID> sourceGroupCompositionIds;

    private final SourceRole sourceRole;
    private final BlockPos anchorPos;

    private final List<SourcePlacementPlan> sourcePlacementPlans;

    public SourceGroupPlacementPlan(
            UUID frontId,
            SourceGroupComposition initialSourceGroupComposition,
            SourceRole sourceRole,
            BlockPos anchorPos
    ) {
        if (frontId == null) {
            throw new IllegalArgumentException(
                    "Front ID cannot be null."
            );
        }

        if (initialSourceGroupComposition == null) {
            throw new IllegalArgumentException(
                    "Initial source-group composition cannot be null."
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
        this.sourceGroupCompositionIds = new ArrayList<>();

        this.sourceRole = sourceRole;
        this.anchorPos = anchorPos.immutable();

        this.sourcePlacementPlans = new ArrayList<>();

        bindSourceGroupComposition(
                initialSourceGroupComposition
        );
    }

    public UUID getSourceGroupPlacementId() {
        return sourceGroupPlacementId;
    }

    public UUID getFrontId() {
        return frontId;
    }

    /**
     * Binds another wave's group composition to this physical source group.
     */
    public void bindSourceGroupComposition(
            SourceGroupComposition sourceGroupComposition
    ) {
        if (sourceGroupComposition == null) {
            throw new IllegalArgumentException(
                    "Source-group composition cannot be null."
            );
        }

        UUID sourceGroupCompositionId =
                sourceGroupComposition
                        .getSourceGroupCompositionId();

        if (sourceGroupCompositionIds.contains(
                sourceGroupCompositionId
        )) {
            throw new IllegalArgumentException(
                    "Source-group placement "
                            + sourceGroupPlacementId
                            + " is already bound to source-group composition "
                            + sourceGroupCompositionId
                            + "."
            );
        }

        sourceGroupCompositionIds.add(
                sourceGroupCompositionId
        );
    }

    public List<UUID> getSourceGroupCompositionIds() {
        return Collections.unmodifiableList(
                sourceGroupCompositionIds
        );
    }

    public boolean isBoundToSourceGroupComposition(
            UUID sourceGroupCompositionId
    ) {
        return sourceGroupCompositionId != null
                && sourceGroupCompositionIds.contains(
                sourceGroupCompositionId
        );
    }

    public int getBoundCompositionCount() {
        return sourceGroupCompositionIds.size();
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
            SourcePlacementProfile placementProfile,
            Direction facing,
            BlockPos sourceAnchorPos
    ) {
        if (sourceComposition == null) {
            throw new IllegalArgumentException(
                    "Source composition cannot be null."
            );
        }

        if (placementProfile == null) {
            throw new IllegalArgumentException(
                    "Source placement profile cannot be null."
            );
        }

        if (facing == null) {
            throw new IllegalArgumentException(
                    "Source facing cannot be null."
            );
        }

        if (sourceAnchorPos == null) {
            throw new IllegalArgumentException(
                    "Source anchor position cannot be null."
            );
        }

        SourcePlacementPlan sourcePlacementPlan =
                new SourcePlacementPlan(
                        sourceGroupPlacementId,
                        sourceComposition.getSourceCompositionId(),
                        sourceComposition.getRequiredSourceType(),
                        sourceComposition.getRequiredSourceSize(),
                        sourceComposition.getSourceRole(),
                        placementProfile,
                        facing,
                        sourceAnchorPos
                );

        sourcePlacementPlans.add(sourcePlacementPlan);

        return sourcePlacementPlan;
    }

    public List<SourcePlacementPlan> getSourcePlacementPlans() {
        return Collections.unmodifiableList(
                sourcePlacementPlans
        );
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