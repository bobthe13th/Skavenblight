package org.ratden.skavenblight.ai.goal.clanrat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import org.ratden.skavenblight.Config;
import org.ratden.skavenblight.ai.goal.SiegeGoal;
import org.ratden.skavenblight.ai.pathing.LiveTerrainAccess;
import org.ratden.skavenblight.ai.pathing.FlowStep;
import org.ratden.skavenblight.ai.pathing.SiegeProject;
import org.ratden.skavenblight.ai.pathing.PathAction;
import org.ratden.skavenblight.ai.pathing.PathStepEvaluator;
import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;

import java.util.EnumSet;
import java.util.Optional;

/**
 * Shared skeleton for project-scoped construction goals: a rat registers as a worker on the
 * SiegeProject nearest unbuilt work belongs to, keeps station (approach + animate) near it while
 * registered, and the project itself - not this goal - places blocks as accumulated work covers
 * their cost (see SiegeProject.tick()). BuildFlowFieldGoal is its only remaining concrete subclass
 * (Task 16 deleted every goal that used to split off a subset of construction actions), matching
 * all four construction PathAction values now that climbing and the old per-block claim goals are
 * gone.
 */
public abstract class AbstractSiegeProjectGoal extends Goal implements SiegeGoal {

    protected final PathfinderMob mob;
    protected RegionFlowField flowField;
    private final PathStepEvaluator pathStepEvaluator = new PathStepEvaluator();

    private SiegeProject registeredProject;

    protected AbstractSiegeProjectGoal(PathfinderMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public void setFlowField(RegionFlowField flowField) {
        this.flowField = flowField;
    }

    /** Which PathAction values this goal handles - BuildFlowFieldGoal's is all four construction
     * actions (TUNNEL/BRIDGE/CARVED_STAIR/AIR_STAIR). */
    protected abstract boolean matchesAction(PathAction action);

    private Optional<FlowStep> findEffectiveNode() {
        return SiegeNodeLookahead.findEffectiveNode(this.flowField, this.mob, this::matchesAction);
    }

    /**
     * The project this goal would register on right now, if any - shared by canUse() and start() so
     * they can never disagree about WHICH project the decision is about.
     */
    private Optional<SiegeProject> findCandidateProject() {
        if (this.flowField == null) return Optional.empty();
        return findEffectiveNode()
                .filter(node -> matchesAction(node.action()))
                .flatMap(node -> this.flowField.findProjectFor(node.pos()));
    }

    /**
     * Deliberately mirrors {@code start()}'s real registration preconditions via
     * {@link SiegeProject#canAcceptWorker} rather than just asking "does a project exist here?".
     * Checking only for existence let this goal take the mob's {MOVE, LOOK} flags at priority 6
     * while {@code start()}'s registration silently failed (out of {@code projectWorkRadius} of the
     * project's next unbuilt step, or the project full and unable to widen), leaving {@code tick()}
     * a no-op with {@code registeredProject == null} - and permanently starving
     * {@code AwaitFormationGoal} (priority 8) and {@code FollowFlowFieldGoal} (priority 9), which
     * can never get the MOVE flag back. See SiegeProject#canAcceptWorker for the full writeup.
     */
    @Override
    public boolean canUse() {
        if (this.flowField == null || !this.mob.isAlive()) return false;
        // Mid-leap guard - see AbstractSiegeConstructionGoal.canUse()'s own identical check for
        // the full history of why this specific condition (not bare onGround()) is correct.
        if (!this.mob.onGround() && this.mob.getDeltaMovement().y > 1.0E-2) return false;
        if (!(this.mob.level() instanceof ServerLevel serverLevel)) return false;

        return findCandidateProject().filter(project -> canWorkOn(project, serverLevel)).isPresent();
    }

    private boolean canWorkOn(SiegeProject project, ServerLevel serverLevel) {
        return project.canAcceptWorker(this.mob, new LiveTerrainAccess(serverLevel), this.pathStepEvaluator,
                Config.projectWorkRadius, Config.maxProjectWorkers, Config.workersPerWidenStep);
    }

    @Override
    public void start() {
        findCandidateProject().ifPresent(project -> {
            if (this.mob.level() instanceof ServerLevel serverLevel
                    && project.tryRegisterWorker(this.mob, new LiveTerrainAccess(serverLevel), this.pathStepEvaluator,
                    Config.projectWorkRadius, Config.maxProjectWorkers, Config.workersPerWidenStep)) {
                this.registeredProject = project;
            }
        });
    }

    /**
     * The exact same predicate canUse() uses, against the project actually registered - NOT a
     * distance check against whatever node the flow field happens to hand back. Two reasons it has
     * to be the same predicate:
     *
     * <p>(1) It has to measure the right thing. The registered project's own next unbuilt step is
     * what this mob is contributing work toward; {@code findEffectiveNode()}'s node is only ever
     * the next hop from the mob's own cell, which can be a completely different position (and can
     * stop matching this goal's actions entirely) while the project itself is still perfectly
     * workable.
     *
     * <p>(2) Any predicate STRICTER than canUse()'s would flip-flop forever. Registration is gated
     * at {@code Config.projectWorkRadius} (3.5), so a continue-check with a tighter tolerance (the
     * old {@code MAX_PROJECT_DRIFT_DISTANCE} = 2.5) would stop a rat that registered at 3.0 on the
     * very next tick - and since canUse() would still be true, vanilla's GoalSelector immediately
     * restarts it in the same tick, re-taking the MOVE flag at priority 6 and re-starving the same
     * lower-priority goals Fix 3 exists to unblock. {@code canAcceptWorker} checks radius BEFORE
     * its already-registered short-circuit precisely so it can serve both roles with one threshold.
     */
    @Override
    public boolean canContinueToUse() {
        if (this.registeredProject == null || !this.mob.isAlive() || !(this.mob.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        return canWorkOn(this.registeredProject, serverLevel);
    }

    @Override
    public void tick() {
        if (this.registeredProject == null || !(this.mob.level() instanceof ServerLevel serverLevel)) return;

        this.mob.setDeltaMovement(0, this.mob.getDeltaMovement().y, 0);
        findEffectiveNode().ifPresent(node -> this.mob.getLookControl().setLookAt(
                node.pos().getX() + 0.5D, node.pos().getY() + 0.5D, node.pos().getZ() + 0.5D));

        if (this.mob.tickCount % 5 == 0) this.mob.swing(InteractionHand.MAIN_HAND);

        this.registeredProject.tick(serverLevel, this.flowField, this.pathStepEvaluator);
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
                .map(FlowStep::pos);
    }
}
