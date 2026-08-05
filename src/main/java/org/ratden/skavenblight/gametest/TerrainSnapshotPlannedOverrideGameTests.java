package org.ratden.skavenblight.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.ai.pathing.TerrainSnapshot;

import java.util.Set;

import static org.ratden.skavenblight.gametest.PathingRegionGameTests.check;

@GameTestHolder(Skavenblight.MODID)
@PrefixGameTestTemplate(false)
public class TerrainSnapshotPlannedOverrideGameTests {

    @GameTest(template = "pathing_test_giant", timeoutTicks = 40)
    public static void overriddenPositionReportsTheOverrideBlocksDestroySpeedNotTheRealBlocksDestroySpeed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos overriddenRelative = new BlockPos(5, 2, 5);
        BlockPos ordinaryRelative = new BlockPos(6, 2, 5);
        BlockPos overriddenAbsolute = helper.absolutePos(overriddenRelative);
        BlockPos ordinaryAbsolute = helper.absolutePos(ordinaryRelative);

        // Real bedrock at both positions - both would report destroySpeed < 0 with no override.
        helper.setBlock(overriddenRelative, Blocks.BEDROCK.defaultBlockState());
        helper.setBlock(ordinaryRelative, Blocks.BEDROCK.defaultBlockState());

        BlockState overrideAir = Blocks.AIR.defaultBlockState();
        java.util.function.Function<BlockPos, BlockState> override =
                pos -> pos.equals(overriddenAbsolute) ? overrideAir : null;

        TerrainSnapshot.RefreshResult result = TerrainSnapshot.refresh(level, null,
                Set.of(new ChunkPos(overriddenAbsolute)), Set.of(new ChunkPos(overriddenAbsolute)),
                level.getMinBuildHeight(), level.getMaxBuildHeight(), Integer.MAX_VALUE, override);
        TerrainSnapshot snapshot = result.snapshot();

        helper.succeedWhen(() -> {
            check(snapshot.getDestroySpeed(overriddenAbsolute) >= 0,
                    "overridden position must report the OVERRIDE block's destroySpeed (air, >= 0), not real bedrock's (-1)");
            check(snapshot.getDestroySpeed(ordinaryAbsolute) < 0,
                    "a position with no override must still report the real bedrock's destroySpeed unchanged");
        });
    }
}
