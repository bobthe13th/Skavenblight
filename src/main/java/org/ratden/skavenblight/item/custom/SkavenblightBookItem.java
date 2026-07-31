package org.ratden.skavenblight.item.custom;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import com.klikli_dev.modonomicon.client.gui.book.BookAddress;

// Import the internal GUI manager instead of the API
import com.klikli_dev.modonomicon.client.gui.BookGuiManager;

public class SkavenblightBookItem extends Item {

    // The ID of your Modonomicon book
    private static final ResourceLocation BOOK_ID = ResourceLocation.fromNamespaceAndPath("skavenblight", "nexus_research");

    public SkavenblightBookItem(Properties properties) {
        super(properties.stacksTo(1)); // Books shouldn't stack
    }

    @Override
    public @NotNull InteractionResultHolder<ItemStack> use(@NotNull Level level, @NotNull Player player, @NotNull InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (level.isClientSide()) {
            // Call the isolated client method
            ClientAccess.openBook();
        }

        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    private static class ClientAccess {
        static void openBook() {
            // Wrap the BOOK_ID in a default BookAddress
            BookGuiManager.get().openBook(BookAddress.defaultFor(BOOK_ID));
        }
    }
}