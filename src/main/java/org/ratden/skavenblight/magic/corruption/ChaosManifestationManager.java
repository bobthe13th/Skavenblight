package org.ratden.skavenblight.magic.corruption;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Loads data/<namespace>/magic/chaos_manifestations/**.json, exactly like SpellManager loads
 * spells. Multiple files may target the same severity — their entries are pooled, not the last
 * one winning, so datapacks can add manifestations without owning the whole table.
 */
public class ChaosManifestationManager extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static Map<CorruptionTier, List<ChaosManifestationTable.WeightedEntry>> pooled = new EnumMap<>(CorruptionTier.class);

    public ChaosManifestationManager() {
        super(new Gson(), "magic/chaos_manifestations");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resourceList, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<CorruptionTier, List<ChaosManifestationTable.WeightedEntry>> result = new EnumMap<>(CorruptionTier.class);
        resourceList.forEach((id, json) -> {
            try {
                ChaosManifestationTable.CODEC.parse(JsonOps.INSTANCE, json)
                        .resultOrPartial(error -> LOGGER.error("Failed to parse chaos manifestation table {}: {}", id, error))
                        .ifPresent(table -> result.computeIfAbsent(table.severity(), k -> new ArrayList<>())
                                .addAll(table.entries()));
            } catch (RuntimeException e) {
                LOGGER.error("Failed to parse chaos manifestation table {}", id, e);
            }
        });
        pooled = result;
        LOGGER.info("Loaded chaos manifestation tables for {} severities", pooled.size());
    }

    /** Applies one random consequence from the given severity's pool. No-op if that severity has no entries. */
    public static void resolve(ServerLevel level, LivingEntity target, CorruptionTier severity) {
        List<ChaosManifestationTable.WeightedEntry> entries = pooled.get(severity);
        if (entries == null || entries.isEmpty()) {
            LOGGER.warn("No chaos manifestation entries for severity {}", severity);
            return;
        }
        int roll = level.getRandom().nextInt(ChaosManifestationTable.totalWeight(entries));
        ChaosManifestationTable.pickWeighted(entries, roll).apply(level, target);
    }
}
