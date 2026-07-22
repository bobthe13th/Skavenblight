package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class SiegeInteractionHandler {

    /**
     * Executes block construction or world interaction for every valid SiegeAction type:
     * WALK, MINE, BUILD_BRIDGE, BUILD_STAIR, BUILD_LANDING, BUILD_PILLAR, LEAP, BUILD_LADDER, BUILD_SPIRAL
     */
    public static void constructSiegeBlock(
            ServerLevel level,
            BlockPos pos,
            Direction facing,
            SiegeNode.SiegeAction action,
            StandardFlowField flowField
    ) {
        // 1. Non-construction actions (pure movement / spatial jumps)
        if (action == SiegeNode.SiegeAction.WALK || action == SiegeNode.SiegeAction.LEAP) {
            return;
        }

        // 2. Destructive actions
        if (action == SiegeNode.SiegeAction.MINE) {
            executeBreach(level, pos, flowField);
            return;
        }

        // 3. Ensure target space can be replaced by a block
        if (!level.getBlockState(pos).canBeReplaced()) {
            return;
        }

        Direction validFacing = (facing != null) ? facing : Direction.NORTH;
        BlockState stateToPlace;

        switch (action) {
            case BUILD_STAIR -> {
                stateToPlace = Blocks.COBBLESTONE_STAIRS.defaultBlockState()
                        .setValue(StairBlock.FACING, validFacing);
            }

            case BUILD_SPIRAL -> {
                // Rotate stair orientation based on Y-level modulo 4 to form a winding staircase
                Direction spiralFacing = Direction.from2DDataValue(Math.abs(pos.getY()) % 4);
                stateToPlace = Blocks.COBBLESTONE_STAIRS.defaultBlockState()
                        .setValue(StairBlock.FACING, spiralFacing);
            }

            case BUILD_LADDER -> {
                // Find an adjacent solid wall block to attach the ladder
                Direction wallDirection = null;
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos adjacentPos = pos.relative(dir);
                    if (level.getBlockState(adjacentPos).isSolidRender(level, adjacentPos)) {
                        wallDirection = dir;
                        break;
                    }
                }

                if (wallDirection != null) {
                    // Minecraft ladders face AWAY from the supporting wall
                    stateToPlace = Blocks.LADDER.defaultBlockState()
                            .setValue(LadderBlock.FACING, wallDirection.getOpposite());
                } else {
                    // Fallback to solid block if no wall is available
                    stateToPlace = Blocks.COBBLESTONE.defaultBlockState();
                }
            }
            case BUILD_LANDING -> {
                // Generate a 3x3 platform centered beneath the landing node
                for (int x = -1; x <= 1; x++) {
                    for (int z = -1; z <= 1; z++) {
                        BlockPos platformPos = pos.below().offset(x, 0, z);
                        if (level.getBlockState(platformPos).canBeReplaced()) {
                            level.setBlockAndUpdate(platformPos, Blocks.COBBLESTONE.defaultBlockState());
                        }
                    }
                }
                stateToPlace = Blocks.COBBLESTONE.defaultBlockState();
            }

            case BUILD_BRIDGE, BUILD_PILLAR -> {
                stateToPlace = Blocks.COBBLESTONE.defaultBlockState();
            }

            default -> {
                stateToPlace = Blocks.COBBLESTONE.defaultBlockState();
            }
        }

        // Place block and emit break sound/particle FX
        level.setBlockAndUpdate(pos, stateToPlace);
        level.levelEvent(2001, pos, Block.getId(stateToPlace));
    }

    /**
     * Checks whether the target block bounding box is free of living entity collision.
     */
    public static boolean isSpaceClear(ServerLevel level, BlockPos pos) {
        AABB box = new AABB(pos);
        List<LivingEntity> entities = level.getEntitiesOfClass(LivingEntity.class, box);
        return entities.isEmpty();
    }

    /**
     * Applies a physical impulse vector to push non-builder entities out of the target block position.
     */
    public static void pushOccupantsAway(ServerLevel level, BlockPos pos, PathfinderMob builder) {
        AABB box = new AABB(pos);
        List<LivingEntity> entities = level.getEntitiesOfClass(LivingEntity.class, box);
        Vec3 center = Vec3.atCenterOf(pos);

        for (LivingEntity entity : entities) {
            if (entity.equals(builder)) continue; // Skip the active builder

            Vec3 pushDir = entity.position().subtract(center);
            if (pushDir.lengthSqr() < 0.0001D) {
                pushDir = new Vec3(0.2D, 0.2D, 0.2D); // Fallback vector if centered exactly
            } else {
                pushDir = pushDir.normalize().scale(0.35D).add(0.0D, 0.15D, 0.0D);
            }

            entity.setDeltaMovement(entity.getDeltaMovement().add(pushDir));
            entity.hasImpulse = true;
        }
    }

    /**
     * Calculates the required mining time in ticks based on block hardness.
     */
    public static int calculateMiningTicks(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        float destroySpeed = state.getDestroySpeed(level, pos);

        if (destroySpeed < 0.0F) {
            return 10000; // Bedrock / Unbreakable
        }

        return Math.max(10, (int) (destroySpeed * 12.0F));
    }

    /**
     * Destroys a block in place and updates flow field status if applicable.
     */
    public static void executeBreach(ServerLevel level, BlockPos pos, StandardFlowField flowField) {
        level.destroyBlock(pos, true);
    }
}