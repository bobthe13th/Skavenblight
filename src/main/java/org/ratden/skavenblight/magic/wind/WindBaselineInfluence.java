package org.ratden.skavenblight.magic.wind;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * One natural driver of a chunk's wind baseline (time of day, biome, distance to a Chaos Gate, ...).
 * Each implementation nudges baselineDeltaOut (indexed by Wind.ordinal(), length 8) — it does not
 * set absolute values, since multiple influences stack additively before WindGridManager.tick clamps
 * the result. Register new influences by adding one line to WindGridManager.INFLUENCES.
 */
public interface WindBaselineInfluence {
    void apply(ServerLevel level, ChunkPos pos, float[] baselineDeltaOut);
}
