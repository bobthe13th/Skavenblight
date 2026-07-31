package org.ratden.skavenblight.entity.custom.wolfCat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.ratden.skavenblight.event.skavenIncursion.leadership.IncursionOwnedMob;
import org.ratden.skavenblight.event.skavenIncursion.runtime.entity.AttachedMobAssignmentEntity;

import java.util.UUID;

/**
 * Temporary Cat-based incursion mob used by the planning and runtime tests.
 *
 * Incursion and leadership identities are persisted directly on the entity so
 * Minecraft carries them with the entity through chunk saves and world
 * restarts.
 *
 * Attached-mob assignment identity is also entity-owned. This allows a loaded
 * entity to announce which planned attachment it fulfils without requiring
 * continuous central position tracking.
 */
public class WolfCat
        extends Cat
        implements IncursionOwnedMob,
        AttachedMobAssignmentEntity {

    private static final String SCENARIO_ID_TAG =
            "scenario_id";

    private static final String SOURCE_ID_TAG =
            "source_id";

    private static final String PACK_ID_TAG =
            "pack_id";

    private static final String CLAW_ID_TAG =
            "claw_id";

    private static final String FANG_ID_TAG =
            "fang_id";

    private static final String VERMIN_TIDE_ID_TAG =
            "vermintide_id";

    private static final String ATTACHED_MOB_ASSIGNMENT_ID_TAG =
            "attached_mob_assignment_id";

    private static final String TEST_PACK_LEADER_TAG =
            "test_pack_leader";

    private UUID scenarioId;
    private UUID sourceId;

    private UUID packId;
    private UUID clawId;
    private UUID fangId;
    private UUID vermintideId;

    /**
     * Stable immutable-plan identity for the attached assignment fulfilled by
     * this entity.
     *
     * Ordinary Wolf Cats leave this null.
     */
    private UUID attachedMobAssignmentId;

    /*
     * Testing-only marker.
     *
     * This is deliberately specific to WolfCat rather than being added to
     * IncursionOwnedMob, because the real Pack Leader system has not yet
     * been designed.
     */
    private boolean testPackLeader;

    public WolfCat(
            EntityType<? extends Cat> type,
            Level level
    ) {
        super(
                type,
                level
        );

        this.attachedMobAssignmentId =
                null;

        this.testPackLeader =
                false;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Cat.createAttributes()
                .add(
                        Attributes.MAX_HEALTH,
                        10.0D
                )
                .add(
                        Attributes.MOVEMENT_SPEED,
                        0.45D
                )
                .add(
                        Attributes.ATTACK_DAMAGE,
                        3.0D
                )
                .add(
                        Attributes.FOLLOW_RANGE,
                        40.0D
                )
                .add(
                        Attributes.SCALE,
                        1.0D
                );
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(
                1,
                new FloatGoal(
                        this
                )
        );

        this.goalSelector.addGoal(
                2,
                new MeleeAttackGoal(
                        this,
                        1.25D,
                        false
                )
        );

        this.goalSelector.addGoal(
                3,
                new WolfCatAttackBlockGoal(
                        this
                )
        );

        this.goalSelector.addGoal(
                4,
                new BreakNexusObstructionGoal(
                        this,
                        1.15D
                )
        );

        this.goalSelector.addGoal(
                5,
                new MoveToActiveNexusGoal(
                        this,
                        1.15D
                )
        );

        this.goalSelector.addGoal(
                6,
                new WaterAvoidingRandomStrollGoal(
                        this,
                        0.9D
                )
        );

        this.goalSelector.addGoal(
                7,
                new LookAtPlayerGoal(
                        this,
                        Player.class,
                        8.0F
                )
        );

        this.goalSelector.addGoal(
                8,
                new RandomLookAroundGoal(
                        this
                )
        );

        this.targetSelector.addGoal(
                1,
                new NearestAttackableTargetGoal<>(
                        this,
                        Player.class,
                        true
                )
        );
    }

    public void setTestPackLeader(
            boolean testPackLeader
    ) {
        this.testPackLeader =
                testPackLeader;
    }

    public boolean isTestPackLeader() {
        return testPackLeader;
    }

    @Override
    public void setScenarioId(
            UUID scenarioId
    ) {
        this.scenarioId =
                scenarioId;
    }

    @Override
    public UUID getScenarioId() {
        return scenarioId;
    }

    @Override
    public void setSourceId(
            UUID sourceId
    ) {
        this.sourceId =
                sourceId;
    }

    @Override
    public UUID getSourceId() {
        return sourceId;
    }

    @Override
    public void setPackId(
            UUID packId
    ) {
        this.packId =
                packId;
    }

    @Override
    public UUID getPackId() {
        return packId;
    }

    @Override
    public void setClawId(
            UUID clawId
    ) {
        this.clawId =
                clawId;
    }

    @Override
    public UUID getClawId() {
        return clawId;
    }

    @Override
    public void setFangId(
            UUID fangId
    ) {
        this.fangId =
                fangId;
    }

    @Override
    public UUID getFangId() {
        return fangId;
    }

    @Override
    public void setVermintideId(
            UUID vermintideId
    ) {
        this.vermintideId =
                vermintideId;
    }

    @Override
    public UUID getVermintideId() {
        return vermintideId;
    }

    @Override
    public UUID getAttachedMobAssignmentId() {
        return attachedMobAssignmentId;
    }

    @Override
    public void setAttachedMobAssignmentId(
            UUID attachedMobAssignmentId
    ) {
        this.attachedMobAssignmentId =
                attachedMobAssignmentId;
    }

    @Override
    public void addAdditionalSaveData(
            CompoundTag tag
    ) {
        super.addAdditionalSaveData(
                tag
        );

        if (scenarioId != null) {
            tag.putUUID(
                    SCENARIO_ID_TAG,
                    scenarioId
            );
        }

        if (sourceId != null) {
            tag.putUUID(
                    SOURCE_ID_TAG,
                    sourceId
            );
        }

        if (packId != null) {
            tag.putUUID(
                    PACK_ID_TAG,
                    packId
            );
        }

        if (clawId != null) {
            tag.putUUID(
                    CLAW_ID_TAG,
                    clawId
            );
        }

        if (fangId != null) {
            tag.putUUID(
                    FANG_ID_TAG,
                    fangId
            );
        }

        if (vermintideId != null) {
            tag.putUUID(
                    VERMIN_TIDE_ID_TAG,
                    vermintideId
            );
        }

        if (attachedMobAssignmentId != null) {
            tag.putUUID(
                    ATTACHED_MOB_ASSIGNMENT_ID_TAG,
                    attachedMobAssignmentId
            );
        }

        tag.putBoolean(
                TEST_PACK_LEADER_TAG,
                testPackLeader
        );
    }

    @Override
    public void readAdditionalSaveData(
            CompoundTag tag
    ) {
        super.readAdditionalSaveData(
                tag
        );

        scenarioId =
                tag.hasUUID(
                        SCENARIO_ID_TAG
                )
                        ? tag.getUUID(
                        SCENARIO_ID_TAG
                )
                        : null;

        sourceId =
                tag.hasUUID(
                        SOURCE_ID_TAG
                )
                        ? tag.getUUID(
                        SOURCE_ID_TAG
                )
                        : null;

        packId =
                tag.hasUUID(
                        PACK_ID_TAG
                )
                        ? tag.getUUID(
                        PACK_ID_TAG
                )
                        : null;

        clawId =
                tag.hasUUID(
                        CLAW_ID_TAG
                )
                        ? tag.getUUID(
                        CLAW_ID_TAG
                )
                        : null;

        fangId =
                tag.hasUUID(
                        FANG_ID_TAG
                )
                        ? tag.getUUID(
                        FANG_ID_TAG
                )
                        : null;

        vermintideId =
                tag.hasUUID(
                        VERMIN_TIDE_ID_TAG
                )
                        ? tag.getUUID(
                        VERMIN_TIDE_ID_TAG
                )
                        : null;

        attachedMobAssignmentId =
                tag.hasUUID(
                        ATTACHED_MOB_ASSIGNMENT_ID_TAG
                )
                        ? tag.getUUID(
                        ATTACHED_MOB_ASSIGNMENT_ID_TAG
                )
                        : null;

        testPackLeader =
                tag.getBoolean(
                        TEST_PACK_LEADER_TAG
                );
    }
}