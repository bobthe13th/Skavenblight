package org.ratden.skavenblight.event.skavenIncursion.scenario.testing;

import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.ratden.skavenblight.entity.custom.wolfCat.WolfCat;

import java.util.UUID;

public final class TestPackLeader {
    public static final double LEADER_SCALE = 3D;

    public static void apply(WolfCat wolfCat, UUID packId) {
        if (wolfCat == null) {
            throw new IllegalArgumentException("wolfCat cannot be null");
        }

        if (packId == null) {
            throw new IllegalArgumentException("packId cannot be null");
        }

        UUID existingPackId = wolfCat.getPackId();

        if (existingPackId != null && !existingPackId.equals(packId)) {
            throw new IllegalStateException(
                    "WolfCat already belongs to a different Pack"
            );
        }

        wolfCat.setPackId(packId);
        wolfCat.setTestPackLeader(true);

        AttributeInstance scaleAttribute =
                wolfCat.getAttribute(Attributes.SCALE);

        if (scaleAttribute == null) {
            throw new IllegalStateException(
                    "WolfCat does not have the SCALE attribute registered"
            );
        }

        scaleAttribute.setBaseValue(LEADER_SCALE);
        wolfCat.refreshDimensions();
    }

    private TestPackLeader() {
    }
}