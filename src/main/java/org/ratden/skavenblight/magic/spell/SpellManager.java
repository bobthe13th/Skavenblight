package org.ratden.skavenblight.magic.spell;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

/** Loads data/<namespace>/magic/spells/**.json, exactly like vanilla loads recipes. */
public class SpellManager extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static Map<ResourceLocation, Spell> spells = Map.of();

    public SpellManager() {
        super(new Gson(), "magic/spells");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resourceList, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, Spell> result = new HashMap<>();
        resourceList.forEach((id, json) -> Spell.CODEC.parse(JsonOps.INSTANCE, json)
                .resultOrPartial(error -> LOGGER.error("Failed to parse spell {}: {}", id, error))
                .ifPresent(spell -> result.put(id, spell)));
        spells = Map.copyOf(result);
        LOGGER.info("Loaded {} spells", spells.size());
    }

    public static Spell get(ResourceLocation id) {
        return spells.get(id);
    }

    public static Map<ResourceLocation, Spell> getAll() {
        return spells;
    }
}
