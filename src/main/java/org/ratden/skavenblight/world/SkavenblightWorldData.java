package org.ratden.skavenblight.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.ratden.skavenblight.event.skavenIncursion.scenario.ScenarioPattern;

public class SkavenblightWorldData extends SavedData {
    public static final String DATA_NAME = "skavenblight_world_data";
    public static final String DEFAULT_SCHEME_ID = "tutorial";

    public static final Factory<SkavenblightWorldData> FACTORY =
            new Factory<>(
                    SkavenblightWorldData::new,
                    SkavenblightWorldData::load
            );

    public static SkavenblightWorldData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                FACTORY,
                DATA_NAME
        );
    }

    // Campaign
    private boolean skavenblightStarted;
    private long skavenblightStartGameTime;

    // Nexus
    private boolean hasActiveNexus;
    private BlockPos activeNexusPos;

    // Difficulty
    private int threat;
    private int incursionTempo;

    // Scheme
    private String currentSchemeId;
    private long currentSchemeStartGameTime;
    private long currentSchemeDeadlineGameTime;
    private int schemeComplexity;
    private int schemeProgress;
    private int skavenSchemeSuccesses;
    private int skavenSchemeFailures;

    // Director memory
    private long lastGlobalIncursionGameTime;
    private String lastScenarioId;
    private String lastScenarioPattern;

    private long lastAssaultGameTime;
    private long lastRaidGameTime;
    private long lastInfiltrationGameTime;
    private long lastAmbushGameTime;
    private long lastRitualGameTime;
    private long lastSiegeGameTime;

    public SkavenblightWorldData() {
        this.skavenblightStarted = false;
        this.skavenblightStartGameTime = 0L;

        this.hasActiveNexus = false;
        this.activeNexusPos = BlockPos.ZERO;

        this.threat = 10;
        this.incursionTempo = 0;

        this.schemeComplexity = 0;
        this.schemeProgress = 0;

        this.currentSchemeId = DEFAULT_SCHEME_ID;
        this.currentSchemeStartGameTime = 0L;
        this.currentSchemeDeadlineGameTime = 0L;
        this.skavenSchemeSuccesses = 0;
        this.skavenSchemeFailures = 0;

        this.lastGlobalIncursionGameTime = 0L;
        this.lastScenarioId = "";
        this.lastScenarioPattern = "";

        this.lastAssaultGameTime = 0L;
        this.lastRaidGameTime = 0L;
        this.lastInfiltrationGameTime = 0L;
        this.lastAmbushGameTime = 0L;
        this.lastRitualGameTime = 0L;
        this.lastSiegeGameTime = 0L;
    }

    public static SkavenblightWorldData load(CompoundTag tag, HolderLookup.Provider registries) {
        SkavenblightWorldData data = new SkavenblightWorldData();

        data.skavenblightStarted = tag.getBoolean("skavenblight_started");
        data.skavenblightStartGameTime = tag.getLong("skavenblight_start_game_time");

        data.hasActiveNexus = tag.getBoolean("has_active_nexus");
        if (data.hasActiveNexus) {
            data.activeNexusPos = new BlockPos(
                    tag.getInt("active_nexus_x"),
                    tag.getInt("active_nexus_y"),
                    tag.getInt("active_nexus_z")
            );
        }

        data.threat = tag.getInt("threat");
        data.incursionTempo = tag.getInt("incursion_tempo");

        data.schemeComplexity = tag.getInt("scheme_complexity");
        data.schemeProgress = tag.getInt("scheme_progress");

        data.currentSchemeId = tag.getString("current_scheme_id");
        if (data.currentSchemeId.isEmpty() || data.currentSchemeId.equals("chieftain_test")) {
            data.currentSchemeId = DEFAULT_SCHEME_ID;
        }

        data.currentSchemeStartGameTime = tag.getLong("current_scheme_start_game_time");
        data.currentSchemeDeadlineGameTime = tag.getLong("current_scheme_deadline_game_time");
        data.skavenSchemeSuccesses = tag.getInt("skaven_scheme_successes");
        data.skavenSchemeFailures = tag.getInt("skaven_scheme_failures");

        data.lastGlobalIncursionGameTime = tag.getLong("last_global_incursion_game_time");
        data.lastScenarioId = tag.getString("last_scenario_id");
        data.lastScenarioPattern = tag.getString("last_scenario_pattern");

        data.lastAssaultGameTime = tag.getLong("last_assault_game_time");
        data.lastRaidGameTime = tag.getLong("last_raid_game_time");
        data.lastInfiltrationGameTime = tag.getLong("last_infiltration_game_time");
        data.lastAmbushGameTime = tag.getLong("last_ambush_game_time");
        data.lastRitualGameTime = tag.getLong("last_ritual_game_time");
        data.lastSiegeGameTime = tag.getLong("last_siege_game_time");

        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean("skavenblight_started", skavenblightStarted);
        tag.putLong("skavenblight_start_game_time", skavenblightStartGameTime);

        tag.putBoolean("has_active_nexus", hasActiveNexus);
        if (hasActiveNexus && activeNexusPos != null) {
            tag.putInt("active_nexus_x", activeNexusPos.getX());
            tag.putInt("active_nexus_y", activeNexusPos.getY());
            tag.putInt("active_nexus_z", activeNexusPos.getZ());
        }

        tag.putInt("threat", threat);
        tag.putInt("incursion_tempo", incursionTempo);

        tag.putInt("scheme_complexity", schemeComplexity);
        tag.putInt("scheme_progress", schemeProgress);

        tag.putString("current_scheme_id", currentSchemeId);
        tag.putLong("current_scheme_start_game_time", currentSchemeStartGameTime);
        tag.putLong("current_scheme_deadline_game_time", currentSchemeDeadlineGameTime);
        tag.putInt("skaven_scheme_successes", skavenSchemeSuccesses);
        tag.putInt("skaven_scheme_failures", skavenSchemeFailures);

        tag.putLong("last_global_incursion_game_time", lastGlobalIncursionGameTime);
        tag.putString("last_scenario_id", lastScenarioId);
        tag.putString("last_scenario_pattern", lastScenarioPattern);

        tag.putLong("last_assault_game_time", lastAssaultGameTime);
        tag.putLong("last_raid_game_time", lastRaidGameTime);
        tag.putLong("last_infiltration_game_time", lastInfiltrationGameTime);
        tag.putLong("last_ambush_game_time", lastAmbushGameTime);
        tag.putLong("last_ritual_game_time", lastRitualGameTime);
        tag.putLong("last_siege_game_time", lastSiegeGameTime);

        return tag;
    }

    // Campaign

    public boolean isSkavenblightStarted() {
        return skavenblightStarted;
    }

    public long getSkavenblightStartGameTime() {
        return skavenblightStartGameTime;
    }

    public void startSkavenblight(long gameTime) {
        this.skavenblightStarted = true;
        this.skavenblightStartGameTime = gameTime;

        if (this.currentSchemeId == null || this.currentSchemeId.isEmpty() || this.currentSchemeId.equals("chieftain_test")) {
            this.currentSchemeId = DEFAULT_SCHEME_ID;
        }

        this.currentSchemeStartGameTime = gameTime;
        setDirty();
    }

    public void setSkavenblightStarted(boolean skavenblightStarted) {
        this.skavenblightStarted = skavenblightStarted;
        setDirty();
    }

    // Nexus

    public boolean hasActiveNexus() {
        return hasActiveNexus;
    }

    public BlockPos getActiveNexusPos() {
        return activeNexusPos;
    }

    public void setActiveNexus(BlockPos pos) {
        this.hasActiveNexus = true;
        this.activeNexusPos = pos.immutable();
        setDirty();
    }

    public void clearActiveNexus() {
        this.hasActiveNexus = false;
        this.activeNexusPos = BlockPos.ZERO;
        setDirty();
    }

    public boolean isActiveNexus(BlockPos pos) {
        return hasActiveNexus && activeNexusPos.equals(pos);
    }

    // Difficulty

    public int getThreat() {
        return threat;
    }

    public void setThreat(int threat) {
        this.threat = Math.max(0, threat);
        setDirty();
    }

    public int getIncursionTempo() {
        return incursionTempo;
    }

    public void setIncursionTempo(int incursionTempo) {
        this.incursionTempo = Math.max(0, incursionTempo);
        setDirty();
    }

    public void addIncursionTempo(int amount) {
        setIncursionTempo(this.incursionTempo + amount);
    }

    // Scheme

    public int getSchemeComplexity() {
        return schemeComplexity;
    }

    public void setSchemeComplexity(int schemeComplexity) {
        this.schemeComplexity = Math.max(0, schemeComplexity);
        setDirty();
    }

    public int getSchemeProgress() {
        return schemeProgress;
    }

    public void setSchemeProgress(int schemeProgress) {
        this.schemeProgress = Math.max(0, Math.min(100, schemeProgress));
        setDirty();
    }

    public String getCurrentSchemeId() {
        return currentSchemeId;
    }

    public void setCurrentSchemeId(String currentSchemeId) {
        if (currentSchemeId == null || currentSchemeId.isEmpty()) {
            this.currentSchemeId = DEFAULT_SCHEME_ID;
        } else {
            this.currentSchemeId = currentSchemeId;
        }

        setDirty();
    }

    public long getCurrentSchemeStartGameTime() {
        return currentSchemeStartGameTime;
    }

    public void setCurrentSchemeStartGameTime(long currentSchemeStartGameTime) {
        this.currentSchemeStartGameTime = currentSchemeStartGameTime;
        setDirty();
    }

    public long getCurrentSchemeDeadlineGameTime() {
        return currentSchemeDeadlineGameTime;
    }

    public void setCurrentSchemeDeadlineGameTime(long currentSchemeDeadlineGameTime) {
        this.currentSchemeDeadlineGameTime = currentSchemeDeadlineGameTime;
        setDirty();
    }

    public int getSkavenSchemeSuccesses() {
        return skavenSchemeSuccesses;
    }

    public void addSkavenSchemeSuccess() {
        this.skavenSchemeSuccesses++;
        setDirty();
    }

    public int getSkavenSchemeFailures() {
        return skavenSchemeFailures;
    }

    public void addSkavenSchemeFailure() {
        this.skavenSchemeFailures++;
        setDirty();
    }

    // Director memory

    public long getLastGlobalIncursionGameTime() {
        return lastGlobalIncursionGameTime;
    }

    public void setLastGlobalIncursionGameTime(long lastGlobalIncursionGameTime) {
        this.lastGlobalIncursionGameTime = lastGlobalIncursionGameTime;
        setDirty();
    }

    public String getLastScenarioId() {
        return lastScenarioId;
    }

    public void setLastScenarioId(String lastScenarioId) {
        this.lastScenarioId = lastScenarioId == null ? "" : lastScenarioId;
        setDirty();
    }

    public String getLastScenarioPattern() {
        return lastScenarioPattern;
    }

    public void setLastScenarioPattern(String lastScenarioPattern) {
        this.lastScenarioPattern = lastScenarioPattern == null ? "" : lastScenarioPattern;
        setDirty();
    }

    public void recordScenarioStarted(String scenarioId, ScenarioPattern pattern, long gameTime) {
        this.lastGlobalIncursionGameTime = gameTime;
        this.lastScenarioId = scenarioId == null ? "" : scenarioId;
        this.lastScenarioPattern = pattern == null ? "" : pattern.name();

        if (pattern == ScenarioPattern.ASSAULT) {
            this.lastAssaultGameTime = gameTime;
        } else if (pattern == ScenarioPattern.RAID) {
            this.lastRaidGameTime = gameTime;
        } else if (pattern == ScenarioPattern.INFILTRATION) {
            this.lastInfiltrationGameTime = gameTime;
        } else if (pattern == ScenarioPattern.AMBUSH) {
            this.lastAmbushGameTime = gameTime;
        } else if (pattern == ScenarioPattern.RITUAL) {
            this.lastRitualGameTime = gameTime;
        } else if (pattern == ScenarioPattern.SIEGE) {
            this.lastSiegeGameTime = gameTime;
        }

        setDirty();
    }

    public long getLastAssaultGameTime() {
        return lastAssaultGameTime;
    }

    public long getLastRaidGameTime() {
        return lastRaidGameTime;
    }

    public long getLastInfiltrationGameTime() {
        return lastInfiltrationGameTime;
    }

    public long getLastAmbushGameTime() {
        return lastAmbushGameTime;
    }

    public long getLastRitualGameTime() {
        return lastRitualGameTime;
    }

    public long getLastSiegeGameTime() {
        return lastSiegeGameTime;
    }
}