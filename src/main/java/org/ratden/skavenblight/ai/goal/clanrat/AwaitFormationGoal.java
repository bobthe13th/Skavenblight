package org.ratden.skavenblight.ai.goal.clanrat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import org.ratden.skavenblight.ai.goal.SiegeGoal;
import org.ratden.skavenblight.ai.pathing.SiegeNode;
import org.ratden.skavenblight.ai.pathing.region.Region;
import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;
import org.ratden.skavenblight.entity.custom.ClanratEntity;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.Optional;

/**
 * Runs when a rat's nearest construction work (per BuildFlowFieldGoal/WidenStairsGoal - the
 * stair/pillar/spiral "climbing" family; SmartBreachGoal's breach work is out of scope) exists
 * but is already claimed by a different, living mob. Rather than falling through to
 * FollowFlowFieldGoal and physically walking up to join whatever crowd has formed at that
 * contested spot, this goal first looks for ANY OTHER unclaimed climb-type node anywhere in the
 * rat's own region and redirects there instead - see AwaitFormationGoalGameTests for the
 * formation-slot fallback used when nothing else is available.
 *
 * <p>Priority sits below the construction goals (only runs once they've already declined) and
 * above FollowFlowFieldGoal (pre-empts plain "walk toward the crowd" specifically for the
 * contended-target case - see ClanratEntity's goal registration).
 */
public class AwaitFormationGoal extends Goal implements SiegeGoal {

    private static final int FORMATION_MIN_RADIUS = 4;
    private static final int FORMATION_MAX_RADIUS = 12;
    private static final long RECHECK_INTERVAL_TICKS = 40;

    private final PathfinderMob mob;
    private RegionFlowField flowField;

    private BlockPos contestedAnchor;
    private BlockPos redirectTarget;
    private BlockPos formationSlot;
    private long nextRecheckTime = 0;

    public AwaitFormationGoal(PathfinderMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public void setFlowField(RegionFlowField flowField) {
        this.flowField = flowField;
    }

    @Override
    public boolean canUse() {
        if (this.flowField == null || !(this.mob.level() instanceof ServerLevel)) return false;
        return findContestedAnchor().isPresent();
    }

    private Optional<BlockPos> findContestedAnchor() {
        if (!(this.mob instanceof ClanratEntity clanrat)) return Optional.empty();
        return clanrat.peekAnyClaimedConstructionTarget();
    }

    @Override
    public void start() {
        this.redirectTarget = null;
        this.formationSlot = null;
        this.nextRecheckTime = 0;
        this.contestedAnchor = findContestedAnchor().orElse(null);
        seekWorkOrFormation();
    }

    private void seekWorkOrFormation() {
        if (this.contestedAnchor == null || !(this.mob.level() instanceof ServerLevel serverLevel)) return;

        Optional<BlockPos> alternative = findUnclaimedAlternative(serverLevel);
        if (alternative.isPresent()) {
            this.redirectTarget = alternative.get();
            if (this.flowField != null) {
                this.flowField.tryClaimTarget(this.redirectTarget, this.mob);
            }
            this.mob.getNavigation().moveTo(
                    this.redirectTarget.getX() + 0.5D, this.redirectTarget.getY(), this.redirectTarget.getZ() + 0.5D, 1.0D);
            return;
        }

        findFormationSlot().ifPresent(slot -> {
            this.formationSlot = slot;
            if (this.flowField != null) {
                this.flowField.tryClaimFormationSlot(this.formationSlot, this.mob);
            }
            this.mob.getNavigation().moveTo(
                    this.formationSlot.getX() + 0.5D, this.formationSlot.getY(), this.formationSlot.getZ() + 0.5D, 1.0D);
        });
    }

    /** Nearest unclaimed climb-type (stair/pillar/spiral) node anywhere in this rat's own region, by straight-line distance. */
    private Optional<BlockPos> findUnclaimedAlternative(ServerLevel level) {
        RegionFlowField field = this.flowField;
        if (field == null) return Optional.empty();
        BlockPos mobPos = this.mob.blockPosition();
        // getInstructionMap() is keyed by STANDING position ("from here, do this"), not by the
        // target the action would build - the actual build position is each SiegeNode's own
        // pos(). Searching keys instead of values' pos() looks plausible but silently returns
        // whichever standing position happens to satisfy the filters (often the mob's own
        // current position), never a genuine alternative target - caught by
        // testAwaitFormationGoalRedirectsToUnclaimedAlternative failing to find a target one hop
        // further away than the contested one.
        return field.getInstructionMap().values().stream()
                .filter(node -> node.action().isClimbDependent())
                .distinct()
                .filter(node -> isPositionAvailable(field, node))
                .map(SiegeNode::pos)
                .filter(pos -> level.getBlockState(pos).canBeReplaced())
                .min(Comparator.comparingDouble(pos -> pos.distSqr(mobPos)));
    }

    /**
     * BUILD_SPIRAL still goes through the old per-block claim table (SpiralSapperGoal is
     * untouched by the project-scoped overhaul - see the design doc's Scope). BUILD_STAIR and
     * BUILD_PILLAR moved to project-worker registration (Task 8) - "available" for those means
     * their owning SiegeProject isn't at capacity, not "unclaimed" (that table no longer reflects
     * them at all).
     */
    private boolean isPositionAvailable(RegionFlowField field, SiegeNode node) {
        if (node.action() == SiegeNode.SiegeAction.BUILD_SPIRAL) {
            return !field.isTargetClaimed(node.pos());
        }
        return field.findProjectFor(node.pos()).map(project -> !project.isAtCapacity()).orElse(true);
    }

    /**
     * Searches outward from the rat's OWN current position (not the contested target) in
     * expanding square rings, validated against the REAL Region membership this rat's flowField
     * belongs to - never blind geometry. Region membership alone is NOT sufficient to guarantee a
     * slot is never inside a wall or over a drop, though: a provisionally-claimed-but-unbuilt
     * connector cell reports {@code region.contains()}-true too (see the live-ground check a few
     * lines below in this same method, and its own inline comment, for why). Anchoring on the
     * rat's own position rather than the target matters: a macro chain's contested target is
     * often one Y level up from any real ground (the next unbuilt climbing step), so it may not
     * be region-member territory at all yet - the rat's own standing position always is, since
     * it's already there. Ordered ring-by-ring, and within a ring in a fixed scan order, so
     * independent rats naturally fill in an outward pattern without any of them needing to know
     * about "the formation" as a shared object.
     */
    private Optional<BlockPos> findFormationSlot() {
        RegionFlowField field = this.flowField;
        if (field == null) return Optional.empty();
        Region region = field.getOwner() == null ? null
                : field.getOwner().getRegionIndex().getRegions().stream()
                        .filter(r -> r.getId() == field.getRegionId())
                        .findFirst().orElse(null);
        if (region == null) return Optional.empty();

        BlockPos searchOrigin = this.mob.blockPosition();
        for (int r = FORMATION_MIN_RADIUS; r <= FORMATION_MAX_RADIUS; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos candidate = searchOrigin.offset(dx, 0, dz);
                    if (!region.contains(candidate)) continue;
                    // region.contains() alone isn't enough: a provisionally-claimed-but-unbuilt
                    // connector cell reports true too (see RegionGraph.registerConnector and
                    // docs/superpowers/specs/2026-07-30-region-merge-detection-design.md's
                    // background invariants) - require real, current solid ground beneath the
                    // candidate as well, the same live-terrain check
                    // TerrainEvaluator.isWalkableTerrain uses for the identical reason.
                    if (!this.mob.level().getBlockState(candidate.below()).blocksMotion()) continue;
                    if (field.isFormationSlotClaimed(candidate)) continue;
                    if (field.getInstructionMap().containsKey(candidate)) continue;
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean canContinueToUse() {
        return this.mob.isAlive() && this.flowField != null
                && (this.redirectTarget != null || this.formationSlot != null);
    }

    @Override
    public void tick() {
        if (!(this.mob.level() instanceof ServerLevel serverLevel)) return;

        if (this.redirectTarget != null) {
            // Arrived close enough - stop here (see stop()'s release-and-reclaim comment).
            if (this.mob.blockPosition().closerThan(this.redirectTarget, AbstractSiegeConstructionGoal.MAX_TARGET_CLAIM_DISTANCE)) {
                this.redirectTarget = null;
            }
            return;
        }

        if (this.formationSlot != null && this.mob.blockPosition().closerThan(this.formationSlot, 1.5D)
                && this.mob.getNavigation().isDone()) {
            long now = serverLevel.getGameTime();
            if (now >= this.nextRecheckTime) {
                this.nextRecheckTime = now + RECHECK_INTERVAL_TICKS;
                Optional<BlockPos> alternative = findUnclaimedAlternative(serverLevel);
                if (alternative.isPresent()) {
                    RegionFlowField field = this.flowField;
                    if (field != null) {
                        field.releaseFormationSlot(this.formationSlot);
                    }
                    this.formationSlot = null;
                    this.redirectTarget = alternative.get();
                    if (field != null) {
                        field.tryClaimTarget(this.redirectTarget, this.mob);
                    }
                    this.mob.getNavigation().moveTo(
                            this.redirectTarget.getX() + 0.5D, this.redirectTarget.getY(), this.redirectTarget.getZ() + 0.5D, 1.0D);
                }
            }
        }
    }

    @Override
    public void stop() {
        // Release-and-reclaim, not hand-off - still required, but for the per-block-claim goal
        // family, NOT for BuildFlowFieldGoal. (This comment used to cite BuildFlowFieldGoal and
        // WidenStairsGoal; WidenStairsGoal was deleted during the project-scoped overhaul, and
        // BuildFlowFieldGoal - now an AbstractSiegeProjectGoal - doesn't consult the claim table at
        // all any more, it registers as a worker on the owning SiegeProject instead.)
        //
        // What still makes this necessary: every remaining claim-table consumer treats ANY claim on
        // a position as "someone has it", without comparing the claimant to the asking mob -
        // AbstractSiegeConstructionGoal.canUse() (SmartBreachGoal), SpiralSapperGoal,
        // WarpSapperGoal and DeployClimbableGoal all filter on a bare
        // flowField.isTargetClaimed(pos), and so does this goal's OWN isPositionAvailable for
        // BUILD_SPIRAL. Carrying this mob's claim across the goal transition would therefore make
        // the receiving goal - including this same mob's own next goal - refuse to pick the target
        // back up. Releasing here and letting whoever takes over re-claim fresh via its own
        // already-tested start() is the only safe pattern given those filters' semantics.
        if (this.redirectTarget != null && this.flowField != null) {
            this.flowField.releaseTarget(this.redirectTarget);
        }
        if (this.formationSlot != null && this.flowField != null) {
            this.flowField.releaseFormationSlot(this.formationSlot);
        }
        this.redirectTarget = null;
        this.formationSlot = null;
        this.contestedAnchor = null;
        this.mob.getNavigation().stop();
    }
}
