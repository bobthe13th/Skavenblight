package org.ratden.skavenblight.magic.spell;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.Config;
import org.ratden.skavenblight.magic.corruption.Corruption;
import org.ratden.skavenblight.magic.player.ModAttachments;
import org.ratden.skavenblight.magic.player.PlayerMagicData;
import org.ratden.skavenblight.magic.wind.ChunkWindState;
import org.ratden.skavenblight.magic.wind.WindGridManager;

/**
 * The single place a spell actually gets cast — used by the debug /magic cast command today and
 * intended for any future real casting UI/item, so casting-adjacent mechanics (Dhar gating,
 * Monolith zones) live here once instead of being duplicated per call site.
 */
public final class SpellCasting {

    private SpellCasting() {}

    public record Outcome(boolean success, boolean blockedDarkMagic, int roll, int total, int degreesOfSuccess) {}

    public static Outcome attemptCast(ServerPlayer player, Spell spell) {
        ServerLevel level = (ServerLevel) player.level();
        PlayerMagicData data = player.getData(ModAttachments.PLAYER_MAGIC.get());

        if (spell.dark() && !data.darkMagicUnlocked()) {
            return new Outcome(false, true, 0, 0, 0);
        }

        int aptitude = data.getAptitude(spell.wind());
        ChunkPos chunkPos = new ChunkPos(player.blockPosition());
        ChunkWindState windState = WindGridManager.get(level).getOrCreate(chunkPos);
        float windLevel = windState.getCurrent(spell.wind());
        int windLevelBonus = CastingResolver.windLevelBonus(windLevel);

        int roll = level.getRandom().nextInt(100) + 1;
        CastingResolver.CastResult result = CastingResolver.resolve(aptitude, windLevelBonus, spell.castingNumber(), roll);
        int total = aptitude + windLevelBonus - spell.castingNumber();

        if (spell.dark()) {
            Corruption.grant(player, Config.dharCastCorruption);
        }

        if (!result.success()) {
            return new Outcome(false, false, roll, total, 0);
        }

        spell.effect().apply(player, player);
        windState.setCurrent(spell.wind(), Math.max(0f, windLevel - spell.castingNumber()));
        return new Outcome(true, false, roll, total, result.degreesOfSuccess());
    }
}
