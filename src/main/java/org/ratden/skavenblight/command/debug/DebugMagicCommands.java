package org.ratden.skavenblight.command.debug;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.ratden.skavenblight.magic.Wind;
import org.ratden.skavenblight.magic.player.ModAttachments;
import org.ratden.skavenblight.magic.player.PlayerMagicData;
import org.ratden.skavenblight.magic.spell.Spell;
import org.ratden.skavenblight.magic.spell.SpellCasting;
import org.ratden.skavenblight.magic.spell.SpellManager;

public class DebugMagicCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("magic")
                .then(Commands.literal("cast")
                        .then(Commands.argument("spell", ResourceLocationArgument.id())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                                        SpellManager.getAll().keySet(), builder))
                                .executes(context -> cast(context.getSource(),
                                        ResourceLocationArgument.getId(context, "spell")))))
                .then(Commands.literal("set_aptitude")
                        .then(Commands.argument("wind", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(0, 100))
                                        .executes(context -> setAptitude(context.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(context, "wind"),
                                                IntegerArgumentType.getInteger(context, "amount"))))))
                .then(Commands.literal("info")
                        .executes(context -> info(context.getSource())));
    }

    private static int cast(CommandSourceStack source, ResourceLocation spellId) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        Spell spell = SpellManager.get(spellId);
        if (spell == null) {
            source.sendFailure(Component.literal("Unknown spell: " + spellId));
            return 0;
        }

        SpellCasting.Outcome outcome = SpellCasting.attemptCast(player, spell);

        if (outcome.blockedDarkMagic()) {
            source.sendFailure(Component.literal(
                    "Cast failed: " + spellId + " is a Dhar spell and you have not unlocked Dark Magic."));
            return 0;
        }

        if (!outcome.success()) {
            source.sendFailure(Component.literal(
                    "Cast failed: rolled " + outcome.roll() + ", needed <= " + outcome.total()));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
                "Cast " + spellId + " successfully! (rolled " + outcome.roll()
                        + ", degrees of success: " + outcome.degreesOfSuccess() + ")"), true);
        return 1;
    }

    private static int setAptitude(CommandSourceStack source, String windId, int amount) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        Wind wind = parseWind(windId);
        if (wind == null) {
            source.sendFailure(Component.literal("Unknown wind: " + windId));
            return 0;
        }

        PlayerMagicData data = player.getData(ModAttachments.PLAYER_MAGIC.get());
        player.setData(ModAttachments.PLAYER_MAGIC.get(), data.withAptitude(wind, amount));

        source.sendSuccess(() -> Component.literal(
                "Set " + wind.getLoreName() + " aptitude to " + amount), true);
        return 1;
    }

    private static int info(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        PlayerMagicData data = player.getData(ModAttachments.PLAYER_MAGIC.get());
        StringBuilder sb = new StringBuilder("Magic data:");
        for (Wind wind : Wind.values()) {
            sb.append("\n").append(wind.getLoreName())
                    .append(": tier=").append(data.getTier(wind))
                    .append(", aptitude=").append(data.getAptitude(wind));
        }
        sb.append("\nKnown spells: ").append(data.knownSpells());
        String message = sb.toString();
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static Wind parseWind(String id) {
        for (Wind wind : Wind.values()) {
            if (wind.getSerializedName().equals(id)) {
                return wind;
            }
        }
        return null;
    }
}
