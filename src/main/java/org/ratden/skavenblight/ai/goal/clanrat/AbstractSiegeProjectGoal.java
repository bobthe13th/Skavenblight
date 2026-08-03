package org.ratden.skavenblight.ai.goal.clanrat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import org.ratden.skavenblight.Config;
import org.ratden.skavenblight.ai.goal.SiegeGoal;
import org.ratden.skavenblight.ai.pathing.LiveTerrainAccess;
import org.ratden.skavenblight.ai.pathing.SiegeNode;
import org.ratden.skavenblight.ai.pathing.SiegeProject;
import org.ratden.skavenblight.ai.pathing.TerrainEvaluator;
import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;

import java.util.EnumSet;
import java.util.Optional;

/**
 * Shared skeleton for project-scoped construction goals: a rat registers as a worker on the
 * SiegeProject nearest unbuilt work belongs to, keeps station (approach + animate) near it while
 * registered, and the project itself - not this goal - places blocks as accumulated work covers
 * their cost (see SiegeProject.tick()). Sibling to AbstractSiegeConstructionGoal, not a subclass:
 * the claim/fixed-duration-execute core those goals share is exactly what this class replaces, so
 * inheriting from it would mean overriding away most of what it provides. Shares only the
 * lookahead lookup (SiegeNodeLookahead) with that class. WidenStairsGoal, SmartBreachGoal,
 * SpiralSapperGoal, and DeployClimbableGoal are unaffected by this class - see the design doc's
 * Scope section for why the latter two stay on the old per-block claim.
 */
public abstract class AbstractSiegeProjectGoal extends Goal implements SiegeGoal {

    protected final PathfinderMob mob;
    protected RegionFlowField flowField;
    private final TerrainEvaluator terrainEvaluator = new TerrainEvaluator();

    private SiegeProject registeredProject;

    /** Same tolerance AbstractSiegeConstructionGoal uses for post-claim crowd-shove - this class
     * doesn't claim a single block, but a rat drifting this far from the project's own next
     * unbuilt step while registered is exactly the same "can't close the gap under its own MOVE
     * flag being zeroed every tick" situation that constant is sized for. */
    public static final double MAX_PROJECT_DRIFT_DISTANCE = 2.5D;

    protected AbstractSiegeProjectGoal(PathfinderMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public void setFlowField(RegionFlowField flowField) {
        this.flowField = flowField;
    }

    /** Which SiegeNode.SiegeAction values this goal handles - e.g. BuildFlowFieldGoal's
     * BUILD_STAIR/BUILD_BRIDGE/BUILD_PILLAR/BUILD_LANDING/BUILD_LADDER/BUILD_SPIRAL. */
    protected abstract boolean matchesAction(SiegeNode.SiegeAction action);

    private Optional<SiegeNode> findEffectiveNode() {
        return SiegeNodeLookahead.findEffectiveNode(this.flowField, this.mob, this::matchesAction);
    }

    @Override
    public boolean canUse() {
        if (this.flowField == null || !this.mob.isAlive()) return false;
        // Mid-leap guard - see AbstractSiegeConstructionGoal.canUse()'s own identical check for
        // the full history of why this specific condition (not bare onGround()) is correct.
        if (!this.mob.onGround() && this.mob.getDeltaMovement().y > 1.0E-2) return false;

        return findEffectiveNode()
                .filter(node -> matchesAction(node.action()))
                .flatMap(node -> this.flowField.findProjectFor(node.pos()))
                .isPresent();
    }

    @Override
    public void start() {
        findEffectiveNode()
                .filter(node -> matchesAction(node.action()))
                .flatMap(node -> this.flowField.findProjectFor(node.pos()))
                .ifPresent(project -> {
                    if (this.mob.level() instanceof ServerLevel serverLevel
                            && project.tryRegisterWorker(this.mob, new LiveTerrainAccess(serverLevel), this.terrainEvaluator,
                            Config.projectWorkRadius, Config.maxProjectWorkers, Config.workersPerWidenStep)) {
                        this.registeredProject = project;
                    }
                });
    }

    @Override
    public boolean canContinueToUse() {
        if (this.registeredProject == null || !this.mob.isAlive() || !(this.mob.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        return findEffectiveNode()
                .filter(node -> matchesAction(node.action()))
                .map(node -> this.mob.blockPosition().closerThan(node.pos(), MAX_PROJECT_DRIFT_DISTANCE))
                .orElse(false);
    }

    @Override
    public void tick() {
        if (this.registeredProject == null || !(this.mob.level() instanceof ServerLevel serverLevel)) return;

        this.mob.setDeltaMovement(0, this.mob.getDeltaMovement().y, 0);
        findEffectiveNode().ifPresent(node -> this.mob.getLookControl().setLookAt(
                node.pos().getX() + 0.5D, node.pos().getY() + 0.5D, node.pos().getZ() + 0.5D));

        if (this.mob.tickCount % 5 == 0) this.mob.swing(InteractionHand.MAIN_HAND);

        this.registeredProject.tick(serverLevel, this.flowField, this.terrainEvaluator);
    }

    @Override
    public void stop() {
        if (this.registeredProject != null) {
            this.registeredProject.unregisterWorker(this.mob);
        }
        this.registeredProject = null;
    }

    /** For AwaitFormationGoal (Task 9): is there build work nearby whose owning project is
     * currently full? Mirrors AbstractSiegeConstructionGoal.peekClaimedTarget()'s role for the
     * old per-block claim. */
    public Optional<BlockPos> peekAtCapacityTarget() {
        if (this.flowField == null) return Optional.empty();
        return findEffectiveNode()
                .filter(node -> matchesAction(node.action()))
                .filter(node -> this.flowField.findProjectFor(node.pos()).map(SiegeProject::isAtCapacity).orElse(false))
                .map(SiegeNode::pos);
    }
}
