package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;

/** Flood/routing data: one cell's step during Dijkstra expansion, oriented toward the flood's
 * own target (predecessorPos is one hop closer to the target, i.e. the mob's successor). */
public record FlowStep(BlockPos pos, PathAction action, BlockPos predecessorPos) {}
