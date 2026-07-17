package org.ratden.skavenblight.event.skavenIncursion.planning.front;

import net.minecraft.core.BlockPos;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.SourceGroupComposition;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceGroupPlacementPlan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Holds the complete planned result for one tactical front.
 *
 * Focused planners progressively add wave, composition, and source-placement
 * results to this object. FrontPlan itself does not calculate those results.
 */
public class FrontPlan {

    private final UUID frontId;
    private final int frontIndex;

    private final BlockPos anchorPos;
    private final FrontPlacementPattern placementPattern;

    private final double threatShare;
    private final double complexityShare;
    private final boolean dominant;

    private final List<WavePlan> wavePlans;
    private final List<SourceGroupPlacementPlan> sourceGroupPlacementPlans;

    public FrontPlan(
            UUID frontId,
            int frontIndex,
            BlockPos anchorPos,
            FrontPlacementPattern placementPattern,
            double threatShare,
            double complexityShare,
            boolean dominant
    ) {
        if (frontId == null) {
            throw new IllegalArgumentException("Front ID cannot be null.");
        }

        if (frontIndex < 0) {
            throw new IllegalArgumentException(
                    "Front index cannot be negative."
            );
        }

        if (anchorPos == null) {
            throw new IllegalArgumentException(
                    "Front anchor position cannot be null."
            );
        }

        if (placementPattern == null) {
            throw new IllegalArgumentException(
                    "Front placement pattern cannot be null."
            );
        }

        validateShare(threatShare, "Threat");
        validateShare(complexityShare, "Complexity");

        this.frontId = frontId;
        this.frontIndex = frontIndex;
        this.anchorPos = anchorPos.immutable();
        this.placementPattern = placementPattern;
        this.threatShare = threatShare;
        this.complexityShare = complexityShare;
        this.dominant = dominant;

        this.wavePlans = new ArrayList<>();
        this.sourceGroupPlacementPlans = new ArrayList<>();
    }

    public UUID getFrontId() {
        return frontId;
    }

    /**
     * Zero-based position of this front within the IncursionPlan.
     *
     * Debug formatting can display this as F1 by adding one.
     */
    public int getFrontIndex() {
        return frontIndex;
    }

    public BlockPos getAnchorPos() {
        return anchorPos;
    }

    public FrontPlacementPattern getPlacementPattern() {
        return placementPattern;
    }

    public double getThreatShare() {
        return threatShare;
    }

    public double getComplexityShare() {
        return complexityShare;
    }

    public boolean isDominant() {
        return dominant;
    }

    public void addWavePlan(WavePlan wavePlan) {
        if (wavePlan == null) {
            throw new IllegalArgumentException(
                    "Wave plan cannot be null."
            );
        }

        for (WavePlan existingWavePlan : wavePlans) {
            if (existingWavePlan.getWaveIndex()
                    == wavePlan.getWaveIndex()) {
                throw new IllegalArgumentException(
                        "Front already contains wave index "
                                + wavePlan.getWaveIndex()
                                + "."
                );
            }
        }

        wavePlans.add(wavePlan);
    }

    public List<WavePlan> getWavePlans() {
        return Collections.unmodifiableList(wavePlans);
    }

    public boolean hasWavePlans() {
        return !wavePlans.isEmpty();
    }

    public WavePlan getWavePlan(int waveIndex) {
        for (WavePlan wavePlan : wavePlans) {
            if (wavePlan.getWaveIndex() == waveIndex) {
                return wavePlan;
            }
        }

        return null;
    }

    public void addSourceGroupPlacementPlan(
            SourceGroupPlacementPlan sourceGroupPlacementPlan
    ) {
        if (sourceGroupPlacementPlan == null) {
            throw new IllegalArgumentException(
                    "Source-group placement plan cannot be null."
            );
        }

        if (!frontId.equals(sourceGroupPlacementPlan.getFrontId())) {
            throw new IllegalArgumentException(
                    "Source-group placement belongs to a different front."
            );
        }

        for (SourceGroupPlacementPlan existingPlan
                : sourceGroupPlacementPlans) {
            if (existingPlan.getSourceGroupPlacementId().equals(
                    sourceGroupPlacementPlan.getSourceGroupPlacementId()
            )) {
                throw new IllegalArgumentException(
                        "Front already contains source-group placement ID "
                                + sourceGroupPlacementPlan
                                .getSourceGroupPlacementId()
                                + "."
                );
            }
        }

        sourceGroupPlacementPlans.add(sourceGroupPlacementPlan);
    }

    public List<SourceGroupPlacementPlan>
    getSourceGroupPlacementPlans() {
        return Collections.unmodifiableList(sourceGroupPlacementPlans);
    }

    public boolean hasSourceGroupPlacementPlans() {
        return !sourceGroupPlacementPlans.isEmpty();
    }

    public SourceGroupPlacementPlan getSourceGroupPlacementPlan(
            UUID sourceGroupPlacementId
    ) {
        if (sourceGroupPlacementId == null) {
            return null;
        }

        for (SourceGroupPlacementPlan sourceGroupPlacementPlan
                : sourceGroupPlacementPlans) {
            if (sourceGroupPlacementPlan
                    .getSourceGroupPlacementId()
                    .equals(sourceGroupPlacementId)) {
                return sourceGroupPlacementPlan;
            }
        }

        return null;
    }

    private static void validateShare(
            double share,
            String shareName
    ) {
        if (!Double.isFinite(share)) {
            throw new IllegalArgumentException(
                    shareName + " share must be a finite number."
            );
        }

        if (share < 0.0D || share > 1.0D) {
            throw new IllegalArgumentException(
                    shareName
                            + " share must be between 0.0 and 1.0."
            );
        }
    }

    /**
     * Stores the resolved planning result for one wave within this front.
     *
     * The shares describe this wave's portion of the total incursion budget.
     * The integer budgets are the exact amounts assigned to this particular
     * front after both wave and front allocations have been resolved.
     *
     * SourceGroupComposition objects record how that wave's threat and
     * complexity were spent before physical source placement occurs.
     */
    public static class WavePlan {

        private final int waveIndex;

        private final double threatShare;
        private final double complexityShare;

        private final int threatBudget;
        private final int complexityBudget;

        private final List<SourceGroupComposition> sourceGroupCompositions;

        public WavePlan(
                int waveIndex,
                double threatShare,
                double complexityShare,
                int threatBudget,
                int complexityBudget
        ) {
            if (waveIndex < 0) {
                throw new IllegalArgumentException(
                        "Wave index cannot be negative."
                );
            }

            validateShare(threatShare, "Wave threat");
            validateShare(complexityShare, "Wave complexity");

            if (threatBudget < 0) {
                throw new IllegalArgumentException(
                        "Wave threat budget cannot be negative."
                );
            }

            if (complexityBudget < 0) {
                throw new IllegalArgumentException(
                        "Wave complexity budget cannot be negative."
                );
            }

            this.waveIndex = waveIndex;
            this.threatShare = threatShare;
            this.complexityShare = complexityShare;
            this.threatBudget = threatBudget;
            this.complexityBudget = complexityBudget;
            this.sourceGroupCompositions = new ArrayList<>();
        }

        /**
         * Zero-based wave position.
         *
         * Debug formatting can display this as W1 by adding one.
         */
        public int getWaveIndex() {
            return waveIndex;
        }

        public double getThreatShare() {
            return threatShare;
        }

        public double getComplexityShare() {
            return complexityShare;
        }

        public int getThreatBudget() {
            return threatBudget;
        }

        public int getComplexityBudget() {
            return complexityBudget;
        }

        public void addSourceGroupComposition(
                SourceGroupComposition sourceGroupComposition
        ) {
            if (sourceGroupComposition == null) {
                throw new IllegalArgumentException(
                        "Source-group composition cannot be null."
                );
            }

            sourceGroupCompositions.add(sourceGroupComposition);
        }

        public List<SourceGroupComposition> getSourceGroupCompositions() {
            return Collections.unmodifiableList(sourceGroupCompositions);
        }

        public boolean hasSourceGroupCompositions() {
            return !sourceGroupCompositions.isEmpty();
        }

        public int getThreatSpent() {
            int total = 0;

            for (SourceGroupComposition sourceGroupComposition
                    : sourceGroupCompositions) {
                total += sourceGroupComposition.getTotalThreatSpent();
            }

            return total;
        }

        public int getUnspentThreat() {
            return threatBudget - getThreatSpent();
        }
    }
}