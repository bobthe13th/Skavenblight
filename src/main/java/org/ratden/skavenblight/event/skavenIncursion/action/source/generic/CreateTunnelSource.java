package org.ratden.skavenblight.event.skavenIncursion.action.source.generic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.ratden.skavenblight.block.ModBlocks;
import org.ratden.skavenblight.block.custom.SkavenTunnelSourceBlock;
import org.ratden.skavenblight.block.entity.SkavenTunnelSourceEntity;
import org.ratden.skavenblight.block.entity.state.SourceState;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeadershipContext;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourcePlacementPlan;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourcePlacementProfile;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourcePlacementProfileCatalogue;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceReservationArea;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceSize;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceType;

import java.util.UUID;

/**
 * Runtime action that prepares and creates a Skaven tunnel source.
 *
 * Planning decides the source position, facing and approved preparation
 * profile. This action performs the corresponding world changes.
 */
public final class CreateTunnelSource {

    /**
     * Legacy entry point used by existing Scenarios, debug commands and load
     * tests.
     *
     * Until those callers are migrated to complete placement plans, they use
     * the authored NORMAL Skaven tunnel profile and a neutral north-facing
     * orientation.
     */
    public static UUID execute(
            ServerLevel level,
            BlockPos pos,
            SourceState sourceState,
            LeadershipContext leadershipContext
    ) {
        SourcePlacementProfile placementProfile =
                SourcePlacementProfileCatalogue.require(
                        SourceType.SKAVEN_TUNNEL,
                        SourceSize.NORMAL
                );

        return executeTunnel(
                level,
                pos,
                Direction.NORTH,
                placementProfile,
                sourceState,
                leadershipContext
        );
    }

    /**
     * Planning-aware entry point.
     *
     * Uses the final position, facing and placement profile stored in the
     * validated SourcePlacementPlan.
     */
    public static UUID execute(
            ServerLevel level,
            SourcePlacementPlan sourcePlacementPlan,
            SourceState sourceState,
            LeadershipContext leadershipContext
    ) {
        if (sourcePlacementPlan == null) {
            throw new IllegalArgumentException(
                    "Source placement plan cannot be null."
            );
        }

        if (sourcePlacementPlan.getSourceType()
                != SourceType.SKAVEN_TUNNEL) {

            throw new IllegalArgumentException(
                    "CreateTunnelSource cannot create source type "
                            + sourcePlacementPlan.getSourceType()
                            + "."
            );
        }

        if (!sourcePlacementPlan.hasPlacedPos()) {
            throw new IllegalArgumentException(
                    "Source placement plan "
                            + sourcePlacementPlan.getSourcePlacementId()
                            + " does not have a final position."
            );
        }

        return executeTunnel(
                level,
                sourcePlacementPlan.getPlacedPos(),
                sourcePlacementPlan.getFacing(),
                sourcePlacementPlan.getPlacementProfile(),
                sourceState,
                leadershipContext
        );
    }

    /**
     * Prepares, places and initialises one physical tunnel source.
     *
     * Once the tunnel block has been placed, its creation is treated
     * transactionally:
     *
     * - the expected block entity must exist;
     * - its persistent ownership data must be initialised;
     * - it must expose a source UUID.
     *
     * Failure during that process restores the block state that occupied the
     * source position immediately before the tunnel block was placed.
     */
    private static UUID executeTunnel(
            ServerLevel level,
            BlockPos pos,
            Direction facing,
            SourcePlacementProfile placementProfile,
            SourceState sourceState,
            LeadershipContext leadershipContext
    ) {
        validateArguments(
                level,
                pos,
                facing,
                placementProfile,
                sourceState,
                leadershipContext
        );

        prepareTunnelArea(
                level,
                pos,
                facing,
                placementProfile
        );

        /*
         * Capture the state produced by terrain preparation. In normal
         * planning-aware placement this will be air.
         *
         * This is the state restored if the newly placed source cannot be
         * initialised.
         */
        BlockState replacedBlockState =
                level.getBlockState(
                        pos
                );

        BlockState tunnelBlockState =
                ModBlocks.SKAVEN_TUNNEL_SOURCE.get()
                        .defaultBlockState()
                        .setValue(
                                SkavenTunnelSourceBlock.SOURCE_STATE,
                                sourceState
                        );

        boolean blockPlaced =
                level.setBlock(
                        pos,
                        tunnelBlockState,
                        3
                );

        if (!blockPlaced) {
            return null;
        }

        BlockEntity blockEntity =
                level.getBlockEntity(
                        pos
                );

        if (!(blockEntity
                instanceof SkavenTunnelSourceEntity tunnelSource)) {

            rollbackPlacedTunnelSource(
                    level,
                    pos,
                    replacedBlockState
            );

            return null;
        }

        try {
            initialiseTunnelSource(
                    level,
                    tunnelSource,
                    leadershipContext
            );

            UUID sourceId =
                    tunnelSource.getSourceId();

            if (sourceId == null) {
                throw new IllegalStateException(
                        "New tunnel source at "
                                + formatBlockPos(
                                pos
                        )
                                + " has no source ID."
                );
            }

            return sourceId;
        } catch (RuntimeException exception) {
            try {
                rollbackPlacedTunnelSource(
                        level,
                        pos,
                        replacedBlockState
                );
            } catch (RuntimeException rollbackException) {
                /*
                 * Preserve the original initialisation failure while also
                 * exposing the more serious rollback failure.
                 */
                exception.addSuppressed(
                        rollbackException
                );
            }

            throw exception;
        }
    }

    /**
     * Writes the persistent ownership and creation information belonging to
     * one newly created physical source.
     */
    private static void initialiseTunnelSource(
            ServerLevel level,
            SkavenTunnelSourceEntity tunnelSource,
            LeadershipContext leadershipContext
    ) {
        tunnelSource.setScenarioId(
                leadershipContext.scenarioId()
        );

        tunnelSource.setVermintideId(
                leadershipContext.vermintideId()
        );

        tunnelSource.setFangId(
                leadershipContext.fangId()
        );

        tunnelSource.setClawId(
                leadershipContext.clawId()
        );

        tunnelSource.setPackId(
                leadershipContext.packId()
        );

        tunnelSource.setCreatedGameTime(
                level.getGameTime()
        );
    }

    /**
     * Removes an incompletely initialised tunnel source and restores the block
     * state that occupied its position immediately before source placement.
     *
     * The method only replaces the position when it still contains the tunnel
     * source created by this action. If another system has already changed the
     * position, that newer world state is not overwritten.
     */
    private static void rollbackPlacedTunnelSource(
            ServerLevel level,
            BlockPos pos,
            BlockState replacedBlockState
    ) {
        BlockState currentBlockState =
                level.getBlockState(
                        pos
                );

        if (!currentBlockState.is(
                ModBlocks.SKAVEN_TUNNEL_SOURCE.get()
        )) {
            return;
        }

        boolean restored =
                level.setBlock(
                        pos,
                        replacedBlockState,
                        3
                );

        if (!restored) {
            throw new IllegalStateException(
                    "Could not roll back incomplete tunnel source at "
                            + formatBlockPos(
                            pos
                    )
                            + "."
            );
        }

        BlockState restoredBlockState =
                level.getBlockState(
                        pos
                );

        if (!restoredBlockState.equals(
                replacedBlockState
        )) {
            throw new IllegalStateException(
                    "Tunnel-source rollback at "
                            + formatBlockPos(
                            pos
                    )
                            + " reported success but did not restore the "
                            + "expected block state."
            );
        }
    }

    /**
     * Constructs the source's guaranteed emergence area.
     *
     * Foundation layers provide stable walkable ground. The clearance volume
     * prevents emerging mobs from immediately suffocating or becoming trapped
     * before their pathing, digging and construction behaviours take over.
     */
    private static void prepareTunnelArea(
            ServerLevel level,
            BlockPos sourcePos,
            Direction facing,
            SourcePlacementProfile placementProfile
    ) {
        SourceReservationArea.WorldBounds preparationBounds =
                placementProfile
                        .preparationArea()
                        .resolve(
                                sourcePos,
                                facing
                        );

        prepareFoundation(
                level,
                sourcePos,
                preparationBounds,
                placementProfile.foundationDepth()
        );

        prepareClearance(
                level,
                sourcePos,
                preparationBounds,
                placementProfile.clearanceHeight()
        );
    }

    private static void prepareFoundation(
            ServerLevel level,
            BlockPos sourcePos,
            SourceReservationArea.WorldBounds preparationBounds,
            int foundationDepth
    ) {
        for (int x = preparationBounds.minX();
             x <= preparationBounds.maxX();
             x++) {

            for (int z = preparationBounds.minZ();
                 z <= preparationBounds.maxZ();
                 z++) {

                for (int depth = 1;
                     depth <= foundationDepth;
                     depth++) {

                    BlockPos foundationPos =
                            new BlockPos(
                                    x,
                                    sourcePos.getY() - depth,
                                    z
                            );

                    level.setBlock(
                            foundationPos,
                            Blocks.DIRT.defaultBlockState(),
                            3
                    );
                }
            }
        }
    }

    private static void prepareClearance(
            ServerLevel level,
            BlockPos sourcePos,
            SourceReservationArea.WorldBounds preparationBounds,
            int clearanceHeight
    ) {
        for (int x = preparationBounds.minX();
             x <= preparationBounds.maxX();
             x++) {

            for (int z = preparationBounds.minZ();
                 z <= preparationBounds.maxZ();
                 z++) {

                for (int height = 0;
                     height < clearanceHeight;
                     height++) {

                    BlockPos clearancePos =
                            new BlockPos(
                                    x,
                                    sourcePos.getY() + height,
                                    z
                            );

                    level.setBlock(
                            clearancePos,
                            Blocks.AIR.defaultBlockState(),
                            3
                    );
                }
            }
        }
    }

    private static void validateArguments(
            ServerLevel level,
            BlockPos pos,
            Direction facing,
            SourcePlacementProfile placementProfile,
            SourceState sourceState,
            LeadershipContext leadershipContext
    ) {
        if (level == null) {
            throw new IllegalArgumentException(
                    "Source level cannot be null."
            );
        }

        if (pos == null) {
            throw new IllegalArgumentException(
                    "Source position cannot be null."
            );
        }

        if (facing == null
                || facing.getAxis().isVertical()) {

            throw new IllegalArgumentException(
                    "Source facing must be horizontal."
            );
        }

        if (placementProfile == null) {
            throw new IllegalArgumentException(
                    "Source placement profile cannot be null."
            );
        }

        if (sourceState == null) {
            throw new IllegalArgumentException(
                    "Source state cannot be null."
            );
        }

        if (leadershipContext == null) {
            throw new IllegalArgumentException(
                    "Leadership context cannot be null."
            );
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

    private CreateTunnelSource() {
    }
}