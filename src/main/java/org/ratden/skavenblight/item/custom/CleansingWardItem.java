package org.ratden.skavenblight.item.custom;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.Config;
import org.ratden.skavenblight.magic.Wind;
import org.ratden.skavenblight.magic.corruption.Corruption;
import org.ratden.skavenblight.magic.player.ModAttachments;
import org.ratden.skavenblight.magic.player.PlayerMagicData;
import org.ratden.skavenblight.magic.wind.ChunkWindState;
import org.ratden.skavenblight.magic.wind.WindGridManager;

/**
 * The deliberately rare, high-friction way to reduce Corruption: requires standing in strong
 * ambient Hysh (Light opposes Dhar thematically), a long per-player cooldown, and consumes the
 * item. This is the one exception to Corruption being a one-way ratchet.
 */
public class CleansingWardItem extends Item {

    public CleansingWardItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.pass(stack);
        }

        PlayerMagicData data = serverPlayer.getData(ModAttachments.PLAYER_MAGIC.get());
        long now = serverLevel.getGameTime();
        if (data.lastCleanseGameTime() != 0 && now - data.lastCleanseGameTime() < Config.cleanseCooldownTicks) {
            serverPlayer.displayClientMessage(Component.literal("The Ward is spent. You must wait before it can cleanse you again."), true);
            return InteractionResultHolder.fail(stack);
        }

        ChunkWindState windState = WindGridManager.get(serverLevel).getOrCreate(new ChunkPos(serverPlayer.blockPosition()));
        if (windState.getCurrent(Wind.HYSH) < Config.cleanseHyshRequirement) {
            serverPlayer.displayClientMessage(Component.literal("The Light here is too faint to cleanse you."), true);
            return InteractionResultHolder.fail(stack);
        }

        if (Corruption.getPoints(serverPlayer) <= 0) {
            serverPlayer.displayClientMessage(Component.literal("You bear no corruption to cleanse."), true);
            return InteractionResultHolder.fail(stack);
        }

        Corruption.reduce(serverPlayer, Config.cleanseAmount);
        serverPlayer.setData(ModAttachments.PLAYER_MAGIC.get(),
                serverPlayer.getData(ModAttachments.PLAYER_MAGIC.get()).withLastCleanseGameTime(now));
        serverPlayer.displayClientMessage(Component.literal("The Light burns the taint from you."), true);
        stack.shrink(1);
        return InteractionResultHolder.consume(stack);
    }
}
