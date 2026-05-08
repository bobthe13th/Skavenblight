package org.ratden.skavenblight;

import net.minecraft.resources.ResourceLocation;
import java.util.HashMap;
import java.util.Map;

public class WealthRegistry {

    // A map storing the Item's ID and its numerical wealth value
    private static final Map<ResourceLocation, Integer> WEALTH_MAP = new HashMap<>();

    // 1. A method to register base values manually using the 1.21.1 method
    public static void registerBaseValues() {
        WEALTH_MAP.put(ResourceLocation.parse("minecraft:dirt"), 1);
        WEALTH_MAP.put(ResourceLocation.parse("minecraft:iron_ingot"), 100);
        WEALTH_MAP.put(ResourceLocation.parse("minecraft:diamond"), 1000);
    }

    // 2. A method to get the value of an item
    public static int getItemValue(ResourceLocation itemID) {
        // Returns the value, or 0 if the item isn't in the map
        return WEALTH_MAP.getOrDefault(itemID, 0);
    }

    // 3. A method to clear the map (useful for when you reload data)
    public static void clear() {
        WEALTH_MAP.clear();
    }
}