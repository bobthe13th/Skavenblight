package org.ratden.skavenblight.gametest;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.ratden.skavenblight.Config;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.ai.pathing.SiegeNode;
import org.ratden.skavenblight.ai.pathing.region.Region;
import org.ratden.skavenblight.ai.pathing.region.RegionConnector;
import org.ratden.skavenblight.ai.pathing.region.RegionIndex;
import org.ratden.skavenblight.ai.pathing.region.TerritoryRegionMap;
import org.slf4j.Logger;

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
 * {@link #anchorChunkFor} for the reusable helper that does this (including the alignment proof)
 * and {@link #testTwoDisconnectedRegionsGetOneConnector} for an example caller. Later tasks
 * adding their own {@code @GameTest} methods to this file should call {@link #anchorChunkFor}
 * rather than re-deriving this by hand.</li>
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

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Throws (so {@code succeedWhen} keeps retrying) until {@code condition} holds. */
    static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }

    /**
     * A single chunk to use as territory (see the class javadoc's "territory wider than one
     * chunk" bullet for why one chunk is deliberate), plus that chunk's own structure-relative
     * origin ({@code baseX}/{@code baseZ}) so callers can place trench/nexus/etc. geometry as
     * OFFSETS into the chunk's local 0..15 window instead of against the structure's fixed
     * 0..31 - the whole point being that this window's actual position varies per run (GameTest
     * doesn't chunk-align structure placement) and callers should never need to know or assume
     * where it lands.
     */
    private record ChunkAnchor(ChunkPos chunk, int baseX, int baseZ) {}

    /**
     * Picks the chunk containing structure-relative point (16, *, 16) and returns it as a
     * {@link ChunkAnchor}. This exact anchor point is load-bearing, not arbitrary - the
     * following holds for ANY GameTest placement offset:
     *
     * <p>Let {@code r = structureOriginX mod 16} (r in [0,15]). The chunk containing world-x =
     * {@code structureOriginX + 16} starts at relative x = {@code 16 - r} (in [1,16]) and ends at
     * relative x = {@code 31 - r} (in [16,31]) - i.e. that chunk's 16-wide window is ALWAYS
     * entirely contained in [1,31], regardless of alignment, never touching the structure's own
     * edges, let alone the encasement padding beyond them (see the class javadoc's "territory
     * wider than one chunk" bullet for what that padding contaminates). The same proof applies to
     * z. Confirmed empirically across multiple {@code runGameTestServer} invocations, each
     * placing the structure at a different world position/chunk-alignment offset (see
     * task-4-report.md) - this always produced a single clean chunk fully inside the structure's
     * own footprint. This is also why {@link #testSingleConnectedRegion}'s nexus at relative
     * (16,1,16) was never contaminated by the padding despite nobody having diagnosed why at the
     * time it was written.
     */
    private static ChunkAnchor anchorChunkFor(GameTestHelper helper) {
        BlockPos structureOrigin = helper.absolutePos(BlockPos.ZERO);
        ChunkPos chunk = new ChunkPos(helper.absolutePos(new BlockPos(16, 2, 16)));
        int baseX = chunk.getMinBlockX() - structureOrigin.getX();
        int baseZ = chunk.getMinBlockZ() - structureOrigin.getZ();
        return new ChunkAnchor(chunk, baseX, baseZ);
    }

    /**
     * Structure-relative Y of the world's actual minimum build height, for full-depth carves
     * (see the class javadoc's "single-layer floor carve" bullet for why a floor-only carve
     * isn't enough).
     */
    private static int minRelY(GameTestHelper helper) {
        return helper.getLevel().getMinBuildHeight() - helper.absolutePos(BlockPos.ZERO).getY();
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
     * {@link #testSingleConnectedRegion}) whose alignment is discovered at runtime via
     * {@link #anchorChunkFor} rather than assumed - see that method's own javadoc for the
     * alignment proof (why anchoring on relative (16, *, 16) always keeps the chosen chunk fully
     * inside the structure's own footprint, regardless of where GameTest actually places it this
     * run). Every coordinate below is an offset from that chunk's own {@code baseX}/{@code baseZ},
     * not against the structure's fixed 0..31. The trench is carved full-depth (down to
     * {@link #minRelY}, not just the floor layer) per the class javadoc's underside-sliver note.
     *
     * <p><b>On the connector-count assertion below:</b> {@code RegionGraph.build} dedups
     * candidate connectors per unordered region-id pair ({@code bestPerPair}/{@code pairKey}), so
     * with exactly 2 regions the count is guaranteed to be AT MOST 1 - it cannot double-count the
     * one possible pair. It does NOT guarantee the count is exactly 1 rather than 0: whether
     * {@code tryTrace} actually finds a connector at all depends on the trench's width/depth
     * being within {@code SiegeLineTracer}'s reach and {@code RegionGraph.MAX_CHAIN_HOPS}, a
     * property of this test's geometry, not of the dedup map. That it comes out to exactly 1 here
     * is confirmed empirically (see task-4-report.md's two independent runs), not derived from
     * the dedup guarantee alone.
     */
    @GameTest(template = "pathing_test", timeoutTicks = 600, skyAccess = true)
    public static void testTwoDisconnectedRegionsGetOneConnector(GameTestHelper helper) {
        ChunkAnchor anchor = anchorChunkFor(helper);
        Set<ChunkPos> territory = Set.of(anchor.chunk());
        int baseX = anchor.baseX();
        int baseZ = anchor.baseZ();

        // A 4-block-wide trench (exceeds LEAP's documented 1-block-only range - see
        // SiegeNode.SiegeAction.LEAP's javadoc - so only a BUILD_BRIDGE-type connector can cross
        // it), splitting the chunk into a nexus side (local x 0-5) and a far side (local x 10-15).
        // Carved full-depth (see method javadoc) so no walkable sliver survives underneath it.
        int minRelY = minRelY(helper);
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

    /**
     * The literal headline requirement: with three regions in a line - C, then B, then A (the
     * nexus region) - a REAL direct C-A connector exists (so this is a genuine cost comparison
     * between two actually-discovered routes, not merely "the only path that exists"), but it
     * costs more than the two-hop C-B-A route, and the route tree must pick the cheaper two-hop
     * route. This is deliberately the scenario that distinguishes a real Dijkstra
     * ({@code RegionRouteTree.compute}, weighted by {@code RegionConnector.cost()}) from a plain
     * unweighted BFS/hop-count search: BFS would prefer the direct 1-hop C-A connector purely for
     * having fewer hops, even though it costs more; only cost-aware search picks the 2-hop route
     * here. An earlier revision of this test made a direct C-A connector geometrically impossible
     * instead of merely more expensive - that version passed under a plain-BFS-shaped
     * {@code RegionRouteTree.compute} just as easily as under the real Dijkstra one (a 3-node
     * chain with only one possible path can't tell the two apart), so it wasn't actually
     * exercising the "cheaper," cost-aware part of the headline requirement. See the method body's
     * assertions for the specific invariants this now checks instead of merely logging them.
     *
     * <p>Kept within a single {@link #anchorChunkFor} chunk (16x16) like
     * {@link #testTwoDisconnectedRegionsGetOneConnector}, for the same reason - see that test's
     * javadoc and the class javadoc's "territory wider than one chunk" bullet. All three regions
     * plus both gaps have to fit in that one 16-wide (local x) window:
     *
     * <pre>
     * local x:  0  1  2 | 3  4  5  6  7 | 8  9 10 | 11 12 | 13 14 15
     *           `---C---'  `--gapCB(5)--'  `--B---'  `gapBA`  `---A---'
     * </pre>
     *
     * <p>Region C (local x 0-2) and region A (local x 13-15, containing the nexus) are separated
     * by the full 16-wide corridor; region B (local x 8-10) sits in the middle. The gap on B's far
     * side from C is 5 blocks wide ("long/expensive" - more BUILD_BRIDGE steps, matching the
     * brief's "wide/expensive-looking" framing), the gap on B's near side to A is 2 blocks wide
     * ("short/cheap"). Both gaps are carved full-depth (down to {@link #minRelY}, not just the
     * floor layer) per the class javadoc's underside-sliver note, exactly like the two-region
     * test's trench - across the FULL local z range (0-15) EXCEPT that region B's own floor is
     * only left intact for local z 0-7; for local z 8-15 it's carved away too (same full-depth
     * treatment), merging the two gaps into one continuous 10-wide corridor (local x 3-12) at
     * those z rows with nothing of B's in the way.
     *
     * <p>This split is what makes both connectors real: {@code RegionGraph.tryTrace} traces a
     * straight line (fixed direction per call - see that method's own javadoc) from a boundary
     * cell and keeps chaining through mid-air landings, but returns the moment it lands on a
     * DIFFERENT region's walkable ground (landing back in the SAME region, or in mid-air, doesn't
     * end the trace - it keeps extending in the same direction instead). At local z 0-7, where
     * B's floor is intact, a horizontal ray from C's boundary lands on B's floor after crossing
     * the 5-wide gap and stops there, registering C-B (not reaching A in the same trace); B's own
     * boundary similarly reaches A across the 2-wide gap. At local z 8-15, where B's floor has
     * been removed, there is no walkable ground for a horizontal ray to land on until it clears
     * the full 10-wide corridor and reaches A directly - registering a real C-A connector.
     *
     * <p>Why the direct connector still costs more despite being one hop instead of two:
     * {@code SiegeLineTracer.trace} charges one {@code base} unit
     * ({@code Config.buildingBasePenalty * 10}) per BUILD_BRIDGE step PLUS one more for the
     * trace's initial offset, then doubles the total for a completed horizontal trace - so a
     * straight run of {@code n} open columns costs {@code 2 * ((n+1) * base + 10)}. Crossing the
     * two gaps as SEPARATE traces (n=5 then n=2) pays the {@code (n+1)} multiplier twice, for
     * {@code 6 + 3 = 9} base-units total; crossing all 10 columns (the original 5+2 gap columns
     * PLUS the 3 columns that used to be B's floor) as ONE trace pays it once, for
     * {@code 11} base-units - MORE, even though it's a single hop instead of two. The actual
     * logged connector costs (see task-5-report.md's fix-round entry) fit this exactly with
     * {@code base=1500}: 18020 for C-B (n=5, 6 base-units), 9020 for B-A (n=2, 3 base-units,
     * 27040 combined), against 33020 for the direct C-A connector (n=10, 11 base-units) - the two
     * assertions on {@code hopCostC} below check this arithmetic directly rather than leaving it
     * as something only visible in the log.
     */
    @GameTest(template = "pathing_test", timeoutTicks = 600, skyAccess = true)
    public static void testThreeRegionsRouteThroughCheaperIntermediateHop(GameTestHelper helper) {
        ChunkAnchor anchor = anchorChunkFor(helper);
        Set<ChunkPos> territory = Set.of(anchor.chunk());
        int baseX = anchor.baseX();
        int baseZ = anchor.baseZ();
        int minRelY = minRelY(helper);

        // Gap C|B: local x 3-7 (5 wide, "long/expensive"). Gap B|A: local x 11-12 (2 wide,
        // "short/cheap"). Both carved full-depth (see method javadoc) across the FULL local z
        // range (0-15) so no walkable sliver survives underneath either one.
        for (int lx = 3; lx <= 7; lx++) {
            for (int lz = 0; lz <= 15; lz++) {
                for (int y = minRelY; y <= 1; y++) {
                    helper.setBlock(new BlockPos(baseX + lx, y, baseZ + lz), Blocks.AIR.defaultBlockState());
                }
            }
        }
        for (int lx = 11; lx <= 12; lx++) {
            for (int lz = 0; lz <= 15; lz++) {
                for (int y = minRelY; y <= 1; y++) {
                    helper.setBlock(new BlockPos(baseX + lx, y, baseZ + lz), Blocks.AIR.defaultBlockState());
                }
            }
        }
        // Also remove region B's own floor for local z 8-15 (full depth, same as above) - this
        // opens ONE continuous 10-wide corridor (local x 3-12) at those z rows with no B floor to
        // land on, letting a straight horizontal trace from C reach A directly. B survives only at
        // local z 0-7, where both surrounding gaps stay their original width and the cheaper
        // C-B/B-A connectors are found instead. See method javadoc for why this makes the direct
        // C-A connector real (not just geometrically absent) while still costing more overall.
        for (int lx = 8; lx <= 10; lx++) {
            for (int lz = 8; lz <= 15; lz++) {
                for (int y = minRelY; y <= 1; y++) {
                    helper.setBlock(new BlockPos(baseX + lx, y, baseZ + lz), Blocks.AIR.defaultBlockState());
                }
            }
        }

        // Nexus marker inside region A (local x 13-15), helper-Y=2 (the walkable layer) - same
        // convention as testTwoDisconnectedRegionsGetOneConnector's nexus placement.
        BlockPos relativeNexusPos = new BlockPos(baseX + 14, 2, baseZ + 8);
        helper.setBlock(relativeNexusPos, Blocks.STONE.defaultBlockState());
        BlockPos nexusPos = helper.absolutePos(relativeNexusPos);

        // Probe into region C (local x 0-2, any z - it spans the full local z range). Probe into
        // region B at local z 4 (inside B's surviving z 0-7 band, well clear of the z=8 edge where
        // its floor was removed) rather than z 8, which is now part of the direct corridor.
        BlockPos probeC = helper.absolutePos(new BlockPos(baseX + 1, 2, baseZ + 8));
        BlockPos probeB = helper.absolutePos(new BlockPos(baseX + 9, 2, baseZ + 4));

        TerritoryRegionMap regionMap = new TerritoryRegionMap();
        regionMap.rebuild(helper.getLevel(), territory, nexusPos);

        boolean[] loggedDump = {false};

        helper.succeedWhen(() -> {
            check(!regionMap.isCalculating(), "region map still calculating");
            List<Region> regions = regionMap.getRegionIndex().getRegions();
            check(regions.size() == 3, "expected 3 regions (A/B/C), found " + regions.size());

            // Resolved to primitive ints (after the null checks) and used as primitives
            // throughout below - deliberately, so every comparison against them is an unambiguous
            // value comparison rather than the boxed-Integer reference-equality trap the class
            // javadoc/testTwoDisconnectedRegionsGetOneConnector already warns about.
            Integer regionCBoxed = regionMap.getRegionIndex().regionIdAt(probeC);
            check(regionCBoxed != null, "region C probe position isn't in any region - adjust the probe");
            Integer regionBBoxed = regionMap.getRegionIndex().regionIdAt(probeB);
            check(regionBBoxed != null, "region B probe position isn't in any region - adjust the probe");
            int regionC = regionCBoxed;
            int regionB = regionBBoxed;
            int regionA = regionMap.getRouteTree().getRootRegionId();

            // Evidence dump: every region's bounds/cell count plus the route tree's hop cost and
            // parent for it, and every connector RegionGraph actually built (with cost) - so the
            // C->B->A routing decision (and the more expensive direct C-A connector it's passing
            // up) is visible in the log, not just asserted. Logged once (not on every succeedWhen
            // retry tick) via loggedDump.
            if (!loggedDump[0]) {
                for (Region region : regions) {
                    LOGGER.info("[Skavenblight][test] region {}: min={} max={} cells={} hopCost={} parent={}",
                            region.getId(), region.getMin(), region.getMax(), region.cellCount(),
                            regionMap.getRouteTree().getHopCost(region.getId()),
                            regionMap.getRouteTree().getParentRegion(region.getId()));
                }
                for (RegionConnector connector : regionMap.getRegionGraph().getAllConnectors()) {
                    LOGGER.info("[Skavenblight][test] connector region{}<->region{} cost={}",
                            connector.regionA(), connector.regionB(), connector.cost());
                }
                LOGGER.info("[Skavenblight][test] regionC={} regionB={} regionA(root)={}",
                        regionC, regionB, regionA);
                loggedDump[0] = true;
            }

            // A real direct C-A connector must exist - otherwise "C's parent is B" is only true
            // because no other path exists at all, which can't distinguish cost-aware routing from
            // plain hop-count BFS (see method javadoc).
            RegionConnector directCA = regionMap.getRegionGraph().getAllConnectors().stream()
                    .filter(c -> (c.regionA() == regionC && c.regionB() == regionA)
                            || (c.regionA() == regionA && c.regionB() == regionC))
                    .findFirst().orElse(null);
            check(directCA != null,
                    "expected a real direct C-A connector (more expensive than the via-B route) - "
                            + "without one, this test can't distinguish cost-aware routing from plain hop-count BFS");

            RegionConnector parentConnectorOfC = regionMap.getRouteTree().getParentConnector(regionC);
            check(parentConnectorOfC != null, "region C should have a parent connector");

            int hopCostC = regionMap.getRouteTree().getHopCost(regionC);
            int hopCostB = regionMap.getRouteTree().getHopCost(regionB);
            check(hopCostC == hopCostB + parentConnectorOfC.cost(),
                    "region C's hop cost should equal region B's hop cost plus C's parent-connector cost (found C="
                            + hopCostC + ", B=" + hopCostB + ", connector=" + parentConnectorOfC.cost() + ")");

            check(hopCostC < directCA.cost(),
                    "the via-B route's total cost should be cheaper than the direct C-A connector's cost (found via-B="
                            + hopCostC + ", direct=" + directCA.cost() + ") - otherwise picking B over the direct "
                            + "connector wouldn't actually demonstrate cost-aware routing");

            Integer parentOfCBoxed = regionMap.getRouteTree().getParentRegion(regionC);
            check(parentOfCBoxed != null, "region C should be reachable through some parent hop");
            int parentOfC = parentOfCBoxed;
            check(regionC != regionB && parentOfC == regionB,
                    "region C's route should hop through the cheaper region B, not the more expensive direct "
                            + "connector to A (found parent=" + parentOfC + ", regionB=" + regionB + ", regionA=" + regionA + ")");
        });
    }

    /**
     * Characterization test for {@link RegionIndex#regionAt}/{@code regionIdAt}, written BEFORE
     * the task-6 refactor that replaces its linear scan-every-Region-and-call-contains()
     * implementation with a real O(1) per-chunk flat-array lookup. Same assertions must hold
     * both before and after that refactor - this test exists to prove behavior didn't change,
     * not to test new functionality.
     *
     * <p>The task-6 brief's original snippet placed the nexus at fixed structure-relative (16, 1,
     * 16) and probed fixed structure-relative (5, *, 5) with a raw {@code Set.of(new
     * ChunkPos(nexusPos))} territory. That doesn't survive contact with the class javadoc's
     * "territory wider than one chunk" bullet: the territory here is exactly the single chunk
     * containing relative (16, *, 16) (per {@link #anchorChunkFor}'s alignment proof), but that
     * chunk's own 16-wide window can land anywhere in [1, 31] depending on the run's chunk
     * alignment - it is NOT guaranteed to contain a fixed point like relative x=5. A probe placed
     * outside the actual territory chunk would non-deterministically resolve to null regardless
     * of which RegionIndex implementation is under test, so - as this class's other multi-probe
     * tests already do - every coordinate below is anchored via {@link #anchorChunkFor} and
     * expressed as an offset from that chunk's own {@code baseX}/{@code baseZ} instead.
     *
     * <p>The walkable probe uses helper-Y=2 (template-Y=1, the actual open/standable layer - see
     * the class javadoc's helper-Y-offset bullet), not the brief's helper-Y=1 (template-Y=0, the
     * solid floor block itself): {@code RegionScanner} seeds/stores cells at the position a mob's
     * feet occupy, i.e. the open cell above the solid floor, not the floor block. helper-Y=1 is
     * solid stone there and was never a member of any region even under the old linear-scan code.
     */
    @GameTest(template = "pathing_test", timeoutTicks = 400, skyAccess = true)
    public static void testRegionIndexLookupCorrectness(GameTestHelper helper) {
        ChunkAnchor anchor = anchorChunkFor(helper);
        Set<ChunkPos> territory = Set.of(anchor.chunk());
        int baseX = anchor.baseX();
        int baseZ = anchor.baseZ();

        BlockPos relativeNexusPos = new BlockPos(baseX + 8, 1, baseZ + 8);
        helper.setBlock(relativeNexusPos, Blocks.STONE.defaultBlockState());
        BlockPos nexusPos = helper.absolutePos(relativeNexusPos);

        TerritoryRegionMap regionMap = new TerritoryRegionMap();
        regionMap.rebuild(helper.getLevel(), territory, nexusPos);

        helper.succeedWhen(() -> {
            check(!regionMap.isCalculating(), "region map still calculating");
            RegionIndex index = regionMap.getRegionIndex();

            BlockPos walkable = helper.absolutePos(new BlockPos(baseX + 5, 2, baseZ + 5));
            check(index.regionIdAt(walkable) != null, "an open floor cell should resolve to a region");

            BlockPos belowFloor = helper.absolutePos(new BlockPos(baseX + 5, -60, baseZ + 5));
            check(index.regionIdAt(belowFloor) == null, "a position far below the floor shouldn't resolve to any region");

            BlockPos aboveCeiling = helper.absolutePos(new BlockPos(baseX + 5, 300, baseZ + 5));
            check(index.regionIdAt(aboveCeiling) == null, "a position far above the structure shouldn't resolve to any region");
        });
    }

    /**
     * Task-8: a region's cell {@code BitSet} otherwise only grows via a full rebuild or a
     * dirty-region rescan of that region's OWN prior bounding box, so a long chained connector's
     * midpoint can sit outside BOTH endpoint regions' natural flood-fill bounds indefinitely -
     * {@link TerritoryRegionMap#getRegionFlowFieldFor} resolves a region FIRST, so a mob standing
     * on such a cell mid-crossing fails that lookup even though the connector's own instructions
     * would otherwise be available.
     *
     * <p>Geometry: a lone elevated platform 40 blocks straight up from the main floor, built
     * directly above one of the main floor's own boundary cells (deliberately placed at the
     * anchored chunk's own edge, per {@link #anchorChunkFor}, so it qualifies as a boundary cell
     * under the class javadoc's "territory wider than one chunk" constraints without needing any
     * carve at all). {@code SiegeLineTracer}'s own per-trace cap is 32 blocks
     * ({@code MAX_PROJECT_LENGTH}), so a single vertical trace from the main floor can't reach the
     * platform directly: it ends in a synthetic, unsupported {@code BUILD_LANDING} mid-shaft (at
     * +32), and {@code RegionGraph.tryTrace} has to chain a SECOND hop from there to actually reach
     * the platform - exactly the multi-hop-chained-connector scenario this task targets, and it
     * fits entirely inside one anchored chunk (no horizontal room needed at all, so none of the
     * class javadoc's carve-related gotchas apply here).
     *
     * <p>Every cell either orientation of the resulting connector's {@code SiegeProject} touches is
     * a position a mob could be standing on mid-crossing (see {@code RegionGraph.outboundInstructions}/
     * {@code inboundInstructions}'s docs for why each hop is paired with its own action rather than
     * reusing the tracer's raw, one-off instruction map) - none of them should fail a region lookup.
     */
    @GameTest(template = "pathing_test", timeoutTicks = 800, skyAccess = true)
    public static void testLongConnectorCellsAreNeverOrphanedFromLookup(GameTestHelper helper) {
        ChunkAnchor anchor = anchorChunkFor(helper);
        Set<ChunkPos> territory = Set.of(anchor.chunk());
        int baseX = anchor.baseX();
        int baseZ = anchor.baseZ();

        // Launch column at the chunk's own edge (local x = 0) - guaranteed a Region-A boundary
        // cell (fewer than BOUNDARY_WALKABLE_NEIGHBOR_THRESHOLD walkable neighbors, since stepping
        // further in -x leaves the single-chunk territory bounds) regardless of this run's actual
        // world-alignment offset.
        int shaftLocalX = 0;
        int shaftLocalZ = 8;

        // Elevated platform: a single solid block at helper-y=41 (40 blocks above the main floor's
        // own walkable layer, helper-y=1), with open air both above (head clearance) and all the
        // way down the shaft to the main floor - isolated enough (no solid neighbor at its own
        // layer) that RegionScanner floods it as its own single-cell Region.
        helper.setBlock(new BlockPos(baseX + shaftLocalX, 41, baseZ + shaftLocalZ), Blocks.STONE.defaultBlockState());

        BlockPos relativeNexusPos = new BlockPos(baseX + 4, 1, baseZ + 4);
        helper.setBlock(relativeNexusPos, Blocks.STONE.defaultBlockState());
        BlockPos nexusPos = helper.absolutePos(relativeNexusPos);

        TerritoryRegionMap regionMap = new TerritoryRegionMap();
        regionMap.rebuild(helper.getLevel(), territory, nexusPos);

        boolean[] loggedDump = {false};

        helper.succeedWhen(() -> {
            check(!regionMap.isCalculating(), "region map still calculating");

            List<Region> regions = regionMap.getRegionIndex().getRegions();
            check(regions.size() == 2,
                    "expected exactly 2 regions (main floor + elevated platform), found " + regions.size());

            check(regionMap.getRegionGraph().getAllConnectors().size() == 1,
                    "expected exactly 1 chained connector between the main floor and the elevated platform, found "
                            + regionMap.getRegionGraph().getAllConnectors().size());

            RegionConnector connector = regionMap.getRegionGraph().getAllConnectors().get(0);

            boolean logThisPass = !loggedDump[0];
            if (logThisPass) {
                for (Region region : regions) {
                    LOGGER.info("[Skavenblight][test] region {}: min={} max={} cells={}",
                            region.getId(), region.getMin(), region.getMax(), region.cellCount());
                }
                LOGGER.info("[Skavenblight][test] connector region{}<->region{} cost={} entryInA={} entryInB={}",
                        connector.regionA(), connector.regionB(), connector.cost(),
                        connector.entryInA().toShortString(), connector.entryInB().toShortString());
            }

            // Every position either orientation's instruction map touches is a cell a mob can be
            // standing on mid-crossing - none of them should fail a region lookup, regardless of
            // which endpoint region ends up resolving it (see task-8-report.md's tie-break note).
            for (BlockPos step : connector.projectTowardA().getInstructions().keySet()) {
                Integer resolvedRegion = regionMap.getRegionIndex().regionIdAt(step);
                check(resolvedRegion != null,
                        "connector cell " + step.toShortString() + " isn't claimed by any region - a mob standing there would be orphaned");
                if (logThisPass) {
                    LOGGER.info("[Skavenblight][test] connector cell {} -> region {}", step.toShortString(), resolvedRegion);
                }
            }
            for (BlockPos step : connector.projectTowardB().getInstructions().keySet()) {
                check(regionMap.getRegionIndex().regionIdAt(step) != null,
                        "connector cell " + step.toShortString() + " isn't claimed by any region - a mob standing there would be orphaned");
            }
            loggedDump[0] = true;
        });
    }

    /**
     * Task 9 Step 0: {@code recomputeDirtyRegions} used to rebuild {@code this.regionIndex} from
     * the FULL region list once per dirty region INSIDE its per-region loop (task-6 made this a
     * real ~384KB-per-chunk allocation, not the old cheap {@code List.copyOf}) - for N
     * simultaneously-dirty regions in one batch that discarded N-1 intermediate RegionIndex
     * instances unread before the batch finished. The fix moves the reconstruction out of the
     * loop to run exactly once per batch.
     *
     * <p>This is primarily a wasted-allocation fix, and the final published {@code RegionIndex}'s
     * CONTENT is the same either way (the OLD code's per-iteration index was already built from an
     * up-to-date region list each time, so whichever iteration ran last already published the
     * right answer) - so there is deliberately no failing-before/passing-after split on content.
     * There IS a real, positive concurrency improvement though: {@code regionIndex} is a volatile
     * field read by live mob-pathing queries from other threads at any time - the OLD code
     * published a NEW index after EVERY region in the batch (region A's fresh index visible while
     * region B was still stale/mid-recompute), whereas this fix publishes exactly once, after the
     * WHOLE batch, so no external reader can ever observe a partially-updated batch. This test is a
     * coherence check that a post-dirty-batch state still resolves correctly - true before the fix
     * and after it - not a test of the atomicity improvement itself (which isn't independently
     * observable from a single-threaded GameTest).
     *
     * <p><b>Important caveat discovered while writing this test, corrected after further review
     * (see the report):</b> this geometry does NOT exercise {@code recomputeDirtyRegions}'s
     * per-region loop / non-topology-changed fast path at all, despite the name - it always
     * full-rebuilds instead. This is NOT merely because both regions here happen to live in the
     * same single chunk (a GameTest-only convention - see the class javadoc's "territory wider
     * than one chunk" bullet). It is a GENERAL property: this geometry's 2 regions are joined by a
     * connector, and {@code RegionGraph.registerConnector}'s {@code addCell} calls (task-8)
     * unconditionally expand each endpoint region's bounding box (via {@code Region.addCell}'s
     * unconditional {@code expandBounds}) to include the chunk containing the OTHER endpoint's own
     * natural landing cell - confirmed by tracing {@code SiegeLineTracer.trace}, whose
     * {@code orderedSteps} always ends with the landing position itself. Since
     * {@code recomputeDirtyRegions}'s {@code localBounds} is the full chunk-grid rectangle from a
     * region's min to max bounds, and {@code RegionScanner.scan} always sweeps that rectangle's
     * FULL height, this would hold even if the two regions lived in genuinely different chunks in a
     * real production territory - rescanning either one always rediscovers the other's landing
     * cell as a separate component ({@code rescanned.size() &gt;= 2}), the TOPOLOGY-CHANGED branch,
     * not the fast path - confirmed below via the {@code getGeneration()} bump. What this test
     * actually proves is that a real, {@code tick()}-driven 2-region dirty batch (as opposed to
     * task-8/9's other tests, which only ever call {@code rebuild()} once) still lands on a
     * coherent, correctly-resolving index either way - genuine coverage, just not of Step 0's
     * specific code path. See the report for the full analysis and what it implies for
     * characterizing rebuild frequency.
     *
     * <p>Geometry: {@link #testTwoDisconnectedRegionsGetOneConnector}'s exact trench split (2
     * regions). After the initial rebuild settles, BOTH regions are marked dirty via two
     * {@code onBlockChanged} calls queued back-to-back BEFORE either is drained by a
     * {@code tick()} call, so the very next eligible {@code tick()} hands
     * {@code recomputeDirtyRegions} both region ids in one batch.
     * {@code regionMap.tick(helper.getLevel())} is driven manually every GameTest tick from inside
     * {@code succeedWhen} because this test's {@code TerritoryRegionMap} is a bare instance, not
     * registered with anything (like {@code WarpFluxNetwork}) that would otherwise call
     * {@code tick()} for it.
     */
    // timeoutTicks generous relative to Config.minimumSettleDelayMs (1000ms real time, the actual
    // gate here - see tick()'s terrainSettled check): observed empirically that 800 ticks was too
    // tight a budget under this GameTest server's real (non-1:1) tick pacing and occasionally
    // timed out before 1000ms of wall-clock time had actually elapsed since the block changes were
    // reported, even though the batch was otherwise handled correctly once given enough time.
    @GameTest(template = "pathing_test", timeoutTicks = 1600, skyAccess = true)
    public static void testDirtyRegionBatchProducesOneCoherentFinalIndex(GameTestHelper helper) {
        ChunkAnchor anchor = anchorChunkFor(helper);
        Set<ChunkPos> territory = Set.of(anchor.chunk());
        int baseX = anchor.baseX();
        int baseZ = anchor.baseZ();
        int minRelY = minRelY(helper);

        for (int lx = 6; lx <= 9; lx++) {
            for (int lz = 0; lz <= 15; lz++) {
                for (int y = minRelY; y <= 1; y++) {
                    helper.setBlock(new BlockPos(baseX + lx, y, baseZ + lz), Blocks.AIR.defaultBlockState());
                }
            }
        }

        BlockPos relativeNexusPos = new BlockPos(baseX + 1, 2, baseZ + 8);
        helper.setBlock(relativeNexusPos, Blocks.STONE.defaultBlockState());
        BlockPos nexusPos = helper.absolutePos(relativeNexusPos);

        BlockPos nearProbe = helper.absolutePos(new BlockPos(baseX + 2, 2, baseZ + 8));
        BlockPos farProbe = helper.absolutePos(new BlockPos(baseX + 12, 2, baseZ + 8));

        TerritoryRegionMap regionMap = new TerritoryRegionMap();
        regionMap.rebuild(helper.getLevel(), territory, nexusPos);

        int[] nearRegionBefore = {-1};
        int[] farRegionBefore = {-1};
        boolean[] changesReported = {false};
        RegionIndex[] indexBeforeChange = {null};
        long[] generationBeforeChange = {-1};

        helper.succeedWhen(() -> {
            regionMap.tick(helper.getLevel());
            check(!regionMap.isCalculating(), "region map still calculating");

            if (indexBeforeChange[0] == null) {
                List<Region> regions = regionMap.getRegionIndex().getRegions();
                check(regions.size() == 2, "expected exactly 2 regions before the dirty batch, found " + regions.size());

                Integer nearId = regionMap.getRegionIndex().regionIdAt(nearProbe);
                Integer farId = regionMap.getRegionIndex().regionIdAt(farProbe);
                check(nearId != null && farId != null, "both probes must resolve before the dirty batch");
                check(!nearId.equals(farId), "the two probes must be in different regions to begin with");

                nearRegionBefore[0] = nearId;
                farRegionBefore[0] = farId;
                indexBeforeChange[0] = regionMap.getRegionIndex();
                generationBeforeChange[0] = regionMap.getGeneration();

                // Both queued before either is drained by a tick() call, so the very next dirty
                // recompute sees BOTH region ids in one batch - see method javadoc.
                regionMap.onBlockChanged(nearProbe);
                regionMap.onBlockChanged(farProbe);
                changesReported[0] = true;
                check(false, "waiting for the 2-region dirty batch to be picked up");
            }

            check(changesReported[0], "block changes were never reported");
            check(regionMap.getRegionIndex() != indexBeforeChange[0],
                    "getRegionIndex() is still the SAME instance as before the block changes - the dirty "
                            + "batch hasn't been recomputed yet");

            // Pins the method javadoc's caveat as an executable claim: this geometry always lands
            // on the topology-changed/full-rebuild branch (generation bumps), never the
            // non-topology-changed fast path (which would leave generation unchanged).
            check(regionMap.getGeneration() > generationBeforeChange[0],
                    "expected the dirty batch to trigger a full rebuild (generation bump) - if this "
                            + "ever fails, the fast path became reachable and this test's javadoc caveat is stale");

            Integer nearIdAfter = regionMap.getRegionIndex().regionIdAt(nearProbe);
            Integer farIdAfter = regionMap.getRegionIndex().regionIdAt(farProbe);
            check(nearIdAfter != null && farIdAfter != null, "both probes must still resolve after the batch recompute");
            check(nearIdAfter == nearRegionBefore[0],
                    "near-side probe resolved to a different region after the batch (" + nearIdAfter + " vs " + nearRegionBefore[0] + ")");
            check(farIdAfter == farRegionBefore[0],
                    "far-side probe resolved to a different region after the batch (" + farIdAfter + " vs " + farRegionBefore[0] + ")");
            check(!nearIdAfter.equals(farIdAfter),
                    "the two probes collapsed into the same region after the batch recompute - the batch's "
                            + "final index isn't coherent");
        });
    }

    /**
     * Task 9 Step 0b: {@code RegionGraph.registerConnector} (task-8) claims a connector's traced
     * cells into BOTH endpoint regions, but {@code RegionIndex}'s shared-cell tie-break
     * (last-write-wins in region SCAN/discovery order) and the route tree's parent/child
     * assignment (cost order from the root) are unrelated orderings.
     * {@code rebuildRegionsAndGraph}'s active-project injection used to only ever give the
     * connector's crossing project to the route tree's CHILD side
     * ({@code parentConnector.projectFor(childId)}) - the PARENT side got nothing for these cells
     * beyond its own (unrelated) upstream project, so a shared cell the tie-break happened to hand
     * to the PARENT had no flow-field instruction at all, even though the region lookup itself
     * succeeded (task-8's own check).
     *
     * <p>Geometry: reuses {@link #testLongConnectorCellsAreNeverOrphanedFromLookup}'s exact
     * vertical shaft (a main floor plus an isolated elevated platform 40 blocks up, joined by one
     * chained connector), but relocates the nexus marker from the main floor onto the platform's
     * own support block - an inert re-placement of the same STONE already there (same convention
     * as every other nexus marker in this class), whose {@code .above()} neighbor is the
     * platform's one walkable cell. {@code RegionScanner.scan} discovers regions strictly
     * Y-ascending within a single-chunk territory, so the low main floor is ALWAYS discovered (and
     * numbered) before the high platform, regardless of where the nexus sits - moving the nexus
     * onto the platform therefore makes the platform BOTH the route tree's root/parent (it
     * contains the nexus) AND the region {@code RegionIndex} writes LAST for any cell the
     * connector claims into both endpoints (last-write-wins) - i.e. the tie-break winner is now
     * the PARENT, the exact case task-8's own test happened not to cover (there, the child
     * coincidentally won both the tie-break and the project injection). The root/parent-id
     * assertions below confirm this geometry inversion actually landed as intended, rather than
     * silently falling back to the coincidental case this test is specifically trying to avoid.
     */
    @GameTest(template = "pathing_test", timeoutTicks = 800, skyAccess = true)
    public static void testParentRegionGetsRealInstructionsForSharedConnectorCells(GameTestHelper helper) {
        ChunkAnchor anchor = anchorChunkFor(helper);
        Set<ChunkPos> territory = Set.of(anchor.chunk());
        int baseX = anchor.baseX();
        int baseZ = anchor.baseZ();

        int shaftLocalX = 0;
        int shaftLocalZ = 8;

        helper.setBlock(new BlockPos(baseX + shaftLocalX, 41, baseZ + shaftLocalZ), Blocks.STONE.defaultBlockState());

        // Nexus on the platform's OWN support block (already stone - an inert re-placement, same
        // convention as every other nexus marker in this class): its .above() neighbor is the
        // platform's one walkable cell, making the platform the route tree's root - see method
        // javadoc for why this inverts the tie-break/parent-child scan order relative to task-8's
        // own test.
        BlockPos relativeNexusPos = new BlockPos(baseX + shaftLocalX, 41, baseZ + shaftLocalZ);
        helper.setBlock(relativeNexusPos, Blocks.STONE.defaultBlockState());
        BlockPos nexusPos = helper.absolutePos(relativeNexusPos);

        TerritoryRegionMap regionMap = new TerritoryRegionMap();
        regionMap.rebuild(helper.getLevel(), territory, nexusPos);

        boolean[] loggedDump = {false};

        helper.succeedWhen(() -> {
            check(!regionMap.isCalculating(), "region map still calculating");

            List<Region> regions = regionMap.getRegionIndex().getRegions();
            check(regions.size() == 2,
                    "expected exactly 2 regions (main floor + elevated platform), found " + regions.size());
            check(regionMap.getRegionGraph().getAllConnectors().size() == 1,
                    "expected exactly 1 chained connector, found "
                            + regionMap.getRegionGraph().getAllConnectors().size());

            RegionConnector connector = regionMap.getRegionGraph().getAllConnectors().get(0);
            int rootId = regionMap.getRouteTree().getRootRegionId();
            int childId = connector.other(rootId);
            Integer childsParent = regionMap.getRouteTree().getParentRegion(childId);

            if (!loggedDump[0]) {
                for (Region region : regions) {
                    LOGGER.info("[Skavenblight][test] region {}: min={} max={} cells={}",
                            region.getId(), region.getMin(), region.getMax(), region.cellCount());
                }
                LOGGER.info("[Skavenblight][test] rootId={} childId={} childsParent={} connector region{}<->region{}",
                        rootId, childId, childsParent, connector.regionA(), connector.regionB());
            }

            // Geometry proof (see method javadoc): the platform, which contains the nexus, must be
            // the root/parent - if this ever fails, the inverted-scan-order trick stopped working
            // and every assertion below would be proving nothing.
            check(rootId != childId, "root and child must be different regions");
            check(childsParent != null && childsParent == rootId,
                    "expected the platform (root) to be the child's parent - geometry didn't invert as intended");

            boolean anyCellResolvedToParent = false;
            for (BlockPos step : connector.projectTowardA().getInstructions().keySet()) {
                Integer resolvedRegion = regionMap.getRegionIndex().regionIdAt(step);
                check(resolvedRegion != null,
                        "connector cell " + step.toShortString() + " isn't claimed by any region");

                if (!loggedDump[0]) {
                    LOGGER.info("[Skavenblight][test] connector cell {} -> region {}", step.toShortString(), resolvedRegion);
                }

                if (resolvedRegion == rootId) {
                    anyCellResolvedToParent = true;
                }

                SiegeNode instruction = regionMap.getRegionFlowFieldFor(step).getNextSiegeNode(helper.getLevel(), step);
                check(instruction != null,
                        "connector cell " + step.toShortString() + " resolved to region " + resolvedRegion
                                + " but got no flow-field instruction - a mob standing there would be stuck");
            }
            loggedDump[0] = true;

            // Confirms the tie-break actually landed on the PARENT for at least one cell -
            // without this, the test could pass vacuously if every shared cell happened to
            // resolve to the child instead, which wouldn't exercise Step 0b's fix at all.
            check(anyCellResolvedToParent,
                    "no connector cell resolved to the parent region (id " + rootId + ") - the "
                            + "tie-break/geometry didn't actually land on the parent, so this test isn't "
                            + "exercising the bug it targets");
        });
    }

    /**
     * Task 9 Step 0c: {@code recomputeDirtyRegions}'s non-topology-changed fast path replaces a
     * dirty region wholesale with a fresh ordinary flood-fill result
     * ({@code rescanned.get(0).withId(regionId)}), which by construction can never include a
     * connector's {@code addCell}-claimed cells (they aren't flood-fill-reachable from the
     * region's interior - that's the whole reason task-8 needed {@code addCell} in the first
     * place). The fix (see {@code reclaimConnectorCells}) re-adds them right after the rescan.
     * That fast path is NOT exercised by any GameTest in this class - this test's own geometry
     * (a single unrelated block change, never closing any connector's gap) cannot reach it, for
     * the general reason traced below; the fix is verified by code inspection only for THIS
     * geometry. **Narrowed per a later finding (Task 9 Steps 1-5 - see task-9-report.md's
     * fix-round and {@code reclaimConnectorCells}'s own updated javadoc): the fast path IS
     * reachable, and reaches a real, distinct, unfixed bug (duplicate overlapping Region objects,
     * a stale connector graph, one region silently orphaned) at the exact moment a connector's
     * gap fully closes, if that closing dirty batch contains both endpoint region ids - which
     * {@code testRepeatedConnectorCompletionsDontExplodeRebuildCount} exercises and confirms.**
     * THIS test verifies something adjacent but distinct, discovered while writing it.
     *
     * <p><b>This geometry cannot reach the fast path at all - it always full-rebuilds instead,
     * exactly like {@link #testDirtyRegionBatchProducesOneCoherentFinalIndex}. The cause is NOT
     * merely this class's single-chunk-territory convention (see the class javadoc's "territory
     * wider than one chunk" bullet) - it is a general property of {@code Region.addCell} plus
     * {@code recomputeDirtyRegions}'s {@code localBounds} construction, confirmed by tracing
     * {@code SiegeLineTracer.trace}: a completed trace's {@code orderedSteps} always ends with
     * {@code endPos} itself (added to {@code orderedSteps} BEFORE the walkable-terrain check that
     * returns it), and {@code RegionGraph.registerConnector} calls
     * {@code fromRegion.addCell(step.pos())} for every step including that last one - so
     * {@code fromRegion}'s bounding box is UNCONDITIONALLY expanded (via {@code addCell}'s
     * unconditional {@code expandBounds}) to include the exact chunk containing {@code toRegion}'s
     * own pre-existing natural landing cell, for every connector, regardless of distance or which
     * chunk either region's own natural footprint occupies. Since {@code localBounds} is the full
     * rectangular hull from a region's min to max chunk (not just chunks its natural cells occupy)
     * and {@code RegionScanner.scan} always sweeps that hull's FULL height, any dirty rescan of
     * either endpoint region therefore always rediscovers the other endpoint's landing cell as a
     * separate component ({@code rescanned.size() &gt;= 2}) - the TOPOLOGY-CHANGED branch, not the
     * fast path - FOR AS LONG AS THE GAP REMAINS GENUINELY OPEN, which is always true for this
     * test's own one-off unrelated-block-change geometry. This applies symmetrically to the OTHER
     * endpoint too (the first traced step is "the first position past the anchor," pulling that
     * region's own bbox back toward THIS region's boundary cell). The {@code getGeneration()}
     * assertion below pins this specific run's outcome down as an executable fact. **Narrowed per
     * a later finding (Task 9 Steps 1-5): the fast path is NOT unreachable in general - once a
     * connector's gap fully closes (not exercised by this test's own geometry), the completing
     * dirty rescan finds exactly 1 piece and takes the fast path instead, which - when the closing
     * batch contains both endpoint ids, as {@code testRepeatedConnectorCompletionsDontExplodeRebuildCount}
     * confirms it deterministically does for that test's geometry - exposes a real, distinct,
     * unfixed bug. See task-9-report.md's Steps 1-5 fix-round and {@code reclaimConnectorCells}'s
     * own updated javadoc for the full trace and evidence.</b>
     *
     * <p>What this test DOES prove, which is still new/genuine coverage: extends
     * {@link #testLongConnectorCellsAreNeverOrphanedFromLookup}'s exact geometry (unmodified -
     * nexus stays on the main floor) with a REAL {@code tick()}-driven dirty-region recompute
     * (task-8's own test never calls {@code tick()} at all, only {@code rebuild()}) - after an
     * unrelated block change is reported near the main floor's interior and the resulting full
     * rebuild completes, every connector cell still resolves correctly, exactly as it did after
     * the very first rebuild. That's real regression coverage against the topology-changed path
     * regressing, even though it says nothing about the fast path Step 0c actually targets.
     */
    @GameTest(template = "pathing_test", timeoutTicks = 1200, skyAccess = true)
    public static void testConnectorCellsSurviveADirtyRegionRescan(GameTestHelper helper) {
        ChunkAnchor anchor = anchorChunkFor(helper);
        Set<ChunkPos> territory = Set.of(anchor.chunk());
        int baseX = anchor.baseX();
        int baseZ = anchor.baseZ();

        int shaftLocalX = 0;
        int shaftLocalZ = 8;

        helper.setBlock(new BlockPos(baseX + shaftLocalX, 41, baseZ + shaftLocalZ), Blocks.STONE.defaultBlockState());

        BlockPos relativeNexusPos = new BlockPos(baseX + 4, 1, baseZ + 4);
        helper.setBlock(relativeNexusPos, Blocks.STONE.defaultBlockState());
        BlockPos nexusPos = helper.absolutePos(relativeNexusPos);

        // Well inside the main floor's own interior, far from the shaft (local x=0) and the nexus
        // marker (local x=4,z=4) - an unrelated position that should still resolve cleanly to the
        // main floor's region both before and after whatever recompute it provokes (see method
        // javadoc for why that recompute is always a full rebuild in this geometry, not the fast
        // path the method name suggests).
        BlockPos unrelatedPos = helper.absolutePos(new BlockPos(baseX + 10, 2, baseZ + 10));

        TerritoryRegionMap regionMap = new TerritoryRegionMap();
        regionMap.rebuild(helper.getLevel(), territory, nexusPos);

        RegionIndex[] indexBeforeChange = {null};
        long[] generationBeforeChange = {-1};
        boolean[] changeReported = {false};
        boolean[] loggedDump = {false};

        helper.succeedWhen(() -> {
            regionMap.tick(helper.getLevel());
            check(!regionMap.isCalculating(), "region map still calculating");

            if (indexBeforeChange[0] == null) {
                // First settle: capture the post-rebuild index/generation and fire the unrelated
                // block change that should provoke exactly one dirty-region recompute.
                indexBeforeChange[0] = regionMap.getRegionIndex();
                generationBeforeChange[0] = regionMap.getGeneration();
                regionMap.onBlockChanged(unrelatedPos);
                changeReported[0] = true;
                check(false, "waiting for the dirty recompute to run");
            }

            check(changeReported[0], "unrelated block change was never reported");
            check(regionMap.getRegionIndex() != indexBeforeChange[0],
                    "getRegionIndex() is still the SAME instance as before the block change - the dirty "
                            + "recompute hasn't run yet (or never will)");

            // Pins the method javadoc's caveat as an executable claim: this geometry always lands
            // on the topology-changed/full-rebuild branch (generation bumps), never the
            // non-topology-changed fast path Step 0c actually targets.
            check(regionMap.getGeneration() > generationBeforeChange[0],
                    "expected the dirty recompute to trigger a full rebuild (generation bump) - if "
                            + "this ever fails, the fast path became reachable and this test's javadoc "
                            + "caveat is stale");

            List<Region> regions = regionMap.getRegionIndex().getRegions();
            check(regions.size() == 2,
                    "expected still exactly 2 regions after the recompute (no real topology change to "
                            + "the SHAPE of either region, just a full rebuild that reconstructs the same "
                            + "partition), found " + regions.size());

            RegionConnector connector = regionMap.getRegionGraph().getAllConnectors().get(0);

            if (!loggedDump[0]) {
                for (Region region : regions) {
                    LOGGER.info("[Skavenblight][test] post-rescan region {}: min={} max={} cells={}",
                            region.getId(), region.getMin(), region.getMax(), region.cellCount());
                }
            }

            for (BlockPos step : connector.projectTowardA().getInstructions().keySet()) {
                Integer resolvedRegion = regionMap.getRegionIndex().regionIdAt(step);
                check(resolvedRegion != null,
                        "connector cell " + step.toShortString() + " was dropped by the dirty rescan - a "
                                + "mob standing there would be orphaned");
                if (!loggedDump[0]) {
                    LOGGER.info("[Skavenblight][test] post-rescan connector cell {} -> region {}", step.toShortString(), resolvedRegion);
                }
            }
            for (BlockPos step : connector.projectTowardB().getInstructions().keySet()) {
                check(regionMap.getRegionIndex().regionIdAt(step) != null,
                        "connector cell " + step.toShortString() + " was dropped by the dirty rescan - a "
                                + "mob standing there would be orphaned");
            }
            loggedDump[0] = true;
        });
    }

    // Task 9 characterization test constants - see the test method's own javadoc below for what
    // each governs. Declared ABOVE the javadoc (rather than between it and the method, where a
    // {@code @link}-to-self would silently attach the javadoc to the wrong element and leave the
    // test method with none - javac won't flag this) so the javadoc block below documents the
    // method it immediately precedes.
    private static final int FILL_COLUMN_COUNT = 6;
    // 100 ticks * ~2.9ms/tick (measured empirically in this environment - see method javadoc) =>
    // ~290ms apart; comfortably more than RECALC_COOLDOWN_TICKS's 80-tick minimum. Total span for
    // FILL_COLUMN_COUNT=6 placements = 500 ticks =~ 1450ms, comfortably under
    // TOPOLOGY_REBUILD_COOLDOWN_MS's 2000ms, so once Step 4's debounce exists, all of them fall
    // inside ONE debounce window.
    private static final long FILL_COLUMN_SPACING_TICKS = 100L;
    // How long (real ms) to wait after the LAST placement before reading final counts - must
    // exceed TerritoryRegionMap.TOPOLOGY_REBUILD_COOLDOWN_MS (2000ms, not otherwise visible from
    // this package) so any still-pending re-queued region gets its last chance to be picked up.
    private static final long POST_FILL_SETTLE_MS = 2500L;

    /**
     * Task 9 Steps 1-4: characterizes how many full territory rebuilds a sustained siege's
     * connector-completion traffic produces, then (Step 3 having confirmed the naive answer is
     * bad) proves Step 4's debounce actually bounds it.
     *
     * <p>Geometry: {@link #testTwoDisconnectedRegionsGetOneConnector}'s trench-split layout,
     * widened to {@link #FILL_COLUMN_COUNT} columns (see below for why) - a gap splitting the
     * chunk into a near side and far side, joined by the one connector {@code RegionGraph.build}
     * finds across it, with the nexus at local ({@code baseX+1}, helper y=2, {@code baseZ+8}).
     * After the initial {@code rebuild()} is issued, this simulates a rat filling the trench in
     * one column at a time - as {@code BuildFlowFieldGoal}'s real construction would, reporting
     * each placement via {@code onBlockChanged} the same way
     * {@code RegionFlowField.forceRecalculation} does (Task 3) - restoring the floor block
     * (helper y=1) at local x=6, then 7, 8, ... up through {@code 6 + FILL_COLUMN_COUNT - 1}, all
     * at the same z row (local z=8, the nexus/probe row from
     * {@link #testTwoDisconnectedRegionsGetOneConnector}).
     *
     * <p><b>Why the brief's literal snippet for this test could not be used as written:</b> it (a)
     * used fixed structure-relative coordinates spanning local z=0..31 as ONE territory chunk set
     * ({@code Set.of(new ChunkPos(nexusPos))}) while carving/probing across more than 16 blocks of
     * z - exactly the encasement-padding contamination the class javadoc's "territory wider than
     * one chunk" bullet warns against; and (b) never called
     * {@code regionMap.tick(helper.getLevel())} anywhere. Every other test in this class that
     * drives {@code onBlockChanged} learned (see
     * {@link #testDirtyRegionBatchProducesOneCoherentFinalIndex}'s javadoc) that this
     * {@code TerritoryRegionMap} is a bare instance nobody else ticks - without an explicit,
     * repeated {@code tick()} call, {@code recomputeDirtyRegions} never runs at all and
     * {@code getTopologyRebuildCount()} would trivially stay 0 forever: a false pass for the wrong
     * reason (a broken test), not the real characterization this task needs. Rewritten here using
     * this class's established {@link #anchorChunkFor}/{@link #minRelY} single-chunk convention
     * and a single {@code succeedWhen} that drives {@code tick()} every check, matching
     * {@code testDirtyRegionBatchProducesOneCoherentFinalIndex}'s own pattern.
     *
     * <p><b>Sequencing: fixed real-time-calibrated tick spacing, not confirm-then-advance.</b> An
     * earlier revision of this test placed each column only after confirming (via
     * {@code getTopologyRebuildCount() + getBlockChangeRebuildCount()} increasing) that the
     * PREVIOUS one's own dirty batch had been picked up and finished, to guard against several
     * placements coalescing into one {@code recomputeDirtyRegions} batch under this GameTest
     * server's concurrent-test thread contention (all 12 of this mod's GameTests run in one
     * process sharing one background executor - the same pool {@code TerritoryRegionMap.tick()}
     * dispatches every async recompute onto - so two fixed-tick placements could get queued before
     * either one's async batch actually finished). That confirm-then-advance approach is
     * fundamentally incompatible with testing Step 4's debounce: once the debounce exists, a
     * DEBOUNCED placement's counters never increase until the full {@code
     * TOPOLOGY_REBUILD_COOLDOWN_MS} (2000ms REAL time) window elapses, so confirm-then-advance just
     * SERIALIZES each of the {@link #FILL_COLUMN_COUNT} placements against its own full debounce
     * wait - reproducing the exact same total rebuild count, just spread out, never letting
     * multiple pending changes coexist long enough for the debounce to actually COALESCE them
     * (confirmed empirically: this failed with "waiting for column 1's dirty recompute to be
     * picked up" once the debounce was added - see task-9-report.md). Fixed by using fixed-tick
     * spacing after all, but CALIBRATED against this environment's actual measured tick rate
     * (~2.9ms/tick empirically, i.e. this GameTest server runs roughly 17x faster than vanilla's
     * 20 ticks/sec - see task-9-report.md's calibration measurement) rather than guessed: each
     * placement is spaced {@link #FILL_COLUMN_SPACING_TICKS} ticks apart (comfortably more than
     * {@code RECALC_COOLDOWN_TICKS}'s 80-tick minimum, so each still has a real chance at its own
     * batch pre-debounce), with the FULL {@link #FILL_COLUMN_COUNT}-placement span calibrated to
     * land comfortably UNDER {@code TOPOLOGY_REBUILD_COOLDOWN_MS} so that, once the debounce
     * exists, all of them fall inside ONE debounce window and coalesce. The final assertion then
     * waits, using {@code System.currentTimeMillis()} directly (matching the debounce's own real
     * wall-clock unit, not a tick-count guess), until at least {@code TOPOLOGY_REBUILD_COOLDOWN_MS}
     * plus a safety margin has elapsed since the LAST placement, guaranteeing any still-pending
     * re-queued region has had its final chance to be picked up before the count is read.
     *
     * <p><b>{@code Config.minimumSettleDelayMs} is temporarily forced to 0</b> (restored once all
     * placements have been issued, before this method's final wait/assertions run) so batch
     * dispatch is gated purely by the deterministic, tick-based {@code RECALC_COOLDOWN_TICKS} (80
     * ticks) instead of ALSO waiting out the real-time settle delay on top of everything else.
     * This is the same plain mutable static {@code DebugPathingCommands} already adjusts at
     * runtime, not a new pattern.
     *
     * <p>Per the Task 9 Step 0c parking note on {@code reclaimConnectorCells} (see
     * task-9-report.md's Steps 0/0b/0c section): once the initial {@code rebuild()} registers the
     * one connector across this trench, EVERY subsequent dirty rescan of either endpoint region -
     * SO LONG AS THE GAP HASN'T FULLY CLOSED YET (a connector, however narrow, still exists) -
     * always finds {@code rescanned.size() >= 2}, taking the topology-changed/full-rebuild branch.
     *
     * <p><b>Trench width discovered empirically to matter here, beyond the brief's original
     * 4-wide figure:</b> {@code recomputeDirtyRegions}'s own check is {@code rescanned.size() !=
     * 1} - which reliably flags a SPLIT (more than 1 piece found) but silently MISSES the specific
     * dirty rescan that completes a MERGE, since from either old region's own perspective that
     * rescan finds exactly 1 piece (itself, now spanning what used to be both sides) - satisfying
     * "no change" by this check's own logic even though a genuine topology change (a merge) just
     * happened. Confirmed empirically (see task-9-report.md): with only 4 columns (the brief's
     * original width), the LAST placement - the one that actually finishes the crossing - always
     * lands on this missed-merge case instead of the topology-changed branch, so only 3 of the 4
     * placements produce a rebuild-count increment, landing EXACTLY AT the brief's own
     * {@code <= 3} threshold rather than past it - a real result, but not a robust demonstration
     * (a one-off timing quirk either way would flip the assertion). Widened to
     * {@link #FILL_COLUMN_COUNT} columns so the {@code FILL_COLUMN_COUNT - 1} "still gapped,
     * connector active" placements alone comfortably exceed the threshold regardless of how the
     * final, structurally-different merge placement resolves.
     *
     * <p><b>Step 4's debounce is expected to be INERT at vanilla tick rate - see
     * task-9-report.md.</b> {@code RECALC_COOLDOWN_TICKS} (80 ticks) already gates every dispatch
     * of {@code recomputeDirtyRegions}, and that method increments {@code topologyRebuildCount} at
     * most once per dispatch (it {@code return}s immediately after the first hit) - so two
     * consecutive topology-changed hits are naturally at least 80 ticks apart, which at vanilla 20
     * ticks/second is 4000ms, already stricter than {@code TOPOLOGY_REBUILD_COOLDOWN_MS}'s 2000ms.
     * This test can only observe the debounce actually suppressing anything because this GameTest
     * server ticks roughly 17x faster than vanilla (measured ~2.9ms/tick - see
     * {@link #FILL_COLUMN_SPACING_TICKS}'s doc), compressing 80 ticks to ~230ms, well inside the
     * 2000ms window. <b>This test is EXPECTED TO FAIL before Step 4's debounce exists, and to pass
     * once it's added</b> - see task-9-report.md's Steps 1-5 section for the actual run output and
     * the production-inertness finding in full.
     */
    // Generous relative to POST_FILL_SETTLE_MS (2500ms real time, the actual gate - see
    // succeedWhen below): this GameTest server's tick-to-real-time ratio is NOT stable across runs
    // (observed empirically - a calibration measurement of ~2.9ms/tick under light load produced
    // real timeouts under heavier concurrent-test load, where ticks apparently run even faster
    // relative to wall-clock time, needing far more of them to reach the same real-ms target -
    // see task-9-report.md and testDirtyRegionBatchProducesOneCoherentFinalIndex's own similar
    // timeoutTicks comment for the same class of issue). Set generously high so the tick BUDGET is
    // never the bottleneck regardless of how fast/slow this particular run's ticks happen to pace
    // against real time - the real gate is POST_FILL_SETTLE_MS itself, not this number.
    @GameTest(template = "pathing_test", timeoutTicks = 40000, skyAccess = true)
    public static void testRepeatedConnectorCompletionsDontExplodeRebuildCount(GameTestHelper helper) {
        ChunkAnchor anchor = anchorChunkFor(helper);
        Set<ChunkPos> territory = Set.of(anchor.chunk());
        int baseX = anchor.baseX();
        int baseZ = anchor.baseZ();
        int minRelY = minRelY(helper);
        int fillLocalZ = 8;

        // A FILL_COLUMN_COUNT-wide trench (local x 6..(6+FILL_COLUMN_COUNT-1), full local z 0-15) -
        // wider than testTwoDisconnectedRegionsGetOneConnector's original 4-wide trench (see this
        // method's own javadoc for why), carved full-depth (see class javadoc's underside-sliver
        // note). The near side stays at local x 0-5 (6 wide); the far side is pushed out to
        // whatever's left (local x (6+FILL_COLUMN_COUNT)..15), still comfortably wide enough for a
        // real region.
        for (int lx = 6; lx < 6 + FILL_COLUMN_COUNT; lx++) {
            for (int lz = 0; lz <= 15; lz++) {
                for (int y = minRelY; y <= 1; y++) {
                    helper.setBlock(new BlockPos(baseX + lx, y, baseZ + lz), Blocks.AIR.defaultBlockState());
                }
            }
        }

        BlockPos relativeNexusPos = new BlockPos(baseX + 1, 2, baseZ + fillLocalZ);
        helper.setBlock(relativeNexusPos, Blocks.STONE.defaultBlockState());
        BlockPos nexusPos = helper.absolutePos(relativeNexusPos);

        int originalSettleDelayMs = Config.minimumSettleDelayMs;
        Config.minimumSettleDelayMs = 0;

        TerritoryRegionMap regionMap = new TerritoryRegionMap();
        regionMap.rebuild(helper.getLevel(), territory, nexusPos);

        // Places each column's floor block (helper y=1) but reports onBlockChanged against the
        // WALKABLE cell above it (helper y=2), not the block actually placed - matching the
        // convention every other onBlockChanged call in this class already uses (see
        // testConnectorCellsSurviveADirtyRegionRescan's unrelatedPos /
        // testDirtyRegionBatchProducesOneCoherentFinalIndex's nearProbe/farProbe, all
        // walkable-layer positions). tick()'s neighborsAndSelf only checks ORTHOGONAL neighbors of
        // whatever position it's given, all at that SAME Y - for a same-level wall break this
        // correctly lands on the flanking regions' own walkable cells, but for a FLOOR change the
        // newly-walkable cell sits one Y ABOVE the block that actually changed, one diagonal step
        // from the existing region's walkable edge, which no single orthogonal neighbor of the
        // floor block itself reaches. Reporting the walkable cell directly sidesteps that gap: its
        // own west/east neighbor lands exactly on the pre-existing near/far region's edge cell (the
        // first/last column) or on the previous column's own now-claimed cell (every column in
        // between, cascading once the prior column's own rebuild/recompute has absorbed it).
        //
        // See this method's own javadoc for why these are spaced by FILL_COLUMN_SPACING_TICKS
        // fixed ticks rather than confirmed one-at-a-time.
        long[] lastPlacementRealTimeMs = {-1L};
        for (int i = 0; i < FILL_COLUMN_COUNT; i++) {
            int lx = baseX + 6 + i;
            boolean isLast = (i == FILL_COLUMN_COUNT - 1);
            helper.runAfterDelay(FILL_COLUMN_SPACING_TICKS * (i + 1), () -> {
                helper.setBlock(new BlockPos(lx, 1, baseZ + fillLocalZ), Blocks.STONE.defaultBlockState());
                regionMap.onBlockChanged(helper.absolutePos(new BlockPos(lx, 2, baseZ + fillLocalZ)));
                if (isLast) {
                    // Restored here (right after the last placement is issued), not gated behind
                    // the POST_FILL_SETTLE_MS wait below - Config is a JVM-wide static shared with
                    // every other GameTest in this run, and the settle delay isn't needed for
                    // anything past this point anyway, so restoring it as early as possible avoids
                    // leaving 0 in place for the rest of the session if this test times out before
                    // reaching its final assertions (a plain field write, not gated by
                    // succeedWhen's retry loop, so it happens exactly once regardless of outcome).
                    Config.minimumSettleDelayMs = originalSettleDelayMs;
                    lastPlacementRealTimeMs[0] = System.currentTimeMillis();
                }
            });
        }

        helper.succeedWhen(() -> {
            regionMap.tick(helper.getLevel());

            check(lastPlacementRealTimeMs[0] > 0,
                    "waiting for all " + FILL_COLUMN_COUNT + " column placements to be issued");

            // Waits out TOPOLOGY_REBUILD_COOLDOWN_MS (plus margin) using REAL elapsed time,
            // matching the debounce's own wall-clock unit - see method javadoc for why a tick-count
            // guess isn't reliable here. tick() keeps being driven above on every retry, so any
            // region re-queued by the debounce (Step 4) still gets picked up during this wait.
            check(System.currentTimeMillis() - lastPlacementRealTimeMs[0] >= POST_FILL_SETTLE_MS,
                    "waiting for the topology-rebuild cooldown window (if any) to fully elapse after the last placement");

            check(!regionMap.isCalculating(), "region map still calculating");
            LOGGER.info("[Skavenblight][test] final counts after {} column placements: topologyRebuildCount={} blockChangeRebuildCount={}",
                    FILL_COLUMN_COUNT, regionMap.getTopologyRebuildCount(), regionMap.getBlockChangeRebuildCount());

            // Post-review diagnostic: a review of this test's blockChangeRebuildCount=2 result
            // (instead of the 1 the "6th placement completes the merge via the fast path" prose
            // implied) raised a real, distinct hypothesis - see task-9-report.md's fix-round for
            // the full write-up - that the merge-completing batch can carry BOTH the near and far
            // region ids as dirty simultaneously (the closing placement's west neighbor resolves
            // to the already-absorbed near id, its east neighbor to the far region's own native
            // cell), and since the fast-path branch below only `continue`s (no early return, unlike
            // the topology-changed branch), BOTH ids can independently take the fast path in the
            // SAME batch once the gap is fully closed - each re-flooding the identical, now-unified
            // chunk and getting stamped with its OWN id via `withId`, producing two Region objects
            // in `updatedRegions` with fully overlapping cell sets. RegionIndex's last-write-wins
            // per-cell stamping (see its constructor) would then make whichever region processed
            // LAST the only one any position actually resolves to, silently orphaning the other
            // (still present in getRegions(), zero resolvable cells) - while regionGraph/routeTree,
            // untouched by the fast path, keep listing the now-physically-stale connector between
            // them. This directly inspects the post-merge state to confirm or refute that.
            List<Region> finalRegions = regionMap.getRegionIndex().getRegions();
            for (Region r : finalRegions) {
                LOGGER.info("[Skavenblight][test][diagnostic] post-merge region {} min={} max={} cells={}",
                        r.getId(), r.getMin(), r.getMax(), r.cellCount());
            }
            List<RegionConnector> finalConnectors = regionMap.getRegionGraph() != null
                    ? regionMap.getRegionGraph().getAllConnectors() : List.of();
            LOGGER.info("[Skavenblight][test][diagnostic] post-merge connector count={}", finalConnectors.size());

            // Probes the ORIGINAL near-side interior (local x=1, well inside the pre-fill near
            // region, unrelated to any fill column) and the ORIGINAL far-side interior (local x =
            // 6+FILL_COLUMN_COUNT+1, well inside the pre-fill far region) - two positions that
            // were on opposite sides of the trench before any column was ever filled. Once the
            // trench is fully closed these are physically one connected floor; if the duplicate-
            // region hypothesis is correct, RegionIndex's tie-break resolves BOTH to whichever
            // region's id happened to be processed last in updatedRegions, even though
            // finalRegions.size() still reports >= 2 (the orphaned duplicate never gets removed
            // from the list, only masked from lookups).
            BlockPos nearProbe = helper.absolutePos(new BlockPos(baseX + 1, 2, baseZ + fillLocalZ));
            BlockPos farProbe = helper.absolutePos(new BlockPos(baseX + 6 + FILL_COLUMN_COUNT + 1, 2, baseZ + fillLocalZ));
            Integer nearProbeId = regionMap.getRegionIndex().regionIdAt(nearProbe);
            Integer farProbeId = regionMap.getRegionIndex().regionIdAt(farProbe);
            LOGGER.info("[Skavenblight][test][diagnostic] post-merge nearProbe -> region {}, farProbe -> region {}, "
                            + "region count={}, blockChangeRebuildCount={}",
                    nearProbeId, farProbeId, finalRegions.size(), regionMap.getBlockChangeRebuildCount());

            check(regionMap.getTopologyRebuildCount() <= 3,
                    "filling in one " + FILL_COLUMN_COUNT + "-block-wide connector triggered "
                            + regionMap.getTopologyRebuildCount()
                            + " full topology rebuilds - expected at most a handful, not one per block");
        });
    }
}
