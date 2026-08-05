package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** One entry in a SiegeProject's ordered build list. Order comes from list position, not a
 * predecessor field - facing is computed once at plan time and never re-derived later. */
public record PlannedStep(BlockPos pos, PathAction action, Direction facing) {}
