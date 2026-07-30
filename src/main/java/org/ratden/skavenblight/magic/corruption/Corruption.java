package org.ratden.skavenblight.magic.corruption;

import net.minecraft.server.level.ServerPlayer;
import org.ratden.skavenblight.magic.player.ModAttachments;
import org.ratden.skavenblight.magic.player.PlayerMagicData;

/**
 * The single choke point every corruption source (Monoliths, Dhar casting, the Tome, future
 * sources) grants or reduces through. Never mutate PlayerMagicData.corruptionPoints directly
 * from elsewhere — go through here so every source is easy to find and the floor-at-zero rule
 * can't be forgotten in a new call site.
 */
public final class Corruption {

    private Corruption() {}

    /** Pure arithmetic, extracted so it's testable without a live ServerPlayer. */
    public static int applyDelta(int currentPoints, int delta) {
        return Math.max(0, currentPoints + delta);
    }

    public static CorruptionTier grant(ServerPlayer player, int amount) {
        PlayerMagicData data = player.getData(ModAttachments.PLAYER_MAGIC.get());
        int updated = applyDelta(data.corruptionPoints(), amount);
        player.setData(ModAttachments.PLAYER_MAGIC.get(), data.withCorruptionPoints(updated));
        return CorruptionTier.forPoints(updated);
    }

    public static CorruptionTier reduce(ServerPlayer player, int amount) {
        return grant(player, -Math.abs(amount));
    }

    public static int getPoints(ServerPlayer player) {
        return player.getData(ModAttachments.PLAYER_MAGIC.get()).corruptionPoints();
    }

    public static CorruptionTier getTier(ServerPlayer player) {
        return CorruptionTier.forPoints(getPoints(player));
    }
}
