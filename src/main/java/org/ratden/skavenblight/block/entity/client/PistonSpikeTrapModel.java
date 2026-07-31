package org.ratden.skavenblight.block.entity.client;

import net.minecraft.resources.ResourceLocation;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.block.entity.PistonSpikeTrapBlockEntity;
import software.bernie.geckolib.model.GeoModel;

public class PistonSpikeTrapModel extends GeoModel<PistonSpikeTrapBlockEntity> {

    @Override
    public ResourceLocation getModelResource(PistonSpikeTrapBlockEntity animatable) {
        // Ensure your file is at: src/main/resources/assets/skavenblight/geo/spike_piston.geo.json
        return ResourceLocation.fromNamespaceAndPath(Skavenblight.MODID, "geo/spike_piston.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(PistonSpikeTrapBlockEntity animatable) {
        // Ensure your texture is at: src/main/resources/assets/skavenblight/textures/block/warp_spike_trap.png
        // (Change "warp_spike_trap.png" if you named the texture file something else!)
        return ResourceLocation.fromNamespaceAndPath(Skavenblight.MODID, "textures/block/spike_piston.png");
    }

    @Override
    public ResourceLocation getAnimationResource(PistonSpikeTrapBlockEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(Skavenblight.MODID, "animations/spike_piston.animation.json");
    }
}