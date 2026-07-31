package org.ratden.skavenblight.block.entity.debug;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.ratden.skavenblight.block.custom.debug.DebugIncursionAnchorBlock;
import org.ratden.skavenblight.block.entity.ModBlockEntities;
import org.ratden.skavenblight.event.skavenIncursion.planning.front.FrontPlacementPattern;
import org.ratden.skavenblight.event.skavenIncursion.planning.source.SourceRole;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Persistent debug metadata for either a front anchor or an additional
 * source-group anchor.
 *
 * The block entity stores a snapshot of planning identities and world
 * positions required for inspection and visualisation. It does not own any
 * of the represented planning or runtime objects.
 *
 * No leadership IDs are generated here. Leadership information can be added
 * later only when the corresponding planned leadership groups actually
 * exist.
 */
public class DebugIncursionAnchorEntity extends BlockEntity {

    private final DebugAnchorType anchorType;

    private UUID incursionId;

    private UUID frontId;
    private int frontIndex;

    private FrontPlacementPattern frontPlacementPattern;
    private double threatShare;
    private double complexityShare;
    private boolean dominant;

    private UUID sourceGroupPlacementId;
    private int sourceGroupIndex;
    private SourceRole sourceRole;

    private int sourceGroupLoad;
    private int maximumSourceGroupLoad;

    private List<DebugAnchorSourceLink> sourceLinks;

    public DebugIncursionAnchorEntity(
            BlockPos pos,
            BlockState blockState
    ) {
        super(
                ModBlockEntities.DEBUG_INCURSION_ANCHOR.get(),
                pos,
                blockState
        );

        this.anchorType =
                resolveAnchorType(
                        blockState
                );

        clearDebugData();
    }

    public DebugAnchorType getAnchorType() {
        return anchorType;
    }

    public boolean isFrontAnchor() {
        return anchorType
                == DebugAnchorType.FRONT;
    }

    public boolean isSourceGroupAnchor() {
        return anchorType
                == DebugAnchorType.SOURCE_GROUP;
    }

    /**
     * Populates this marker from one validated physical source-group plan.
     *
     * For a FRONT anchor, sourceGroupIndex must be zero because the front
     * anchor represents the first physical source group.
     *
     * For a SOURCE_GROUP anchor, sourceGroupIndex must be greater than zero
     * because separate marker blocks are only created for additional groups.
     */
    public void initialise(
            UUID incursionId,
            UUID frontId,
            int frontIndex,
            FrontPlacementPattern frontPlacementPattern,
            double threatShare,
            double complexityShare,
            boolean dominant,
            UUID sourceGroupPlacementId,
            int sourceGroupIndex,
            SourceRole sourceRole,
            int sourceGroupLoad,
            int maximumSourceGroupLoad,
            List<DebugAnchorSourceLink> sourceLinks
    ) {
        if (incursionId == null) {
            throw new IllegalArgumentException(
                    "Debug anchor incursion ID cannot be null."
            );
        }

        if (frontId == null) {
            throw new IllegalArgumentException(
                    "Debug anchor front ID cannot be null."
            );
        }

        if (frontIndex < 0) {
            throw new IllegalArgumentException(
                    "Debug anchor front index cannot be negative."
            );
        }

        if (frontPlacementPattern == null) {
            throw new IllegalArgumentException(
                    "Debug anchor front placement pattern cannot be null."
            );
        }

        validateShare(
                threatShare,
                "Threat"
        );

        validateShare(
                complexityShare,
                "Complexity"
        );

        if (sourceGroupPlacementId == null) {
            throw new IllegalArgumentException(
                    "Debug anchor source-group placement ID cannot be null."
            );
        }

        validateSourceGroupIndex(
                sourceGroupIndex
        );

        if (sourceRole == null) {
            throw new IllegalArgumentException(
                    "Debug anchor source role cannot be null."
            );
        }

        if (sourceGroupLoad <= 0) {
            throw new IllegalArgumentException(
                    "Debug anchor source-group load must be greater than zero."
            );
        }

        if (maximumSourceGroupLoad <= 0) {
            throw new IllegalArgumentException(
                    "Debug anchor maximum source-group load must be greater "
                            + "than zero."
            );
        }

        if (sourceGroupLoad > maximumSourceGroupLoad) {
            throw new IllegalArgumentException(
                    "Debug anchor source-group load "
                            + sourceGroupLoad
                            + " exceeds maximum load "
                            + maximumSourceGroupLoad
                            + "."
            );
        }

        List<DebugAnchorSourceLink> copiedSourceLinks =
                copyAndValidateSourceLinks(
                        sourceLinks
                );

        this.incursionId =
                incursionId;

        this.frontId =
                frontId;

        this.frontIndex =
                frontIndex;

        this.frontPlacementPattern =
                frontPlacementPattern;

        this.threatShare =
                threatShare;

        this.complexityShare =
                complexityShare;

        this.dominant =
                dominant;

        this.sourceGroupPlacementId =
                sourceGroupPlacementId;

        this.sourceGroupIndex =
                sourceGroupIndex;

        this.sourceRole =
                sourceRole;

        this.sourceGroupLoad =
                sourceGroupLoad;

        this.maximumSourceGroupLoad =
                maximumSourceGroupLoad;

        this.sourceLinks =
                copiedSourceLinks;

        setChanged();
    }

    public boolean isInitialised() {
        return incursionId != null
                && frontId != null
                && frontIndex >= 0
                && frontPlacementPattern != null
                && sourceGroupPlacementId != null
                && sourceGroupIndex >= 0
                && sourceRole != null
                && sourceGroupLoad > 0
                && maximumSourceGroupLoad > 0
                && !sourceLinks.isEmpty();
    }

    public UUID getIncursionId() {
        return incursionId;
    }

    public UUID getFrontId() {
        return frontId;
    }

    /**
     * Returns the zero-based front index.
     */
    public int getFrontIndex() {
        return frontIndex;
    }

    public FrontPlacementPattern getFrontPlacementPattern() {
        return frontPlacementPattern;
    }

    public double getThreatShare() {
        return threatShare;
    }

    public double getComplexityShare() {
        return complexityShare;
    }

    public boolean isDominant() {
        return dominant;
    }

    public UUID getSourceGroupPlacementId() {
        return sourceGroupPlacementId;
    }

    /**
     * Returns the zero-based physical source-group index within the front.
     *
     * Index zero is represented by the front anchor. Additional source-group
     * anchors use indexes one and above.
     */
    public int getSourceGroupIndex() {
        return sourceGroupIndex;
    }

    public SourceRole getSourceRole() {
        return sourceRole;
    }

    public int getSourceGroupLoad() {
        return sourceGroupLoad;
    }

    public int getMaximumSourceGroupLoad() {
        return maximumSourceGroupLoad;
    }

    public int getRemainingSourceGroupLoad() {
        return Math.max(
                0,
                maximumSourceGroupLoad
                        - sourceGroupLoad
        );
    }

    public List<DebugAnchorSourceLink> getSourceLinks() {
        return sourceLinks;
    }

    public int getSourceCount() {
        return sourceLinks.size();
    }

    private void validateSourceGroupIndex(
            int sourceGroupIndex
    ) {
        if (sourceGroupIndex < 0) {
            throw new IllegalArgumentException(
                    "Debug source-group index cannot be negative."
            );
        }

        if (isFrontAnchor()
                && sourceGroupIndex != 0) {
            throw new IllegalArgumentException(
                    "A front anchor must represent source-group index zero."
            );
        }

        if (isSourceGroupAnchor()
                && sourceGroupIndex == 0) {
            throw new IllegalArgumentException(
                    "An additional source-group anchor must use an index "
                            + "greater than zero."
            );
        }
    }

    private static void validateShare(
            double share,
            String shareName
    ) {
        if (!Double.isFinite(share)) {
            throw new IllegalArgumentException(
                    shareName
                            + " share must be finite."
            );
        }

        if (share < 0.0D
                || share > 1.0D) {
            throw new IllegalArgumentException(
                    shareName
                            + " share must be between zero and one."
            );
        }
    }

    private static List<DebugAnchorSourceLink>
    copyAndValidateSourceLinks(
            List<DebugAnchorSourceLink> sourceLinks
    ) {
        if (sourceLinks == null
                || sourceLinks.isEmpty()) {
            throw new IllegalArgumentException(
                    "Debug anchor must link to at least one source."
            );
        }

        List<DebugAnchorSourceLink> copiedLinks =
                new ArrayList<>();

        Set<UUID> sourcePlacementIds =
                new HashSet<>();

        Set<BlockPos> sourcePositions =
                new HashSet<>();

        for (DebugAnchorSourceLink sourceLink
                : sourceLinks) {

            if (sourceLink == null) {
                throw new IllegalArgumentException(
                        "Debug source link cannot be null."
                );
            }

            if (!sourcePlacementIds.add(
                    sourceLink.sourcePlacementId()
            )) {
                throw new IllegalArgumentException(
                        "Debug anchor contains duplicate source-placement ID "
                                + sourceLink.sourcePlacementId()
                                + "."
                );
            }

            if (!sourcePositions.add(
                    sourceLink.sourcePos()
            )) {
                throw new IllegalArgumentException(
                        "Debug anchor contains duplicate source position "
                                + sourceLink.sourcePos()
                                + "."
                );
            }

            copiedLinks.add(
                    sourceLink
            );
        }

        return List.copyOf(
                copiedLinks
        );
    }

    private static DebugAnchorType resolveAnchorType(
            BlockState blockState
    ) {
        if (!(blockState.getBlock()
                instanceof DebugIncursionAnchorBlock anchorBlock)) {
            throw new IllegalArgumentException(
                    "Debug incursion anchor entity requires a debug "
                            + "incursion anchor block."
            );
        }

        return anchorBlock.getAnchorType();
    }

    private void clearDebugData() {
        incursionId = null;

        frontId = null;
        frontIndex = -1;

        frontPlacementPattern = null;
        threatShare = 0.0D;
        complexityShare = 0.0D;
        dominant = false;

        sourceGroupPlacementId = null;
        sourceGroupIndex = -1;
        sourceRole = null;

        sourceGroupLoad = 0;
        maximumSourceGroupLoad = 0;

        sourceLinks = List.of();
    }

    @Override
    protected void saveAdditional(
            CompoundTag tag,
            HolderLookup.Provider registries
    ) {
        super.saveAdditional(
                tag,
                registries
        );

        if (incursionId != null) {
            tag.putUUID(
                    "incursion_id",
                    incursionId
            );
        }

        if (frontId != null) {
            tag.putUUID(
                    "front_id",
                    frontId
            );
        }

        tag.putInt(
                "front_index",
                frontIndex
        );

        if (frontPlacementPattern != null) {
            tag.putString(
                    "front_placement_pattern",
                    frontPlacementPattern.name()
            );
        }

        tag.putDouble(
                "threat_share",
                threatShare
        );

        tag.putDouble(
                "complexity_share",
                complexityShare
        );

        tag.putBoolean(
                "dominant",
                dominant
        );

        if (sourceGroupPlacementId != null) {
            tag.putUUID(
                    "source_group_placement_id",
                    sourceGroupPlacementId
            );
        }

        tag.putInt(
                "source_group_index",
                sourceGroupIndex
        );

        if (sourceRole != null) {
            tag.putString(
                    "source_role",
                    sourceRole.name()
            );
        }

        tag.putInt(
                "source_group_load",
                sourceGroupLoad
        );

        tag.putInt(
                "maximum_source_group_load",
                maximumSourceGroupLoad
        );

        ListTag sourceLinkTags =
                new ListTag();

        for (DebugAnchorSourceLink sourceLink
                : sourceLinks) {

            CompoundTag sourceLinkTag =
                    new CompoundTag();

            sourceLinkTag.putUUID(
                    "source_placement_id",
                    sourceLink.sourcePlacementId()
            );

            sourceLinkTag.putLong(
                    "source_pos",
                    sourceLink.sourcePos().asLong()
            );

            sourceLinkTags.add(
                    sourceLinkTag
            );
        }

        tag.put(
                "source_links",
                sourceLinkTags
        );
    }

    @Override
    protected void loadAdditional(
            CompoundTag tag,
            HolderLookup.Provider registries
    ) {
        super.loadAdditional(
                tag,
                registries
        );

        clearDebugData();

        if (tag.hasUUID(
                "incursion_id"
        )) {
            incursionId =
                    tag.getUUID(
                            "incursion_id"
                    );
        }

        if (tag.hasUUID(
                "front_id"
        )) {
            frontId =
                    tag.getUUID(
                            "front_id"
                    );
        }

        frontIndex =
                tag.getInt(
                        "front_index"
                );

        frontPlacementPattern =
                readFrontPlacementPattern(
                        tag
                );

        threatShare =
                tag.getDouble(
                        "threat_share"
                );

        complexityShare =
                tag.getDouble(
                        "complexity_share"
                );

        dominant =
                tag.getBoolean(
                        "dominant"
                );

        if (tag.hasUUID(
                "source_group_placement_id"
        )) {
            sourceGroupPlacementId =
                    tag.getUUID(
                            "source_group_placement_id"
                    );
        }

        sourceGroupIndex =
                tag.getInt(
                        "source_group_index"
                );

        sourceRole =
                readSourceRole(
                        tag
                );

        sourceGroupLoad =
                tag.getInt(
                        "source_group_load"
                );

        maximumSourceGroupLoad =
                tag.getInt(
                        "maximum_source_group_load"
                );

        sourceLinks =
                readSourceLinks(
                        tag
                );
    }

    private static FrontPlacementPattern
    readFrontPlacementPattern(
            CompoundTag tag
    ) {
        if (!tag.contains(
                "front_placement_pattern",
                Tag.TAG_STRING
        )) {
            return null;
        }

        try {
            return FrontPlacementPattern.valueOf(
                    tag.getString(
                            "front_placement_pattern"
                    )
            );
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static SourceRole readSourceRole(
            CompoundTag tag
    ) {
        if (!tag.contains(
                "source_role",
                Tag.TAG_STRING
        )) {
            return null;
        }

        try {
            return SourceRole.valueOf(
                    tag.getString(
                            "source_role"
                    )
            );
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static List<DebugAnchorSourceLink>
    readSourceLinks(
            CompoundTag tag
    ) {
        if (!tag.contains(
                "source_links",
                Tag.TAG_LIST
        )) {
            return List.of();
        }

        ListTag sourceLinkTags =
                tag.getList(
                        "source_links",
                        Tag.TAG_COMPOUND
                );

        List<DebugAnchorSourceLink> loadedLinks =
                new ArrayList<>();

        Set<UUID> loadedPlacementIds =
                new HashSet<>();

        Set<BlockPos> loadedPositions =
                new HashSet<>();

        for (int index = 0;
             index < sourceLinkTags.size();
             index++) {

            CompoundTag sourceLinkTag =
                    sourceLinkTags.getCompound(
                            index
                    );

            if (!sourceLinkTag.hasUUID(
                    "source_placement_id"
            )) {
                continue;
            }

            if (!sourceLinkTag.contains(
                    "source_pos",
                    Tag.TAG_LONG
            )) {
                continue;
            }

            UUID sourcePlacementId =
                    sourceLinkTag.getUUID(
                            "source_placement_id"
                    );

            BlockPos sourcePos =
                    BlockPos.of(
                            sourceLinkTag.getLong(
                                    "source_pos"
                            )
                    );

            if (!loadedPlacementIds.add(
                    sourcePlacementId
            )) {
                continue;
            }

            if (!loadedPositions.add(
                    sourcePos
            )) {
                continue;
            }

            loadedLinks.add(
                    new DebugAnchorSourceLink(
                            sourcePlacementId,
                            sourcePos
                    )
            );
        }

        return List.copyOf(
                loadedLinks
        );
    }
}