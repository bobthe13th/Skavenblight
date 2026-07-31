package org.ratden.skavenblight.util;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import org.ratden.skavenblight.Skavenblight;

/**
 * Declares the custom data tags used by Skavenblight.
 *
 * Tag keys provide stable identifiers used by code and data generation.
 * Their actual contents are supplied through generated or handwritten
 * data files.
 */
public final class ModTags {

    public static final class Items {

        public static final TagKey<Item> WARPABLE_ITEMS =
                createTag(
                        "warpable_items"
                );

        private static TagKey<Item> createTag(
                String name
        ) {
            return ItemTags.create(
                    ResourceLocation.fromNamespaceAndPath(
                            Skavenblight.MODID,
                            name
                    )
            );
        }

        private Items() {
        }
    }

    private ModTags() {
    }
}