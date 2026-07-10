package org.ratden.skavenblight.event.skavenIncursion.action.effect;

import net.minecraft.server.level.ServerLevel;

public interface SkavenEffect {

    String getId();

    void execute(ServerLevel level);
}