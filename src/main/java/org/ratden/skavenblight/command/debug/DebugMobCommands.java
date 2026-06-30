package org.ratden.skavenblight.command.debug;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.ratden.skavenblight.event.skavenIncursion.leadership.IncursionOwnedMob;

import java.util.Optional;

public class DebugMobCommands {

    private static final double PICK_RANGE = 20.0D;

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("mob")
                .then(Commands.literal("info")
                        .executes(context -> mobInfo(context.getSource())));
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

    private static Entity findLookedAtEntity(ServerPlayer player) {
        Vec3 eyePos = player.getEyePosition();
        Vec3 lookVec = player.getLookAngle();
        Vec3 endPos = eyePos.add(lookVec.scale(PICK_RANGE));

        AABB searchBox = player.getBoundingBox()
                .expandTowards(lookVec.scale(PICK_RANGE))
                .inflate(1.0D);

        Entity closestEntity = null;
        double closestDistance = PICK_RANGE * PICK_RANGE;

        for (Entity entity : player.serverLevel().getEntities(player, searchBox,
                target -> !target.isSpectator() && target.isPickable())) {

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