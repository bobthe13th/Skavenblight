package org.ratden.skavenblight.magic.wind.influence;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.magic.Wind;
import org.ratden.skavenblight.magic.wind.WindBaselineInfluence;

/** Hysh (white/light) is elevated during the day, Ulgu (grey/shadow) during the night. */
public class TimeOfDayInfluence implements WindBaselineInfluence {

    private static final float DAY_HYSH_BONUS = 150f;
    private static final float NIGHT_ULGU_BONUS = 150f;
    private static final long DAY_TICKS = 12000L;
    private static final long FULL_CYCLE_TICKS = 24000L;

    @Override
    public void apply(ServerLevel level, ChunkPos pos, float[] baselineDeltaOut) {
        long dayTime = level.getDayTime() % FULL_CYCLE_TICKS;
        if (dayTime < DAY_TICKS) {
            baselineDeltaOut[Wind.HYSH.ordinal()] += DAY_HYSH_BONUS;
        } else {
            baselineDeltaOut[Wind.ULGU.ordinal()] += NIGHT_ULGU_BONUS;
        }
    }
}
