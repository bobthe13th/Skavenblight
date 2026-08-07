package org.ratden.skavenblight.ai.goal.clanrat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import org.ratden.skavenblight.Config;
import org.ratden.skavenblight.ai.goal.SiegeGoal;
import org.ratden.skavenblight.ai.pathing.FlowStep;
import org.ratden.skavenblight.ai.pathing.PathAction;
import org.ratden.skavenblight.ai.pathing.region.Region;
import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;
import org.ratden.skavenblight.entity.custom.ClanratEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Runs when a rat's nearest construction work (per BuildFlowFieldGoal - the only remaining
 * project-execution goal, matching all four construction actions) exists but is already claimed
 * (its owning SiegeProject is at capacity). Rather than falling through to FollowFlowFieldGoal and
 * physically walking up to join whatever crowd has formed at that contested spot, this goal first
 * looks for ANY OTHER unclaimed construction node anywhere in the rat's own region and redirects
 * there instead - see AwaitFormationGoalGameTests for the formation-grid fallback used when
 * nothing else is available.
 *
 * <p>Priority sits below the construction goals (only runs once they've already declined) and
 * above FollowFlowFieldGoal (pre-empts plain "walk toward the crowd" specifically for the
 * contended-target case - see ClanratEntity's goal registration).
 */
public class AwaitFormationGoal extends Goal implements SiegeGoal {

    private static final int FORMATION_MIN_RADIUS = 4;
    private static final int FORMATION_MAX_RADIUS = 12;
    private static final long RECHECK_INTERVAL_TICKS = 40;
    /** Same arrival-distance value AbstractSiegeConstructionGoal used before its removal. */
    private static final double MAX_TARGET_CLAIM_DISTANCE = 2.5D;

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

    /** Nearest unclaimed construction node anywhere in this rat's own region, by straight-line distance. */
    private Optional<BlockPos> findUnclaimedAlternative(ServerLevel level) {
        RegionFlowField field = this.flowField;
        if (field == null) return Optional.empty();
        BlockPos mobPos = this.mob.blockPosition();
        // Under the disambiguated FlowStep convention (see RegionFlowField.getNextStep's own doc),
        // a map value's pos() always equals its own key - so filtering by node.action() and mapping
        // to node.pos() is equivalent to filtering/mapping the map's own keys directly. Kept as a
        // values() stream (rather than entrySet().keySet()) because isPositionAvailable and the
        // action filter both need the FlowStep itself, not just its position.
        return field.getInstructionMap().values().stream()
                .filter(node -> node.action() != PathAction.WALK)
                .distinct()
                .filter(node -> isPositionAvailable(field, node))
                .map(FlowStep::pos)
                .filter(pos -> level.getBlockState(pos).canBeReplaced())
                .min(Comparator.comparingDouble(pos -> pos.distSqr(mobPos)));
    }

    /**
     * "Available" means the position's owning SiegeProject isn't at capacity - BuildFlowFieldGoal
     * is the only project-execution goal now (Task 16 deleted every per-block-claim construction
     * goal that used to need the old claim table for this), so every construction action goes
     * through project-worker registration uniformly.
     */
    private boolean isPositionAvailable(RegionFlowField field, FlowStep node) {
        return field.findProjectFor(node.pos()).map(project -> !project.isAtCapacity()).orElse(true);
    }

    /**
     * Anchors on the rat's OWN current position (not the contested target), validated against the
     * REAL Region membership this rat's flowField belongs to - never blind geometry. Sizes a
     * row/column grid to {@code getClaimedFormationSlotCount() + 1} (this rat plus everyone
     * already waiting), the primary mechanism (see {@link #computeFormationGrid}), falling back to
     * the old expanding-ring scan only if the grid can't find enough valid slots (heavily
     * obstructed terrain). Anchoring on the rat's own position rather than the target matters: a
     * macro chain's contested target is often one Y level up from any real ground (the next
     * unbuilt step), so it may not be region-member territory at all yet - the rat's own standing
     * position always is, since it's already there.
     */
    private Optional<BlockPos> findFormationSlot() {
        RegionFlowField field = this.flowField;
        if (field == null) return Optional.empty();
        Region region = regionFor(field);
        if (region == null) return Optional.empty();

        BlockPos searchOrigin = this.mob.blockPosition();
        int ratCount = field.getClaimedFormationSlotCount() + 1;
        List<BlockPos> grid = computeFormationGrid(ratCount, searchOrigin, region, field);
        if (!grid.isEmpty()) {
            return Optional.of(grid.get(0));
        }
        return findFormationSlotByRingScan(region, field, searchOrigin);
    }

    private Region regionFor(RegionFlowField field) {
        return field.getOwner() == null ? null
                : field.getOwner().getRegionGraph().getRegions().stream()
                        .filter(r -> r.getId() == field.getRegionId())
                        .findFirst().orElse(null);
    }

    /**
     * Real dynamic row/column formation grid, sized to fit both the available space and
     * {@code ratCount} - see the design doc's explicit callout that the old ring-scan "never
     * observed working" as real formation structure. Deterministic, predictable spacing
     * ({@code Config.formationSlotSpacing} between adjacent slots) instead of "wherever the search
     * happened to land first."
     *
     * <p>Roughly square by construction ({@code columns = ceil(sqrt(ratCount))},
     * {@code rows = ceil(ratCount / columns)}), scaling from a single 1x1 slot for one rat up to a
     * crowd. If the resulting grid doesn't yield {@code ratCount} valid candidates (some fail this
     * class's own validity checks - out of region, no solid ground, already claimed, or already a
     * flow-field target), grows whichever dimension is smaller by one and re-evaluates the WHOLE
     * grid from scratch, bounded to a fixed number of growth attempts so heavily obstructed terrain
     * can't loop forever. Returns valid slots in stable row-major order, up to {@code ratCount} of
     * them.
     */
    public List<BlockPos> computeFormationGrid(int ratCount, BlockPos anchor, Region region, RegionFlowField field) {
        if (ratCount <= 0) return List.of();

        int columns = (int) Math.ceil(Math.sqrt(ratCount));
        int rows = (int) Math.ceil((double) ratCount / columns);

        List<BlockPos> valid = List.of();
        for (int attempt = 0; attempt < 20; attempt++) {
            valid = gridCandidates(anchor, columns, rows, region, field);
            if (valid.size() >= ratCount) break;
            if (columns <= rows) columns++; else rows++;
        }

        return valid.size() > ratCount ? valid.subList(0, ratCount) : valid;
    }

    private List<BlockPos> gridCandidates(BlockPos anchor, int columns, int rows, Region region, RegionFlowField field) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                int dx = (int) Math.round((col - columns / 2) * Config.formationSlotSpacing);
                int dz = (int) Math.round((row - rows / 2) * Config.formationSlotSpacing);
                BlockPos candidate = anchor.offset(dx, 0, dz);
                if (isValidFormationSlot(region, field, candidate)) {
                    candidates.add(candidate);
                }
            }
        }
        return candidates;
    }

    /**
     * Same validity checks the old ring-scan used, shared by both mechanisms: real Region
     * membership (never blind geometry), real solid ground beneath (region membership alone isn't
     * enough - a provisionally-claimed-but-unbuilt connector cell reports {@code region.contains()}
     * true too, see RegionGraph.registerConnector), not already claimed, and not already a
     * flow-field instruction target.
     */
    private boolean isValidFormationSlot(Region region, RegionFlowField field, BlockPos candidate) {
        if (!region.contains(candidate)) return false;
        if (!this.mob.level().getBlockState(candidate.below()).blocksMotion()) return false;
        if (field.isFormationSlotClaimed(candidate)) return false;
        return !field.getInstructionMap().containsKey(candidate);
    }

    /**
     * Fallback only (see {@link #findFormationSlot}): searches outward from {@code searchOrigin}
     * in expanding square rings, ordered ring-by-ring and within a ring in a fixed scan order, so
     * independent rats naturally fill in an outward pattern without any of them needing to know
     * about "the formation" as a shared object. Kept for the case the grid can't find enough valid
     * slots in heavily obstructed terrain - the grid is the PRIMARY mechanism now, not this.
     */
    private Optional<BlockPos> findFormationSlotByRingScan(Region region, RegionFlowField field, BlockPos searchOrigin) {
        for (int r = FORMATION_MIN_RADIUS; r <= FORMATION_MAX_RADIUS; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos candidate = searchOrigin.offset(dx, 0, dz);
                    if (isValidFormationSlot(region, field, candidate)) {
                        return Optional.of(candidate);
                    }
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
            if (this.mob.blockPosition().closerThan(this.redirectTarget, MAX_TARGET_CLAIM_DISTANCE)) {
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
        // Release-and-reclaim, not hand-off. BuildFlowFieldGoal (the only remaining construction
        // goal, an AbstractSiegeProjectGoal) never consults the per-block claim table at all - it
        // registers as a worker on the owning SiegeProject instead. This goal's OWN redirectTarget
        // claim is now the claim table's only remaining consumer (confirmed via grep): it exists
        // purely so multiple AwaitFormationGoal instances, running on different mobs, don't pick
        // the same unclaimed alternative in the same tick - releasing here (rather than carrying
        // the claim across this goal's own start()/stop() cycle) lets a fresh call to
        // findUnclaimedAlternative() see it as available again once this mob moves on.
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
