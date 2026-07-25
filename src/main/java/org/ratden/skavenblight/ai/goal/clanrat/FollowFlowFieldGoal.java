package org.ratden.skavenblight.ai.goal.clanrat;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import org.ratden.skavenblight.ai.goal.SiegeGoal;
import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;
import org.ratden.skavenblight.ai.pathing.SiegeNode;
import org.slf4j.Logger;

import java.util.EnumSet;

public class FollowFlowFieldGoal extends Goal implements SiegeGoal {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final PathfinderMob mob;
    private final double speedModifier;
    private RegionFlowField flowField;

    private int pathingUpdateTimer = 0;
    private Vec3 lastPosition = null;
    private int escapeHatchTicks = 0;

    public FollowFlowFieldGoal(PathfinderMob mob, double speedModifier) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    public void setFlowField(RegionFlowField flowField) {
        this.flowField = flowField;
    }

    @Override
    public boolean canUse() {
        if (this.flowField == null || this.mob.getTarget() != null) return false;

        // --- CHANGED: Always return true if we have a flow field.
        // We will decide whether to use Flow Field or Vanilla pathing in the tick() method.
        return true;
    }

    @Override
    public void start() {
        this.lastPosition = this.mob.position();
        this.escapeHatchTicks = 0;
        this.pathingUpdateTimer = 0;
    }

    @Override
    public void tick() {
        if (this.escapeHatchTicks > 0) {
            this.escapeHatchTicks--;
            if (this.mob.getNavigation().isDone()) {
                this.escapeHatchTicks = 0;
            }
            return;
        }

        if (--this.pathingUpdateTimer <= 0) {
            this.pathingUpdateTimer = 10;
            Vec3 currentPosition = this.mob.position();

            if (this.lastPosition != null && this.mob.getNavigation().isInProgress()) {
                double distanceMovedSqr = currentPosition.distanceToSqr(this.lastPosition);
                if (distanceMovedSqr < 0.04) {
                    BlockPos escapePos = findEscapePos(this.mob.blockPosition());
                    // Naturally rate-limited to at most once per ~70 ticks per mob (10-tick
                    // poll + 60-tick escape-hatch cooldown below), so this won't spam even
                    // under an 80-mob load test - INFO rather than DEBUG so it actually lands in
                    // latest.log instead of being buried in debug.log's much higher-volume
                    // output (see SiegeProjectManager's "Line aborted" line - 98% of a whole
                    // session's debug.log in testing). Previously silent entirely - "rats seem
                    // stuck" during testing had no log trail showing where or how often this
                    // actually fires.
                    if (escapePos != null) {
                        LOGGER.info("[Skavenblight] {} stuck at {} (hasn't moved in ~{} ticks) - escape-hatch heading to {}",
                                this.mob.getClass().getSimpleName(), this.mob.blockPosition().toShortString(), this.pathingUpdateTimer, escapePos.toShortString());

                        this.mob.getNavigation().moveTo(
                                escapePos.getX() + 0.5D,
                                escapePos.getY(),
                                escapePos.getZ() + 0.5D,
                                this.speedModifier
                        );
                        this.escapeHatchTicks = 60;
                        this.lastPosition = currentPosition;
                        return;
                    } else {
                        LOGGER.info("[Skavenblight] {} stuck at {} (hasn't moved in ~{} ticks) - no escape-hatch target found (no flow-field chain ahead)",
                                this.mob.getClass().getSimpleName(), this.mob.blockPosition().toShortString(), this.pathingUpdateTimer);
                    }
                }
            }
            this.lastPosition = currentPosition;

            BlockPos currentPos = this.mob.blockPosition();
            SiegeNode targetNode = this.flowField.getNextSiegeNode((ServerLevel) this.mob.level(), currentPos);

            // --- Wilderness fallback ---
            // No instruction at our feet. Rather than beeline straight at the raw nexus
            // coordinate (which can be across the exact terrain - a pit, a cliff - that
            // necessitated macro projects in the first place), head toward the nearest
            // block the flow field HAS mapped, if any; only fall back to the raw nexus
            // position when nothing is mapped at all.
            //
            // Hand vanilla navigation the actual far target, not a single manually-computed
            // step toward it - moveTo-ing one adjacent block at a time (as getDynamicWildernessNode
            // does for the debug visualizer's per-tile arrows) gives vanilla's pathfinder
            // nothing to route around, so a rat stalls at the very first obstacle needing a
            // multi-block detour instead of actually pathing in.
            if (targetNode == null) {
                BlockPos heading = this.flowField.getWildernessHeadingTarget(currentPos);
                this.mob.getNavigation().moveTo(
                        heading.getX() + 0.5D,
                        heading.getY(),
                        heading.getZ() + 0.5D,
                        this.speedModifier
                );
                return;
            }

            // If we DO have a node, but it isn't WALK, stop moving so the Builder/Miner goals can take over.
            if (targetNode.action() != SiegeNode.SiegeAction.WALK) {
                this.mob.getNavigation().stop();
                return;
            }

            // --- strict flow field execution below ---
            BlockPos nextInChain = targetNode.pos();
            int maxLookAhead = 3;

            for (int i = 0; i < maxLookAhead; i++) {
                SiegeNode next = this.flowField.getNextSiegeNode((ServerLevel) this.mob.level(), nextInChain);
                if (next == null || next.pos().equals(nextInChain) || next.action() != SiegeNode.SiegeAction.WALK) {
                    break;
                }
                nextInChain = next.pos();
            }

            this.mob.getNavigation().moveTo(
                    nextInChain.getX() + 0.5D,
                    nextInChain.getY(),
                    nextInChain.getZ() + 0.5D,
                    this.speedModifier
            );
        }
    }

    private BlockPos findEscapePos(BlockPos startPos) {
        BlockPos current = startPos;
        for (int i = 0; i < 6; i++) {
            SiegeNode next = this.flowField.getNextSiegeNode((ServerLevel) this.mob.level(), current);
            if (next == null || next.pos().equals(current)) break;
            current = next.pos();
        }
        return current.equals(startPos) ? null : current;
    }
}