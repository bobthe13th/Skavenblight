package org.ratden.skavenblight.item.custom;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.ratden.skavenblight.magic.player.ModAttachments;
import org.ratden.skavenblight.magic.player.PlayerMagicData;

/**
 * Right-click to unlock Dark Magic (Dhar) spells once, permanently. Simply carrying the Tome
 * afterward keeps draining a small trickle of Corruption — see CorruptionTickHandler — this is
 * the "unlock gate + ongoing risk" design chosen for this item.
 */
public class TomeOfCorruptionItem extends Item {

    public TomeOfCorruptionItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, net.minecraft.world.InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }

        PlayerMagicData data = player.getData(ModAttachments.PLAYER_MAGIC.get());
        if (data.darkMagicUnlocked()) {
            player.displayClientMessage(Component.literal("You have already given yourself to Dark Magic."), true);
            return InteractionResultHolder.pass(stack);
        }

        player.setData(ModAttachments.PLAYER_MAGIC.get(), data.withDarkMagicUnlocked(true));
        player.displayClientMessage(Component.literal("The Tome's secrets are yours. Dhar spells are now within your reach."), true);
        return InteractionResultHolder.consume(stack);
    }
}
