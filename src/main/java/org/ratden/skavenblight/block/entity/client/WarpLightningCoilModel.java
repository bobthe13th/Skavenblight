package org.ratden.skavenblight.block.entity.client;

import net.minecraft.resources.ResourceLocation;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.block.entity.WarpLightningCoilBlockEntity;
import software.bernie.geckolib.model.GeoModel;

public class WarpLightningCoilModel extends GeoModel<WarpLightningCoilBlockEntity> {
    @Override
    public ResourceLocation getModelResource(WarpLightningCoilBlockEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(Skavenblight.MODID, "geo/warp_lightning_coil.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(WarpLightningCoilBlockEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(Skavenblight.MODID, "textures/block/warp_lightning_coil.png");
    }

    @Override
    public ResourceLocation getAnimationResource(WarpLightningCoilBlockEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(Skavenblight.MODID, "animations/warp_lightning_coil.animation.json");
    }
}
