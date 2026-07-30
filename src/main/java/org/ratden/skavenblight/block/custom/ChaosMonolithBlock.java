package org.ratden.skavenblight.block.custom;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import org.ratden.skavenblight.Config;
import org.ratden.skavenblight.block.entity.ChaosMonolithBlockEntity;

/**
 * Placeholder Chaos Monolith (WFRP Realms of Sorcery p.132-133): a tough, wound-tracked structure
 * that risk-gates a "read the runes" action and buffs-and-endangers casting nearby (see
 * SpellCasting for the casting-zone half of this, wired in Task 7). Only the generic Chaos-flavor
 * variant is implemented — the per-god variants (Khorne suppresses magic entirely, etc.) are
 * explicit future flavor work, not built here.
 */
public class ChaosMonolithBlock extends BaseEntityBlock {

    public static final com.mojang.serialization.MapCodec<ChaosMonolithBlock> CODEC = simpleCodec(ChaosMonolithBlock::new);

    public ChaosMonolithBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected com.mojang.serialization.MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ChaosMonolithBlockEntity(pos, state);
    }

    @Override
    protected void attack(BlockState state, Level level, BlockPos pos, Player player) {
        super.attack(state, level, pos, player);
        if (level.isClientSide) {
            return;
        }
        if (level.getBlockEntity(pos) instanceof ChaosMonolithBlockEntity monolith) {
            boolean destroyed = monolith.applyDamage(player);
            if (!destroyed) {
                player.displayClientMessage(Component.literal(
                        "The Monolith shudders. (" + monolith.getWounds() + " Wounds remaining)"), true);
            }
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.PASS;
        }
        if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        if (!(level.getBlockEntity(pos) instanceof ChaosMonolithBlockEntity monolith)) {
            return InteractionResult.PASS;
        }

        long gameTime = serverLevel.getGameTime();
        if (monolith.isReadOnCooldown(gameTime)) {
            player.displayClientMessage(Component.literal(
                    "The runes are quiet for now — their power is spent."), true);
            return InteractionResult.CONSUME;
        }

        int roll = serverLevel.getRandom().nextInt(100);
        if (roll < Config.monolithReadCorruptionChancePercent) {
            int gained = 1 + serverLevel.getRandom().nextInt(5);
            org.ratden.skavenblight.magic.corruption.Corruption.grant(serverPlayer, gained);
            player.displayClientMessage(Component.literal(
                    "The blasphemous runes claw at your mind. You gain " + gained + " Corruption."), true);
        } else {
            player.displayClientMessage(Component.literal(
                    "You resist the whispers of the runes... for now."), true);
        }
        monolith.markRead(gameTime);
        return InteractionResult.CONSUME;
    }
}
