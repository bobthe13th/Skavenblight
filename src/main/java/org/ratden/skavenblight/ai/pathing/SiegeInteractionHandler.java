package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class SiegeInteractionHandler {

    /**
     * Checks whether the target block space is free of living entities.
     * Prevents mobs from suffocating or getting trapped inside placed blocks.
     */
    public static boolean isSpaceClear(ServerLevel level, BlockPos pos) {
        AABB box = new AABB(pos);
        List<LivingEntity> occupants = level.getEntitiesOfClass(
                LivingEntity.class,
                box,
                e -> !e.isSpectator() && e.isAlive()
        );
        return occupants.isEmpty();
    }

    /**
     * Applies an outward physical impulse to any entity occupying the target block
     * to push them clear before block placement.
     */
    public static void pushOccupantsAway(ServerLevel level, BlockPos pos, PathfinderMob builder) {
        AABB clearanceBox = new AABB(pos).inflate(0.3D, 0.5D, 0.3D);
        List<LivingEntity> occupants = level.getEntitiesOfClass(
                LivingEntity.class,
                clearanceBox,
                e -> e != builder && e.isAlive()
        );

        for (LivingEntity occupant : occupants) {
            Vec3 pushVector = occupant.position().subtract(Vec3.atCenterOf(pos));

            if (pushVector.lengthSqr() < 0.001D) {
                pushVector = new Vec3(0.2D, 0.15D, 0.2D);
            } else {
                pushVector = pushVector.normalize().scale(0.3D).add(0.0D, 0.15D, 0.0D);
            }

            occupant.setDeltaMovement(occupant.getDeltaMovement().add(pushVector));
            occupant.hasImpulse = true;
        }
    }

    /**
     * Places the appropriate siege block (stairs, bridges, pillars) based on the action type.
     */
    public static void constructSiegeBlock(
            ServerLevel level,
            BlockPos pos,
            Direction facing,
            SiegeNode.SiegeAction action,
            StandardFlowField flowField
    ) {
        if (!level.getBlockState(pos).canBeReplaced()) {
            return;
        }

        BlockState stateToPlace;
        Direction validFacing = (facing != null) ? facing : Direction.NORTH;

        switch (action) {
            case BUILD_STAIR -> stateToPlace = Blocks.COBBLESTONE_STAIRS.defaultBlockState()
                    .setValue(StairBlock.FACING, validFacing);
            case BUILD_BRIDGE, BUILD_PILLAR, BUILD_LANDING -> stateToPlace = Blocks.COBBLESTONE.defaultBlockState();
            case BUILD_LADDER -> {
                return; // <-- ADDED: Let DeployClimbableGoal handle this!
            }
            default -> stateToPlace = Blocks.COBBLESTONE.defaultBlockState();
        }

        // Place the block and play the placement sound/particle event
        level.setBlockAndUpdate(pos, stateToPlace);
        level.levelEvent(2001, pos, Block.getId(stateToPlace));
    }

    /**
     * Calculates required mining ticks based on block hardness.
     */
    public static int calculateMiningTicks(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        float destroySpeed = state.getDestroySpeed(level, pos);

        if (destroySpeed < 0.0F) {
            return 10000; // Bedrock / Unbreakable
        }

        // Scaled mining time based on hardness (min 10 ticks, max scaled)
        return Math.max(10, (int) (destroySpeed * 12.0F));
    }

    /**
     * Destroys a target block during breaching operations.
     */
    public static void executeBreach(ServerLevel level, BlockPos pos, StandardFlowField flowField) {
        level.destroyBlock(pos, true);
    }
}