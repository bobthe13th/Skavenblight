package org.ratden.skavenblight.event.skavenIncursion.planning.source;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import org.ratden.skavenblight.network.WarpFluxGridManager;
import org.ratden.skavenblight.network.WarpFluxNetwork;

import java.util.HashSet;
import java.util.Set;

/**
 * Block-precise horizontal geometry for the Warp Flux network belonging to
 * an active Warpstone Nexus.
 *
 * Source placement uses this instead of the chunk-based Flow Field territory.
 * Every conduit and endpoint belonging to the Nexus-backed network contributes
 * to the protected base shape.
 *
 * Disconnected networks are ignored. When no matching network can be found,
 * the Nexus position itself remains protected as a safe fallback.
 */
public final class WarpFluxNetworkGeometry {

    private static final int SEARCH_MARGIN = 32;

    private final BlockPos nexusPos;
    private final Set<BlockPos> protectedPositions;
    private final boolean nexusBackedNetworkFound;

    private WarpFluxNetworkGeometry(
            BlockPos nexusPos,
            Set<BlockPos> protectedPositions,
            boolean nexusBackedNetworkFound
    ) {
        if (nexusPos == null) {
            throw new IllegalArgumentException(
                    "Nexus position cannot be null."
            );
        }

        if (protectedPositions == null
                || protectedPositions.isEmpty()) {
            throw new IllegalArgumentException(
                    "Protected network positions cannot be empty."
            );
        }

        this.nexusPos = nexusPos.immutable();
        this.protectedPositions =
                Set.copyOf(protectedPositions);

        this.nexusBackedNetworkFound =
                nexusBackedNetworkFound;
    }

    /**
     * Resolves all network blocks belonging to the supplied Nexus.
     *
     * More than one matching network is deliberately accepted. This protects
     * against temporary grid-manager inconsistencies by combining every
     * network that identifies the Nexus as an endpoint or has a conduit
     * directly connected to it.
     */
    public static WarpFluxNetworkGeometry resolve(
            ServerLevel level,
            BlockPos nexusPos
    ) {
        if (level == null) {
            throw new IllegalArgumentException(
                    "Planning level cannot be null."
            );
        }

        if (nexusPos == null) {
            throw new IllegalArgumentException(
                    "Nexus position cannot be null."
            );
        }

        BlockPos immutableNexusPos =
                nexusPos.immutable();

        Set<BlockPos> protectedPositions =
                new HashSet<>();

        boolean networkFound = false;

        WarpFluxGridManager gridManager =
                WarpFluxGridManager.get(level);

        if (gridManager != null) {
            for (WarpFluxNetwork network
                    : gridManager.getAllNetworks()) {

                if (!isConnectedToNexus(
                        network,
                        immutableNexusPos
                )) {
                    continue;
                }

                networkFound = true;

                for (BlockPos conduitPos
                        : network.getConduits()) {
                    protectedPositions.add(
                            conduitPos.immutable()
                    );
                }

                for (BlockPos endpointPos
                        : network.getEndpoints()) {
                    protectedPositions.add(
                            endpointPos.immutable()
                    );
                }
            }
        }

        /*
         * The Nexus itself is always protected, even when it has no connected
         * conduits or the network manager has not yet recorded the endpoint.
         */
        protectedPositions.add(immutableNexusPos);

        return new WarpFluxNetworkGeometry(
                immutableNexusPos,
                protectedPositions,
                networkFound
        );
    }

    public BlockPos getNexusPos() {
        return nexusPos;
    }

    public Set<BlockPos> getProtectedPositions() {
        return protectedPositions;
    }

    public int getProtectedPositionCount() {
        return protectedPositions.size();
    }

    public boolean hasNexusBackedNetwork() {
        return nexusBackedNetworkFound;
    }

    /**
     * Returns whether a single horizontal position is at least the supplied
     * distance from every protected network block.
     *
     * Exactly the requested distance is allowed.
     */
    public boolean hasPointClearance(
            BlockPos candidatePos,
            int minimumClearance
    ) {
        if (candidatePos == null) {
            throw new IllegalArgumentException(
                    "Candidate position cannot be null."
            );
        }

        validateClearance(minimumClearance);

        long requiredDistanceSquared =
                square(minimumClearance);

        for (BlockPos protectedPos
                : protectedPositions) {

            if (horizontalDistanceSquared(
                    candidatePos,
                    protectedPos
            ) < requiredDistanceSquared) {
                return false;
            }
        }

        return true;
    }

    /**
     * Returns whether every block in a resolved source reservation remains at
     * least the supplied horizontal distance from every protected network
     * block.
     */
    public boolean hasReservationClearance(
            SourceReservationArea.WorldBounds reservationBounds,
            int minimumClearance
    ) {
        if (reservationBounds == null) {
            throw new IllegalArgumentException(
                    "Reservation bounds cannot be null."
            );
        }

        validateClearance(minimumClearance);

        long requiredDistanceSquared =
                square(minimumClearance);

        for (BlockPos protectedPos
                : protectedPositions) {

            long distanceSquared =
                    distanceSquaredFromPointToBounds(
                            protectedPos,
                            reservationBounds
                    );

            if (distanceSquared
                    < requiredDistanceSquared) {
                return false;
            }
        }

        return true;
    }

    /**
     * Finds the first position along a tactical angle that reaches the
     * requested minimum clearance from the complete protected network.
     *
     * The search starts from the Nexus and moves horizontally outward.
     */
    public BlockPos findPositionAtMinimumClearance(
            double angleDegrees,
            int minimumClearance
    ) {
        validateClearance(minimumClearance);

        double radians =
                Math.toRadians(angleDegrees);

        int maximumSearchDistance =
                getMaximumNetworkRadiusFromNexus()
                        + minimumClearance
                        + SEARCH_MARGIN;

        for (int distance = minimumClearance;
             distance <= maximumSearchDistance;
             distance++) {

            BlockPos candidatePos =
                    positionAtAngleAndDistance(
                            nexusPos,
                            radians,
                            distance
                    );

            if (hasPointClearance(
                    candidatePos,
                    minimumClearance
            )) {
                return candidatePos;
            }
        }

        return null;
    }

    /**
     * Chooses a clearance within the supplied preferred band and finds a
     * position along the tactical angle that satisfies it.
     */
    public BlockPos findPositionInClearanceBand(
            double angleDegrees,
            int minimumPreferredClearance,
            int maximumPreferredClearance,
            RandomSource random
    ) {
        if (random == null) {
            throw new IllegalArgumentException(
                    "Planning random source cannot be null."
            );
        }

        validateClearance(minimumPreferredClearance);
        validateClearance(maximumPreferredClearance);

        if (maximumPreferredClearance
                < minimumPreferredClearance) {
            throw new IllegalArgumentException(
                    "Maximum preferred clearance cannot be below "
                            + "minimum preferred clearance."
            );
        }

        int selectedClearance =
                chooseValue(
                        minimumPreferredClearance,
                        maximumPreferredClearance,
                        random
                );

        return findPositionAtMinimumClearance(
                angleDegrees,
                selectedClearance
        );
    }

    /**
     * Returns the horizontal distance from a point to its nearest protected
     * network block.
     */
    public double getMinimumHorizontalDistance(
            BlockPos candidatePos
    ) {
        if (candidatePos == null) {
            throw new IllegalArgumentException(
                    "Candidate position cannot be null."
            );
        }

        long nearestDistanceSquared =
                Long.MAX_VALUE;

        for (BlockPos protectedPos
                : protectedPositions) {

            nearestDistanceSquared =
                    Math.min(
                            nearestDistanceSquared,
                            horizontalDistanceSquared(
                                    candidatePos,
                                    protectedPos
                            )
                    );
        }

        return Math.sqrt(nearestDistanceSquared);
    }

    /**
     * Returns the horizontal distance from a reservation rectangle to its
     * nearest protected network block.
     */
    public double getMinimumHorizontalDistance(
            SourceReservationArea.WorldBounds reservationBounds
    ) {
        if (reservationBounds == null) {
            throw new IllegalArgumentException(
                    "Reservation bounds cannot be null."
            );
        }

        long nearestDistanceSquared =
                Long.MAX_VALUE;

        for (BlockPos protectedPos
                : protectedPositions) {

            nearestDistanceSquared =
                    Math.min(
                            nearestDistanceSquared,
                            distanceSquaredFromPointToBounds(
                                    protectedPos,
                                    reservationBounds
                            )
                    );
        }

        return Math.sqrt(nearestDistanceSquared);
    }

    private static boolean isConnectedToNexus(
            WarpFluxNetwork network,
            BlockPos nexusPos
    ) {
        if (network == null) {
            return false;
        }

        if (network.getEndpoints().contains(
                nexusPos
        )) {
            return true;
        }

        /*
         * This secondary check protects against an endpoint list that has not
         * yet been refreshed but whose conduit is visibly connected to the
         * Nexus in the world.
         */
        for (BlockPos conduitPos
                : network.getConduits()) {

            if (isDirectlyAdjacent(
                    conduitPos,
                    nexusPos
            )) {
                return true;
            }
        }

        return false;
    }

    private static boolean isDirectlyAdjacent(
            BlockPos first,
            BlockPos second
    ) {
        int difference =
                Math.abs(first.getX() - second.getX())
                        + Math.abs(first.getY() - second.getY())
                        + Math.abs(first.getZ() - second.getZ());

        return difference == 1;
    }

    private int getMaximumNetworkRadiusFromNexus() {
        long maximumDistanceSquared = 0L;

        for (BlockPos protectedPos
                : protectedPositions) {

            maximumDistanceSquared =
                    Math.max(
                            maximumDistanceSquared,
                            horizontalDistanceSquared(
                                    nexusPos,
                                    protectedPos
                            )
                    );
        }

        return (int) Math.ceil(
                Math.sqrt(maximumDistanceSquared)
        );
    }

    private static BlockPos positionAtAngleAndDistance(
            BlockPos origin,
            double angleRadians,
            int distance
    ) {
        int offsetX =
                (int) Math.round(
                        Math.cos(angleRadians)
                                * distance
                );

        int offsetZ =
                (int) Math.round(
                        Math.sin(angleRadians)
                                * distance
                );

        return origin.offset(
                offsetX,
                0,
                offsetZ
        ).immutable();
    }

    private static int chooseValue(
            int minimum,
            int maximum,
            RandomSource random
    ) {
        if (maximum <= minimum) {
            return minimum;
        }

        return minimum
                + random.nextInt(
                maximum - minimum + 1
        );
    }

    private static long horizontalDistanceSquared(
            BlockPos first,
            BlockPos second
    ) {
        long differenceX =
                (long) first.getX()
                        - second.getX();

        long differenceZ =
                (long) first.getZ()
                        - second.getZ();

        return differenceX * differenceX
                + differenceZ * differenceZ;
    }

    /**
     * Calculates the shortest horizontal distance from one network block to
     * an inclusive reservation rectangle.
     */
    private static long distanceSquaredFromPointToBounds(
            BlockPos point,
            SourceReservationArea.WorldBounds bounds
    ) {
        long differenceX = 0L;
        long differenceZ = 0L;

        if (point.getX() < bounds.minX()) {
            differenceX =
                    (long) bounds.minX()
                            - point.getX();
        } else if (point.getX() > bounds.maxX()) {
            differenceX =
                    (long) point.getX()
                            - bounds.maxX();
        }

        if (point.getZ() < bounds.minZ()) {
            differenceZ =
                    (long) bounds.minZ()
                            - point.getZ();
        } else if (point.getZ() > bounds.maxZ()) {
            differenceZ =
                    (long) point.getZ()
                            - bounds.maxZ();
        }

        return differenceX * differenceX
                + differenceZ * differenceZ;
    }

    private static long square(
            int value
    ) {
        return (long) value * value;
    }

    private static void validateClearance(
            int minimumClearance
    ) {
        if (minimumClearance < 0) {
            throw new IllegalArgumentException(
                    "Network clearance cannot be negative."
            );
        }
    }
}