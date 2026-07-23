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

    public static void constructSiegeBlock(
            ServerLevel level,
            BlockPos pos,
            Direction facing,
            SiegeNode.SiegeAction action,
            StandardFlowField flowField
    ) {
        if (action == SiegeNode.SiegeAction.WALK || action == SiegeNode.SiegeAction.LEAP) {
            return;
        }

        if (action == SiegeNode.SiegeAction.MINE) {
            executeBreach(level, pos, flowField);
            return;
        }

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
                Direction spiralFacing = Direction.from2DDataValue(Math.abs(pos.getY()) % 4);
                stateToPlace = Blocks.COBBLESTONE_STAIRS.defaultBlockState()
                        .setValue(StairBlock.FACING, spiralFacing);
            }

            case BUILD_LADDER -> {
                Direction wallDirection = null;
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos adjacentPos = pos.relative(dir);
                    if (level.getBlockState(adjacentPos).isSolidRender(level, adjacentPos)) {
                        wallDirection = dir;
                        break;
                    }
                }

                if (wallDirection != null) {
                    stateToPlace = Blocks.LADDER.defaultBlockState()
                            .setValue(LadderBlock.FACING, wallDirection.getOpposite());
                } else {
                    stateToPlace = Blocks.COBBLESTONE.defaultBlockState();
                }
            }
            case BUILD_LANDING -> {
                // FIXED: Create 3x3 staging platform beneath landing node, but leave landing space open
                for (int x = -1; x <= 1; x++) {
                    for (int z = -1; z <= 1; z++) {
                        BlockPos platformPos = pos.below().offset(x, 0, z);
                        if (level.getBlockState(platformPos).canBeReplaced()) {
                            level.setBlockAndUpdate(platformPos, Blocks.COBBLESTONE.defaultBlockState());
                        }
                    }
                }
                // Return early so cobblestone isn't placed inside the target node standing area
                return;
            }

            case BUILD_BRIDGE, BUILD_PILLAR -> {
                stateToPlace = Blocks.COBBLESTONE.defaultBlockState();
            }

            default -> {
                stateToPlace = Blocks.COBBLESTONE.defaultBlockState();
            }
        }

        level.setBlockAndUpdate(pos, stateToPlace);
        level.levelEvent(2001, pos, Block.getId(stateToPlace));
    }

    public static boolean isSpaceClear(ServerLevel level, BlockPos pos, LivingEntity builder) {
        AABB box = new AABB(pos);
        List<LivingEntity> entities = level.getEntitiesOfClass(LivingEntity.class, box, entity -> entity != builder);
        return entities.isEmpty();
    }

    public static void pushOccupantsAway(ServerLevel level, BlockPos pos, PathfinderMob builder) {
        AABB box = new AABB(pos);
        List<LivingEntity> entities = level.getEntitiesOfClass(LivingEntity.class, box);
        Vec3 center = Vec3.atCenterOf(pos);

        for (LivingEntity entity : entities) {
            if (entity.equals(builder)) continue;

            Vec3 delta = entity.position().subtract(center);
            double horizLenSqr = delta.x * delta.x + delta.z * delta.z;
            Vec3 horizontal = horizLenSqr < 0.0001D
                    ? new Vec3(0.2D, 0.0D, 0.2D)
                    : new Vec3(delta.x, 0.0D, delta.z).normalize().scale(0.35D);

            // A queued rat on a narrow bridge/staircase has nothing but open air to either
            // side - pushing it "away from center" without checking for a floor is how a
            // nudge turns into knocking it off the edge. Only apply the horizontal shove if
            // it actually lands somewhere with ground; otherwise just hop it in place.
            BlockPos landingPos = BlockPos.containing(entity.position().add(horizontal));
            boolean hasFloor = level.getBlockState(landingPos.below()).blocksMotion();

            Vec3 pushVec = hasFloor ? horizontal.add(0.0D, 0.15D, 0.0D) : new Vec3(0.0D, 0.2D, 0.0D);

            entity.setDeltaMovement(entity.getDeltaMovement().add(pushVec));
            entity.hasImpulse = true;
        }
    }

    public static int calculateMiningTicks(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        float destroySpeed = state.getDestroySpeed(level, pos);

        if (destroySpeed < 0.0F) {
            return 10000;
        }

        return Math.max(10, (int) (destroySpeed * 12.0F));
    }

    public static void executeBreach(ServerLevel level, BlockPos pos, StandardFlowField flowField) {
        level.destroyBlock(pos, true);
    }
}