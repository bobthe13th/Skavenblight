package org.ratden.skavenblight.ai.pathing.nbt;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.ratden.skavenblight.ai.pathing.PathAction;
import org.ratden.skavenblight.ai.pathing.PlannedStep;

import java.util.ArrayList;
import java.util.List;

/**
 * NBT read/write for SiegeProjectSnapshot. BlockPos is stored as a single packed long (this
 * codebase's own established convention - see WarpFluxGridManager - not NbtUtils/Tag round-tripping),
 * PathAction/Direction as their plain enum names.
 */
public final class SiegeProjectNbtCodec {

    private static final String PROJECT_ID = "projectId";
    private static final String NETWORK_ID = "networkId";
    private static final String ENTRY_POS = "entryPos";
    private static final String EXPECTED_ENTRY_COST = "expectedEntryCost";
    private static final String EXIT_POS = "exitPos";
    private static final String BUILD_ORDER = "buildOrder";
    private static final String WIDEN_ANCHOR = "widenAnchor";
    private static final String WIDTH = "width";
    private static final String ACCUMULATED_WORK = "accumulatedWork";
    private static final String LAST_TICKED_GAME_TIME = "lastTickedGameTime";

    private static final String STEP_POS = "pos";
    private static final String STEP_ACTION = "action";
    private static final String STEP_FACING = "facing";

    private SiegeProjectNbtCodec() {
    }

    public static CompoundTag write(SiegeProjectSnapshot snapshot) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(PROJECT_ID, snapshot.projectId());
        tag.putUUID(NETWORK_ID, snapshot.networkId());
        tag.putLong(ENTRY_POS, snapshot.entryPos().asLong());
        tag.putInt(EXPECTED_ENTRY_COST, snapshot.expectedEntryCost());
        if (snapshot.exitPos() != null) {
            tag.putLong(EXIT_POS, snapshot.exitPos().asLong());
        }
        tag.put(BUILD_ORDER, writeBuildOrder(snapshot.buildOrder()));
        tag.putLong(WIDEN_ANCHOR, snapshot.widenAnchor().asLong());
        tag.putInt(WIDTH, snapshot.width());
        tag.putDouble(ACCUMULATED_WORK, snapshot.accumulatedWork());
        tag.putLong(LAST_TICKED_GAME_TIME, snapshot.lastTickedGameTime());
        return tag;
    }

    public static SiegeProjectSnapshot read(CompoundTag tag) {
        return new SiegeProjectSnapshot(
                tag.getUUID(PROJECT_ID),
                tag.getUUID(NETWORK_ID),
                BlockPos.of(tag.getLong(ENTRY_POS)),
                tag.getInt(EXPECTED_ENTRY_COST),
                tag.contains(EXIT_POS) ? BlockPos.of(tag.getLong(EXIT_POS)) : null,
                readBuildOrder(tag.getList(BUILD_ORDER, net.minecraft.nbt.Tag.TAG_COMPOUND)),
                BlockPos.of(tag.getLong(WIDEN_ANCHOR)),
                tag.getInt(WIDTH),
                tag.getDouble(ACCUMULATED_WORK),
                tag.getLong(LAST_TICKED_GAME_TIME));
    }

    private static ListTag writeBuildOrder(List<PlannedStep> buildOrder) {
        ListTag listTag = new ListTag();
        for (PlannedStep step : buildOrder) {
            CompoundTag stepTag = new CompoundTag();
            stepTag.putLong(STEP_POS, step.pos().asLong());
            stepTag.putString(STEP_ACTION, step.action().name());
            stepTag.putString(STEP_FACING, step.facing().name());
            listTag.add(stepTag);
        }
        return listTag;
    }

    private static List<PlannedStep> readBuildOrder(ListTag listTag) {
        List<PlannedStep> buildOrder = new ArrayList<>(listTag.size());
        for (int i = 0; i < listTag.size(); i++) {
            CompoundTag stepTag = listTag.getCompound(i);
            buildOrder.add(new PlannedStep(
                    BlockPos.of(stepTag.getLong(STEP_POS)),
                    PathAction.valueOf(stepTag.getString(STEP_ACTION)),
                    Direction.valueOf(stepTag.getString(STEP_FACING))));
        }
        return List.copyOf(buildOrder);
    }
}
