package org.ratden.skavenblight.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.ai.pathing.region.Region;
import org.ratden.skavenblight.ai.pathing.region.TerritoryRegionMap;

import java.util.List;
import java.util.Set;

/**
 * GameTest scaffolding for the region/graph-level pathing subsystem. Every later task in the
 * region-pathing-hardening plan (4, 5, 6, 7, 8, 9, 12) adds a {@code @GameTest} method to this
 * same file, reusing {@code skavenblight:pathing_test} (a flat 32x8x32 platform: solid stone
 * floor, open air above - see the structure's own generation note in task-2-report.md for how it
 * was produced without a dev client).
 *
 * <p>Two non-obvious things every method added here MUST account for, discovered the hard way
 * while writing {@link #testSingleConnectedRegion}:
 *
 * <ul>
 * <li><b>{@code skyAccess = true} is mandatory on every {@code @GameTest} in this class.</b>
 * Omitting it (the brief's original snippet did) makes {@code GameTestInfo.prepareTestStructure()}
 * auto-encase the structure in an invisible barrier box, including a ROOF one block above the
 * structure's own top layer. Because {@code TerritoryRegionMap}/{@code RegionScanner} deliberately
 * scan the FULL vertical build-height column (not just the structure's own 8 layers - see this
 * mod's top-level CLAUDE.md on why), the flat top of that barrier roof - same XZ footprint as the
 * platform, one Y above it - registers as its own fully-connected, disconnected "floor", producing
 * a bogus second Region purely as a test-harness artifact. Confirmed via direct block-state
 * inspection: with {@code skyAccess} left at its default (false), a barrier appeared exactly one
 * block above the structure's topmost captured layer, and the region scanner reported a second
 * 256-cell region sitting on top of it. Setting {@code skyAccess = true} suppresses the roof
 * (see {@code StructureUtils#encaseStructure}'s {@code p_326950_} argument) and the test collapses
 * back to the single intended region.</li>
 * <li><b>Helper-relative Y is template-relative Y + 1.</b> {@code StructureBlockEntity}'s
 * {@code structurePos} field defaults to {@code (0, 1, 0)} (not {@code (0,0,0)}) and is never
 * overridden by the GameTest harness's own structure-block setup, so the ACTUAL paste origin is
 * one block above {@code structureBlockPos}; but {@code GameTestHelper#absolutePos}/{@code #setBlock}
 * add the relative coordinate directly onto {@code structureBlockPos} with no such offset. Net
 * effect: {@code helper.setBlock(new BlockPos(x, 0, z), ...)} targets the STRUCTURE'S OWN
 * template-relative y=-1 (below the floor), helper y=1 targets the template's floor (y=0), and
 * helper y=2 targets the template's first open/walkable layer (y=1) - i.e. every helper Y is one
 * higher than the corresponding template Y. {@link #testSingleConnectedRegion} places its (inert -
 * the floor there was already stone) nexus marker at helper y=1, which is why the test still reads
 * as targeting "one above the floor" in its own comments despite that being template y=0. Any
 * later test carving gaps/walls into the floor must add 1 to whatever Y it means in template terms.</li>
 * </ul>
 */
@GameTestHolder(Skavenblight.MODID)
@PrefixGameTestTemplate(false)
public class PathingRegionGameTests {

    /** Throws (so {@code succeedWhen} keeps retrying) until {@code condition} holds. */
    static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }

    @GameTest(template = "pathing_test", timeoutTicks = 400, skyAccess = true)
    public static void testSingleConnectedRegion(GameTestHelper helper) {
        BlockPos relativeNexusPos = new BlockPos(16, 1, 16);
        helper.setBlock(relativeNexusPos, Blocks.STONE.defaultBlockState());
        BlockPos nexusPos = helper.absolutePos(relativeNexusPos);

        TerritoryRegionMap regionMap = new TerritoryRegionMap();
        Set<ChunkPos> territory = Set.of(new ChunkPos(nexusPos));
        regionMap.rebuild(helper.getLevel(), territory, nexusPos);

        helper.succeedWhen(() -> {
            check(!regionMap.isCalculating(), "region map still calculating");
            List<Region> regions = regionMap.getRegionIndex().getRegions();
            check(regions.size() == 1,
                    "expected exactly 1 region on a flat open platform, found " + regions.size());
            check(regions.get(0).cellCount() > 100,
                    "expected the single region to cover most of the open floor, found only "
                            + regions.get(0).cellCount() + " cells");
        });
    }
}
