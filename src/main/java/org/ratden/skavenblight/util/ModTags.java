package org.ratden.skavenblight.util;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import org.ratden.skavenblight.Skavenblight;

public class ModTags {
    public static class Blocks{

        private static TagKey<Block> createTag(String name){
            return BlockTags.create(ResourceLocation.fromNamespaceAndPath(Skavenblight.MODID, name));
        }
    }

    public static class Items{
        public static TagKey<Item> WARPABLE_ITEMS = createTag("warpable_items");

        private static TagKey<Item> createTag(String name){
            return ItemTags.create(ResourceLocation.fromNamespaceAndPath(Skavenblight.MODID, name));
        }
    }
}
