package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.ratden.skavenblight.ai.pathing.nbt.SiegeProjectNbtCodec;
import org.ratden.skavenblight.ai.pathing.nbt.SiegeProjectSnapshot;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent store of every active SiegeProject across every network in one ServerLevel, following
 * this codebase's established SavedData convention (see SkavenIncursionSavedData) - format-version
 * int, SavedData.Factory, LinkedHashMap&lt;UUID, X&gt; keyed by project id for deterministic save
 * order. One store per level, not per network: {@code networkId} on each snapshot (a WarpFluxNetwork
 * id, NOT SiegeProject's own connector-pairing networkId field - see SiegeProjectSnapshot) is what
 * {@link #snapshotsForNetwork} filters on.
 */
public final class SiegeProjectStore extends SavedData {

    public static final String DATA_NAME = "skavenblight_siege_projects";

    private static final int CURRENT_FORMAT_VERSION = 1;

    private static final String FORMAT_VERSION = "formatVersion";
    private static final String PROJECTS = "projects";

    public static final SavedData.Factory<SiegeProjectStore> FACTORY =
            new SavedData.Factory<>(SiegeProjectStore::new, SiegeProjectStore::load);

    private final Map<UUID, SiegeProjectSnapshot> snapshotsById;

    public SiegeProjectStore() {
        this.snapshotsById = new LinkedHashMap<>();
    }

    private SiegeProjectStore(Map<UUID, SiegeProjectSnapshot> loadedSnapshots) {
        this.snapshotsById = new LinkedHashMap<>(loadedSnapshots);
    }

    public static SiegeProjectStore get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /** Adds a newly-active project or overwrites its previous snapshot - one call for both cases,
     * unlike SkavenIncursionSavedData's stricter add/replace split, since a SiegeProject's own
     * lifecycle (tick()-driven progress, auto-widening) makes "already stored" the common case,
     * not a programmer error worth guarding against. */
    public void addOrReplace(SiegeProject project, UUID networkId) {
        SiegeProjectSnapshot snapshot = new SiegeProjectSnapshot(
                project.getId(), networkId, project.getEntryPos(), project.getExpectedEntryCost(),
                project.getExitPos(), project.getBuildOrder(), project.getWidenAnchor(), project.getWidth(),
                project.getAccumulatedWork(), project.getLastTickedGameTime());
        snapshotsById.put(project.getId(), snapshot);
        setDirty();
    }

    /** @return the removed snapshot, or null if no project used that id. */
    public SiegeProjectSnapshot remove(UUID projectId) {
        SiegeProjectSnapshot removed = snapshotsById.remove(projectId);
        if (removed != null) {
            setDirty();
        }
        return removed;
    }

    public Collection<SiegeProjectSnapshot> snapshotsForNetwork(UUID networkId) {
        return snapshotsById.values().stream()
                .filter(s -> s.networkId().equals(networkId))
                .toList();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt(FORMAT_VERSION, CURRENT_FORMAT_VERSION);

        ListTag projectTags = new ListTag();
        for (SiegeProjectSnapshot snapshot : snapshotsById.values()) {
            projectTags.add(SiegeProjectNbtCodec.write(snapshot));
        }
        tag.put(PROJECTS, projectTags);
        return tag;
    }

    public static SiegeProjectStore load(CompoundTag tag, HolderLookup.Provider registries) {
        int formatVersion = tag.getInt(FORMAT_VERSION);
        if (formatVersion != CURRENT_FORMAT_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported SiegeProjectStore format version " + formatVersion
                            + ". Current supported version is " + CURRENT_FORMAT_VERSION + ".");
        }

        ListTag projectTags = tag.getList(PROJECTS, Tag.TAG_COMPOUND);
        Map<UUID, SiegeProjectSnapshot> loaded = new LinkedHashMap<>();
        for (int i = 0; i < projectTags.size(); i++) {
            SiegeProjectSnapshot snapshot = SiegeProjectNbtCodec.read(projectTags.getCompound(i));
            loaded.put(snapshot.projectId(), snapshot);
        }
        return new SiegeProjectStore(loaded);
    }
}
