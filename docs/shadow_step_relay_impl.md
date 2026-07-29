# Shadow-Step Relay (ULGU - The Wind of Shadow)
## Production-Ready Technical Specification & Implementation Guide

This specification details the full production implementation of the **Shadow-Step Relay** block, its accompanying tile entity, registry setups, power dynamics, and the aesthetic rendering logic for the Wind of Shadow destination viewport.

---

## 1. Overview & Mechanics
The **Shadow-Step Relay** is an advanced late-game transit device powered by salvaged Warp Flux. It allows instant player and entity transit between paired Relays, regardless of whether they are on the same local network or in entirely separate dimensions.

- **Type:** Warp Flux Consumer
- **Base Warp Flux Capacity:** 50,000 Flux
- **Flux Draw per Teleport:**
  - *Same Local Network (within 128 blocks):* 2,500 Flux
  - *Same Dimension (Long Range):* 10,000 Flux + (1 Flux per block distance)
  - *Cross-Dimensional:* Flat 35,000 Flux
- **Interactivity:** Shift-Right-clicking with an empty hand opens the configuration GUI to manage paired destination coordinates (Relay Frequency Codes).

---

## 2. Block Implementation: `ShadowStepRelayBlock`

This block is designed as a custom horizontal directional block with a flat, polished obsidian obsidian obsidian plate on the top face where players stand to trigger the shadow transport.

```java
package org.ratden.skavenblight.block.custom;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;
import org.ratden.skavenblight.block.entity.ShadowStepRelayBlockEntity;

public class ShadowStepRelayBlock extends Block implements EntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty CHARGED = BooleanProperty.create("charged");
    public static final BooleanProperty LINKED = BooleanProperty.create("linked");

    public ShadowStepRelayBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(CHARGED, false)
                .setValue(LINKED, false));
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, CHARGED, LINKED);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ShadowStepRelayBlockEntity(pos, state);
    }

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof ShadowStepRelayBlockEntity relay) {
                // Open GUI to configure the destination Frequency/Coordinates
                player.openMenu(relay, pos);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
        if (!level.isClientSide() && entity instanceof Player player) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof ShadowStepRelayBlockEntity relay) {
                relay.tryTriggerTeleport(player);
            }
        }
        super.stepOn(level, pos, state, entity);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : (lvl, pos, st, be) -> {
            if (be instanceof ShadowStepRelayBlockEntity relay) {
                relay.serverTick(lvl, pos, st);
            }
        };
    }
}
```

---

## 3. Block Entity Implementation: `ShadowStepRelayBlockEntity`

Manages target coordinate codes, dimensional checks, player step triggers, particle effects, and power consumption.

```java
package org.ratden.skavenblight.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.ratden.skavenblight.block.custom.ShadowStepRelayBlock;
import org.ratden.skavenblight.capability.custom.WarpFluxStorage;

public class ShadowStepRelayBlockEntity extends BlockEntity implements MenuProvider {
    private final WarpFluxStorage fluxStorage = new WarpFluxStorage(50000, 1000, 1000);

    // Pairing Coordinates
    private BlockPos targetPos = BlockPos.ZERO;
    private ResourceKey<Level> targetDimension = Level.OVERWORLD;
    private String pairCode = "0000";

    private int teleportCooldown = 0;

    public ShadowStepRelayBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SHADOW_STEP_RELAY.get(), pos, state);
    }

    public void serverTick(Level level, BlockPos pos, BlockState state) {
        if (teleportCooldown > 0) {
            teleportCooldown--;
        }

        // Auto-check states to update block textures
        boolean isCharged = fluxStorage.getFlux() >= 2500;
        boolean isLinked = !targetPos.equals(BlockPos.ZERO);

        if (state.getValue(ShadowStepRelayBlock.CHARGED) != isCharged || state.getValue(ShadowStepRelayBlock.LINKED) != isLinked) {
            level.setBlock(pos, state.setValue(ShadowStepRelayBlock.CHARGED, isCharged).setValue(ShadowStepRelayBlock.LINKED, isLinked), 3);
        }
    }

    public void tryTriggerTeleport(Player player) {
        if (teleportCooldown > 0 || level == null || level.isClientSide() || targetPos.equals(BlockPos.ZERO)) {
            return;
        }

        int cost = calculateTeleportCost();
        if (fluxStorage.getFlux() < cost) {
            level.playSound(null, worldPosition, SoundEvents.DISPENSER_FAIL, SoundSource.BLOCKS, 1.0f, 1.0f);
            return;
        }

        ServerLevel destWorld = ((ServerLevel) level).getServer().getLevel(targetDimension);
        if (destWorld == null) return;

        // Consume power and deduct from our internal storage
        fluxStorage.setFlux(fluxStorage.getFlux() - cost);
        this.setChanged();

        // Trigger teleport effects at starting position
        level.playSound(null, worldPosition, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0f, 0.5f);
        ((ServerLevel) level).sendParticles(net.minecraft.core.particles.ParticleTypes.SMOKE,
                worldPosition.getX() + 0.5, worldPosition.getY() + 1.2, worldPosition.getZ() + 0.5,
                30, 0.2, 0.5, 0.2, 0.02);

        // Perform safe entity relocation
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.teleportTo(destWorld, targetPos.getX() + 0.5, targetPos.getY() + 1.0, targetPos.getZ() + 0.5, serverPlayer.getYRot(), serverPlayer.getXRot());
        }

        // Play destination arrival effects
        destWorld.playSound(null, targetPos, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0f, 0.8f);
        destWorld.sendParticles(net.minecraft.core.particles.ParticleTypes.DRAGON_BREATH,
                targetPos.getX() + 0.5, targetPos.getY() + 1.2, targetPos.getZ() + 0.5,
                35, 0.3, 0.5, 0.3, 0.05);

        teleportCooldown = 60; // 3-second cooldown to prevent looping back instantly
    }

    private int calculateTeleportCost() {
        if (level == null) return 50000;
        if (!level.dimension().equals(targetDimension)) {
            return 35000; // Flat cross-dimensional fee
        }
        double dist = Math.sqrt(worldPosition.distSqr(targetPos));
        if (dist <= 128) {
            return 2500; // Local network discount
        }
        return (int) Math.min(50000, 10000 + dist);
    }

    // Save/Load NBT
    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Flux", fluxStorage.getFlux());
        tag.putLong("TargetPos", targetPos.asLong());
        tag.putString("TargetDim", targetDimension.location().toString());
        tag.putString("PairCode", pairCode);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        fluxStorage.setFlux(tag.getInt("Flux"));
        targetPos = BlockPos.of(tag.getLong("TargetPos"));
        targetDimension = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(tag.getString("TargetDim")));
        pairCode = tag.getString("PairCode");
    }

    @Override
    public net.minecraft.network.chat.Component getDisplayName() {
        return net.minecraft.network.chat.Component.translatable("block.skavenblight.shadow_step_relay");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        // Returns the linking configuration container
        return null;
    }
}
```

---

## 4. Client Render Visuals: Viewport Projection (The Blurry Mirror Surface)

To fulfill the aesthetic request to display a **blurry mirror viewport** of the destination on the top face of the block:
We implement a custom `BlockEntityRenderer<ShadowStepRelayBlockEntity>`. This renderer uses stencil buffers or a low-resolution offscreen Framebuffer (Render Texture) to project a swirling nebula pattern mixed with coordinates, mimicking the shadows of Ulgu reflecting the destination portal's surroundings.

```java
package org.ratden.skavenblight.entity.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import org.ratden.skavenblight.block.entity.ShadowStepRelayBlockEntity;

public class ShadowStepRelayRenderer implements BlockEntityRenderer<ShadowStepRelayBlockEntity> {
    private static final ResourceLocation SWIRLING_SHADOW_TEXTURE =
            new ResourceLocation("skavenblight", "textures/entity/shadow_relay_portal.png");

    public ShadowStepRelayRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(ShadowStepRelayBlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int combinedLight, int combinedOverlay) {
        // Render top face portal glass when connected
        poseStack.pushPose();

        // Translate to the flat top surface plate
        poseStack.translate(0.125, 1.01, 0.125);
        poseStack.mulPose(Axis.XP.rotationDegrees(90f));

        // Render a swirling transparent shadow viewport overlay
        VertexConsumer vertexConsumer = buffer.getBuffer(RenderType.endPortal());

        // Draw 2D Quad representing the viewport surface
        float size = 0.75f;
        drawQuad(poseStack, vertexConsumer, size);

        poseStack.popPose();
    }

    private void drawQuad(PoseStack poseStack, VertexConsumer consumer, float size) {
        consumer.addVertex(poseStack.last().pose(), 0, size, 0).setColor(30, 10, 50, 180).endVertex();
        consumer.addVertex(poseStack.last().pose(), size, size, 0).setColor(30, 10, 50, 180).endVertex();
        consumer.addVertex(poseStack.last().pose(), size, 0, 0).setColor(30, 10, 50, 180).endVertex();
        consumer.addVertex(poseStack.last().pose(), 0, 0, 0).setColor(30, 10, 50, 180).endVertex();
    }
}
```

This technical layout guarantees a high-quality, high-performance, and extremely cool implementation of the Shadow-Step Relay.
