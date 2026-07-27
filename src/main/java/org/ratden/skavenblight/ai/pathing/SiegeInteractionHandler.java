package org.ratden.skavenblight.ai.pathing;

import com.mojang.logging.LogUtils;
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
import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;
import org.ratden.skavenblight.debug.SiegeActivityLog;
import org.slf4j.Logger;

import java.util.List;

public class SiegeInteractionHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * @param actor the mob performing the action, or null if unattributed (kept nullable so
     *              this stays callable from any future non-mob-driven trigger). Used only for
     *              diagnostics: the self-entombment check below and {@link SiegeActivityLog}.
     */
    public static void constructSiegeBlock(
            ServerLevel level,
            BlockPos pos,
            Direction facing,
            SiegeNode.SiegeAction action,
            RegionFlowField flowField,
            LivingEntity actor
    ) {
        if (action == SiegeNode.SiegeAction.WALK || action == SiegeNode.SiegeAction.LEAP) {
            return;
        }

        if (action == SiegeNode.SiegeAction.MINE) {
            executeBreach(level, pos, flowField, actor);
            return;
        }

        if (!level.getBlockState(pos).canBeReplaced()) {
            return;
        }

        // A mob's own hitbox can overlap the block it's about to place into - isSpaceClear()
        // deliberately excludes the builder from ITS check (a rat must be able to stand where
        // its own pillar/stair target is to reach it), so nothing else was verifying this.
        // Reported in testing: "the rat that made it up placed a block inside where they were
        // standing, then hung." Logged rather than blocked outright since the correct fix
        // (have the mob step clear, or jump mid-placement for pillar-style actions) needs
        // real reproduction data first - see this WARN plus SiegeActivityLog's trail.
        if (actor != null && actor.getBoundingBox().intersects(new AABB(pos))) {
            LOGGER.warn("[Skavenblight] {} ({}) executing {} at {} while its own hitbox overlaps the target - " +
                            "risk of self-entombment. Mob pos: {}",
                    actor.getClass().getSimpleName(), actor.getUUID().toString().substring(0, 8),
                    action, pos.toShortString(), actor.blockPosition().toShortString());
        }

        Direction validFacing = (facing != null) ? facing : Direction.NORTH;
        BlockState stateToPlace;

        switch (action) {
            case BUILD_STAIR -> {
                stateToPlace = Blocks.COBBLESTONE_STAIRS.defaultBlockState()
                        .setValue(StairBlock.FACING, validFacing);

                // Clear 2 blocks of headroom above the step (3 total with the step itself),
                // matching the clearance TerrainEvaluator#determineMacroAction already requires
                // at plan time. That check only runs once, when the line is first traced, but
                // the line is executed one step at a time over many ticks - a different
                // project, or a later step of this same line, can obstruct that headroom before
                // a rat actually reaches it, sealing the passage it just climbed. Reported in
                // testing: rats got stuck "placing more stairs on top of the staircase,
                // blocking the path."
                for (int y = 1; y <= 2; y++) {
                    BlockPos headroomPos = pos.above(y);
                    BlockState headroomState = level.getBlockState(headroomPos);
                    if (headroomState.blocksMotion() && !headroomState.canBeReplaced()) {
                        // Diagnostic only (systematic-debugging evidence-gathering): this destroy
                        // was previously silent - only the main placement below got logged - so a
                        // headroom-clear that happens to hit an already-completed sibling step
                        // (e.g. in a tight vertical spiral shaft) left zero trace of what knocked
                        // it down, even though SiegeActivityLog otherwise records every siege
                        // action. Investigating a report of repeated identical BUILD_SPIRAL
                        // executions at the same position - this will show directly whether a
                        // later step's headroom-clear is destroying an earlier, already-built one.
                        SiegeActivityLog.record(level.getGameTime(), actor, headroomPos, SiegeNode.SiegeAction.MINE,
                                "headroom-clear for " + action + " at " + pos.toShortString(), regionIdOf(flowField));
                        level.destroyBlock(headroomPos, false);
                    }
                }
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
                    // The planner (TerrainEvaluator#determineMacroAction) only ever emits
                    // BUILD_LADDER when it found a wall at plan time - if none exists by
                    // execution time, terrain changed in between (commonly: a nearby
                    // MINE/BUILD project cleared the exact wall this ladder needed). Silently
                    // falling back to a plain block means a rat can still climb it like a
                    // pillar, but the plan's intent (a climbable ladder) quietly failed with no
                    // other record of it - log it so a recurring pattern is visible.
                    LOGGER.warn("[Skavenblight] BUILD_LADDER at {} found no adjacent wall at execution time - " +
                            "substituting a plain block instead of a ladder", pos.toShortString());
                    stateToPlace = Blocks.COBBLESTONE.defaultBlockState();
                }
            }
            case BUILD_LANDING -> {
                // 3x3 staging platform beneath the landing node, plus 2 blocks of headroom
                // above it. A landing chained mid-tunnel (between two staircases) previously
                // only got a floor - the space above stayed whatever solid rock the line was
                // driven through, so the "platform" had nowhere to actually stand or turn.
                for (int x = -1; x <= 1; x++) {
                    for (int z = -1; z <= 1; z++) {
                        BlockPos platformPos = pos.below().offset(x, 0, z);
                        if (level.getBlockState(platformPos).canBeReplaced()) {
                            level.setBlockAndUpdate(platformPos, Blocks.COBBLESTONE.defaultBlockState());
                        }

                        for (int y = 0; y <= 1; y++) {
                            BlockPos headroomPos = pos.offset(x, y, z);
                            BlockState headroomState = level.getBlockState(headroomPos);
                            if (headroomState.blocksMotion() && !headroomState.canBeReplaced()) {
                                // See the matching comment on BUILD_STAIR's headroom clear above -
                                // same previously-silent-destroy diagnostic gap.
                                SiegeActivityLog.record(level.getGameTime(), actor, headroomPos, SiegeNode.SiegeAction.MINE,
                                        "headroom-clear for BUILD_LANDING at " + pos.toShortString(), regionIdOf(flowField));
                                level.destroyBlock(headroomPos, false);
                            }
                        }
                    }
                }
                SiegeActivityLog.record(level.getGameTime(), actor, pos, action, "landing platform + headroom cleared", regionIdOf(flowField));
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
        SiegeActivityLog.record(level.getGameTime(), actor, pos, action, stateToPlace.getBlock().getDescriptionId(), regionIdOf(flowField));
    }

    /** Region id to attribute a logged action to, or null when the actor has no field assigned. */
    private static Integer regionIdOf(RegionFlowField flowField) {
        return flowField != null ? flowField.getRegionId() : null;
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

            // "Has a floor" alone isn't enough on a staircase - the sideways landing spot can
            // still be a lower step or the slope beside it, several blocks down, which reads
            // to a player as "pushed off the stairs" even though technically it landed on
            // something solid. Require the landing to be roughly the entity's own standing
            // height too, so a nudge on a stair/bridge stays a nudge instead of tipping the
            // entity down the adjacent slope. Reported in testing: "they started pushing each
            // other off the staircase."
            boolean sameLevel = Math.abs(landingPos.getY() - entity.blockPosition().getY()) <= 1;

            Vec3 pushVec = (hasFloor && sameLevel) ? horizontal.add(0.0D, 0.15D, 0.0D) : new Vec3(0.0D, 0.2D, 0.0D);

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

    public static void executeBreach(ServerLevel level, BlockPos pos, RegionFlowField flowField, LivingEntity actor) {
        level.destroyBlock(pos, true);
        SiegeActivityLog.record(level.getGameTime(), actor, pos, SiegeNode.SiegeAction.MINE, "breached", regionIdOf(flowField));
    }
}