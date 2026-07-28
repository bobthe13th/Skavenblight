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
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.ai.pathing.region.Region;
import org.ratden.skavenblight.ai.pathing.region.RegionConnector;
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
}
