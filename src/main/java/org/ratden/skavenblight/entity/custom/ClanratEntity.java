package org.ratden.skavenblight.entity.custom;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.ai.goal.*;
import org.ratden.skavenblight.ai.goal.clanrat.BuildFlowFieldGoal;
import org.ratden.skavenblight.ai.goal.clanrat.DeployClimbableGoal;
import org.ratden.skavenblight.ai.goal.clanrat.FollowFlowFieldGoal;
import org.ratden.skavenblight.ai.goal.clanrat.SmartBreachGoal;
import org.ratden.skavenblight.ai.goal.clanrat.SpiralSapperGoal;
import org.ratden.skavenblight.ai.goal.clanrat.WarpSapperGoal;
import org.ratden.skavenblight.ai.goal.clanrat.WidenStairsGoal;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;
import org.ratden.skavenblight.ai.pathing.region.Region;
import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;
import org.ratden.skavenblight.ai.pathing.region.RegionIndex;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import org.ratden.skavenblight.network.WarpFluxGridManager;
import org.ratden.skavenblight.network.WarpFluxNetwork;

public class ClanratEntity extends Monster implements GeoEntity {
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private RegionFlowField currentFlowField = null;
    private int currentRegionId = -1;
    private int territoryCheckCooldown = 0;

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
        // WarpSapperGoal must outrank SmartBreachGoal: it only fires for blocks too hard to
        // hand-mine in a reasonable time (see its canUse() threshold), and needs first refusal
        // so those blocks get demolished with TNT instead of a rat standing there mining for
        // a very long time.
        this.goalSelector.addGoal(2, new WarpSapperGoal(this));
        this.goalSelector.addGoal(3, new SmartBreachGoal(this));
        // SpiralSapperGoal/DeployClimbableGoal own BUILD_SPIRAL/BUILD_LADDER respectively and
        // must outrank BuildFlowFieldGoal, which only handles those actions as a generic
        // fallback if the specialized goal's own canUse() declines.
        this.goalSelector.addGoal(4, new SpiralSapperGoal(this));
        this.goalSelector.addGoal(5, new DeployClimbableGoal(this));
        this.goalSelector.addGoal(6, new BuildFlowFieldGoal(this));
        this.goalSelector.addGoal(7, new WidenStairsGoal(this));
        this.goalSelector.addGoal(8, new FollowFlowFieldGoal(this, 1.2D));
        this.goalSelector.addGoal(10, new WaterAvoidingRandomStrollGoal(this, 1.0D));
        this.goalSelector.addGoal(11, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(11, new RandomLookAroundGoal(this));
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();

        if (--this.territoryCheckCooldown > 0) return;
        this.territoryCheckCooldown = 40;

        if (!(this.level() instanceof ServerLevel serverLevel)) return;

        WarpFluxGridManager gridManager = WarpFluxGridManager.get(serverLevel);
        ChunkPos currentChunk = this.chunkPosition();

        WarpFluxNetwork closestNetwork = null;
        double closestDist = Double.MAX_VALUE;

        for (WarpFluxNetwork network : gridManager.getAllNetworks()) {
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
            // True wilderness or stranded - handled by FollowFlowFieldGoal's/StrandedGoal's own
            // null-flowField fallback paths (see Task 10). Clear any stale assignment.
            if (this.currentRegionId != -1) {
                this.assignFlowField(null);
                this.currentRegionId = -1;
            }
            return;
        }

        if (region.getId() == this.currentRegionId) return; // still in the same region, no re-fetch needed

        RegionFlowField field = closestNetwork.getRegionMap().getRegionFlowFieldFor(this.blockPosition());
        this.currentRegionId = region.getId();
        this.assignFlowField(field);
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