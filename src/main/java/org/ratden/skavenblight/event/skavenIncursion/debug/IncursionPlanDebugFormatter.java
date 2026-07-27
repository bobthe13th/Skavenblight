package org.ratden.skavenblight.event.skavenIncursion.debug;

import net.minecraft.core.BlockPos;
import org.ratden.skavenblight.event.skavenIncursion.director.IncursionTargetType;
import org.ratden.skavenblight.event.skavenIncursion.planning.FrontPlacementGeometry;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.IncursionPlanningContext;
import org.ratden.skavenblight.event.skavenIncursion.planning.chunk.IncursionChunkLoadPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.chunk.IncursionChunkLoadPlanCalculator;
import org.ratden.skavenblight.event.skavenIncursion.planning.chunk.SourceGroupChunkLoadPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.composition.SourceGroupComposition;
import org.ratden.skavenblight.event.skavenIncursion.planning.front.FrontPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceGroupPlacementPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourcePlacementPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceReservationArea;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Produces a human-readable diagnostic report for a completed IncursionPlan.
 *
 * This formatter is independent of the command that requested the plan so the
 * same report can later be reused by command inspection, logs, overlays or a
 * persisted incursion debug record.
 */
public final class IncursionPlanDebugFormatter {

    public static String format(
            IncursionPlan incursionPlan,
            IncursionPlanningContext planningContext
    ) {
        if (incursionPlan == null) {
            throw new IllegalArgumentException(
                    "Incursion plan cannot be null."
            );
        }

        if (planningContext == null) {
            throw new IllegalArgumentException(
                    "Incursion planning context cannot be null."
            );
        }

        IncursionChunkLoadPlan chunkLoadPlan =
                new IncursionChunkLoadPlanCalculator()
                        .calculate(
                                incursionPlan,
                                planningContext
                        );

        int totalWaves = 0;
        int totalSourceGroups = 0;
        int totalSources = 0;
        int totalMobs = 0;
        int totalThreatSpent = 0;

        Map<String, Integer> mobCounts =
                new LinkedHashMap<>();

        for (FrontPlan frontPlan
                : incursionPlan.getFrontPlans()) {

            /*
             * Every front contains the same authored global wave indexes.
             * Taking the maximum reports the number of incursion waves rather
             * than incorrectly multiplying it by the number of fronts.
             */
            totalWaves = Math.max(
                    totalWaves,
                    frontPlan.getWavePlans().size()
            );

            totalSourceGroups +=
                    frontPlan
                            .getSourceGroupPlacementPlans()
                            .size();

            for (FrontPlan.WavePlan wavePlan
                    : frontPlan.getWavePlans()) {

                totalThreatSpent +=
                        wavePlan.getThreatSpent();

                for (SourceGroupComposition groupComposition
                        : wavePlan
                        .getSourceGroupCompositions()) {

                    for (SourceGroupComposition.SourceComposition
                            sourceComposition
                            : groupComposition
                            .getSourceCompositions()) {

                        totalMobs +=
                                sourceComposition
                                        .getTotalMobCount();

                        for (SourceGroupComposition.MobEntry mobEntry
                                : sourceComposition.getMobEntries()) {

                            mobCounts.merge(
                                    mobEntry.getMobId(),
                                    mobEntry.getCount(),
                                    Integer::sum
                            );
                        }
                    }
                }
            }

            for (SourceGroupPlacementPlan groupPlacement
                    : frontPlan
                    .getSourceGroupPlacementPlans()) {

                totalSources +=
                        groupPlacement
                                .getSourcePlacementPlans()
                                .size();
            }
        }

        StringBuilder message =
                new StringBuilder();

        message.append("Incursion planning succeeded.")
                .append("\nIncursion ID: ")
                .append(incursionPlan.getIncursionId())
                .append("\nScenario: ")
                .append(
                        planningContext
                                .scenarioDefinition()
                                .id()
                )
                .append("\nStratagem: ")
                .append(
                        planningContext
                                .stratagemDefinition()
                                .getId()
                )
                .append("\nTarget type: ")
                .append(planningContext.targetType())
                .append("\nTarget position: ")
                .append(
                        formatBlockPos(
                                planningContext.targetPos()
                        )
                );

        appendPlacementBoundarySummary(
                message,
                planningContext
        );

        message.append("\nThreat: ")
                .append(totalThreatSpent)
                .append(" / ")
                .append(planningContext.totalThreatBudget())
                .append("\nComplexity budget: ")
                .append(planningContext.totalComplexityBudget())
                .append("\nFronts: ")
                .append(incursionPlan.getFrontCount())
                .append("\nWaves: ")
                .append(totalWaves)
                .append("\nPhysical source groups: ")
                .append(totalSourceGroups)
                .append("\nPhysical sources: ")
                .append(totalSources)
                .append("\nPlanned mobs: ")
                .append(totalMobs);

        appendChunkLoadSummary(
                message,
                chunkLoadPlan
        );

        message.append("\nMob composition:");

        if (mobCounts.isEmpty()) {
            message.append(" none");
        } else {
            for (Map.Entry<String, Integer> entry
                    : mobCounts.entrySet()) {
                message.append("\n  ")
                        .append(entry.getKey())
                        .append(": ")
                        .append(entry.getValue());
            }
        }

        appendFrontReports(
                message,
                incursionPlan,
                chunkLoadPlan
        );

        return message.toString();
    }

    private static void appendPlacementBoundarySummary(
            StringBuilder message,
            IncursionPlanningContext planningContext
    ) {
        FrontPlacementGeometry placementGeometry =
                FrontPlacementGeometry.from(
                        planningContext
                );

        if (placementGeometry
                .usesProtectedNetworkBoundary()) {

            message.append("\nProtected network positions: ")
                    .append(
                            placementGeometry
                                    .getProtectedNetworkGeometry()
                                    .getProtectedPositionCount()
                    )
                    .append("\nHard source clearance from network: ")
                    .append(
                            placementGeometry
                                    .getDistanceProfile()
                                    .getHardMinimumNetworkClearance()
                    )
                    .append("\nPreferred front clearance from network: ")
                    .append(
                            placementGeometry
                                    .getPreferredMinimumDistance()
                    )
                    .append("-")
                    .append(
                            placementGeometry
                                    .getPreferredMaximumDistance()
                    );

            return;
        }

        message.append("\nTarget radius: ")
                .append(
                        placementGeometry
                                .getBaseRadius()
                )
                .append("\nPreferred front distance from target centre: ")
                .append(
                        placementGeometry
                                .getPreferredMinimumDistance()
                )
                .append("-")
                .append(
                        placementGeometry
                                .getPreferredMaximumDistance()
                );

        /*
         * A player-targeted incursion remains target-centred, but its eventual
         * source reservations must still respect any captured protected network.
         */
        if (planningContext
                .hasProtectedNetworkGeometrySnapshot()) {

            message.append("\nAdditional protected network positions: ")
                    .append(
                            planningContext
                                    .protectedNetworkGeometrySnapshot()
                                    .getProtectedPositionCount()
                    );
        }
    }

    private static void appendChunkLoadSummary(
            StringBuilder message,
            IncursionChunkLoadPlan chunkLoadPlan
    ) {
        message.append("\n\nChunk footprint")
                .append("\nProtected base chunks: ")
                .append(
                        chunkLoadPlan
                                .getProtectedBaseChunkCount()
                )
                .append("\nRetained loaded chunks: ")
                .append(
                        chunkLoadPlan
                                .getRetainedLoadedChunkCount()
                )
                .append("\nBaseline ticking chunks: ")
                .append(
                        chunkLoadPlan
                                .getProtectedBaseChunkCount()
                )
                .append("\nMaximum wave source activation chunks: ")
                .append(
                        chunkLoadPlan
                                .getMaximumWaveSourceActivationChunkCount()
                )
                .append("\nMaximum total ticking chunks: ")
                .append(
                        chunkLoadPlan
                                .getMaximumWaveTotalTickingChunkCount()
                );

        for (int waveIndex
                : chunkLoadPlan.getWaveIndexes()) {

            message.append("\n  Wave ")
                    .append(waveIndex + 1)
                    .append(": groups ")
                    .append(
                            chunkLoadPlan
                                    .getRequiredSourceGroupPlacementIdsForWave(
                                            waveIndex
                                    )
                                    .size()
                    )
                    .append(" | source activation ")
                    .append(
                            chunkLoadPlan
                                    .getSourceActivationChunksForWave(
                                            waveIndex
                                    )
                                    .size()
                    )
                    .append(" | total ticking ")
                    .append(
                            chunkLoadPlan
                                    .getTotalTickingChunksForWave(
                                            waveIndex
                                    )
                                    .size()
                    );
        }
    }

    private static void appendFrontReports(
            StringBuilder message,
            IncursionPlan incursionPlan,
            IncursionChunkLoadPlan chunkLoadPlan
    ) {
        for (FrontPlan frontPlan
                : incursionPlan.getFrontPlans()) {

            message.append("\n\nF")
                    .append(frontPlan.getFrontIndex() + 1)
                    .append(" anchor: ")
                    .append(
                            formatBlockPos(
                                    frontPlan.getAnchorPos()
                            )
                    )
                    .append("\nPlacement pattern: ")
                    .append(frontPlan.getPlacementPattern())
                    .append("\nThreat share: ")
                    .append(
                            String.format(
                                    "%.2f",
                                    frontPlan.getThreatShare()
                            )
                    );

            int groupNumber = 0;

            for (SourceGroupPlacementPlan groupPlacement
                    : frontPlan
                    .getSourceGroupPlacementPlans()) {

                groupNumber++;

                SourceGroupChunkLoadPlan groupChunkLoadPlan =
                        chunkLoadPlan.getSourceGroupPlan(
                                groupPlacement
                                        .getSourceGroupPlacementId()
                        );

                if (groupChunkLoadPlan == null) {
                    throw new IllegalStateException(
                            "Chunk-load plan does not contain physical "
                                    + "source group "
                                    + groupPlacement
                                    .getSourceGroupPlacementId()
                                    + "."
                    );
                }

                message.append("\nGroup ")
                        .append(groupNumber)
                        .append(" anchor: ")
                        .append(
                                formatBlockPos(
                                        groupPlacement.getAnchorPos()
                                )
                        )
                        .append(" | Role: ")
                        .append(groupPlacement.getSourceRole())
                        .append(" | Group chunks: ")
                        .append(
                                groupChunkLoadPlan
                                        .getGroupFootprintChunkCount()
                        )
                        .append(" | Route chunks: ")
                        .append(
                                groupChunkLoadPlan
                                        .getRouteCorridorChunkCount()
                        )
                        .append(" | Activation union: ")
                        .append(
                                groupChunkLoadPlan
                                        .getActivationChunkCount()
                        );

                int sourceNumber = 0;

                for (SourcePlacementPlan sourcePlacement
                        : groupPlacement
                        .getSourcePlacementPlans()) {

                    sourceNumber++;

                    SourceReservationArea.WorldBounds bounds =
                            sourcePlacement
                                    .getReservationBounds();

                    message.append("\n  Source ")
                            .append(sourceNumber)
                            .append(": ")
                            .append(
                                    formatBlockPos(
                                            sourcePlacement
                                                    .getPlacedPos()
                                    )
                            )
                            .append(" | ")
                            .append(sourcePlacement.getSourceSize())
                            .append(" ")
                            .append(sourcePlacement.getSourceType())
                            .append(" | Facing ")
                            .append(sourcePlacement.getFacing())
                            .append(" | Reservation ")
                            .append(bounds.width())
                            .append("x")
                            .append(bounds.depth())
                            .append(" | Bound compositions: ")
                            .append(
                                    sourcePlacement
                                            .getBoundCompositionCount()
                            );
                }
            }
        }
    }

    private static String formatBlockPos(
            BlockPos pos
    ) {
        return pos.getX()
                + ", "
                + pos.getY()
                + ", "
                + pos.getZ();
    }

    private IncursionPlanDebugFormatter() {
    }
}