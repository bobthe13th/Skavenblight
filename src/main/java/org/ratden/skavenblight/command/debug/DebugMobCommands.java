package org.ratden.skavenblight.command.debug;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.ratden.skavenblight.event.skavenIncursion.action.mob.generic.SpawnWolfCats;
import org.ratden.skavenblight.event.skavenIncursion.action.mob.generic.SpawnWolfRats;
import org.ratden.skavenblight.event.skavenIncursion.leadership.IncursionOwnedMob;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeaderGroup;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeaderGroupType;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeaderRank;
import org.ratden.skavenblight.event.skavenIncursion.leadership.LeadershipRegistry;

import java.util.Optional;
import java.util.UUID;

public class DebugMobCommands {

    private static final double PICK_RANGE = 20.0D;

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("mob")
                .then(Commands.literal("info")
                        .executes(context -> mobInfo(context.getSource())))

                .then(Commands.literal("spawn")
                        .then(Commands.literal("wolf_rat")
                                .executes(context -> spawnWolfRat(
                                        context.getSource(),
                                        1
                                ))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 50))
                                        .executes(context -> spawnWolfRat(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "count")
                                        ))))

                        .then(Commands.literal("wolf_cat")
                                .executes(context -> spawnWolfCat(
                                        context.getSource(),
                                        1
                                ))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 50))
                                        .executes(context -> spawnWolfCat(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "count")
                                        )))));
    }

    private static int mobInfo(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();

            Entity entity = findLookedAtEntity(player);

            if (entity == null) {
                source.sendFailure(Component.literal("No entity found in front of you."));
                return 0;
            }

            if (!(entity instanceof IncursionOwnedMob ownedMob)) {
                source.sendFailure(Component.literal("Target entity is not an incursion-owned mob."));
                return 0;
            }

            source.sendSuccess(
                    () -> Component.literal(
                            "Mob Leadership Info"
                                    + "\nEntity: " + entity.getType()
                                    + "\nEntity UUID: " + entity.getUUID()
                                    + "\nScenario ID: " + ownedMob.getScenarioId()
                                    + "\nSource ID: " + ownedMob.getSourceId()
                                    + "\nVermintide ID: " + ownedMob.getVermintideId()
                                    + "\nFang ID: " + ownedMob.getFangId()
                                    + "\nClaw ID: " + ownedMob.getClawId()
                                    + "\nPack ID: " + ownedMob.getPackId()
                    ),
                    false
            );

            return 1;

        } catch (Exception exception) {
            source.sendFailure(Component.literal("Error: " + exception.getMessage()));
            return 0;
        }
    }

    private static int spawnWolfRat(CommandSourceStack source, int count)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {

        ServerPlayer player = source.getPlayerOrException();

        LeadershipRegistry leadershipRegistry =
                new LeadershipRegistry(UUID.randomUUID());

        LeaderGroup vermintideGroup = leadershipRegistry.createLeaderGroup(
                LeaderGroupType.VERMINTIDE,
                LeaderRank.NONE
        );

        LeaderGroup fangGroup = leadershipRegistry.createLeaderGroup(
                LeaderGroupType.FANG,
                LeaderRank.NONE
        );

        LeaderGroup clawGroup = leadershipRegistry.createLeaderGroup(
                LeaderGroupType.CLAW,
                LeaderRank.NONE
        );

        LeaderGroup packGroup = leadershipRegistry.createLeaderGroup(
                LeaderGroupType.PACK,
                LeaderRank.NONE
        );

        UUID debugSourceId = UUID.randomUUID();

        int spawned = SpawnWolfRats.execute(
                player.serverLevel(),
                player.blockPosition(),
                count,
                leadershipRegistry.createContext(
                        vermintideGroup,
                        fangGroup,
                        clawGroup,
                        packGroup
                ),
                debugSourceId
        ).size();

        source.sendSuccess(
                () -> Component.literal(
                        "Debug spawned " + spawned + " wolf rats."
                                + "\nDebug Source ID: " + debugSourceId
                ),
                false
        );

        return spawned;
    }

    private static int spawnWolfCat(CommandSourceStack source, int count)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {

        ServerPlayer player = source.getPlayerOrException();

        LeadershipRegistry leadershipRegistry =
                new LeadershipRegistry(UUID.randomUUID());

        LeaderGroup vermintideGroup = leadershipRegistry.createLeaderGroup(
                LeaderGroupType.VERMINTIDE,
                LeaderRank.NONE
        );

        LeaderGroup fangGroup = leadershipRegistry.createLeaderGroup(
                LeaderGroupType.FANG,
                LeaderRank.NONE
        );

        LeaderGroup clawGroup = leadershipRegistry.createLeaderGroup(
                LeaderGroupType.CLAW,
                LeaderRank.NONE
        );

        LeaderGroup packGroup = leadershipRegistry.createLeaderGroup(
                LeaderGroupType.PACK,
                LeaderRank.NONE
        );

        UUID debugSourceId = UUID.randomUUID();

        int spawned = SpawnWolfCats.execute(
                player.serverLevel(),
                player.blockPosition(),
                count,
                leadershipRegistry.createContext(
                        vermintideGroup,
                        fangGroup,
                        clawGroup,
                        packGroup
                ),
                debugSourceId
        ).size();

        source.sendSuccess(
                () -> Component.literal(
                        "Debug spawned " + spawned + " wolf cats."
                                + "\nDebug Source ID: " + debugSourceId
                ),
                false
        );

        return spawned;
    }

    private static Entity findLookedAtEntity(ServerPlayer player) {
        Vec3 eyePos = player.getEyePosition();
        Vec3 lookVec = player.getLookAngle();
        Vec3 endPos = eyePos.add(lookVec.scale(PICK_RANGE));

        AABB searchBox = player.getBoundingBox()
                .expandTowards(lookVec.scale(PICK_RANGE))
                .inflate(1.0D);

        Entity closestEntity = null;
        double closestDistance = PICK_RANGE * PICK_RANGE;

        for (Entity entity : player.serverLevel().getEntities(
                player,
                searchBox,
                target -> !target.isSpectator() && target.isPickable()
        )) {
            AABB entityBox = entity.getBoundingBox().inflate(entity.getPickRadius());
            Optional<Vec3> hitPos = entityBox.clip(eyePos, endPos);

            if (hitPos.isEmpty()) {
                continue;
            }

            double distance = eyePos.distanceToSqr(hitPos.get());

            if (distance < closestDistance) {
                closestDistance = distance;
                closestEntity = entity;
            }
        }

        return closestEntity;
    }

    private DebugMobCommands() {
    }
}