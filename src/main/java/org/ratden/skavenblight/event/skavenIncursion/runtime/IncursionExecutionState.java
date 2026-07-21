package org.ratden.skavenblight.event.skavenIncursion.runtime;

import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.SourceGroupComposition;
import org.ratden.skavenblight.event.skavenIncursion.planning.front.FrontPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceGroupPlacementPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourcePlacementPlan;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceExecutionState;
import org.ratden.skavenblight.event.skavenIncursion.runtime.source.SourceWaveExecutionState;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Mutable runtime state derived from one completed IncursionPlan.
 *
 * The IncursionPlan remains the immutable description of what was planned.
 * This class builds the runtime objects that execute that plan:
 *
 * - one persistent SourceExecutionState for each physical source placement;
 * - one SourceWaveExecutionState for each source composition assigned during
 *   each wave.
 *
 * SourceExecutionState objects are shared across waves so a physical source
 * location can be reused without duplicating its runtime history.
 *
 * This class does not tick waves, create sources, spawn mobs, or determine
 * wave-transition timing.
 */
public class IncursionExecutionState {

    private final IncursionPlan incursionPlan;

    private final Map<UUID, SourceExecutionState>
            sourceExecutionStatesByPlacementId;

    private final Map<Integer, List<SourceWaveExecutionState>>
            waveSourceStatesByIndex;

    private final List<Integer> waveIndexes;

    public IncursionExecutionState(
            IncursionPlan incursionPlan
    ) {
        if (incursionPlan == null) {
            throw new IllegalArgumentException(
                    "Incursion plan cannot be null."
            );
        }

        if (!incursionPlan.hasFrontPlans()) {
            throw new IllegalArgumentException(
                    "Runtime execution requires an incursion plan "
                            + "containing at least one front."
            );
        }

        this.incursionPlan =
                incursionPlan;

        LinkedHashMap<UUID, SourceExecutionState>
                mutableSourceStates =
                createSourceExecutionStates(
                        incursionPlan
                );

        TreeMap<Integer, List<SourceWaveExecutionState>>
                mutableWaveStates =
                createWaveExecutionStates(
                        incursionPlan,
                        mutableSourceStates
                );

        if (mutableWaveStates.isEmpty()) {
            throw new IllegalArgumentException(
                    "Runtime execution requires an incursion plan "
                            + "containing at least one wave."
            );
        }

        sourceExecutionStatesByPlacementId =
                Collections.unmodifiableMap(
                        new LinkedHashMap<>(
                                mutableSourceStates
                        )
                );

        LinkedHashMap<Integer, List<SourceWaveExecutionState>>
                immutableWaveStates =
                new LinkedHashMap<>();

        for (Map.Entry<Integer, List<SourceWaveExecutionState>>
                entry
                : mutableWaveStates.entrySet()) {

            immutableWaveStates.put(
                    entry.getKey(),
                    List.copyOf(
                            entry.getValue()
                    )
            );
        }

        waveSourceStatesByIndex =
                Collections.unmodifiableMap(
                        immutableWaveStates
                );

        waveIndexes =
                List.copyOf(
                        mutableWaveStates.keySet()
                );
    }

    public IncursionPlan getIncursionPlan() {
        return incursionPlan;
    }

    public UUID getIncursionId() {
        return incursionPlan.getIncursionId();
    }

    public List<Integer> getWaveIndexes() {
        return waveIndexes;
    }

    public int getWaveCount() {
        return waveIndexes.size();
    }

    public int getFirstWaveIndex() {
        return waveIndexes.getFirst();
    }

    public int getLastWaveIndex() {
        return waveIndexes.getLast();
    }

    public boolean hasWave(
            int waveIndex
    ) {
        return waveSourceStatesByIndex.containsKey(
                waveIndex
        );
    }

    /**
     * Returns every source assignment belonging to the supplied global wave
     * index across all fronts.
     */
    public List<SourceWaveExecutionState> getWaveSourceStates(
            int waveIndex
    ) {
        return waveSourceStatesByIndex.getOrDefault(
                waveIndex,
                List.of()
        );
    }

    public SourceExecutionState getSourceExecutionState(
            UUID sourcePlacementId
    ) {
        if (sourcePlacementId == null) {
            return null;
        }

        return sourceExecutionStatesByPlacementId.get(
                sourcePlacementId
        );
    }

    public Collection<SourceExecutionState>
    getSourceExecutionStates() {
        return Collections.unmodifiableCollection(
                sourceExecutionStatesByPlacementId.values()
        );
    }

    public int getPhysicalSourceCount() {
        return sourceExecutionStatesByPlacementId.size();
    }

    /**
     * Creates one persistent runtime object for every physical source
     * placement in the plan.
     */
    private static LinkedHashMap<UUID, SourceExecutionState>
    createSourceExecutionStates(
            IncursionPlan incursionPlan
    ) {
        LinkedHashMap<UUID, SourceExecutionState>
                sourceStates =
                new LinkedHashMap<>();

        for (FrontPlan frontPlan
                : incursionPlan.getFrontPlans()) {

            for (SourceGroupPlacementPlan groupPlacementPlan
                    : frontPlan.getSourceGroupPlacementPlans()) {

                for (SourcePlacementPlan sourcePlacementPlan
                        : groupPlacementPlan
                        .getSourcePlacementPlans()) {

                    UUID sourcePlacementId =
                            sourcePlacementPlan
                                    .getSourcePlacementId();

                    SourceExecutionState sourceExecutionState =
                            new SourceExecutionState(
                                    sourcePlacementPlan
                            );

                    SourceExecutionState existingState =
                            sourceStates.putIfAbsent(
                                    sourcePlacementId,
                                    sourceExecutionState
                            );

                    if (existingState != null) {
                        throw new IllegalArgumentException(
                                "Incursion plan contains duplicate source "
                                        + "placement ID "
                                        + sourcePlacementId
                                        + "."
                        );
                    }
                }
            }
        }

        return sourceStates;
    }

    /**
     * Builds every wave-specific source assignment while sharing the
     * persistent SourceExecutionState belonging to its physical placement.
     */
    private static TreeMap<Integer, List<SourceWaveExecutionState>>
    createWaveExecutionStates(
            IncursionPlan incursionPlan,
            Map<UUID, SourceExecutionState> sourceStates
    ) {
        TreeMap<Integer, List<SourceWaveExecutionState>>
                waveStates =
                new TreeMap<>();

        Set<UUID> assignedSourceCompositionIds =
                new HashSet<>();

        for (FrontPlan frontPlan
                : incursionPlan.getFrontPlans()) {

            for (FrontPlan.WavePlan wavePlan
                    : frontPlan.getWavePlans()) {

                List<SourceWaveExecutionState>
                        sourceStatesForWave =
                        waveStates.computeIfAbsent(
                                wavePlan.getWaveIndex(),
                                ignored -> new java.util.ArrayList<>()
                        );

                for (SourceGroupComposition groupComposition
                        : wavePlan
                        .getSourceGroupCompositions()) {

                    for (SourceGroupComposition.SourceComposition
                            sourceComposition
                            : groupComposition
                            .getSourceCompositions()) {

                        UUID sourceCompositionId =
                                sourceComposition
                                        .getSourceCompositionId();

                        if (!assignedSourceCompositionIds.add(
                                sourceCompositionId
                        )) {
                            throw new IllegalArgumentException(
                                    "Source composition "
                                            + sourceCompositionId
                                            + " has more than one runtime "
                                            + "wave assignment."
                            );
                        }

                        SourcePlacementPlan sourcePlacementPlan =
                                findBoundSourcePlacement(
                                        frontPlan,
                                        sourceCompositionId
                                );

                        SourceExecutionState sourceExecutionState =
                                sourceStates.get(
                                        sourcePlacementPlan
                                                .getSourcePlacementId()
                                );

                        if (sourceExecutionState == null) {
                            throw new IllegalArgumentException(
                                    "No runtime source state exists for "
                                            + "source placement "
                                            + sourcePlacementPlan
                                            .getSourcePlacementId()
                                            + "."
                            );
                        }

                        sourceStatesForWave.add(
                                new SourceWaveExecutionState(
                                        wavePlan.getWaveIndex(),
                                        sourceExecutionState,
                                        sourceComposition
                                )
                        );
                    }
                }
            }
        }

        return waveStates;
    }

    /**
     * Finds the unique physical source bound to one source composition within
     * its parent front.
     */
    private static SourcePlacementPlan findBoundSourcePlacement(
            FrontPlan frontPlan,
            UUID sourceCompositionId
    ) {
        SourcePlacementPlan matchingPlacement =
                null;

        for (SourceGroupPlacementPlan groupPlacementPlan
                : frontPlan.getSourceGroupPlacementPlans()) {

            for (SourcePlacementPlan sourcePlacementPlan
                    : groupPlacementPlan
                    .getSourcePlacementPlans()) {

                if (!sourcePlacementPlan
                        .isBoundToSourceComposition(
                                sourceCompositionId
                        )) {
                    continue;
                }

                if (matchingPlacement != null) {
                    throw new IllegalArgumentException(
                            "Source composition "
                                    + sourceCompositionId
                                    + " is bound to more than one physical "
                                    + "source placement in front "
                                    + frontPlan.getFrontId()
                                    + "."
                    );
                }

                matchingPlacement =
                        sourcePlacementPlan;
            }
        }

        if (matchingPlacement == null) {
            throw new IllegalArgumentException(
                    "Source composition "
                            + sourceCompositionId
                            + " has no physical source placement in front "
                            + frontPlan.getFrontId()
                            + "."
            );
        }

        return matchingPlacement;
    }
}