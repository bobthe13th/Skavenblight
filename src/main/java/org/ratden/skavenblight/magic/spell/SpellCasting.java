package org.ratden.skavenblight.magic.spell;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.Config;
import org.ratden.skavenblight.magic.corruption.ChaosManifestationManager;
import org.ratden.skavenblight.magic.corruption.Corruption;
import org.ratden.skavenblight.magic.corruption.CorruptionTier;
import org.ratden.skavenblight.magic.corruption.MonolithRegistry;
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

    /**
     * WFRP source: all spellcasters within a Monolith's radius add +1d10 to their casting roll's
     * effective bonus, whether the spell is beneficial or not. A raw 0-9 die roll (nextInt(10))
     * maps to a 1-10 result, not 0-9. Extracted as a pure function so the die-roll shape is
     * testable without a live cast.
     */
    public static int monolithBonus(boolean withinZone, int rawD10Roll) {
        return withinZone ? rawD10Roll + 1 : 0;
    }

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

        boolean nearMonolith = MonolithRegistry.isWithinRange(level, player.blockPosition(), Config.monolithCastingRadiusBlocks);
        int zoneBonus = monolithBonus(nearMonolith, level.getRandom().nextInt(10));

        int roll = level.getRandom().nextInt(100) + 1;
        CastingResolver.CastResult result = CastingResolver.resolve(aptitude, windLevelBonus + zoneBonus, spell.castingNumber(), roll);
        int total = aptitude + windLevelBonus + zoneBonus - spell.castingNumber();

        if (spell.dark()) {
            Corruption.grant(player, Config.dharCastCorruption);
        }

        if (!result.success()) {
            if (nearMonolith) {
                ChaosManifestationManager.resolve(level, player, CorruptionTier.CATASTROPHIC);
            }
            return new Outcome(false, false, roll, total, 0);
        }

        spell.effect().apply(player, player);
        windState.setCurrent(spell.wind(), Math.max(0f, windLevel - spell.castingNumber()));
        return new Outcome(true, false, roll, total, result.degreesOfSuccess());
    }
}
