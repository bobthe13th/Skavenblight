package org.ratden.skavenblight.entity.custom;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.EnumSet;

public class AttackBlockGoal extends Goal {

    private final WolfRat ratwolf;
    private final Block targetBlock;
    private BlockPos targetPos;
    private int searchCooldown;

    public AttackBlockGoal(WolfRat ratwolf) {
        this.ratwolf = ratwolf;
        this.targetBlock = Blocks.DIAMOND_BLOCK;
        // Stagger the first search across the cooldown window so a wave of
        // ratwolves spawned on the same tick don't all scan on the same frame.
        // 0-39 ticks (0-~2s). Also desyncs the pack behaviorally.
        this.searchCooldown = ratwolf.getRandom().nextInt(40);
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (searchCooldown > 0) {
            searchCooldown--;
            return false;
        }
        targetPos = findTargetBlock();
        if (targetPos != null) return true;
        searchCooldown = 40;
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        if (targetPos == null) return false;
        return ratwolf.level().getBlockState(targetPos).is(targetBlock);
    }

    @Override
    public void start() {
        if (targetPos != null) {
            ratwolf.getNavigation().moveTo(
                    targetPos.getX() + 0.5,
                    targetPos.getY(),
                    targetPos.getZ() + 0.5,
                    1.4D
            );
        }
    }

    @Override
    public void tick() {
        if (targetPos == null) return;

        ratwolf.getLookControl().setLookAt(
                targetPos.getX() + 0.5,
                targetPos.getY() + 0.5,
                targetPos.getZ() + 0.5
        );

        double distanceSq = ratwolf.distanceToSqr(
                targetPos.getX() + 0.5,
                targetPos.getY(),
                targetPos.getZ() + 0.5
        );

        if (distanceSq < 4.0D) {
            ratwolf.level().destroyBlock(targetPos, true);
            targetPos = null;
        }
    }

    @Override
    public void stop() {
        targetPos = null;
        ratwolf.getNavigation().stop();
    }

    // TODO(#4): Replace the brute-force box scan with a stored Nexus position.
    // With a single Nexus, store its BlockPos when placed (block-place event)
    // and read that one position here instead of scanning ~250k blocks.
    // Turns the worst case (no target found) from a full sweep into a single lookup.
    private BlockPos findTargetBlock() {
        BlockPos center = ratwolf.blockPosition();
        int range = 50;

        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-range, -10, -range),
                center.offset(range, 10, range)
        )) {
            if (ratwolf.level().getBlockState(pos).is(targetBlock)) {
                return pos.immutable();
            }
        }
        return null;
    }
}