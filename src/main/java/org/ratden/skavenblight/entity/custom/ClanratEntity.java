package org.ratden.skavenblight.entity.custom;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.ratden.skavenblight.ai.goal.*;
import org.ratden.skavenblight.ai.goal.clanrat.AbstractSiegeProjectGoal;
import org.ratden.skavenblight.ai.goal.clanrat.BuildFlowFieldGoal;
import org.ratden.skavenblight.ai.goal.clanrat.FollowFlowFieldGoal;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;
import org.ratden.skavenblight.ai.pathing.region.Region;
import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;
import org.ratden.skavenblight.ai.pathing.region.RegionIndex;
import org.ratden.skavenblight.ai.pathing.region.RegionRouteTree;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.player.Player;
import org.ratden.skavenblight.ai.goal.clanrat.AwaitFormationGoal;
import org.ratden.skavenblight.network.WarpFluxGridManager;
import org.ratden.skavenblight.network.WarpFluxNetwork;

import java.util.Optional;

public class ClanratEntity extends Monster implements GeoEntity {
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private RegionFlowField currentFlowField = null;
    private int currentRegionId = -1;
    // TerritoryRegionMap.getGeneration() as of the last successful field fetch - paired with
    // currentRegionId because region ids are renumbered on every rebuild (see below).
    private long lastKnownGeneration = -1;
    private int territoryCheckCooldown = 0;
    private BlockPos strandedHeading = null;
    // See recoverFromStuckAirborne()'s own doc for what these track.
    private int consecutiveAirborneTicks = 0;
    private Vec3 airbornePositionAnchor = null;

    protected static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.clanrat.idle");
    protected static final RawAnimation WALK = RawAnimation.begin().thenLoop("animation.clanrat.walk");
    protected static final RawAnimation FALL = RawAnimation.begin().thenLoop("animation.clanrat.fall");
    protected static final RawAnimation CLIMB = RawAnimation.begin().thenLoop("animation.clanrat.climb");

    public ClanratEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();

        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.2D, false));
        this.goalSelector.addGoal(6, new BuildFlowFieldGoal(this));
        // Sits below the construction goals (only runs once they've already declined - a rat
        // with real, unclaimed work of its own never reaches this) and above
        // FollowFlowFieldGoal (pre-empts plain "walk toward the crowd" specifically for the
        // case where the nearest work is claimed by someone else).
        this.goalSelector.addGoal(8, new AwaitFormationGoal(this));
        this.goalSelector.addGoal(9, new FollowFlowFieldGoal(this, 1.2D));
        this.goalSelector.addGoal(11, new WaterAvoidingRandomStrollGoal(this, 1.0D));
        this.goalSelector.addGoal(12, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(12, new RandomLookAroundGoal(this));
    }

    /**
     * Recovers from a narrow but real physics edge case found via GameTest diagnostics (Task 2,
     * clanrat-gap-crossing-pathing-fix-plan): landing a climb right at a stair block's own
     * collision boundary can leave the mob perpetually airborne, bouncing in a tight, never-
     * settling loop (onGround() staying false indefinitely) rather than landing cleanly. Both
     * FollowFlowFieldGoal's own repeated-hop detection and BuildFlowFieldGoal's construction
     * animation are victims of this, not the cause - the mob's own siege goals keep ping-ponging
     * control between "not grounded yet, can't build" and "resolves to a build action, hand off
     * from Follow", each briefly re-triggering the other's own state resets, so neither ever gets
     * a stable multi-tick window to recover on its own. This runs every tick (ahead of the
     * territoryCheckCooldown gate below, which most ticks skip) specifically because it must
     * survive goal-selector switches between the Follow/Build/Widen/Breach goals, none of which
     * individually see the whole airborne duration.
     */
    private void recoverFromStuckAirborne() {
        if (this.onGround()) {
            this.consecutiveAirborneTicks = 0;
            this.airbornePositionAnchor = null;
            return;
        }

        Vec3 pos = this.position();
        if (this.airbornePositionAnchor == null || this.airbornePositionAnchor.distanceToSqr(pos) > 4.0) {
            // Genuinely traveling (a real fall, a real leap in progress) - not the stuck case
            // this guards against. Re-anchor and restart the count from here.
            this.airbornePositionAnchor = pos;
            this.consecutiveAirborneTicks = 0;
            return;
        }

        if (++this.consecutiveAirborneTicks < 100) return;

        this.consecutiveAirborneTicks = 0;
        this.airbornePositionAnchor = null;
        this.setDeltaMovement(Vec3.ZERO);
        // Force a clean landing at the mob's own current X/Z: walk straight down from here to the
        // first solid ground, rather than guessing at any particular flow-field cell - whatever
        // goal is active next tick re-resolves its own target fresh from wherever this leaves it.
        BlockPos above = BlockPos.containing(pos.x, pos.y, pos.z);
        BlockPos ground = above;
        for (int i = 0; i < 8; i++) {
            BlockPos below = ground.below();
            if (this.level().getBlockState(below).blocksMotion()) break;
            ground = below;
        }
        this.setPos(pos.x, ground.getY(), pos.z);
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();

        recoverFromStuckAirborne();

        if (--this.territoryCheckCooldown > 0) return;
        this.territoryCheckCooldown = 40;

        if (!(this.level() instanceof ServerLevel serverLevel)) return;

        WarpFluxGridManager gridManager = WarpFluxGridManager.get(serverLevel);
        ChunkPos currentChunk = this.chunkPosition();

        WarpFluxNetwork closestNetwork = null;
        double closestDist = Double.MAX_VALUE;

        for (WarpFluxNetwork network : gridManager.getAllNetworks()) {
            // A network with no nexus never bootstraps a region map (see WarpFluxNetwork.tick),
            // so picking one here would just strand this rat with no field forever, even if a
            // real network exists further away - skip it and keep looking.
            if (!network.isValid(serverLevel)) continue;

            if (network.getTerritoryChunks().contains(currentChunk)) {
                closestNetwork = network;
                break;
            }
            for (BlockPos endpoint : network.getEndpoints()) {
                double dist = this.blockPosition().distSqr(endpoint);
                if (dist < closestDist) {
                    closestDist = dist;
                    closestNetwork = network;
                }
            }
        }

        if (closestNetwork == null) return;

        RegionIndex regionIndex = closestNetwork.getRegionMap().getRegionIndex();
        Region region = regionIndex.regionAt(this.blockPosition());

        if (region == null) {
            // True wilderness (e.g. debug-spawned before any region was scanned nearby, or
            // simply outside every mapped region). FollowFlowFieldGoal.canUse() requires a
            // non-null flowField just to start, so it can never engage from here - only
            // StrandedGoal can move the mob, the same way it does for an in-territory but
            // unreachable region below. Clear any stale flow-field assignment and hand
            // StrandedGoal a heading toward the nearest reachable region.
            if (this.currentRegionId != -1) {
                this.assignFlowField(null);
                this.currentRegionId = -1;
            }
            this.strandedHeading = closestNetwork.getRegionMap().getWildernessHeadingTarget(this.blockPosition());
            return;
        }

        // In-territory, but the region graph has no route from this region to the target
        // (not yet scanned, or genuinely sealed off) - distinct from true wilderness above.
        // StrandedGoal takes over: head toward the nearest region with a known route, and
        // attempt a local breach if stalled at the boundary.
        RegionRouteTree routeTree = closestNetwork.getRegionMap().getRouteTree();
        if (routeTree == null || !routeTree.isReachable(region.getId())) {
            this.assignFlowField(null);
            this.currentRegionId = -1;
            this.strandedHeading = closestNetwork.getRegionMap().getWildernessHeadingTarget(this.blockPosition());
            return;
        }

        this.strandedHeading = null;

        // Still in the same region AND that region id still means the same thing - no re-fetch
        // needed. The generation check is not optional: RegionScanner renumbers region ids from 0
        // on every rebuild (over an unordered chunk set), so "region 3" before a rebuild is not
        // "region 3" after one. Comparing the raw int alone let this short-circuit skip the
        // re-fetch straight through a rebuild, leaving the mob holding a RegionFlowField that
        // wraps a FlowFieldState the TerritoryRegionMap has since dropped and will never
        // recompute again.
        long mapGeneration = closestNetwork.getRegionMap().getGeneration();
        if (region.getId() == this.currentRegionId && mapGeneration == this.lastKnownGeneration) return;

        RegionFlowField field = closestNetwork.getRegionMap().getRegionFlowFieldFor(this.blockPosition());
        if (field != null) {
            this.currentRegionId = region.getId();
            this.lastKnownGeneration = mapGeneration;
        }
        this.assignFlowField(field);
    }

    public boolean isStranded() {
        return this.strandedHeading != null;
    }

    public BlockPos getStrandedHeading() {
        return this.strandedHeading;
    }

    public void assignFlowField(RegionFlowField field) {
        this.currentFlowField = field;
        this.goalSelector.getAvailableGoals().forEach(wrappedGoal -> {
            if (wrappedGoal.getGoal() instanceof SiegeGoal siegeGoal) {
                siegeGoal.setFlowField(field);
            }
        });
    }

    /**
     * Names of every currently-RUNNING goal on this rat (e.g. "BuildFlowFieldGoal"), or
     * "<idle>" if none. Used by PathingDebugFileWriter to answer "why is this rat just
     * standing there" - the field the goal system doesn't otherwise expose externally.
     */
    public String getActiveGoalNames() {
        String names = this.goalSelector.getAvailableGoals().stream()
                .filter(wrapped -> wrapped.isRunning())
                .map(wrapped -> wrapped.getGoal().getClass().getSimpleName())
                .reduce((a, b) -> a + "+" + b)
                .orElse(null);
        return names != null ? names : "<idle>";
    }

    /**
     * Diagnostic-only: for every registered siege-project goal on this rat (now just
     * BuildFlowFieldGoal - AbstractSiegeConstructionGoal and its one subclass, SmartBreachGoal,
     * are gone), reports whether it's currently RUNNING alongside a fresh {@code canUse()} call,
     * even for goals that aren't running right now. {@code canUse()} here is a pure read, so
     * calling it on top of the goal selector's own calls is side-effect-free.
     */
    public String describeSiegeGoalCanUseState() {
        String result = this.goalSelector.getAvailableGoals().stream()
                .filter(wrapped -> wrapped.getGoal() instanceof AbstractSiegeProjectGoal)
                .map(wrapped -> {
                    var goal = wrapped.getGoal();
                    boolean canUseNow;
                    try {
                        canUseNow = goal.canUse();
                    } catch (Exception e) {
                        canUseNow = false;
                    }
                    return goal.getClass().getSimpleName() + "[running=" + wrapped.isRunning()
                            + ", canUseNow=" + canUseNow + "]";
                })
                .reduce((a, b) -> a + " " + b)
                .orElse(null);
        return result != null ? result : "<no siege construction goals registered>";
    }

    /**
     * The nearest target BuildFlowFieldGoal on this rat would want to build, if that target
     * exists but its owning SiegeProject is already at capacity. Used by AwaitFormationGoal to
     * decide whether "someone else already has the spot I'd otherwise go queue at" - the
     * project-scoped equivalent of the old per-block claim check (WidenStairsGoal is gone; its
     * own crowd-relief role is now the auto-widening built into SiegeProject itself).
     * Deliberately excludes SmartBreachGoal - breach/MINE contention is out of scope for
     * formation-waiting (see docs/superpowers/plans/2026-07-30-formation-waiting-goal.md's
     * Global Constraints).
     */
    public Optional<BlockPos> peekAnyClaimedConstructionTarget() {
        for (WrappedGoal wrapped : this.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof AbstractSiegeProjectGoal siegeGoal) {
                Optional<BlockPos> claimed = siegeGoal.peekAtCapacityTarget();
                if (claimed.isPresent()) return claimed;
            }
        }
        return Optional.empty();
    }

    /** No goal extends AbstractSiegeConstructionGoal anymore (removed with the last concrete
     * implementation, SmartBreachGoal) - always the "none running" fallback. */
    public String describeActiveSiegeGoalState() {
        return "<no siege construction goal currently running>";
    }

    /**
     * Clanrats are never ambient/vanilla world-spawns - they only ever exist because the
     * siege/incursion system (or a debug command) deliberately spawned them as the attacking
     * force, and are already lifecycle-managed by that system's own manual cleanup commands
     * (see DebugCleanupCommands). Without this override they fall through to vanilla
     * Mob.checkDespawn(), which discards them once the player wanders far enough away -
     * exactly the "the attacking force vanishes because the player walked off" bug reported.
     */
    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.3D)
                .add(Attributes.ATTACK_DAMAGE, 3.0D);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "MovementController", 4, event -> {
            if (this.onClimbable()) {
                return event.setAndContinue(CLIMB);
            }
            if (this.getDeltaMovement().y < -0.15 && !this.onGround()) {
                return event.setAndContinue(FALL);
            }
            if (event.isMoving()) {
                return event.setAndContinue(WALK);
            }
            return event.setAndContinue(IDLE);
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}