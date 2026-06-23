package org.ratden.skavenblight.capability;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.capabilities.BlockCapability;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.capability.custom.IWarpFluxStorage;

public class ModCapabilities {

    public static final BlockCapability<IWarpFluxStorage, Direction> WARP_FLUX =
            BlockCapability.createSided(
                    ResourceLocation.fromNamespaceAndPath(Skavenblight.MODID, "warp_flux"),
                    IWarpFluxStorage.class
            );
}
