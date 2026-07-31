package org.ratden.skavenblight.block.entity.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import com.mojang.math.Axis;
import software.bernie.geckolib.renderer.GeoBlockRenderer;
import org.ratden.skavenblight.block.entity.PistonSpikeTrapBlockEntity;

public class PistonSpikeTrapRenderer extends GeoBlockRenderer<PistonSpikeTrapBlockEntity> {

    public PistonSpikeTrapRenderer() {
        super(new PistonSpikeTrapModel());
    }

    @Override
    protected void rotateBlock(Direction facing, PoseStack poseStack) {
        BlockState state = this.animatable != null ? this.animatable.getBlockState() : null;

        Direction actualFacing = (state != null && state.hasProperty(BlockStateProperties.FACING))
                ? state.getValue(BlockStateProperties.FACING)
                : facing;

        // 1. Shift the pivot point up to the center of the block so it rotates evenly
        poseStack.translate(0.0, 0.5, 0.0);

        // 2. Apply rotations assuming your Blockbench model naturally faces UP
        switch (actualFacing) {
            case UP -> { /* Model naturally points UP, no rotation needed! */ }
            case DOWN -> poseStack.mulPose(Axis.XP.rotationDegrees(180f));  // Flip upside down
            case NORTH -> poseStack.mulPose(Axis.XP.rotationDegrees(270f)); // Pitch forward
            case SOUTH -> poseStack.mulPose(Axis.XP.rotationDegrees(90f));  // Pitch backward
            case EAST -> poseStack.mulPose(Axis.ZP.rotationDegrees(270f));  // Roll right
            case WEST -> poseStack.mulPose(Axis.ZP.rotationDegrees(90f));   // Roll left
        }

        // 3. Shift the pivot back down so it snaps correctly into the world grid
        poseStack.translate(0.0, -0.5, 0.0);
    }
}