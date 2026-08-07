package org.ratden.skavenblight.ai.pathing;

import com.mojang.logging.LogUtils;
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
     * @param isPlatform when true, dispatch to platform-clearing behavior (3x3 floor + headroom,
     *              ported from the old BUILD_LANDING case) regardless of {@code action}, then
     *              return early - a PLATFORM seam (see PlatformInserter) is a post-process
     *              annotation on an EXISTING build-order step, not a 6th PathAction, so this stays
     *              a separate boolean rather than a value {@code action} could hold.
     */
    public static void constructSiegeBlock(
            ServerLevel level,
            BlockPos pos,
            Direction facing,
            PathAction action,
            RegionFlowField flowField,
            LivingEntity actor,
            boolean supportSolidAtClaim,
            boolean isPlatform
    ) {
        if (action == PathAction.WALK) {
            return;
        }

        // Checked FIRST, before any breach/canBeReplaced/climb-dependent logic below - a platform
        // seam (see PlatformInserter) can land on ANY of the four construction actions' own build-
        // order position, including one whose pos is solid rock (a CARVED_STAIR or TUNNEL step).
        // Platform clearing is self-sufficient: its own headroom loop already destroys whatever
        // blocks motion at pos itself (the x=0,z=0,y=0 iteration below), so a separate breach here
        // first would be redundant work that also drops items and logs a spurious "breached" entry
        // for a cell about to be cleared again anyway. Old BUILD_LANDING never needed this ordering
        // question - a landing node was, by construction, already an open mid-air cell - but
        // PLATFORM is no longer tied to one specific action the way BUILD_LANDING was.
        if (isPlatform) {
            // 3x3 staging platform beneath the node, plus 2 blocks of headroom above it. A
            // platform seam chained mid-tunnel (between two staircases) previously only got a
            // floor - the space above stayed whatever solid rock the line was driven through, so
            // the "platform" had nowhere to actually stand or turn.
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
                            // Diagnostic only (systematic-debugging evidence-gathering): this
                            // destroy was previously silent - only the main placement got logged -
                            // so a headroom-clear that happens to hit an already-completed
                            // sibling step left zero trace of what knocked it down, even though
                            // SiegeActivityLog otherwise records every siege action.
                            SiegeActivityLog.record(level.getGameTime(), actor, headroomPos, PathAction.TUNNEL,
                                    "headroom-clear for platform at " + pos.toShortString(), regionIdOf(flowField));
                            level.destroyBlock(headroomPos, false);
                        }
                    }
                }
            }
            SiegeActivityLog.record(level.getGameTime(), actor, pos, action, "platform + headroom cleared", regionIdOf(flowField));
            // Return early so cobblestone isn't placed inside the node's own standing area.
            return;
        }

        // CARVED_STAIR mines AND places: PathStepEvaluator.candidateSteps only ever classifies a
        // neighbor CARVED_STAIR when its own foot or head is a genuine blocking obstacle, so pos
        // itself is typically solid rock at execution time - mining must run BEFORE the
        // canBeReplaced() guard below, or that guard would silently no-op every CARVED_STAIR
        // forever (it never becomes replaceable on its own).
        if (action == PathAction.TUNNEL) {
            executeBreach(level, pos, flowField, actor);
            return;
        } else if (action == PathAction.CARVED_STAIR) {
            executeBreach(level, pos, flowField, actor);
        }

        if (!level.getBlockState(pos).canBeReplaced()) {
            return;
        }

        // supportSolidAtClaim (see AbstractSiegeProjectGoal#supportSolidAtClaim) is only true
        // when this target's support was ALREADY solid back when it was selected/claimed - never
        // for a macro SiegeProject's next unbuilt chain step, whose support is the PREVIOUS step,
        // built moments earlier (a placed stair is self-supporting for pathing purposes
        // regardless of what ends up below it - see PathStepEvaluator.isWalkableTerrain's
        // scaffold short-circuit). So this guard only fires for the actual race: support that WAS
        // there got removed by a different clanrat's concurrent action sometime in the window
        // between claim and execution. CARVED_STAIR/AIR_STAIR are the only two actions that place
        // something meant to be stood ON (mirroring old BUILD_STAIR/BUILD_PILLAR/BUILD_SPIRAL
        // minus the removed pure-climb actions); BRIDGE is excluded like old BUILD_BRIDGE -
        // expected to have open air below by design.
        if (isClimbDependent(action) && supportSolidAtClaim && !level.getBlockState(pos.below()).blocksMotion()) {
            SiegeActivityLog.record(level.getGameTime(), actor, pos, action,
                    "aborted placement - support at " + pos.below().toShortString() + " no longer solid, would float",
                    regionIdOf(flowField));
            return;
        }

        // A mob's own hitbox can overlap the block it's about to place into - isSpaceClear()
        // deliberately excludes the builder from ITS check (a rat must be able to stand where
        // its own construction target is to reach it), so nothing else was verifying this.
        // Reported in testing: "the rat that made it up placed a block inside where they were
        // standing, then hung." Root-caused via a controlled GameTest capture (see
        // ClanratEntity's recoverFromStuckAirborne doc): this exact self-overlap, immediately
        // followed by placing a STAIR (a compound, non-cuboid collision shape) into that
        // overlapped space, embeds the mob partway inside the new shape. Vanilla's collision
        // resolution against a stair's tread/riser discontinuity from an already-embedded
        // starting position (as opposed to falling onto it from clear air) doesn't converge to
        // a stable rest - the mob's own onGround() flickers true for a single tick at the
        // bottom of each bounce, which resets recoverFromStuckAirborne's "100 consecutive
        // airborne ticks" counter every cycle, so that safety net can never trigger either.
        // Fix: push the actor straight down, out of the target's own volume, by exactly the
        // overlap height before the block goes solid - the mob's current footing is already
        // solid ground it was already standing on, so this can never drop it into anything
        // unsupported.
        if (actor != null && actor.getBoundingBox().intersects(new AABB(pos))) {
            AABB overlap = actor.getBoundingBox().intersect(new AABB(pos));
            if (overlap.getYsize() > 0) {
                LOGGER.warn("[Skavenblight] {} ({}) executing {} at {} while its own hitbox overlaps the target " +
                                "by {} - clearing before placement to avoid self-entombment. Mob pos: {}",
                        actor.getClass().getSimpleName(), actor.getUUID().toString().substring(0, 8),
                        action, pos.toShortString(), overlap.getYsize(), actor.blockPosition().toShortString());
                actor.setPos(actor.getX(), actor.getY() - overlap.getYsize() - 0.01D, actor.getZ());
                actor.setDeltaMovement(actor.getDeltaMovement().x, Math.min(actor.getDeltaMovement().y, 0.0D), actor.getDeltaMovement().z);
            }
        }

        Direction validFacing = (facing != null) ? facing : Direction.NORTH;
        BlockState stateToPlace = Blocks.COBBLESTONE.defaultBlockState();

        switch (action) {
            case CARVED_STAIR, AIR_STAIR -> {
                stateToPlace = Blocks.COBBLESTONE_STAIRS.defaultBlockState()
                        .setValue(StairBlock.FACING, validFacing);
                clearStairHeadroom(level, pos, action, flowField, actor);
            }
            default -> { /* BRIDGE uses the COBBLESTONE default above; WALK/TUNNEL already returned. */ }
        }

        level.setBlockAndUpdate(pos, stateToPlace);
        level.levelEvent(2001, pos, Block.getId(stateToPlace));
        SiegeActivityLog.record(level.getGameTime(), actor, pos, action, stateToPlace.getBlock().getDescriptionId(), regionIdOf(flowField));
    }

    /**
     * True for actions that place a block meant to be stood ON, so a mid-construction race that
     * yanks away solid ground out from under the target is worth guarding against (see the
     * supportSolidAtClaim check above) - mirrors old SiegeNode.SiegeAction.isClimbDependent's
     * {@code BUILD_STAIR || BUILD_PILLAR || BUILD_SPIRAL} minus the two pure-climb actions this
     * rewrite permanently removes. TUNNEL/BRIDGE are excluded: TUNNEL places nothing, and BRIDGE
     * is expected to have open air below at placement time by design, same as the old model.
     */
    private static boolean isClimbDependent(PathAction action) {
        return action == PathAction.CARVED_STAIR || action == PathAction.AIR_STAIR;
    }

    /**
     * 3 blocks of headroom above the tread (4 total with the step itself). {@code pos} here is
     * wherever the caller actually placed the block - for an ascending AIR_STAIR/CARVED_STAIR
     * that's one cell BELOW the logical cell PathStepEvaluator.candidateSteps classified (see
     * SiegeProject.placementPositionFor), so clearing y=1..3 above the physical placement covers
     * the logical cell itself (y=1, the mob's own standing space) plus the two cells above it that
     * isActionCompleted's CARVED_STAIR case separately verifies (isWalkableTerrain's own head check
     * at y=2, plus the extra overhang-safety cell at y=3) - matching the clearance
     * PathStepEvaluator.candidateSteps already requires at plan time (checked one cell lower, at
     * the logical target and its own head, before this shift existed). That plan-time check only
     * runs once, when the line is first traced, but the line is executed one step at a time over
     * many ticks - a different project, or a later step of this same line, can obstruct that
     * headroom before a rat actually reaches it, sealing the passage it just climbed. Reported in
     * testing: rats got stuck "placing more stairs on top of the staircase, blocking the path."
     */
    private static void clearStairHeadroom(ServerLevel level, BlockPos pos, PathAction action, RegionFlowField flowField, LivingEntity actor) {
        for (int y = 1; y <= 3; y++) {
            BlockPos headroomPos = pos.above(y);
            BlockState headroomState = level.getBlockState(headroomPos);
            if (headroomState.blocksMotion() && !headroomState.canBeReplaced()) {
                SiegeActivityLog.record(level.getGameTime(), actor, headroomPos, PathAction.TUNNEL,
                        "headroom-clear for " + action + " at " + pos.toShortString(), regionIdOf(flowField));
                level.destroyBlock(headroomPos, false);
            }
        }
    }

    /** Region id to attribute a logged action to, or null when the actor has no field assigned. */
    private static Integer regionIdOf(RegionFlowField flowField) {
        return flowField != null ? flowField.getRegionId() : null;
    }

    /**
     * The {@code BlockState} this action places once complete, for TerritoryRegionMap's
     * planned-cell terrain override (an active project's planned final state is authoritative for
     * terrain evaluation - see that class's onBlockChanged authority fix). {@code WALK} returns
     * null ("no override" - TerrainSnapshot's plannedStateOverride hook treats null that way): a
     * WALK step never changes terrain, so the real world state is already correct. Facing is
     * deliberately not modeled here (unlike the real placement in {@link #constructSiegeBlock}) -
     * a stair's blocksMotion()/isSolidRender()/getDestroySpeed(), the only properties terrain
     * evaluation reads off an overridden state, don't vary with facing.
     */
    public static BlockState finalBlockStateFor(PathAction action) {
        return switch (action) {
            case WALK -> null;
            case TUNNEL -> Blocks.AIR.defaultBlockState();
            case BRIDGE -> Blocks.COBBLESTONE.defaultBlockState();
            case CARVED_STAIR, AIR_STAIR -> Blocks.COBBLESTONE_STAIRS.defaultBlockState();
        };
    }

    /**
     * Whether {@code pos} is clear enough to place a block into without entombing something -
     * checked against each nearby entity's own {@code blockPosition()} (feet), not full AABB
     * overlap. Clanrats are 1.8 blocks tall (see ModEntities#CLANRAT), so a mob simply standing
     * on the ground one cell below/adjacent to the target already has its hitbox poking into
     * this exact space without occupying it in any way that matters - full-AABB overlap treated
     * that as "occupied" too, and in a crowded bottleneck there was almost always some tall
     * neighbor's head in the way, permanently failing this check regardless of whether the
     * target cell itself was ever actually stood in. Confirmed via a diagnostic dump: a claimant
     * stalled 16+ cycles (of a 60-cycle budget) with the target genuinely empty.
     */
    public static boolean isSpaceClear(ServerLevel level, BlockPos pos, LivingEntity builder) {
        AABB searchBox = new AABB(pos).inflate(1.0D);
        List<LivingEntity> entities = level.getEntitiesOfClass(LivingEntity.class, searchBox,
                entity -> entity != builder && entity.blockPosition().equals(pos));
        return entities.isEmpty();
    }

    /** Same feet-position scoping as {@link #isSpaceClear} - a tall neighbor whose head merely brushes {@code pos} has no reason to be shoved. */
    public static void pushOccupantsAway(ServerLevel level, BlockPos pos, PathfinderMob builder) {
        AABB searchBox = new AABB(pos).inflate(1.0D);
        List<LivingEntity> entities = level.getEntitiesOfClass(LivingEntity.class, searchBox,
                entity -> entity.blockPosition().equals(pos));
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

    public static void executeBreach(ServerLevel level, BlockPos pos, RegionFlowField flowField, LivingEntity actor) {
        level.destroyBlock(pos, true);
        SiegeActivityLog.record(level.getGameTime(), actor, pos, PathAction.TUNNEL, "breached", regionIdOf(flowField));
    }
}
