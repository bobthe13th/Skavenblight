package org.ratden.skavenblight.magic.wind;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.magic.Wind;

import java.util.EnumMap;
import java.util.Map;

/**
 * Tier 0 player control (design doc §3.4): a chunk's Wind baseline rises with how many blocks
 * tagged #skavenblight:wind_source/<wind> are placed in it. One tag per Wind, so decorating a
 * tower with the right thematic blocks is itself the mechanic - no new item/block types required.
 */
public class ModWindBlockTags {

    private static final Map<Wind, TagKey<Block>> WIND_SOURCE = new EnumMap<>(Wind.class);

    static {
        for (Wind wind : Wind.values()) {
            WIND_SOURCE.put(wind, TagKey.create(Registries.BLOCK,
                    ResourceLocation.fromNamespaceAndPath(Skavenblight.MODID, "wind_source/" + wind.getSerializedName())));
        }
    }

    public static TagKey<Block> windSource(Wind wind) {
        return WIND_SOURCE.get(wind);
    }
}
