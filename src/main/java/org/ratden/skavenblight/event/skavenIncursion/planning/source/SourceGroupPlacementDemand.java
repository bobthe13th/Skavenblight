package org.ratden.skavenblight.event.skavenIncursion.planning.source;

import org.ratden.skavenblight.event.skavenIncursion.planning.composition.SourceGroupComposition;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.SourceGroupRules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pre-placement infrastructure demand for one persistent physical source
 * group.
 *
 * This object is created before any group anchor, envelope, or physical source
 * position is selected.
 *
 * It answers:
 *
 * - which source-group compositions from different waves can share one
 *   persistent physical group;
 * - how many distinct physical sources that group ultimately requires;
 * - which source compositions reuse the same physical source across waves;
 * - what source profiles must be accommodated by the group's final envelope;
 * - how much source-group load the persistent infrastructure consumes.
 *
 * One physical source group may support at most one SourceGroupComposition
 * from any particular wave. Later waves may reuse the same physical sources.
 *
 * The demand model contains no world positions and performs no terrain
 * checks. SourcePlacementPlanner later converts it into a
 * SourceGroupPlacementPlan with a final SourceGroupEnvelope.
 */
public class SourceGroupPlacementDemand {

    private final UUID sourceGroupDemandId;
    private final UUID frontId;
    private final int sourceGroupIndex;

    private final SourceRole sourceRole;
    private final SourceGroupRules sourceGroupRules;
    private final SourceGroupSpatialRules sourceGroupSpatialRules;

    private final List<PhysicalSourceDemand> physicalSourceDemands;

    private final List<SourceGroupCompositionBinding>
            sourceGroupCompositionBindings;

    private final Map<Integer, UUID>
            sourceGroupCompositionIdByWave;

    /**
     * Creates a demand using the standard spatial rules.
     */
    public SourceGroupPlacementDemand(
            UUID frontId,
            int sourceGroupIndex,
            int initialWaveIndex,
            SourceGroupComposition initialSourceGroupComposition
    ) {
        this(
                frontId,
                sourceGroupIndex,
                initialWaveIndex,
                initialSourceGroupComposition,
                SourceGroupSpatialRules.STANDARD
        );
    }

    /**
     * Creates a demand from its first source-group composition.
     *
     * The first composition is assessed and bound immediately. Construction
     * fails when that composition cannot fit within its own source-group load
     * rules.
     */
    public SourceGroupPlacementDemand(
            UUID frontId,
            int sourceGroupIndex,
            int initialWaveIndex,
            SourceGroupComposition initialSourceGroupComposition,
            SourceGroupSpatialRules sourceGroupSpatialRules
    ) {
        if (frontId == null) {
            throw new IllegalArgumentException(
                    "Source-group demand front ID cannot be null."
            );
        }

        if (sourceGroupIndex < 0) {
            throw new IllegalArgumentException(
                    "Source-group demand index cannot be negative."
            );
        }

        if (initialWaveIndex < 0) {
            throw new IllegalArgumentException(
                    "Initial source-group demand wave index cannot be "
                            + "negative."
            );
        }

        if (initialSourceGroupComposition == null) {
            throw new IllegalArgumentException(
                    "Initial source-group composition cannot be null."
            );
        }

        if (initialSourceGroupComposition.isEmpty()) {
            throw new IllegalArgumentException(
                    "Initial source-group composition cannot be empty."
            );
        }

        if (sourceGroupSpatialRules == null) {
            throw new IllegalArgumentException(
                    "Source-group spatial rules cannot be null."
            );
        }

        this.sourceGroupDemandId =
                UUID.randomUUID();

        this.frontId =
                frontId;

        this.sourceGroupIndex =
                sourceGroupIndex;

        this.sourceRole =
                initialSourceGroupComposition.getSourceRole();

        this.sourceGroupRules =
                initialSourceGroupComposition.getSourceGroupRules();

        this.sourceGroupSpatialRules =
                sourceGroupSpatialRules;

        this.physicalSourceDemands =
                new ArrayList<>();

        this.sourceGroupCompositionBindings =
                new ArrayList<>();

        this.sourceGroupCompositionIdByWave =
                new LinkedHashMap<>();

        CompositionFitAssessment initialAssessment =
                assessComposition(
                        initialWaveIndex,
                        initialSourceGroupComposition
                );

        if (!initialAssessment.successful()) {
            throw new IllegalArgumentException(
                    "Initial source-group composition cannot create a "
                            + "physical source-group demand: "
                            + initialAssessment.failureMessage()
            );
        }

        applyComposition(
                initialSourceGroupComposition,
                initialAssessment
        );
    }

    public UUID getSourceGroupDemandId() {
        return sourceGroupDemandId;
    }

    public UUID getFrontId() {
        return frontId;
    }

    /**
     * Returns the zero-based persistent source-group order within the front.
     *
     * Group zero will later use the front anchor as its conceptual centre.
     * Additional groups receive separately planned anchors.
     */
    public int getSourceGroupIndex() {
        return sourceGroupIndex;
    }

    public SourceRole getSourceRole() {
        return sourceRole;
    }

    public SourceGroupRules getSourceGroupRules() {
        return sourceGroupRules;
    }

    public SourceGroupSpatialRules getSourceGroupSpatialRules() {
        return sourceGroupSpatialRules;
    }

    public int getMaximumSourceGroupLoad() {
        return sourceGroupRules.maximumLoad();
    }

    /**
     * Assesses whether another wave composition can share this persistent
     * source group.
     *
     * This method does not mutate the demand. SourceGroupDemandPlanner can
     * therefore compare several candidate groups and select the assessment
     * requiring the least additional infrastructure.
     */
    public CompositionFitAssessment assessComposition(
            int waveIndex,
            SourceGroupComposition sourceGroupComposition
    ) {
        String compatibilityFailure =
                findCompatibilityFailure(
                        waveIndex,
                        sourceGroupComposition
                );

        if (compatibilityFailure != null) {
            return CompositionFitAssessment.failure(
                    sourceGroupDemandId,
                    sourceGroupComposition == null
                            ? null
                            : sourceGroupComposition
                            .getSourceGroupCompositionId(),
                    waveIndex,
                    getTotalSourceGroupLoad(),
                    getPhysicalSourceCount(),
                    compatibilityFailure
            );
        }

        List<SourceGroupComposition.SourceComposition>
                orderedSourceCompositions =
                new ArrayList<>(
                        sourceGroupComposition.getSourceCompositions()
                );

        orderMostDemandingSourcesFirst(
                orderedSourceCompositions
        );

        Set<UUID> physicalSourcesUsedByThisComposition =
                new HashSet<>();

        List<SourceBindingDecision> bindingDecisions =
                new ArrayList<>();

        int additionalLoadRequired =
                0;

        int newPhysicalSourceCount =
                0;

        for (SourceGroupComposition.SourceComposition sourceComposition
                : orderedSourceCompositions) {

            PhysicalSourceDemand reusablePhysicalSource =
                    findBestReusablePhysicalSource(
                            sourceComposition,
                            physicalSourcesUsedByThisComposition
                    );

            if (reusablePhysicalSource != null) {
                physicalSourcesUsedByThisComposition.add(
                        reusablePhysicalSource
                                .getPhysicalSourceDemandId()
                );

                bindingDecisions.add(
                        SourceBindingDecision.reuse(
                                sourceComposition
                                        .getSourceCompositionId(),
                                reusablePhysicalSource
                                        .getPhysicalSourceDemandId()
                        )
                );

                continue;
            }

            SourcePlacementProfile requiredProfile =
                    SourcePlacementProfileCatalogue.require(
                            sourceComposition
                                    .getRequiredSourceType(),
                            sourceComposition
                                    .getRequiredSourceSize()
                    );

            additionalLoadRequired +=
                    requiredProfile.sourceGroupLoadCost();

            newPhysicalSourceCount++;

            bindingDecisions.add(
                    SourceBindingDecision.create(
                            sourceComposition
                                    .getSourceCompositionId()
                    )
            );
        }

        int currentLoad =
                getTotalSourceGroupLoad();

        int resultingLoad =
                currentLoad
                        + additionalLoadRequired;

        if (resultingLoad
                > getMaximumSourceGroupLoad()) {
            return CompositionFitAssessment.failure(
                    sourceGroupDemandId,
                    sourceGroupComposition
                            .getSourceGroupCompositionId(),
                    waveIndex,
                    currentLoad,
                    getPhysicalSourceCount(),
                    "Binding the composition would increase physical "
                            + "source-group load from "
                            + currentLoad
                            + " to "
                            + resultingLoad
                            + ", exceeding the maximum of "
                            + getMaximumSourceGroupLoad()
                            + "."
            );
        }

        return CompositionFitAssessment.success(
                sourceGroupDemandId,
                sourceGroupComposition
                        .getSourceGroupCompositionId(),
                waveIndex,
                currentLoad,
                getPhysicalSourceCount(),
                bindingDecisions,
                additionalLoadRequired,
                physicalSourceDemands.size()
                        + newPhysicalSourceCount
        );
    }

    /**
     * Applies a previously successful fit assessment.
     *
     * The assessment is checked against the current demand state so an old
     * assessment cannot be applied after another composition has changed the
     * infrastructure demand.
     */
    public void applyComposition(
            SourceGroupComposition sourceGroupComposition,
            CompositionFitAssessment fitAssessment
    ) {
        if (sourceGroupComposition == null) {
            throw new IllegalArgumentException(
                    "Source-group composition cannot be null."
            );
        }

        if (fitAssessment == null) {
            throw new IllegalArgumentException(
                    "Composition fit assessment cannot be null."
            );
        }

        if (!fitAssessment.successful()) {
            throw new IllegalArgumentException(
                    "A failed composition assessment cannot be applied: "
                            + fitAssessment.failureMessage()
            );
        }

        if (!sourceGroupDemandId.equals(
                fitAssessment.sourceGroupDemandId()
        )) {
            throw new IllegalArgumentException(
                    "Composition assessment belongs to a different "
                            + "source-group demand."
            );
        }

        if (!sourceGroupComposition
                .getSourceGroupCompositionId()
                .equals(
                        fitAssessment
                                .sourceGroupCompositionId()
                )) {
            throw new IllegalArgumentException(
                    "Composition assessment belongs to a different "
                            + "source-group composition."
            );
        }

        if (fitAssessment.expectedCurrentLoad()
                != getTotalSourceGroupLoad()) {
            throw new IllegalStateException(
                    "Source-group demand load changed after the composition "
                            + "was assessed."
            );
        }

        if (fitAssessment.expectedPhysicalSourceCount()
                != getPhysicalSourceCount()) {
            throw new IllegalStateException(
                    "Physical source demand count changed after the "
                            + "composition was assessed."
            );
        }

        String compatibilityFailure =
                findCompatibilityFailure(
                        fitAssessment.waveIndex(),
                        sourceGroupComposition
                );

        if (compatibilityFailure != null) {
            throw new IllegalArgumentException(
                    "Composition is no longer compatible with this "
                            + "source-group demand: "
                            + compatibilityFailure
            );
        }

        Map<UUID, SourceGroupComposition.SourceComposition>
                sourceCompositionsById =
                indexSourceCompositions(
                        sourceGroupComposition
                );

        if (fitAssessment.bindingDecisions().size()
                != sourceCompositionsById.size()) {
            throw new IllegalArgumentException(
                    "Composition fit assessment contains the wrong number "
                            + "of source-binding decisions."
            );
        }

        List<PendingBinding> pendingBindings =
                new ArrayList<>();

        Set<UUID> assessedSourceCompositionIds =
                new HashSet<>();

        Set<UUID> reusedPhysicalSourceIds =
                new HashSet<>();

        int calculatedAdditionalLoad =
                0;

        for (SourceBindingDecision bindingDecision
                : fitAssessment.bindingDecisions()) {

            if (!assessedSourceCompositionIds.add(
                    bindingDecision.sourceCompositionId()
            )) {
                throw new IllegalArgumentException(
                        "Composition fit assessment contains duplicate "
                                + "source-composition ID "
                                + bindingDecision
                                .sourceCompositionId()
                                + "."
                );
            }

            SourceGroupComposition.SourceComposition
                    sourceComposition =
                    sourceCompositionsById.get(
                            bindingDecision
                                    .sourceCompositionId()
                    );

            if (sourceComposition == null) {
                throw new IllegalArgumentException(
                        "Composition fit assessment refers to unknown "
                                + "source-composition ID "
                                + bindingDecision
                                .sourceCompositionId()
                                + "."
                );
            }

            if (bindingDecision.reusesPhysicalSource()) {
                UUID physicalSourceDemandId =
                        bindingDecision
                                .physicalSourceDemandId();

                if (!reusedPhysicalSourceIds.add(
                        physicalSourceDemandId
                )) {
                    throw new IllegalArgumentException(
                            "Composition fit assessment attempts to reuse "
                                    + "physical source demand "
                                    + physicalSourceDemandId
                                    + " more than once in the same wave "
                                    + "composition."
                    );
                }

                PhysicalSourceDemand physicalSourceDemand =
                        getPhysicalSourceDemand(
                                physicalSourceDemandId
                        );

                if (physicalSourceDemand == null) {
                    throw new IllegalArgumentException(
                            "Composition fit assessment refers to unknown "
                                    + "physical source demand "
                                    + physicalSourceDemandId
                                    + "."
                    );
                }

                if (!physicalSourceDemand.canSupport(
                        sourceComposition
                )) {
                    throw new IllegalArgumentException(
                            "Physical source demand "
                                    + physicalSourceDemandId
                                    + " can no longer support source "
                                    + "composition "
                                    + sourceComposition
                                    .getSourceCompositionId()
                                    + "."
                    );
                }

                pendingBindings.add(
                        PendingBinding.reuse(
                                sourceComposition,
                                physicalSourceDemand
                        )
                );

                continue;
            }

            PhysicalSourceDemand newPhysicalSourceDemand =
                    new PhysicalSourceDemand(
                            sourceGroupDemandId,
                            sourceRole,
                            sourceComposition
                                    .getRequiredSourceType(),
                            sourceComposition
                                    .getRequiredSourceSize(),
                            fitAssessment.waveIndex()
                    );

            calculatedAdditionalLoad +=
                    newPhysicalSourceDemand
                            .getSourceGroupLoadCost();

            pendingBindings.add(
                    PendingBinding.create(
                            sourceComposition,
                            newPhysicalSourceDemand
                    )
            );
        }

        if (assessedSourceCompositionIds.size()
                != sourceCompositionsById.size()) {
            throw new IllegalArgumentException(
                    "Composition fit assessment does not bind every source "
                            + "composition."
            );
        }

        if (calculatedAdditionalLoad
                != fitAssessment.additionalLoadRequired()) {
            throw new IllegalStateException(
                    "Composition fit assessment additional load changed "
                            + "before application."
            );
        }

        int resultingLoad =
                getTotalSourceGroupLoad()
                        + calculatedAdditionalLoad;

        if (resultingLoad
                > getMaximumSourceGroupLoad()) {
            throw new IllegalStateException(
                    "Applying composition would exceed the source-group "
                            + "load maximum."
            );
        }

        List<SourceDemandBinding> appliedBindings =
                new ArrayList<>();

        for (PendingBinding pendingBinding
                : pendingBindings) {

            PhysicalSourceDemand physicalSourceDemand =
                    pendingBinding.physicalSourceDemand();

            if (pendingBinding.createsPhysicalSource()) {
                physicalSourceDemands.add(
                        physicalSourceDemand
                );
            }

            physicalSourceDemand.bindSourceComposition(
                    pendingBinding
                            .sourceComposition()
                            .getSourceCompositionId()
            );

            appliedBindings.add(
                    new SourceDemandBinding(
                            pendingBinding
                                    .sourceComposition()
                                    .getSourceCompositionId(),
                            physicalSourceDemand
                                    .getPhysicalSourceDemandId()
                    )
            );
        }

        sourceGroupCompositionBindings.add(
                new SourceGroupCompositionBinding(
                        fitAssessment.waveIndex(),
                        sourceGroupComposition
                                .getSourceGroupCompositionId(),
                        appliedBindings
                )
        );

        sourceGroupCompositionIdByWave.put(
                fitAssessment.waveIndex(),
                sourceGroupComposition
                        .getSourceGroupCompositionId()
        );

        if (getTotalSourceGroupLoad()
                != fitAssessment.resultingLoad()) {
            throw new IllegalStateException(
                    "Applied source-group demand load does not match the "
                            + "successful assessment."
            );
        }

        if (getPhysicalSourceCount()
                != fitAssessment
                .resultingPhysicalSourceCount()) {
            throw new IllegalStateException(
                    "Applied physical source count does not match the "
                            + "successful assessment."
            );
        }
    }

    public List<PhysicalSourceDemand> getPhysicalSourceDemands() {
        return Collections.unmodifiableList(
                physicalSourceDemands
        );
    }

    public PhysicalSourceDemand getPhysicalSourceDemand(
            UUID physicalSourceDemandId
    ) {
        if (physicalSourceDemandId == null) {
            return null;
        }

        for (PhysicalSourceDemand physicalSourceDemand
                : physicalSourceDemands) {

            if (physicalSourceDemand
                    .getPhysicalSourceDemandId()
                    .equals(
                            physicalSourceDemandId
                    )) {
                return physicalSourceDemand;
            }
        }

        return null;
    }

    public List<SourceGroupCompositionBinding>
    getSourceGroupCompositionBindings() {
        return Collections.unmodifiableList(
                sourceGroupCompositionBindings
        );
    }

    public boolean isBoundToWave(
            int waveIndex
    ) {
        return sourceGroupCompositionIdByWave.containsKey(
                waveIndex
        );
    }

    public UUID getSourceGroupCompositionIdForWave(
            int waveIndex
    ) {
        return sourceGroupCompositionIdByWave.get(
                waveIndex
        );
    }

    public int getBoundWaveCount() {
        return sourceGroupCompositionIdByWave.size();
    }

    public int getPhysicalSourceCount() {
        return physicalSourceDemands.size();
    }

    public int getTotalSourceGroupLoad() {
        int totalLoad =
                0;

        for (PhysicalSourceDemand physicalSourceDemand
                : physicalSourceDemands) {

            totalLoad +=
                    physicalSourceDemand
                            .getSourceGroupLoadCost();
        }

        return totalLoad;
    }

    public int getRemainingSourceGroupLoad() {
        return sourceGroupRules.getRemainingLoad(
                getTotalSourceGroupLoad()
        );
    }

    public boolean isWithinSourceGroupLoadLimit() {
        return getTotalSourceGroupLoad()
                <= getMaximumSourceGroupLoad();
    }

    /**
     * Returns one placement profile for every distinct persistent physical
     * source required by this group.
     *
     * SourceGroupSpatialRules uses this collection when calculating the
     * initial adaptive envelope radius.
     */
    public List<SourcePlacementProfile>
    getRequiredPlacementProfiles() {
        List<SourcePlacementProfile> placementProfiles =
                new ArrayList<>();

        for (PhysicalSourceDemand physicalSourceDemand
                : physicalSourceDemands) {

            placementProfiles.add(
                    physicalSourceDemand
                            .getPlacementProfile()
            );
        }

        return List.copyOf(
                placementProfiles
        );
    }

    public int calculateInitialEnvelopeRadius() {
        return sourceGroupSpatialRules.calculateInitialRadius(
                getRequiredPlacementProfiles()
        );
    }

    public List<Integer> createEnvelopeRadiusAttempts() {
        return sourceGroupSpatialRules.createRadiusAttempts(
                calculateInitialEnvelopeRadius()
        );
    }

    private String findCompatibilityFailure(
            int waveIndex,
            SourceGroupComposition sourceGroupComposition
    ) {
        if (waveIndex < 0) {
            return "Wave index cannot be negative.";
        }

        if (sourceGroupComposition == null) {
            return "Source-group composition cannot be null.";
        }

        if (sourceGroupComposition.isEmpty()) {
            return "Source-group composition cannot be empty.";
        }

        if (sourceGroupComposition.getSourceRole()
                != sourceRole) {
            return "Source-group composition role "
                    + sourceGroupComposition.getSourceRole()
                    + " does not match demand role "
                    + sourceRole
                    + ".";
        }

        if (!sourceGroupRules.equals(
                sourceGroupComposition.getSourceGroupRules()
        )) {
            return "Source-group composition uses different load rules.";
        }

        if (sourceGroupCompositionIdByWave.containsKey(
                waveIndex
        )) {
            return "Persistent source group already has a composition "
                    + "assigned for wave "
                    + waveIndex
                    + ".";
        }

        UUID sourceGroupCompositionId =
                sourceGroupComposition
                        .getSourceGroupCompositionId();

        for (SourceGroupCompositionBinding existingBinding
                : sourceGroupCompositionBindings) {

            if (existingBinding
                    .sourceGroupCompositionId()
                    .equals(
                            sourceGroupCompositionId
                    )) {
                return "Source-group composition is already bound to this "
                        + "demand.";
            }
        }

        return null;
    }

    private PhysicalSourceDemand findBestReusablePhysicalSource(
            SourceGroupComposition.SourceComposition sourceComposition,
            Set<UUID> physicalSourcesAlreadyUsed
    ) {
        PhysicalSourceDemand bestMatch =
                null;

        for (PhysicalSourceDemand physicalSourceDemand
                : physicalSourceDemands) {

            if (physicalSourcesAlreadyUsed.contains(
                    physicalSourceDemand
                            .getPhysicalSourceDemandId()
            )) {
                continue;
            }

            if (!physicalSourceDemand.canSupport(
                    sourceComposition
            )) {
                continue;
            }

            if (bestMatch == null
                    || isBetterPhysicalSourceMatch(
                    physicalSourceDemand,
                    bestMatch
            )) {
                bestMatch =
                        physicalSourceDemand;
            }
        }

        return bestMatch;
    }

    /**
     * Prefers the smallest compatible persistent source so larger sources are
     * preserved for compositions that genuinely require them.
     */
    private boolean isBetterPhysicalSourceMatch(
            PhysicalSourceDemand candidate,
            PhysicalSourceDemand currentBest
    ) {
        int candidateCapacity =
                candidate.getRequiredSourceSize()
                        .getCapacityUnits();

        int currentBestCapacity =
                currentBest.getRequiredSourceSize()
                        .getCapacityUnits();

        if (candidateCapacity
                != currentBestCapacity) {
            return candidateCapacity
                    < currentBestCapacity;
        }

        int candidateLoad =
                candidate.getSourceGroupLoadCost();

        int currentBestLoad =
                currentBest.getSourceGroupLoadCost();

        if (candidateLoad
                != currentBestLoad) {
            return candidateLoad
                    < currentBestLoad;
        }

        return candidate
                .getPhysicalSourceDemandId()
                .compareTo(
                        currentBest
                                .getPhysicalSourceDemandId()
                ) < 0;
    }

    private static void orderMostDemandingSourcesFirst(
            List<SourceGroupComposition.SourceComposition>
                    sourceCompositions
    ) {
        sourceCompositions.sort(
                (first, second) -> {
                    int sizeComparison =
                            Integer.compare(
                                    second
                                            .getRequiredSourceSize()
                                            .getCapacityUnits(),
                                    first
                                            .getRequiredSourceSize()
                                            .getCapacityUnits()
                            );

                    if (sizeComparison != 0) {
                        return sizeComparison;
                    }

                    int loadComparison =
                            Integer.compare(
                                    second
                                            .getSourceGroupLoadCost(),
                                    first
                                            .getSourceGroupLoadCost()
                            );

                    if (loadComparison != 0) {
                        return loadComparison;
                    }

                    return first
                            .getSourceCompositionId()
                            .compareTo(
                                    second
                                            .getSourceCompositionId()
                            );
                }
        );
    }

    private static Map<
            UUID,
            SourceGroupComposition.SourceComposition
            > indexSourceCompositions(
            SourceGroupComposition sourceGroupComposition
    ) {
        Map<
                UUID,
                SourceGroupComposition.SourceComposition
                > sourceCompositionsById =
                new HashMap<>();

        for (SourceGroupComposition.SourceComposition sourceComposition
                : sourceGroupComposition.getSourceCompositions()) {

            SourceGroupComposition.SourceComposition previous =
                    sourceCompositionsById.put(
                            sourceComposition
                                    .getSourceCompositionId(),
                            sourceComposition
                    );

            if (previous != null) {
                throw new IllegalArgumentException(
                        "Source-group composition contains duplicate "
                                + "source-composition ID "
                                + sourceComposition
                                .getSourceCompositionId()
                                + "."
                );
            }
        }

        return sourceCompositionsById;
    }

    /**
     * Demand for one distinct persistent physical source.
     *
     * The source may execute several SourceCompositions across different
     * waves, but no more than one during any single source-group composition.
     */
    public static class PhysicalSourceDemand {

        private final UUID physicalSourceDemandId;
        private final UUID sourceGroupDemandId;

        private final SourceRole sourceRole;
        private final SourceType requiredSourceType;
        private final SourceSize requiredSourceSize;
        private final SourcePlacementProfile placementProfile;

        private final int firstRequiredWaveIndex;

        private final List<UUID> sourceCompositionIds;

        private PhysicalSourceDemand(
                UUID sourceGroupDemandId,
                SourceRole sourceRole,
                SourceType requiredSourceType,
                SourceSize requiredSourceSize,
                int firstRequiredWaveIndex
        ) {
            if (sourceGroupDemandId == null) {
                throw new IllegalArgumentException(
                        "Physical source demand group ID cannot be null."
                );
            }

            if (sourceRole == null) {
                throw new IllegalArgumentException(
                        "Physical source demand role cannot be null."
                );
            }

            if (requiredSourceType == null) {
                throw new IllegalArgumentException(
                        "Physical source demand type cannot be null."
                );
            }

            if (requiredSourceSize == null) {
                throw new IllegalArgumentException(
                        "Physical source demand size cannot be null."
                );
            }

            if (firstRequiredWaveIndex < 0) {
                throw new IllegalArgumentException(
                        "Physical source demand first wave cannot be "
                                + "negative."
                );
            }

            this.physicalSourceDemandId =
                    UUID.randomUUID();

            this.sourceGroupDemandId =
                    sourceGroupDemandId;

            this.sourceRole =
                    sourceRole;

            this.requiredSourceType =
                    requiredSourceType;

            this.requiredSourceSize =
                    requiredSourceSize;

            this.placementProfile =
                    SourcePlacementProfileCatalogue.require(
                            requiredSourceType,
                            requiredSourceSize
                    );

            this.firstRequiredWaveIndex =
                    firstRequiredWaveIndex;

            this.sourceCompositionIds =
                    new ArrayList<>();
        }

        public UUID getPhysicalSourceDemandId() {
            return physicalSourceDemandId;
        }

        public UUID getSourceGroupDemandId() {
            return sourceGroupDemandId;
        }

        public SourceRole getSourceRole() {
            return sourceRole;
        }

        public SourceType getRequiredSourceType() {
            return requiredSourceType;
        }

        public SourceSize getRequiredSourceSize() {
            return requiredSourceSize;
        }

        public SourcePlacementProfile getPlacementProfile() {
            return placementProfile;
        }

        public int getFirstRequiredWaveIndex() {
            return firstRequiredWaveIndex;
        }

        public int getSourceGroupLoadCost() {
            return placementProfile.sourceGroupLoadCost();
        }

        public boolean canSupport(
                SourceGroupComposition.SourceComposition
                        sourceComposition
        ) {
            if (sourceComposition == null) {
                return false;
            }

            if (sourceComposition.getSourceRole()
                    != sourceRole) {
                return false;
            }

            if (sourceComposition.getRequiredSourceType()
                    != requiredSourceType) {
                return false;
            }

            return requiredSourceSize.canFit(
                    sourceComposition.getRequiredSourceSize()
            );
        }

        private void bindSourceComposition(
                UUID sourceCompositionId
        ) {
            if (sourceCompositionId == null) {
                throw new IllegalArgumentException(
                        "Bound source-composition ID cannot be null."
                );
            }

            if (sourceCompositionIds.contains(
                    sourceCompositionId
            )) {
                throw new IllegalArgumentException(
                        "Physical source demand "
                                + physicalSourceDemandId
                                + " is already bound to source composition "
                                + sourceCompositionId
                                + "."
                );
            }

            sourceCompositionIds.add(
                    sourceCompositionId
            );
        }

        public List<UUID> getSourceCompositionIds() {
            return Collections.unmodifiableList(
                    sourceCompositionIds
            );
        }

        public int getBoundSourceCompositionCount() {
            return sourceCompositionIds.size();
        }
    }

    /**
     * Records how one wave's SourceGroupComposition is distributed across
     * this demand's persistent physical sources.
     */
    public record SourceGroupCompositionBinding(
            int waveIndex,
            UUID sourceGroupCompositionId,
            List<SourceDemandBinding> sourceBindings
    ) {

        public SourceGroupCompositionBinding {
            if (waveIndex < 0) {
                throw new IllegalArgumentException(
                        "Source-group composition binding wave cannot be "
                                + "negative."
                );
            }

            if (sourceGroupCompositionId == null) {
                throw new IllegalArgumentException(
                        "Bound source-group composition ID cannot be null."
                );
            }

            if (sourceBindings == null
                    || sourceBindings.isEmpty()) {
                throw new IllegalArgumentException(
                        "Source-group composition binding requires at least "
                                + "one source binding."
                );
            }

            sourceBindings =
                    List.copyOf(
                            sourceBindings
                    );
        }
    }

    /**
     * Binds one source-sized composition package to one persistent physical
     * source demand.
     */
    public record SourceDemandBinding(
            UUID sourceCompositionId,
            UUID physicalSourceDemandId
    ) {

        public SourceDemandBinding {
            if (sourceCompositionId == null) {
                throw new IllegalArgumentException(
                        "Source-composition binding ID cannot be null."
                );
            }

            if (physicalSourceDemandId == null) {
                throw new IllegalArgumentException(
                        "Physical source demand binding ID cannot be null."
                );
            }
        }
    }

    /**
     * Non-mutating assessment used when choosing which persistent group
     * should receive another wave composition.
     */
    public record CompositionFitAssessment(
            UUID sourceGroupDemandId,
            UUID sourceGroupCompositionId,
            int waveIndex,
            boolean successful,
            int expectedCurrentLoad,
            int expectedPhysicalSourceCount,
            List<SourceBindingDecision> bindingDecisions,
            int additionalLoadRequired,
            int resultingLoad,
            int resultingPhysicalSourceCount,
            String failureMessage
    ) {

        public CompositionFitAssessment {
            if (sourceGroupDemandId == null) {
                throw new IllegalArgumentException(
                        "Composition assessment source-group demand ID "
                                + "cannot be null."
                );
            }

            if (waveIndex < 0) {
                throw new IllegalArgumentException(
                        "Composition assessment wave index cannot be "
                                + "negative."
                );
            }

            if (expectedCurrentLoad < 0
                    || expectedPhysicalSourceCount < 0
                    || additionalLoadRequired < 0
                    || resultingLoad < 0
                    || resultingPhysicalSourceCount < 0) {
                throw new IllegalArgumentException(
                        "Composition assessment counts and loads cannot be "
                                + "negative."
                );
            }

            if (bindingDecisions == null) {
                throw new IllegalArgumentException(
                        "Composition assessment binding decisions cannot be "
                                + "null."
                );
            }

            bindingDecisions =
                    List.copyOf(
                            bindingDecisions
                    );

            boolean hasFailureMessage =
                    failureMessage != null
                            && !failureMessage.isBlank();

            if (successful) {
                if (sourceGroupCompositionId == null) {
                    throw new IllegalArgumentException(
                            "Successful composition assessment requires a "
                                    + "source-group composition ID."
                    );
                }

                if (bindingDecisions.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Successful composition assessment requires "
                                    + "binding decisions."
                    );
                }

                if (hasFailureMessage) {
                    throw new IllegalArgumentException(
                            "Successful composition assessment cannot "
                                    + "contain a failure message."
                    );
                }

                if (resultingLoad
                        != expectedCurrentLoad
                        + additionalLoadRequired) {
                    throw new IllegalArgumentException(
                            "Successful composition assessment contains an "
                                    + "inconsistent resulting load."
                    );
                }

                if (resultingPhysicalSourceCount
                        < expectedPhysicalSourceCount) {
                    throw new IllegalArgumentException(
                            "Successful composition assessment cannot reduce "
                                    + "the physical source count."
                    );
                }
            } else {
                if (!bindingDecisions.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Failed composition assessment cannot contain "
                                    + "binding decisions."
                    );
                }

                if (!hasFailureMessage) {
                    throw new IllegalArgumentException(
                            "Failed composition assessment requires a "
                                    + "failure message."
                    );
                }
            }
        }

        private static CompositionFitAssessment success(
                UUID sourceGroupDemandId,
                UUID sourceGroupCompositionId,
                int waveIndex,
                int expectedCurrentLoad,
                int expectedPhysicalSourceCount,
                List<SourceBindingDecision> bindingDecisions,
                int additionalLoadRequired,
                int resultingPhysicalSourceCount
        ) {
            return new CompositionFitAssessment(
                    sourceGroupDemandId,
                    sourceGroupCompositionId,
                    waveIndex,
                    true,
                    expectedCurrentLoad,
                    expectedPhysicalSourceCount,
                    bindingDecisions,
                    additionalLoadRequired,
                    expectedCurrentLoad
                            + additionalLoadRequired,
                    resultingPhysicalSourceCount,
                    null
            );
        }

        private static CompositionFitAssessment failure(
                UUID sourceGroupDemandId,
                UUID sourceGroupCompositionId,
                int waveIndex,
                int expectedCurrentLoad,
                int expectedPhysicalSourceCount,
                String failureMessage
        ) {
            return new CompositionFitAssessment(
                    sourceGroupDemandId,
                    sourceGroupCompositionId,
                    waveIndex,
                    false,
                    expectedCurrentLoad,
                    expectedPhysicalSourceCount,
                    List.of(),
                    0,
                    expectedCurrentLoad,
                    expectedPhysicalSourceCount,
                    failureMessage
            );
        }

        public int getReusedPhysicalSourceCount() {
            int reusedCount =
                    0;

            for (SourceBindingDecision bindingDecision
                    : bindingDecisions) {

                if (bindingDecision.reusesPhysicalSource()) {
                    reusedCount++;
                }
            }

            return reusedCount;
        }

        public int getNewPhysicalSourceCount() {
            return resultingPhysicalSourceCount
                    - expectedPhysicalSourceCount;
        }
    }

    /**
     * One proposed source-composition binding.
     *
     * A null physicalSourceDemandId means a new persistent physical source
     * must be created when the assessment is applied.
     */
    public record SourceBindingDecision(
            UUID sourceCompositionId,
            UUID physicalSourceDemandId
    ) {

        public SourceBindingDecision {
            if (sourceCompositionId == null) {
                throw new IllegalArgumentException(
                        "Source-binding decision composition ID cannot be "
                                + "null."
                );
            }
        }

        private static SourceBindingDecision reuse(
                UUID sourceCompositionId,
                UUID physicalSourceDemandId
        ) {
            if (physicalSourceDemandId == null) {
                throw new IllegalArgumentException(
                        "Reused physical source demand ID cannot be null."
                );
            }

            return new SourceBindingDecision(
                    sourceCompositionId,
                    physicalSourceDemandId
            );
        }

        private static SourceBindingDecision create(
                UUID sourceCompositionId
        ) {
            return new SourceBindingDecision(
                    sourceCompositionId,
                    null
            );
        }

        public boolean reusesPhysicalSource() {
            return physicalSourceDemandId != null;
        }

        public boolean createsPhysicalSource() {
            return physicalSourceDemandId == null;
        }
    }

    private record PendingBinding(
            SourceGroupComposition.SourceComposition sourceComposition,
            PhysicalSourceDemand physicalSourceDemand,
            boolean createsPhysicalSource
    ) {

        private PendingBinding {
            if (sourceComposition == null) {
                throw new IllegalArgumentException(
                        "Pending source composition cannot be null."
                );
            }

            if (physicalSourceDemand == null) {
                throw new IllegalArgumentException(
                        "Pending physical source demand cannot be null."
                );
            }
        }

        private static PendingBinding reuse(
                SourceGroupComposition.SourceComposition sourceComposition,
                PhysicalSourceDemand physicalSourceDemand
        ) {
            return new PendingBinding(
                    sourceComposition,
                    physicalSourceDemand,
                    false
            );
        }

        private static PendingBinding create(
                SourceGroupComposition.SourceComposition sourceComposition,
                PhysicalSourceDemand physicalSourceDemand
        ) {
            return new PendingBinding(
                    sourceComposition,
                    physicalSourceDemand,
                    true
            );
        }
    }
}