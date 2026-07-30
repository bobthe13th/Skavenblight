package org.ratden.skavenblight.magic.wind.influence;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.Config;
import org.ratden.skavenblight.magic.Wind;
import org.ratden.skavenblight.magic.wind.ChunkWindState;
import org.ratden.skavenblight.magic.wind.WindBaselineInfluence;
import org.ratden.skavenblight.magic.wind.WindGridManager;

/**
 * Tier 0 player control (design doc §3.4): decorating a chunk with tagged mundane blocks
 * (#skavenblight:wind_source/<wind>) raises that Wind's baseline. The count itself is maintained
 * incrementally by TaggedBlockWindHandler on block place/break - this class only reads it, it
 * never rescans the chunk.
 */
public class TaggedBlockInfluence implements WindBaselineInfluence {

    @Override
    public void apply(ServerLevel level, ChunkPos pos, float[] baselineDeltaOut) {
        ChunkWindState state = WindGridManager.get(level).getOrCreate(pos);
        for (Wind wind : Wind.values()) {
            baselineDeltaOut[wind.ordinal()] += state.getTaggedBlockCount(wind) * Config.windSourceBlockBonus;
        }
    }
}
