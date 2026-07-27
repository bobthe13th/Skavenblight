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
 * <li><b>Territory wider than one chunk picks up GameTest's own encasement as bogus extra
 * regions.</b> {@code skyAccess = true} (above) only suppresses the encasement's ROOF; its side
 * walls and a floor beneath the whole structure+padding bounding box remain. Confirmed
 * empirically while writing {@link #testTwoDisconnectedRegionsGetOneConnector}: a territory
 * spanning multiple chunks (needed to see both sides of a >16-block-wide split) picked up (a) a
 * walkable "basement" ledge one layer below the structure's own floor, spanning the padding
 * around the structure, wherever that floor's overhead clearance is open - which is EVERYWHERE
 * in the padding (nothing of ours blocks it there) and also under any gap WE carve into our own
 * floor (removing the floor's solidity opens head clearance for the layer below it too), and (b)
 * a one-cell-wide walkable ledge along the TOP of the encasement's side walls, one ring outside
 * the structure's own footprint. Both are real, fully-connected Regions by the scanner's own
 * rules - not a bug in the scanner, just terrain we didn't intend to test. Because GameTest
 * doesn't chunk-align structure placement, a territory that spans the structure's full 0..31
 * necessarily spills into this padding on at least one side. The fix used here: keep territory
 * to exactly ONE chunk (as {@link #testSingleConnectedRegion} already did) so the encasement's
 * padding is never in scope at all, and derive every other coordinate from THAT chunk's actual
 * (run-dependent) alignment rather than the structure's own fixed 0..31 - see
 * {@link #testTwoDisconnectedRegionsGetOneConnector}'s own comments for the alignment proof.</li>
 * <li><b>A single-layer floor carve still leaves a walkable cell directly underneath it.</b>
 * Removing only the floor block (helper y=1) opens head clearance for the layer below (helper
 * y=0) too, and that layer has its own solid support courtesy of the same encasement floor
 * mentioned above - so a "gap" carved only at helper y=1 registers as two disconnected pieces
 * PLUS a third sliver Region exactly matching the gap's own footprint, one layer down. Carving
 * the full vertical range down to {@code level.getMinBuildHeight()} (not just the floor layer)
 * removes the support entirely and avoids this.</li>
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

    /**
     * End-to-end validation of the headline region-graph requirement: a trench splits the floor
     * into two disconnected regions, {@code RegionGraph} proposes exactly one connector across
     * it, and {@code RegionRouteTree} marks the far side reachable via that connector.
     *
     * <p>The task-4 brief this test was transcribed from used helper-Y=0 for the trench carve,
     * helper-Y=1 for the nexus marker, and {@code Set.of(new ChunkPos(nexusPos))} (a single
     * chunk) as territory, with the trench/nexus/far-side placed at fixed structure-relative
     * coordinates (x=14..17, nexus x=6, far side x>17). None of that survived contact with a real
     * run - see the class javadoc's last two bullets for the two terrain artifacts this
     * discovered (the encasement's padding-side regions, and the floor-only-carve underside
     * sliver). The geometry actually used here fixes both by staying inside a single chunk (like
     * {@link #testSingleConnectedRegion}) whose alignment is discovered at runtime rather than
     * assumed:
     *
     * <ul>
     * <li><b>Why anchor on relative (16, *, 16) to pick the chunk.</b> GameTest doesn't
     * chunk-align structure placement (confirmed empirically - two different runs of this file
     * produced two different nexus world positions with two different chunk-alignment offsets).
     * For a 32-wide structure, let {@code r = structureOriginX mod 16}. The chunk containing
     * world x = structureOriginX + 16 starts at relative x = 16 - r (r in [0,15], so this is in
     * [1,16]) and ends at relative x = 31 - r (in [16,31]) - i.e. for ANY alignment, that chunk's
     * 16-wide window is entirely contained in [1,31], never touching the structure's own edges,
     * let alone the encasement padding beyond them. Same proof applies to z. This is also why
     * {@link #testSingleConnectedRegion}'s nexus at relative (16,1,16) was never contaminated by
     * the padding despite nobody having diagnosed why at the time.</li>
     * <li><b>Everything else is placed relative to that chunk's OWN (run-dependent) window</b> -
     * {@code baseX}/{@code baseZ} below - not against the structure's fixed 0..31, so the test's
     * geometry always lands inside the one safe chunk regardless of where GameTest happens to
     * place the structure this run.</li>
     * <li><b>The trench is carved full-depth</b> (from {@code level.getMinBuildHeight()} up
     * through the floor layer), not just at the floor layer, per the class javadoc's underside-
     * sliver note.</li>
     * </ul>
     */
    @GameTest(template = "pathing_test", timeoutTicks = 600, skyAccess = true)
    public static void testTwoDisconnectedRegionsGetOneConnector(GameTestHelper helper) {
        BlockPos structureOrigin = helper.absolutePos(BlockPos.ZERO);
        ChunkPos chunk = new ChunkPos(helper.absolutePos(new BlockPos(16, 2, 16)));
        Set<ChunkPos> territory = Set.of(chunk);

        // This chunk's own structure-relative window - see the method javadoc's alignment proof
        // for why this 16-wide window is always entirely inside the structure's own [1,31], no
        // matter where GameTest actually placed the structure this run.
        int baseX = chunk.getMinBlockX() - structureOrigin.getX();
        int baseZ = chunk.getMinBlockZ() - structureOrigin.getZ();

        // A 4-block-wide trench (exceeds LEAP's documented 1-block-only range - see
        // SiegeNode.SiegeAction.LEAP's javadoc - so only a BUILD_BRIDGE-type connector can cross
        // it), splitting the chunk into a nexus side (local x 0-5) and a far side (local x 10-15).
        // Carved full-depth (see method javadoc) so no walkable sliver survives underneath it.
        int minRelY = helper.getLevel().getMinBuildHeight() - structureOrigin.getY();
        for (int lx = 6; lx <= 9; lx++) {
            for (int lz = 0; lz <= 15; lz++) {
                for (int y = minRelY; y <= 1; y++) {
                    helper.setBlock(new BlockPos(baseX + lx, y, baseZ + lz), Blocks.AIR.defaultBlockState());
                }
            }
        }

        // helper-Y=2 (template-Y=1, the walkable layer) so the block it occupies goes solid
        // (matching how a real Nexus block replaces a walkable cell) while its horizontal
        // neighbor - nexusPos.north(), the probe used below - stays open air over an intact floor
        // and resolves to a real region id (placing the marker at helper-Y=1 instead would embed
        // it in the already-stone floor, and north() of an embedded marker sits on that same
        // solid floor too - not a member of any region, so regionIdAt returns null and the
        // probe's int != Integer comparison below throws on unboxing).
        BlockPos relativeNexusPos = new BlockPos(baseX + 1, 2, baseZ + 8);
        helper.setBlock(relativeNexusPos, Blocks.STONE.defaultBlockState());
        BlockPos nexusPos = helper.absolutePos(relativeNexusPos);

        TerritoryRegionMap regionMap = new TerritoryRegionMap();
        regionMap.rebuild(helper.getLevel(), territory, nexusPos);

        helper.succeedWhen(() -> {
            check(!regionMap.isCalculating(), "region map still calculating");
            List<Region> regions = regionMap.getRegionIndex().getRegions();
            check(regions.size() == 2, "expected exactly 2 regions (split by the trench), found " + regions.size());
            check(regionMap.getRegionGraph().getAllConnectors().size() == 1,
                    "expected exactly 1 connector between the 2 regions, found "
                            + regionMap.getRegionGraph().getAllConnectors().size());

            int farRegionId = regions.stream().mapToInt(Region::getId)
                    .filter(id -> id != regionMap.getRegionIndex().regionIdAt(nexusPos.north())) // arbitrary non-nexus-side probe
                    .findFirst().orElseThrow();
            check(regionMap.getRouteTree().isReachable(farRegionId),
                    "far region should be reachable via the planned connector");
        });
    }
}
