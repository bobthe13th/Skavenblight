package org.ratden.skavenblight.datagen;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.ItemTagsProvider;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import org.jetbrains.annotations.Nullable;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.block.ModBlocks;
import org.ratden.skavenblight.item.ModItems;
import org.ratden.skavenblight.util.ModTags;

import java.util.concurrent.CompletableFuture;

public class ModItemTagProvider extends ItemTagsProvider {
    public ModItemTagProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider,
                              CompletableFuture<TagLookup<Block>> blockTags, @Nullable ExistingFileHelper existingFileHelper) {
        super(output, lookupProvider, blockTags, Skavenblight.MODID, existingFileHelper);
    }


    @Override
    protected void addTags(HolderLookup.Provider provider) {
        tag(ModTags.Items.WARPABLE_ITEMS)
                .add(Items.DIAMOND_SWORD);

        this.tag(ItemTags.TRIMMABLE_ARMOR)
                .add(ModItems.WARPSTONE_HELMET.get())
                .add(ModItems.WARPSTONE_CHESTPLATE.get())
                .add(ModItems.WARPSTONE_LEGGINGS.get())
                .add(ModItems.WARPSTONE_BOOTS.get());

    }
}
