package org.ratden.skavenblight.event.skavenIncursion.action.source;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import org.ratden.skavenblight.block.custom.SkavenTunnelSourceBlock;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.*;

import java.util.ArrayList;
import java.util.List;

public class SourceGroupPlacement {

    private static final int SOURCE_MINIMUM_SPACING = 6;
    private static final int EXISTING_SOURCE_AVOID_RADIUS = 7;

    private static final int GROUP_BASE_RADIUS = 4;
    private static final int GROUP_RADIUS_PER_SOURCE = 2;

    private static final int GROUP_ANCHOR_JITTER = 3;
    private static final int MAX_GROUP_ANCHOR_ATTEMPTS = 24;
    private static final int MAX_SOURCE_POSITION_ATTEMPTS = 48;

    public static List<SourceGroupPlan> createSourceGroups(
            ServerLevel level,
            BlockPos targetPos,
            SourcePlacementPattern placementPattern,
            SourceRole sourceRole
    ) {
        return createPlannedSources(
                level,
                BasePlacementContext.forDebugPlayer(targetPos),
                placementPattern,
                sourceRole,
                SourceType.SKAVEN_TUNNEL,
                SourceSize.NORMAL,
                getDefaultSourceCountForPattern(placementPattern)
        );
    }

    public static List<SourceGroupPlan> createPlannedSources(
            ServerLevel level,
            BlockPos targetPos,
            SourcePlacementPattern placementPattern,
            SourceRole sourceRole,
            SourceType sourceType,
            SourceSize sourceSize,
            int sourceCount
    ) {
        return createPlannedSources(
                level,
                BasePlacementContext.forDebugPlayer(targetPos),
                placementPattern,
                sourceRole,
                sourceType,
                sourceSize,
                sourceCount
        );
    }

    public static List<SourceGroupPlan> createPlannedSources(
            ServerLevel level,
            BasePlacementContext placementContext,
            SourcePlacementPattern placementPattern,
            SourceRole sourceRole,
            SourceType sourceType,
            SourceSize sourceSize,
            int sourceCount
    ) {
        int safeSourceCount = Math.max(1, sourceCount);

        List<SourceGroupPlan> sourceGroups = createBaseSourceGroups(
                level,
                placementContext,
                placementPattern,
                sourceRole,
                safeSourceCount
        );

        if (sourceGroups.isEmpty()) {
            return sourceGroups;
        }

        int[] sourcesPerGroup = distributeSourceCountForPattern(
                placementPattern,
                safeSourceCount,
                sourceGroups
        );

        List<BlockPos> reservedSourcePositions = new ArrayList<>();

        for (int groupIndex = 0; groupIndex < sourceGroups.size(); groupIndex++) {
            SourceGroupPlan sourceGroup = sourceGroups.get(groupIndex);
            int groupSourceCount = sourcesPerGroup[groupIndex];

            int groupReservationRadius = getReservationRadiusForSourceCount(
                    groupSourceCount
            );

            for (int sourceIndex = 0; sourceIndex < groupSourceCount; sourceIndex++) {
                BlockPos plannedPos = findSourcePositionInGroup(
                        level,
                        sourceGroup.getAnchorPos(),
                        groupReservationRadius,
                        reservedSourcePositions
                );

                SourcePlan sourcePlan = sourceGroup.createSourcePlan(
                        sourceType,
                        sourceSize,
                        sourceGroup.getSourceRole(),
                        plannedPos
                );

                sourcePlan.setPlacedPos(plannedPos);
                reservedSourcePositions.add(plannedPos);
            }
        }

        return sourceGroups;
    }

    private static List<SourceGroupPlan> createBaseSourceGroups(
            ServerLevel level,
            BasePlacementContext placementContext,
            SourcePlacementPattern placementPattern,
            SourceRole sourceRole,
            int sourceCount
    ) {
        return switch (placementPattern) {
            case SINGLE -> createSingle(level, placementContext, sourceRole, sourceCount);
            case CLUSTER -> createCluster(level, placementContext, sourceRole, sourceCount);
            case PINCER -> createPincer(level, placementContext, sourceRole, sourceCount);
            case SURROUND_LIGHT -> createSurroundLight(level, placementContext, sourceRole, sourceCount);
            case DISTANT_SUPPORT -> createDistantSupport(level, placementContext, sourceRole, sourceCount);
        };
    }

    private static List<SourceGroupPlan> createSingle(
            ServerLevel level,
            BasePlacementContext placementContext,
            SourceRole sourceRole,
            int sourceCount
    ) {
        List<SourceGroupPlan> sourceGroups = new ArrayList<>();

        SourceDistanceProfile distanceProfile = getDistanceProfileForPatternAndRole(
                SourcePlacementPattern.SINGLE,
                sourceRole
        );

        BlockPos idealAnchor = randomRingPos(
                level,
                placementContext.getTargetPos(),
                distanceProfile.getMinDistanceFromTarget(placementContext.getBaseRadius()),
                distanceProfile.getMaxDistanceFromTarget(placementContext.getBaseRadius())
        );

        BlockPos anchor = findGroupAnchor(
                level,
                idealAnchor,
                sourceCount,
                new ArrayList<>()
        );

        sourceGroups.add(new SourceGroupPlan(
                SourcePlacementPattern.SINGLE,
                sourceRole,
                anchor
        ));

        return sourceGroups;
    }

    private static List<SourceGroupPlan> createCluster(
            ServerLevel level,
            BasePlacementContext placementContext,
            SourceRole sourceRole,
            int sourceCount
    ) {
        List<SourceGroupPlan> sourceGroups = new ArrayList<>();

        SourceDistanceProfile distanceProfile = getDistanceProfileForPatternAndRole(
                SourcePlacementPattern.CLUSTER,
                sourceRole
        );

        BlockPos idealAnchor = randomRingPos(
                level,
                placementContext.getTargetPos(),
                distanceProfile.getMinDistanceFromTarget(placementContext.getBaseRadius()),
                distanceProfile.getMaxDistanceFromTarget(placementContext.getBaseRadius())
        );

        BlockPos anchor = findGroupAnchor(
                level,
                idealAnchor,
                sourceCount,
                new ArrayList<>()
        );

        sourceGroups.add(new SourceGroupPlan(
                SourcePlacementPattern.CLUSTER,
                sourceRole,
                anchor
        ));

        return sourceGroups;
    }

    private static List<SourceGroupPlan> createPincer(
            ServerLevel level,
            BasePlacementContext placementContext,
            SourceRole sourceRole,
            int sourceCount
    ) {
        List<SourceGroupPlan> sourceGroups = new ArrayList<>();
        List<BlockPos> reservedAnchors = new ArrayList<>();

        SourceDistanceProfile distanceProfile = getDistanceProfileForPatternAndRole(
                SourcePlacementPattern.PINCER,
                sourceRole
        );

        int minDistance = distanceProfile.getMinDistanceFromTarget(placementContext.getBaseRadius());
        int maxDistance = distanceProfile.getMaxDistanceFromTarget(placementContext.getBaseRadius());

        RandomSource random = level.getRandom();
        double angle = random.nextDouble() * Math.PI * 2.0;
        int distance = randomDistance(random, minDistance, maxDistance);

        int firstGroupCount = divideRoundUp(sourceCount, 2);
        int secondGroupCount = sourceCount / 2;

        BlockPos firstIdealAnchor = posAtAngleAndDistance(
                placementContext.getTargetPos(),
                angle,
                distance
        );

        BlockPos firstAnchor = findGroupAnchor(
                level,
                firstIdealAnchor,
                firstGroupCount,
                reservedAnchors
        );

        reservedAnchors.add(firstAnchor);

        BlockPos secondIdealAnchor = posAtAngleAndDistance(
                placementContext.getTargetPos(),
                angle + Math.PI,
                distance
        );

        BlockPos secondAnchor = findGroupAnchor(
                level,
                secondIdealAnchor,
                secondGroupCount,
                reservedAnchors
        );

        sourceGroups.add(new SourceGroupPlan(
                SourcePlacementPattern.PINCER,
                sourceRole,
                firstAnchor
        ));

        sourceGroups.add(new SourceGroupPlan(
                SourcePlacementPattern.PINCER,
                sourceRole,
                secondAnchor
        ));

        return sourceGroups;
    }

    private static List<SourceGroupPlan> createSurroundLight(
            ServerLevel level,
            BasePlacementContext placementContext,
            SourceRole sourceRole,
            int sourceCount
    ) {
        List<SourceGroupPlan> sourceGroups = new ArrayList<>();
        List<BlockPos> reservedAnchors = new ArrayList<>();

        SourceDistanceProfile distanceProfile = getDistanceProfileForPatternAndRole(
                SourcePlacementPattern.SURROUND_LIGHT,
                sourceRole
        );

        int minDistance = distanceProfile.getMinDistanceFromTarget(placementContext.getBaseRadius());
        int maxDistance = distanceProfile.getMaxDistanceFromTarget(placementContext.getBaseRadius());

        RandomSource random = level.getRandom();
        double startAngle = random.nextDouble() * Math.PI * 2.0;

        int[] sourcesPerGroup = distributeSourceCount(
                sourceCount,
                4
        );

        for (int i = 0; i < 4; i++) {
            double angle = startAngle + (Math.PI / 2.0D) * i;
            int distance = randomDistance(random, minDistance, maxDistance);

            BlockPos idealAnchor = posAtAngleAndDistance(
                    placementContext.getTargetPos(),
                    angle,
                    distance
            );

            BlockPos anchor = findGroupAnchor(
                    level,
                    idealAnchor,
                    sourcesPerGroup[i],
                    reservedAnchors
            );

            reservedAnchors.add(anchor);

            sourceGroups.add(new SourceGroupPlan(
                    SourcePlacementPattern.SURROUND_LIGHT,
                    sourceRole,
                    anchor
            ));
        }

        return sourceGroups;
    }

    private static List<SourceGroupPlan> createDistantSupport(
            ServerLevel level,
            BasePlacementContext placementContext,
            SourceRole sourceRole,
            int sourceCount
    ) {
        List<SourceGroupPlan> sourceGroups = new ArrayList<>();
        List<BlockPos> reservedAnchors = new ArrayList<>();

        if (sourceCount <= 1) {
            return createSingle(
                    level,
                    placementContext,
                    SourceRole.COMBAT,
                    sourceCount
            );
        }

        SourceRole supportRole = sourceRole == SourceRole.COMBAT
                ? SourceRole.SUPPORT
                : sourceRole;

        SourceDistanceProfile combatDistanceProfile = SourceDistanceProfile.STANDARD;
        SourceDistanceProfile supportDistanceProfile = getDistanceProfileForPatternAndRole(
                SourcePlacementPattern.DISTANT_SUPPORT,
                supportRole
        ).oneStepFarther();

        int combatCount = Math.max(1, sourceCount - 1);
        int supportCount = 1;

        RandomSource random = level.getRandom();
        double angle = random.nextDouble() * Math.PI * 2.0;

        int combatDistance = randomDistance(
                random,
                combatDistanceProfile.getMinDistanceFromTarget(placementContext.getBaseRadius()),
                combatDistanceProfile.getMaxDistanceFromTarget(placementContext.getBaseRadius())
        );

        BlockPos combatIdealAnchor = posAtAngleAndDistance(
                placementContext.getTargetPos(),
                angle,
                combatDistance
        );

        BlockPos combatAnchor = findGroupAnchor(
                level,
                combatIdealAnchor,
                combatCount,
                reservedAnchors
        );

        reservedAnchors.add(combatAnchor);

        int supportDistance = randomDistance(
                random,
                supportDistanceProfile.getMinDistanceFromTarget(placementContext.getBaseRadius()),
                supportDistanceProfile.getMaxDistanceFromTarget(placementContext.getBaseRadius())
        );

        BlockPos supportIdealAnchor = posAtAngleAndDistance(
                placementContext.getTargetPos(),
                angle,
                supportDistance
        );

        BlockPos supportAnchor = findGroupAnchor(
                level,
                supportIdealAnchor,
                supportCount,
                reservedAnchors
        );

        sourceGroups.add(new SourceGroupPlan(
                SourcePlacementPattern.DISTANT_SUPPORT,
                SourceRole.COMBAT,
                combatAnchor
        ));

        sourceGroups.add(new SourceGroupPlan(
                SourcePlacementPattern.DISTANT_SUPPORT,
                supportRole,
                supportAnchor
        ));

        return sourceGroups;
    }

    private static BlockPos findGroupAnchor(
            ServerLevel level,
            BlockPos idealAnchor,
            int sourceCount,
            List<BlockPos> reservedAnchors
    ) {
        int reservationRadius = getReservationRadiusForSourceCount(sourceCount);
        RandomSource random = level.getRandom();

        for (int attempt = 0; attempt < MAX_GROUP_ANCHOR_ATTEMPTS; attempt++) {
            int expansion = attempt / 6;
            int jitterRadius = GROUP_ANCHOR_JITTER + expansion * 8;

            int offsetX = random.nextInt(jitterRadius * 2 + 1) - jitterRadius;
            int offsetZ = random.nextInt(jitterRadius * 2 + 1) - jitterRadius;

            BlockPos candidate = idealAnchor.offset(offsetX, 0, offsetZ);
            BlockPos surfaceCandidate = getSurfacePos(level, candidate);

            if (!isAnchorFarEnoughFromReservedAnchors(
                    surfaceCandidate,
                    reservationRadius,
                    reservedAnchors
            )) {
                continue;
            }

            if (hasExistingTunnelSourceNearby(
                    level,
                    surfaceCandidate,
                    EXISTING_SOURCE_AVOID_RADIUS
            )) {
                continue;
            }

            return surfaceCandidate;
        }

        return getSurfacePos(level, idealAnchor);
    }

    private static BlockPos findSourcePositionInGroup(
            ServerLevel level,
            BlockPos groupAnchor,
            int reservationRadius,
            List<BlockPos> reservedSourcePositions
    ) {
        RandomSource random = level.getRandom();

        for (int attempt = 0; attempt < MAX_SOURCE_POSITION_ATTEMPTS; attempt++) {
            int currentRadius = Math.max(
                    SOURCE_MINIMUM_SPACING,
                    reservationRadius + (attempt / 12) * 6
            );

            int offsetX = random.nextInt(currentRadius * 2 + 1) - currentRadius;
            int offsetZ = random.nextInt(currentRadius * 2 + 1) - currentRadius;

            BlockPos candidate = getSurfacePos(
                    level,
                    groupAnchor.offset(offsetX, 0, offsetZ)
            );

            if (!isSourcePositionValid(level, candidate, reservedSourcePositions)) {
                continue;
            }

            return candidate;
        }

        return findFallbackSourcePosition(level, groupAnchor, reservedSourcePositions);
    }

    private static BlockPos findFallbackSourcePosition(
            ServerLevel level,
            BlockPos groupAnchor,
            List<BlockPos> reservedSourcePositions
    ) {
        for (int radius = SOURCE_MINIMUM_SPACING; radius <= 96; radius += SOURCE_MINIMUM_SPACING) {
            for (int x = -radius; x <= radius; x += SOURCE_MINIMUM_SPACING) {
                for (int z = -radius; z <= radius; z += SOURCE_MINIMUM_SPACING) {
                    if (Math.abs(x) != radius && Math.abs(z) != radius) {
                        continue;
                    }

                    BlockPos candidate = getSurfacePos(
                            level,
                            groupAnchor.offset(x, 0, z)
                    );

                    if (isSourcePositionValid(level, candidate, reservedSourcePositions)) {
                        return candidate;
                    }
                }
            }
        }

        return getSurfacePos(level, groupAnchor);
    }

    private static boolean isAnchorFarEnoughFromReservedAnchors(
            BlockPos candidate,
            int reservationRadius,
            List<BlockPos> reservedAnchors
    ) {
        for (BlockPos reservedAnchor : reservedAnchors) {
            int requiredDistance = reservationRadius * 2;

            if (horizontalDistanceSqr(candidate, reservedAnchor) < requiredDistance * requiredDistance) {
                return false;
            }
        }

        return true;
    }

    private static boolean isSourcePositionValid(
            ServerLevel level,
            BlockPos candidate,
            List<BlockPos> reservedSourcePositions
    ) {
        for (BlockPos reservedSourcePosition : reservedSourcePositions) {
            if (horizontalDistanceSqr(candidate, reservedSourcePosition)
                    < SOURCE_MINIMUM_SPACING * SOURCE_MINIMUM_SPACING) {
                return false;
            }
        }

        return !hasExistingTunnelSourceNearby(
                level,
                candidate,
                EXISTING_SOURCE_AVOID_RADIUS
        );
    }

    private static boolean hasExistingTunnelSourceNearby(
            ServerLevel level,
            BlockPos center,
            int radius
    ) {
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        for (int x = -radius; x <= radius; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -radius; z <= radius; z++) {
                    mutablePos.set(
                            center.getX() + x,
                            center.getY() + y,
                            center.getZ() + z
                    );

                    if (level.getBlockState(mutablePos).hasProperty(SkavenTunnelSourceBlock.SOURCE_STATE)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static SourceDistanceProfile getDistanceProfileForPatternAndRole(
            SourcePlacementPattern placementPattern,
            SourceRole sourceRole
    ) {
        SourceDistanceProfile profile = getDefaultDistanceProfileForPattern(placementPattern);

        return switch (sourceRole) {
            case SUPPORT, ARTILLERY -> profile.oneStepFarther();
            case SNEAK_ATTACK -> profile.oneStepCloser();
            case COMBAT, LEADER, REINFORCEMENT, DECOY -> profile;
        };
    }

    private static SourceDistanceProfile getDefaultDistanceProfileForPattern(
            SourcePlacementPattern placementPattern
    ) {
        return switch (placementPattern) {
            case SINGLE -> SourceDistanceProfile.STANDARD;
            case CLUSTER -> SourceDistanceProfile.CLOSE;
            case PINCER -> SourceDistanceProfile.STANDARD;
            case SURROUND_LIGHT -> SourceDistanceProfile.FAR;
            case DISTANT_SUPPORT -> SourceDistanceProfile.FAR;
        };
    }

    private static int getDefaultSourceCountForPattern(
            SourcePlacementPattern placementPattern
    ) {
        return switch (placementPattern) {
            case SINGLE -> 1;
            case CLUSTER -> 3;
            case PINCER -> 2;
            case SURROUND_LIGHT -> 4;
            case DISTANT_SUPPORT -> 2;
        };
    }

    private static int[] distributeSourceCount(
            int sourceCount,
            int groupCount
    ) {
        int safeGroupCount = Math.max(1, groupCount);
        int[] sourcesPerGroup = new int[safeGroupCount];

        for (int i = 0; i < sourceCount; i++) {
            sourcesPerGroup[i % safeGroupCount]++;
        }

        return sourcesPerGroup;
    }

    private static int[] distributeSourceCountForPattern(
            SourcePlacementPattern placementPattern,
            int sourceCount,
            List<SourceGroupPlan> sourceGroups
    ) {
        int groupCount = sourceGroups.size();

        if (groupCount <= 0) {
            return new int[0];
        }

        if (placementPattern == SourcePlacementPattern.DISTANT_SUPPORT && groupCount >= 2) {
            int[] sourcesPerGroup = new int[groupCount];

            int supportGroupIndex = findLastSupportLikeGroupIndex(sourceGroups);
            int supportCount = 1;
            int combatGroupCount = groupCount - 1;

            sourcesPerGroup[supportGroupIndex] = supportCount;

            if (combatGroupCount <= 0) {
                sourcesPerGroup[supportGroupIndex] = sourceCount;
                return sourcesPerGroup;
            }

            int remainingCombatSources = Math.max(0, sourceCount - supportCount);

            for (int i = 0; i < remainingCombatSources; i++) {
                int groupIndex = i % combatGroupCount;

                if (groupIndex >= supportGroupIndex) {
                    groupIndex++;
                }

                sourcesPerGroup[groupIndex]++;
            }

            return sourcesPerGroup;
        }

        return distributeSourceCount(
                sourceCount,
                groupCount
        );
    }

    private static int findLastSupportLikeGroupIndex(
            List<SourceGroupPlan> sourceGroups
    ) {
        int fallbackIndex = sourceGroups.size() - 1;

        for (int i = sourceGroups.size() - 1; i >= 0; i--) {
            SourceRole sourceRole = sourceGroups.get(i).getSourceRole();

            if (sourceRole == SourceRole.SUPPORT
                    || sourceRole == SourceRole.ARTILLERY
                    || sourceRole == SourceRole.LEADER) {
                return i;
            }
        }

        return fallbackIndex;
    }

    private static int getReservationRadiusForSourceCount(
            int sourceCount
    ) {
        return GROUP_BASE_RADIUS + Math.max(1, sourceCount) * GROUP_RADIUS_PER_SOURCE;
    }

    private static BlockPos randomRingPos(
            ServerLevel level,
            BlockPos targetPos,
            int minDistance,
            int maxDistance
    ) {
        RandomSource random = level.getRandom();
        double angle = random.nextDouble() * Math.PI * 2.0;
        int distance = randomDistance(random, minDistance, maxDistance);

        return posAtAngleAndDistance(
                targetPos,
                angle,
                distance
        );
    }

    private static int randomDistance(
            RandomSource random,
            int minDistance,
            int maxDistance
    ) {
        int safeMinDistance = Math.max(0, minDistance);
        int safeMaxDistance = Math.max(safeMinDistance, maxDistance);

        if (safeMaxDistance == safeMinDistance) {
            return safeMinDistance;
        }

        return safeMinDistance + random.nextInt(safeMaxDistance - safeMinDistance + 1);
    }

    private static BlockPos posAtAngleAndDistance(
            BlockPos targetPos,
            double angle,
            int distance
    ) {
        int x = targetPos.getX() + (int) Math.round(Math.cos(angle) * distance);
        int z = targetPos.getZ() + (int) Math.round(Math.sin(angle) * distance);

        return new BlockPos(
                x,
                targetPos.getY(),
                z
        );
    }

    private static BlockPos getSurfacePos(
            ServerLevel level,
            BlockPos pos
    ) {
        return level.getHeightmapPos(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                pos
        );
    }

    private static int horizontalDistanceSqr(
            BlockPos first,
            BlockPos second
    ) {
        int dx = first.getX() - second.getX();
        int dz = first.getZ() - second.getZ();

        return dx * dx + dz * dz;
    }

    private static int divideRoundUp(
            int value,
            int divisor
    ) {
        return (value + divisor - 1) / divisor;
    }

    private SourceGroupPlacement() {
    }
}