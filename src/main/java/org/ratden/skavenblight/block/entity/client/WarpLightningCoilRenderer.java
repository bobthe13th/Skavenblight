package org.ratden.skavenblight.block.entity.client;

import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import org.ratden.skavenblight.block.entity.WarpLightningCoilBlockEntity;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public class WarpLightningCoilRenderer extends GeoBlockRenderer<WarpLightningCoilBlockEntity> {
    public WarpLightningCoilRenderer(BlockEntityRendererProvider.Context context) {
        super(new WarpLightningCoilModel());
    }
}
